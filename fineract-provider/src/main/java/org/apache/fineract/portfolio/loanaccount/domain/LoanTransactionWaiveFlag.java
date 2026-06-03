package org.apache.fineract.portfolio.loanaccount.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

/**
 * Stores the {@code is_waive_future_interest_only} flag for a
 * {@link LoanTransaction} in a dedicated narrow table so that the flag
 * can be persisted without altering the very large {@code m_loan_transaction}
 * table.
 *
 * A row in this table always corresponds to a WAIVE_INTEREST transaction.
 * When {@code is_waive_future_interest_only = 1} the transaction processor
 * will use {@code processWaiveFutureInterestOnly} logic instead of the
 * standard interest-waiver logic.
 */
@Entity
@Table(name = "m_loan_transaction_waive_flag",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"loan_transaction_id"},
                             name = "uq_loan_txn_waive_flag_txn_id")
       })
public class LoanTransactionWaiveFlag extends AbstractPersistableCustom<Long> {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_transaction_id", nullable = false)
    private LoanTransaction loanTransaction;

    @Column(name = "is_waive_future_interest_only", nullable = false)
    private boolean waiveFutureInterestOnly;

    /** JPA no-arg constructor. */
    protected LoanTransactionWaiveFlag() {}

    /**
     * @param loanTransaction the owning transaction
     * @param waiveFutureInterestOnly {@code true} when only future interest should be waived
     */
    public LoanTransactionWaiveFlag(final LoanTransaction loanTransaction,
                                    final boolean waiveFutureInterestOnly) {
        this.loanTransaction = loanTransaction;
        this.waiveFutureInterestOnly = waiveFutureInterestOnly;
    }

    public boolean isWaiveFutureInterestOnly() {
        return this.waiveFutureInterestOnly;
    }

    public void setWaiveFutureInterestOnly(final boolean waiveFutureInterestOnly) {
        this.waiveFutureInterestOnly = waiveFutureInterestOnly;
    }

    public LoanTransaction getLoanTransaction() {
        return this.loanTransaction;
    }

    /** Called when the owning transaction is being copied (re-process path). */
    public LoanTransactionWaiveFlag copyFor(final LoanTransaction newOwner) {
        return new LoanTransactionWaiveFlag(newOwner, this.waiveFutureInterestOnly);
    }
}
