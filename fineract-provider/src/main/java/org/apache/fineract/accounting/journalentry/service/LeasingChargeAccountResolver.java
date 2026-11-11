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
package org.apache.fineract.accounting.journalentry.service;

import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.common.AccountingConstants.CASH_ACCOUNTS_FOR_LOAN;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepositoryWrapper;
import org.apache.fineract.infrastructure.core.service.RoutingDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LeasingChargeAccountResolver {

    static final String CHARGE_CONFIG_NAME = "leasing_capitalized_charge_id";
    static final String INTEREST_CONFIG_NAME = "leasing_capitalized_charge_interest_id";

    private final JdbcTemplate jdbcTemplate;
    private final GLAccountRepositoryWrapper accountRepositoryWrapper;

    @Autowired
    public LeasingChargeAccountResolver(final RoutingDataSource dataSource, final GLAccountRepositoryWrapper accountRepositoryWrapper) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.accountRepositoryWrapper = accountRepositoryWrapper;
    }

    public GLAccount resolveCapitalizedChargeAccount(final Long loanProductId, final int accountMappingTypeId, final Long chargeId) {
        if (chargeId == null || loanProductId == null || accountMappingTypeId != CASH_ACCOUNTS_FOR_LOAN.INCOME_FROM_FEES.getValue()) {
            return null;
        }

        final boolean isChargePart = chargeId.equals(findSystemChargeId(CHARGE_CONFIG_NAME));
        final boolean isInterestPart = !isChargePart && chargeId.equals(findSystemChargeId(INTEREST_CONFIG_NAME));
        if (!isChargePart && !isInterestPart) { return null; }

        final List<Map<String, Object>> rows = this.jdbcTemplate.queryForList(
                "SELECT receivable_account_id, income_account_id FROM l_loan_product_capitalized_charge_account_mapping"
                        + " WHERE product_id = ? LIMIT 1",
                loanProductId);
        if (rows.isEmpty()) { return null; }

        final Object accountId = rows.get(0).get(isChargePart ? "receivable_account_id" : "income_account_id");
        if (accountId == null) { return null; }
        return this.accountRepositoryWrapper.findOneWithNotFoundDetection(((Number) accountId).longValue());
    }

    public GLAccount resolveThirdPartyPayableAccount(final Long loanProductId, final Long chargeId) {
        if (loanProductId == null) { return null; }
        final List<Map<String, Object>> rows = this.jdbcTemplate.queryForList(
                "SELECT gl_account_id FROM l_loan_product_liability_account_mapping"
                        + " WHERE product_id = ? AND (charge_id = ? OR charge_id IS NULL) ORDER BY charge_id IS NULL LIMIT 1",
                loanProductId, chargeId);
        if (rows.isEmpty() || rows.get(0).get("gl_account_id") == null) { return null; }
        return this.accountRepositoryWrapper.findOneWithNotFoundDetection(((Number) rows.get(0).get("gl_account_id")).longValue());
    }

    private Long findSystemChargeId(final String configName) {
        final List<Map<String, Object>> rows = this.jdbcTemplate
                .queryForList("SELECT value FROM c_configuration WHERE name = ? AND enabled = 1 LIMIT 1", configName);
        if (rows.isEmpty() || rows.get(0).get("value") == null) { return null; }
        return ((Number) rows.get(0).get("value")).longValue();
    }
}
