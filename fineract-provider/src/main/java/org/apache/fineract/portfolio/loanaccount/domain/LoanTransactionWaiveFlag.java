package org.apache.fineract.portfolio.loanaccount.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import java.io.Serializable;

@Entity
@Table(name = "m_loan_transaction_waive_flag")
public class LoanTransactionWaiveFlag implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "loan_transaction_id", nullable = false)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @javax.persistence.MapsId
    @JoinColumn(name = "loan_transaction_id", nullable = false)
    private LoanTransaction loanTransaction;

    @Column(name = "is_waive_future_interest_only", nullable = false)
    private boolean waiveFutureInterestOnly;

    protected LoanTransactionWaiveFlag() {}

    public LoanTransactionWaiveFlag(final LoanTransaction loanTransaction,
                                    final boolean waiveFutureInterestOnly) {
        this.loanTransaction = loanTransaction;
        this.waiveFutureInterestOnly = waiveFutureInterestOnly;
    }

    public Long getId() {
        return this.id;
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

    public LoanTransactionWaiveFlag copyFor(final LoanTransaction newOwner) {
        return new LoanTransactionWaiveFlag(newOwner, this.waiveFutureInterestOnly);
    }
}
