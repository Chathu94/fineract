/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.loanaccount.service;

import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.RoutingDataSource;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.annotation.CronTarget;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.portfolio.loanaccount.data.LoanScheduleAccrualData;
import org.joda.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LoanAccrualPlatformServiceImpl implements LoanAccrualPlatformService {

    private final LoanReadPlatformService loanReadPlatformService;
    private final LoanAccrualWritePlatformService loanAccrualWritePlatformService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public LoanAccrualPlatformServiceImpl(final LoanReadPlatformService loanReadPlatformService,
            final LoanAccrualWritePlatformService loanAccrualWritePlatformService,
                                          final RoutingDataSource dataSource) {
        this.loanReadPlatformService = loanReadPlatformService;
        this.loanAccrualWritePlatformService = loanAccrualWritePlatformService;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    @CronTarget(jobName = JobName.ADD_ACCRUAL_ENTRIES)
    public void addAccrualAccounting() throws JobExecutionException {
        Collection<LoanScheduleAccrualData> loanScheduleAccrualDatas = this.loanReadPlatformService.retriveScheduleAccrualData();
        StringBuilder sb = new StringBuilder();
        Map<Long, Collection<LoanScheduleAccrualData>> loanDataMap = new HashMap<>();
        for (final LoanScheduleAccrualData accrualData : loanScheduleAccrualDatas) {
            if (loanDataMap.containsKey(accrualData.getLoanId())) {
                loanDataMap.get(accrualData.getLoanId()).add(accrualData);
            } else {
                Collection<LoanScheduleAccrualData> accrualDatas = new ArrayList<>();
                accrualDatas.add(accrualData);
                loanDataMap.put(accrualData.getLoanId(), accrualDatas);
            }
        }

        for (Map.Entry<Long, Collection<LoanScheduleAccrualData>> mapEntry : loanDataMap.entrySet()) {
            try {
                this.loanAccrualWritePlatformService.addAccrualAccounting(mapEntry.getKey(), mapEntry.getValue());
            } catch (Exception e) {
                Throwable realCause = e;
                if (e.getCause() != null) {
                    realCause = e.getCause();
                }
                sb.append("failed to add accural transaction for loan " + mapEntry.getKey() + " with message " + realCause.getMessage());
            }
        }

        if (sb.length() > 0) { throw new JobExecutionException(sb.toString()); }
    }

    @Override
    @CronTarget(jobName = JobName.ADD_PERIODIC_ACCRUAL_ENTRIES)
    public void addPeriodicAccruals() throws JobExecutionException {
//        run service for 2026-03-31 and then change to LocalDate.now() after testing
//        LocalDate tillDate = new LocalDate(2026, 3, 31);
        String errors = addPeriodicAccruals(LocalDate.now());
        if (errors.length() > 0) { throw new JobExecutionException(errors); }
    }

    @Override
    public String addPeriodicAccruals(final LocalDate tilldate) {

        if (ThreadLocalContextUtil.getVTReplicaMode()) {
            this.jdbcTemplate.execute("SET workload = OLAP");
            jdbcTemplate.execute("USE @primary");
        }

        Collection<LoanScheduleAccrualData> loanScheduleAccrualDatas = this.loanReadPlatformService.retrivePeriodicAccrualData(tilldate);
        return addPeriodicAccruals(tilldate, loanScheduleAccrualDatas);
    }

    @Override
    public String addPeriodicAccruals(final LocalDate tilldate, final Long loanId) {

        if (ThreadLocalContextUtil.getVTReplicaMode()) {
            this.jdbcTemplate.execute("SET workload = OLAP");
            jdbcTemplate.execute("USE @primary");
        }

        Collection<LoanScheduleAccrualData> loanScheduleAccrualDatas = this.loanReadPlatformService.retrivePeriodicAccrualData(tilldate, loanId);
        return addPeriodicAccruals(tilldate, loanScheduleAccrualDatas);
    }

    @Override
    public String addPeriodicAccruals(final LocalDate tilldate, Collection<LoanScheduleAccrualData> loanScheduleAccrualDatas) {
        StringBuilder sb = new StringBuilder();
        Map<Long, Collection<LoanScheduleAccrualData>> loanDataMap = new HashMap<>();
        for (final LoanScheduleAccrualData accrualData : loanScheduleAccrualDatas) {
            if (loanDataMap.containsKey(accrualData.getLoanId())) {
                loanDataMap.get(accrualData.getLoanId()).add(accrualData);
            } else {
                Collection<LoanScheduleAccrualData> accrualDatas = new ArrayList<>();
                accrualDatas.add(accrualData);
                loanDataMap.put(accrualData.getLoanId(), accrualDatas);
            }
        }

        int concurrency = 20; // tune as needed
        int queueCapacity = concurrency * 2;

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.CallerRunsPolicy() // back-pressure if queue is full
        );

        AtomicInteger pos = new AtomicInteger(1);

        List<CompletableFuture<Void>> futures = new ArrayList<>(loanDataMap.size());

        FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();

        System.out.println("Found " + loanDataMap.size() + " loans");
        for (Map.Entry<Long, Collection<LoanScheduleAccrualData>> entry : loanDataMap.entrySet()) {
            futures.add(CompletableFuture.runAsync(() -> {
                FineractPlatformTenant originalTenant = ThreadLocalContextUtil.getTenant();
                ThreadLocalContextUtil.setTenant(tenant);

                if (ThreadLocalContextUtil.getVTReplicaMode()) {
                    this.jdbcTemplate.execute("SET workload = OLAP");
                    jdbcTemplate.execute("USE @primary");
                }

                try {
                    int current = pos.getAndIncrement();
                    System.out.println("Processing " + current + "/" + loanDataMap.size());
                    loanAccrualWritePlatformService.addPeriodicAccruals(tilldate, entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    System.out.print(e);
                    Throwable real = (e.getCause() != null) ? e.getCause() : e;
                    synchronized (sb) {
                        sb.append("failed to add accrual transaction for loan ")
                                .append(entry.getKey())
                                .append(" with message ")
                                .append(real.getMessage())
                                .append('\n');
                    }
                } finally {
                    ThreadLocalContextUtil.clearTenant();
                    if (originalTenant != null) {
                        ThreadLocalContextUtil.setTenant(originalTenant);
                    }
                }
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        return sb.toString();
    }

    @Override
    @CronTarget(jobName = JobName.ADD_PERIODIC_ACCRUAL_ENTRIES_FOR_LOANS_WITH_INCOME_POSTED_AS_TRANSACTIONS)
    public void addPeriodicAccrualsForLoansWithIncomePostedAsTransactions() throws JobExecutionException {
        Collection<Long> loanIds = this.loanReadPlatformService.retrieveLoanIdsWithPendingIncomePostingTransactions();
        if(loanIds != null && loanIds.size() > 0){
            StringBuilder sb = new StringBuilder();
            for (Long loanId : loanIds) {
                try {
                    this.loanAccrualWritePlatformService.addIncomeAndAccrualTransactions(loanId);
                } catch (Exception e) {
                    Throwable realCause = e;
                    if (e.getCause() != null) {
                        realCause = e.getCause();
                    }
                    sb.append("failed to add income and accrual transaction for loan " + loanId + " with message " + realCause.getMessage());
                }
            }
            if (sb.length() > 0) { throw new JobExecutionException(sb.toString()); }
        }
    }
}
