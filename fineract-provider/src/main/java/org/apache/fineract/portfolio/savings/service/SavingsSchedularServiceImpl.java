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

import java.util.ArrayList;
import java.util.List;

import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.RoutingDataSource;
import org.apache.fineract.infrastructure.jobs.annotation.CronTarget;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.joda.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SavingsSchedularServiceImpl implements SavingsSchedularService {

    private final JdbcTemplate jdbcTemplate;
    private final SavingsAccountAssembler savingAccountAssembler;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    private final SavingsAccountRepositoryWrapper savingAccountRepositoryWrapper;
    private final SavingsAccountReadPlatformService savingAccountReadPlatformService;

    @Autowired
    public SavingsSchedularServiceImpl(final RoutingDataSource dataSource, final SavingsAccountAssembler savingAccountAssembler,
                                       final SavingsAccountWritePlatformService savingsAccountWritePlatformService,
                                       final SavingsAccountRepositoryWrapper savingAccountRepositoryWrapper,
                                       final SavingsAccountReadPlatformService savingAccountReadPlatformService) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.savingAccountAssembler = savingAccountAssembler;
        this.savingsAccountWritePlatformService = savingsAccountWritePlatformService;
        this.savingAccountRepositoryWrapper = savingAccountRepositoryWrapper;
        this.savingAccountReadPlatformService = savingAccountReadPlatformService;
    }

    @CronTarget(jobName = JobName.POST_INTEREST_FOR_SAVINGS)
    @Override
    public void postInterestForAccounts() throws JobExecutionException {
        List<Long> savingsAccounts = new ArrayList<>();
        StringBuffer sb = new StringBuffer();

        int pageNumber = 0;
        int pageSize = 1000;

        savingsAccounts = this.jdbcTemplate.queryForList("select id from m_savings_account where status_enum = ? limit ? offset ?", new Object[] {
                SavingsAccountStatusType.ACTIVE.getValue(), pageSize, pageNumber * pageSize
                }, Long.class);
//                .savingAccountRepositoryWrapper.findSavingAccountByStatus(SavingsAccountStatusType.ACTIVE
//                .getValue(), new PageRequest(pageNumber, pageSize));
//        System.out.println("Total number of active savings accounts to post interest page " + pageNumber + " : " + savingsAccounts.size());

        while (savingsAccounts.size() > 0) {

            System.out.println("Total number of active savings accounts to post interest page " + pageNumber + " : " + savingsAccounts.size());
            for (final Long savingsAccountId : savingsAccounts) {
                try {
                    System.out.println("Posting interest for Savings with id " + savingsAccountId);
                    final SavingsAccount savingsAccount = this.savingAccountRepositoryWrapper
                            .findOneWithNotFoundDetection(savingsAccountId);
                    savingsAccount.loadLazyCollections();
                    this.savingAccountAssembler.assignSavingAccountHelpers(savingsAccount);
                    boolean postInterestAsOn = false;
                    LocalDate transactionDate = null;
                    this.savingsAccountWritePlatformService.postInterest(savingsAccount, postInterestAsOn, transactionDate);
                } catch (Exception e) {
                    Throwable realCause = e;
                    if (e.getCause() != null) {
                        realCause = e.getCause();
                    }
                    System.out.println("failed to post interest for Savings with id " + savingsAccountId + " with message "
                            + realCause.getMessage());
                    sb.append("failed to post interest for Savings with id " + savingsAccountId + " with message "
                            + realCause.getMessage());
                } finally {
                    pageNumber++;
                    savingsAccounts = this.jdbcTemplate.queryForList("select id from m_savings_account where status_enum = ? limit ? offset ?", new Object[] {
                            SavingsAccountStatusType.ACTIVE.getValue(), pageSize, pageNumber * pageSize
                    }, Long.class);
                }
            }
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
