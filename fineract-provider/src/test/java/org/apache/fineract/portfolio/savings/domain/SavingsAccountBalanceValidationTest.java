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
package org.apache.fineract.portfolio.savings.domain;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.joda.time.LocalDate;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class SavingsAccountBalanceValidationTest {

    private MonetaryCurrency currency;

    @Before
    public void setUp() throws Exception {
        Field field = MoneyHelper.class.getDeclaredField("roundingMode");
        field.setAccessible(true);
        field.set(null, RoundingMode.HALF_EVEN);

        this.currency = new MonetaryCurrency("USD", 2, 1);
    }

    private void setField(final Object target, final String fieldName, final Object value) throws Exception {
        Field field = null;
        Class<?> current = target.getClass();
        while (current != null && field == null) {
            try {
                field = current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException(fieldName);
        }
        field.setAccessible(true);
        field.set(target, value);
    }

    private SavingsAccount createTestSavingsAccount(final BigDecimal minRequiredBalance, final boolean enforceMinRequiredBalance)
            throws Exception {
        final SavingsAccount account = Mockito.spy(new SavingsAccount());
        setField(account, "currency", this.currency);
        setField(account, "minRequiredBalance", minRequiredBalance);
        setField(account, "enforceMinRequiredBalance", enforceMinRequiredBalance);
        setField(account, "allowOverdraft", false);
        setField(account, "overdraftLimit", BigDecimal.ZERO);
        setField(account, "transactions", new ArrayList<SavingsAccountTransaction>());

        final SavingsAccountSummary summary = new SavingsAccountSummary();
        setField(summary, "accountBalance", BigDecimal.valueOf(6121));
        setField(account, "summary", summary);

        return account;
    }

    private SavingsAccountTransaction createTransaction(final SavingsAccount account, final Long id,
            final SavingsAccountTransactionType type, final LocalDate date, final BigDecimal amount) throws Exception {
        final Money moneyAmount = Money.of(this.currency, amount);
        final SavingsAccountTransaction sat;
        if (type.isDeposit()) {
            sat = SavingsAccountTransaction.deposit(account, null, null, date, moneyAmount, new Date(), null);
        } else {
            sat = SavingsAccountTransaction.withdrawal(account, null, null, date, moneyAmount, new Date(), null);
        }
        if (id != null) {
            setField(sat, "id", id);
        }
        return sat;
    }

    @Test
    public void testWithdrawalSucceedsWhenHistoricalBalanceDippedBelowMinRequiredBalance() throws Exception {
        // Minimum required balance is 1000 and enforced
        final SavingsAccount account = createTestSavingsAccount(BigDecimal.valueOf(1000), true);
        @SuppressWarnings("unchecked")
        final List<SavingsAccountTransaction> transactions = (List<SavingsAccountTransaction>) account.getTransactions();

        // Historical transactions:
        // 1. Initial deposit of 6000 (id = 1)
        transactions.add(createTransaction(account, 1L, SavingsAccountTransactionType.DEPOSIT, new LocalDate(2023, 1, 1),
                BigDecimal.valueOf(6000)));

        // 2. Historical withdrawal of 5934 leaving 66 in 2024 (id = 2)
        transactions.add(createTransaction(account, 2L, SavingsAccountTransactionType.WITHDRAWAL, new LocalDate(2024, 1, 31),
                BigDecimal.valueOf(5934)));

        // 3. Subsequent deposits bringing balance to 6121 (id = 3)
        transactions.add(createTransaction(account, 3L, SavingsAccountTransactionType.DEPOSIT, new LocalDate(2026, 8, 11),
                BigDecimal.valueOf(6055)));

        // 4. Proposed new withdrawal of 2300 (id = null, leaving balance = 6121 - 2300 = 3821 >= 1000)
        final SavingsAccountTransaction newWithdrawal = createTransaction(account, null, SavingsAccountTransactionType.WITHDRAWAL,
                new LocalDate(2026, 11, 12), BigDecimal.valueOf(2300));
        transactions.add(newWithdrawal);

        // Validation should succeed without throwing InsufficientAccountBalanceException
        account.validateAccountBalanceDoesNotBecomeNegative(BigDecimal.valueOf(2300), false, null);
    }

    @Test(expected = InsufficientAccountBalanceException.class)
    public void testWithdrawalFailsWhenNewWithdrawalViolatesMinRequiredBalance() throws Exception {
        final SavingsAccount account = createTestSavingsAccount(BigDecimal.valueOf(1000), true);
        @SuppressWarnings("unchecked")
        final List<SavingsAccountTransaction> transactions = (List<SavingsAccountTransaction>) account.getTransactions();

        // Historical deposit of 6121
        transactions.add(createTransaction(account, 1L, SavingsAccountTransactionType.DEPOSIT, new LocalDate(2026, 8, 11),
                BigDecimal.valueOf(6121)));

        // Proposed new withdrawal of 5500 (id = null, leaving balance = 6121 - 5500 = 621 < 1000)
        final SavingsAccountTransaction newWithdrawal = createTransaction(account, null, SavingsAccountTransactionType.WITHDRAWAL,
                new LocalDate(2026, 11, 12), BigDecimal.valueOf(5500));
        transactions.add(newWithdrawal);

        // Should throw InsufficientAccountBalanceException
        account.validateAccountBalanceDoesNotBecomeNegative(BigDecimal.valueOf(5500), false, null);
    }

    @Test(expected = InsufficientAccountBalanceException.class)
    public void testWithdrawalFailsWhenNewWithdrawalExceedsBalance() throws Exception {
        final SavingsAccount account = createTestSavingsAccount(BigDecimal.valueOf(1000), true);
        @SuppressWarnings("unchecked")
        final List<SavingsAccountTransaction> transactions = (List<SavingsAccountTransaction>) account.getTransactions();

        // Historical deposit of 6121
        transactions.add(createTransaction(account, 1L, SavingsAccountTransactionType.DEPOSIT, new LocalDate(2026, 8, 11),
                BigDecimal.valueOf(6121)));

        // Proposed new withdrawal of 7000 (id = null, leaving negative balance)
        final SavingsAccountTransaction newWithdrawal = createTransaction(account, null, SavingsAccountTransactionType.WITHDRAWAL,
                new LocalDate(2026, 11, 12), BigDecimal.valueOf(7000));
        transactions.add(newWithdrawal);

        // Should throw InsufficientAccountBalanceException
        account.validateAccountBalanceDoesNotBecomeNegative(BigDecimal.valueOf(7000), false, null);
    }

    @Test(expected = InsufficientAccountBalanceException.class)
    public void testWithdrawalBlockedByOnHoldFunds() throws Exception {
        final SavingsAccount account = createTestSavingsAccount(BigDecimal.valueOf(1000), true);
        @SuppressWarnings("unchecked")
        final List<SavingsAccountTransaction> transactions = (List<SavingsAccountTransaction>) account.getTransactions();

        // Deposit of 6121
        transactions.add(createTransaction(account, 1L, SavingsAccountTransactionType.DEPOSIT, new LocalDate(2026, 8, 11),
                BigDecimal.valueOf(6121)));

        // 1000 placed on hold on 2026-11-01
        final List<DepositAccountOnHoldTransaction> onHoldList = new ArrayList<>();
        onHoldList.add(DepositAccountOnHoldTransaction.hold(account, BigDecimal.valueOf(1000), new LocalDate(2026, 11, 1)));

        // Attempt withdrawal of 4500 on 2026-11-12
        // Balance after withdrawal: 6121 - 4500 = 1621.
        // Required minimum + on-hold: 1000 + 1000 = 2000.
        // 1621 < 2000 -> must fail with InsufficientAccountBalanceException!
        final SavingsAccountTransaction newWithdrawal = createTransaction(account, null, SavingsAccountTransactionType.WITHDRAWAL,
                new LocalDate(2026, 11, 12), BigDecimal.valueOf(4500));
        transactions.add(newWithdrawal);

        account.validateAccountBalanceDoesNotBecomeNegative(BigDecimal.valueOf(4500), false, onHoldList);
    }

    @Test
    public void testWithdrawalAllowedAfterOnHoldFundsReleased() throws Exception {
        final SavingsAccount account = createTestSavingsAccount(BigDecimal.valueOf(1000), true);
        @SuppressWarnings("unchecked")
        final List<SavingsAccountTransaction> transactions = (List<SavingsAccountTransaction>) account.getTransactions();

        // Deposit of 6121
        transactions.add(createTransaction(account, 1L, SavingsAccountTransactionType.DEPOSIT, new LocalDate(2026, 8, 11),
                BigDecimal.valueOf(6121)));

        // 1000 placed on hold on 2026-11-01, but RELEASED on 2026-11-10
        final List<DepositAccountOnHoldTransaction> onHoldList = new ArrayList<>();
        onHoldList.add(DepositAccountOnHoldTransaction.hold(account, BigDecimal.valueOf(1000), new LocalDate(2026, 11, 1)));
        onHoldList.add(DepositAccountOnHoldTransaction.release(account, BigDecimal.valueOf(1000), new LocalDate(2026, 11, 10)));

        // Attempt withdrawal of 4500 on 2026-11-12
        // Balance after withdrawal: 6121 - 4500 = 1621.
        // Required minimum after release: 1000.
        // 1621 >= 1000 -> must succeed!
        final SavingsAccountTransaction newWithdrawal = createTransaction(account, null, SavingsAccountTransactionType.WITHDRAWAL,
                new LocalDate(2026, 11, 12), BigDecimal.valueOf(4500));
        transactions.add(newWithdrawal);

        account.validateAccountBalanceDoesNotBecomeNegative(BigDecimal.valueOf(4500), false, onHoldList);
    }
}
