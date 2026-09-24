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
package org.apache.fineract.portfolio.loanaccount;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanEventApiJsonValidator;
import org.junit.Test;

public class LoanEventApiJsonValidatorBulkAddLoanChargeTest {

    private final LoanEventApiJsonValidator validator = new LoanEventApiJsonValidator(new FromJsonHelper(), null);

    private static final String HEAD = "{\"locale\": \"en\", \"dateFormat\": \"dd MMMM yyyy\", ";
    private static final String ITEM = "{\"loanId\": 52620, \"chargeId\": 4, \"amount\": \"1000\", \"dueDate\": \"23 September 2026\"}";

    @Test
    public void acceptsChargeItemsWithLoanIds() {
        this.validator.validateBulkAddLoanCharge(HEAD + "\"charges\": [" + ITEM + ", " + ITEM + "], \"continueOnFailure\": true}");
    }

    @Test
    public void rejectsMissingEmptyOrMalformedCharges() {
        assertValidationError("{\"locale\": \"en\"}", "charges");
        assertValidationError(HEAD + "\"charges\": []}", "charges");
        assertValidationError(HEAD + "\"charges\": [1]}", "charges");
        assertValidationError(HEAD + "\"charges\": [{\"chargeId\": 4}]}", "charges.loanId");
        assertValidationError(HEAD + "\"charges\": [{\"loanId\": 0, \"chargeId\": 4}]}", "charges.loanId");
    }

    @Test(expected = UnsupportedParameterException.class)
    public void rejectsUnknownTopLevelParameters() {
        this.validator.validateBulkAddLoanCharge(HEAD + "\"loanIds\": [1], \"charges\": [" + ITEM + "]}");
    }

    private void assertValidationError(final String json, final String parameter) {
        try {
            this.validator.validateBulkAddLoanCharge(json);
            fail("expected validation error for " + json);
        } catch (final PlatformApiDataValidationException e) {
            assertEquals(parameter, e.getErrors().get(0).getParameterName());
        }
    }
}
