-- DhanNow PLAN Phase 4: disbursal confirmation + maker-checker.
--
-- 1. DISBURSAL_CONFIRMATION_PENDING sits between the Disbursement Head's UTR entry and ACTIVE: the
--    loan row is minted DISBURSEMENT_PENDING with the UTR, and an Accountant confirms the transfer
--    (day 0 = the confirmed date) or returns it to the Head. ACCOUNTANT_PENDING stays historical.
alter table loan_application drop constraint if exists loan_application_status_check;

alter table loan_application
    add constraint loan_application_status_check check (status in (
        'DRAFT','KYC_PENDING','KYC_APPROVED','KYC_REJECTED','PRE_APPROVED','REVIEW_PENDING',
        'CREDIT_EXEC_PENDING','CREDIT_EXEC_APPROVED','CREDIT_HEAD_PENDING','CREDIT_HEAD_APPROVED',
        'SANCTIONED','DISBURSEMENT_PENDING','DISBURSAL_CONFIRMATION_PENDING','ACCOUNTANT_PENDING',
        'DISBURSEMENT_FAILED','DISBURSED',
        'ACTIVE','OVERDUE','DEFAULTED','CLOSED','WRITTEN_OFF','REJECTED','CANCELLED'));

-- 2. Who recorded a payment, so the verifier can be held to someone else (recorder ≠ verifier).
--    Null for borrower-recorded payments, payments validated upstream (collections, NACH) and old rows.
alter table payment add column recorded_by bigint;

comment on column payment.recorded_by is
    'Staff member who recorded the payment (PLAN Phase 4 SoD: the verifier must differ). Null = borrower or upstream-validated.';
