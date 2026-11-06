-- DhanNow PLAN Phase 3: the loan's status split into separate dimensions. lifecycle_status is the state
-- machine (LoanLifecycle), hold_status a dispute/fraud hold, repayment_status where repayment stands.
-- The navix status column stays, written in step by the application, for navix reports.
alter table loan
    add column lifecycle_status varchar(32) not null default 'ACTIVE',
    add column hold_status      varchar(16) not null default 'NONE',
    add column repayment_status varchar(32) not null default 'UNPAID';

-- Open loans backfill to ACTIVE: the lifecycle job derives DUE_TODAY / OVERDUE_* from the time against
-- the due date on its first run (CC-13). IN_COLLECTIONS is the navix twin of OVERDUE_REVIEW.
update loan set lifecycle_status = case
    when status in ('CLOSED', 'REPAID', 'DECLINED') then 'CLOSED'
    when status = 'IN_COLLECTIONS' then 'OVERDUE_REVIEW'
    when status in ('ACTIVE', 'OVERDUE') then 'ACTIVE'
    else 'DISBURSEMENT_PENDING'
end;

update loan l set repayment_status = case
    when l.lifecycle_status = 'CLOSED' then 'PAID'
    when exists (select 1 from payment p where p.loan_id = l.id and p.status = 'PENDING_VERIFICATION')
        then 'PAID_PENDING_VERIFICATION'
    when exists (select 1 from payment p where p.loan_id = l.id and p.status = 'VERIFIED') then 'PARTIAL'
    else 'UNPAID'
end;

alter table loan drop constraint loan_status_check;
alter table loan add constraint loan_status_check check (
    status in ('APPLIED', 'UNDER_REVIEW', 'APPROVED', 'DOCS_SIGNED', 'BANK_VERIFIED', 'DISBURSING', 'ACTIVE',
               'REPAID', 'OVERDUE', 'IN_COLLECTIONS', 'CLOSED', 'DECLINED')
    and lifecycle_status in ('DISBURSEMENT_PENDING', 'ACTIVE', 'DUE_TODAY', 'OVERDUE_D1', 'OVERDUE_REVIEW',
                             'RESTRUCTURE_PENDING', 'RESTRUCTURED', 'CLOSED')
    and hold_status in ('NONE', 'DISPUTED', 'FRAUD_REVIEW')
    and repayment_status in ('UNPAID', 'PARTIAL', 'PAID_PENDING_VERIFICATION', 'PAID'));

-- The lifecycle job scans open loans by state and due date.
create index loan_lifecycle_due on loan (lifecycle_status, due_date);

-- The document types Phases 5-6 store.
alter table loan_document drop constraint loan_document_type_check;
alter table loan_document add constraint loan_document_type_check check (type in (
    'AGREEMENT', 'SANCTION', 'KFS', 'SCHEDULE', 'CLOSURE_RECEIPT', 'EXTENSION_ADDENDUM', 'EMI_ADDENDUM'));

-- Days past due at which OVERDUE_D1 becomes OVERDUE_REVIEW (navix OVERDUE_BUCKET_DAYS). Versioned
-- product config like the rest, so it is frozen with the other terms once a version leaves DRAFT.
alter table product_config add column overdue_review_dpd int not null default 7 check (overdue_review_dpd >= 1);

create or replace function product_config_terms_frozen() returns trigger language plpgsql as $$
begin
    if old.status <> 'DRAFT' and (new.version, new.allowed_amounts_paise, new.min_paise, new.max_paise,
            new.due_cutoff_time, new.timezone, new.one_live_loan, new.late_blocks_upgrade_days,
            new.overdue_review_dpd)
            is distinct from (old.version, old.allowed_amounts_paise, old.min_paise, old.max_paise,
            old.due_cutoff_time, old.timezone, old.one_live_loan, old.late_blocks_upgrade_days,
            old.overdue_review_dpd) then
        raise exception 'product_config % is %: its terms are frozen, add a new version', old.version, old.status;
    end if;
    return new;
end $$;
