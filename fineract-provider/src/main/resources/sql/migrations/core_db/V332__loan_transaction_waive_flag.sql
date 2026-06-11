--
-- V332__loan_transaction_waive_flag.sql
--
-- Creates the m_loan_transaction_waive_flag table used to track whether a
-- WAIVE_INTEREST loan transaction should waive only future interest
-- (is_waive_future_interest_only = 1) or all outstanding interest (= 0).
--
-- loan_transaction_id is the primary key (natural key) – no surrogate id column.
--

CREATE TABLE `m_loan_transaction_waive_flag` (
  `loan_transaction_id`           BIGINT      NOT NULL,
  `is_waive_future_interest_only` TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (`loan_transaction_id`)
);
