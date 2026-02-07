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
package org.apache.fineract.portfolio.savings.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.*;

import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.annotation.CronTarget;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.useradministration.api.AppUserApiConstant;
import org.joda.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class SavingsSchedularServiceImpl implements SavingsSchedularService {

    private final SavingsAccountAssembler savingAccountAssembler;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    private final SavingsAccountRepositoryWrapper savingAccountRepositoryWrapper;
    private final SavingsAccountReadPlatformService savingAccountReadPlatformService;

    @Autowired
    public SavingsSchedularServiceImpl(final SavingsAccountAssembler savingAccountAssembler,
            final SavingsAccountWritePlatformService savingsAccountWritePlatformService,
            final SavingsAccountRepositoryWrapper savingAccountRepositoryWrapper,
            final SavingsAccountReadPlatformService savingAccountReadPlatformService) {
        this.savingAccountAssembler = savingAccountAssembler;
        this.savingsAccountWritePlatformService = savingsAccountWritePlatformService;
        this.savingAccountRepositoryWrapper = savingAccountRepositoryWrapper;
        this.savingAccountReadPlatformService = savingAccountReadPlatformService;
    }


    final int PARALLELISM = 20;
    final int PAGE_SIZE = 1000;

    @CronTarget(jobName = JobName.POST_INTEREST_FOR_SAVINGS)
    @Override
    public void postInterestForAccounts() throws JobExecutionException {
//        final List<SavingsAccount> savingsAccounts = this.savingAccountRepositoryWrapper.findSavingAccountByStatus(SavingsAccountStatusType.ACTIVE
//                .getValue());
        StringBuffer sb = new StringBuffer();
        final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();

        ExecutorService executor = Executors.newFixedThreadPool(PARALLELISM);
        CompletionService<Void> completion = new ExecutorCompletionService<>(executor);

        List<String> errors = new CopyOnWriteArrayList<>();

        int inFlight = 0;

        // local buffer of IDs we’ve fetched but not yet submitted
        Deque<Long> buffer = new ArrayDeque<>(PAGE_SIZE);

        try {
            int page = 0;
            boolean hasMore = true;

            // Helper: fetch next page into buffer
            while (hasMore || !buffer.isEmpty() || inFlight > 0) {

                // Fill buffer if empty and DB has more
                while (buffer.isEmpty() && hasMore) {
                    Money PageRequest;
                    PageRequest pageable = new PageRequest(page, PAGE_SIZE);
                    Slice<Long> slice = savingAccountRepositoryWrapper.findIdsByStatus(
                            SavingsAccountStatusType.ACTIVE.getValue(), pageable);

                    buffer.addAll(slice.getContent());
                    hasMore = slice.hasNext();
                    page++;
                }

                // Submit tasks until we hit PARALLELISM or buffer empty
                while (inFlight < PARALLELISM && !buffer.isEmpty()) {
                    Long id = buffer.pollFirst();
                    completion.submit(() -> {
                        final FineractPlatformTenant previous = ThreadLocalContextUtil.getTenant();
                        // set tenant context for this worker thread
                        ThreadLocalContextUtil.setTenant(tenant);
                        try {
                            System.out.println("RUNNING - " + id);
                            SavingsAccount savingsAccount = savingAccountRepositoryWrapper.findOneWithNotFoundDetection(id);

                            savingsAccount.loadLazyCollections();

                            savingAccountAssembler.assignSavingAccountHelpers(savingsAccount);

                            boolean postInterestAsOn = false;
                            LocalDate transactionDate = null;

                            savingsAccountWritePlatformService.postInterest(savingsAccount, postInterestAsOn, transactionDate);
                        } catch (Exception e) {
                            Throwable real = e;
                            errors.add("failed to post interest for Savings with id " + id
                                    + " with message " + e);
                        } finally {
                            if (previous != null) {
                                ThreadLocalContextUtil.setTenant(previous);
                            } else {
                                ThreadLocalContextUtil.clearTenant();
                            }
                        }
                        return null;
                    });
                    inFlight++;
                }

                // If nothing in-flight, loop will fetch more or end
                if (inFlight == 0) {
                    continue;
                }

                // Wait for one to finish, then immediately submit the next
                Future<Void> done = completion.take();
                try { done.get(); } catch (Exception ignore) {}
                inFlight--;
            }

        } catch (Exception e) {
            errors.add("Job failed: " + e);
        } finally {
            executor.shutdown();
        }


        if (sb.length() > 0) { throw new JobExecutionException(sb.toString()); }
    }

    @CronTarget(jobName = JobName.UPDATE_SAVINGS_DORMANT_ACCOUNTS)
    @Override
    public void updateSavingsDormancyStatus() throws JobExecutionException {
    	final LocalDate tenantLocalDate = DateUtils.getLocalDateOfTenant();

    	final List<Long> savingsPendingInactive = this.savingAccountReadPlatformService
    													.retrieveSavingsIdsPendingInactive(tenantLocalDate);
    	if(null != savingsPendingInactive && savingsPendingInactive.size() > 0){
    		for(Long savingsId : savingsPendingInactive){
    			this.savingsAccountWritePlatformService.setSubStatusInactive(savingsId);
    		}
    	}

    	final List<Long> savingsPendingDormant = this.savingAccountReadPlatformService
				.retrieveSavingsIdsPendingDormant(tenantLocalDate);
		if(null != savingsPendingDormant && savingsPendingDormant.size() > 0){
			for(Long savingsId : savingsPendingDormant){
				this.savingsAccountWritePlatformService.setSubStatusDormant(savingsId);
			}
		}

    	final List<Long> savingsPendingEscheat = this.savingAccountReadPlatformService
				.retrieveSavingsIdsPendingEscheat(tenantLocalDate);
		if(null != savingsPendingEscheat && savingsPendingEscheat.size() > 0){
			for(Long savingsId : savingsPendingEscheat){
				this.savingsAccountWritePlatformService.escheat(savingsId);
			}
		}
    }
}
