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
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;

import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInstallmentCharge;
import org.junit.Before;
import org.junit.Test;

public class LoanInstallmentChargeWaiverTest {

    private final MonetaryCurrency currency = new MonetaryCurrencyBuilder().withCode("USD").withDigitsAfterDecimal(2).build();

    @Before
    public void setUp() throws Exception {
        final Field field = MoneyHelper.class.getDeclaredField("roundingMode");
        field.setAccessible(true);
        field.set(null, RoundingMode.HALF_EVEN);
    }

    @Test
    public void undoWaivedAmountRestoresTheChargeOutstandingAmount() {
        final LoanInstallmentCharge charge = newCharge("100.00");

        charge.waive(this.currency);
        final Money amountUndone = charge.undoWaivedAmountBy(Money.of(this.currency, new BigDecimal("100.00")));

        assertEquals(new BigDecimal("100.00"), amountUndone.getAmount());
        assertEquals(new BigDecimal("100.00"), charge.getAmountOutstanding());
        assertEquals(BigDecimal.ZERO.setScale(2), charge.getAmountWaived(this.currency).getAmount());
        assertFalse(charge.isWaived());
        assertFalse(charge.isPaid());
    }

    @Test
    public void undoWaivedAmountCannotUndoMoreThanWasWaived() {
        final LoanInstallmentCharge charge = newCharge("100.00");

        charge.waive(this.currency);
        final Money amountUndone = charge.undoWaivedAmountBy(Money.of(this.currency, new BigDecimal("150.00")));

        assertEquals(new BigDecimal("100.00"), amountUndone.getAmount());
        assertEquals(new BigDecimal("100.00"), charge.getAmountOutstanding());
    }

    private LoanInstallmentCharge newCharge(final String amount) {
        return new LoanInstallmentCharge(new BigDecimal(amount), null,
                new LoanRepaymentScheduleInstallmentBuilder(this.currency).build());
    }
}
