-- DhanNow PLAN Phase 6b: collections-led loan extension.
--
-- An officer offers 14 more days after a call, the Collection Head approves, the borrower confirms with
-- an OTP and pays (fee + GST + 14 days interest + the late charge to the bank date), and the Accountant's
-- verification is the only thing that moves the due date. Cap from product_config.extension_max.

-- 1. The cap is a versioned product rule, frozen with the others once a version leaves DRAFT.
alter table product_config add column extension_max int not null default 3 check (extension_max >= 0);

create or replace function product_config_terms_frozen() returns trigger language plpgsql as $$
begin
    if old.status <> 'DRAFT' and (new.version, new.allowed_amounts_paise, new.min_paise, new.max_paise,
            new.due_cutoff_time, new.timezone, new.one_live_loan, new.late_blocks_upgrade_days,
            new.overdue_review_dpd, new.offer_validity_days, new.extension_max)
            is distinct from (old.version, old.allowed_amounts_paise, old.min_paise, old.max_paise,
            old.due_cutoff_time, old.timezone, old.one_live_loan, old.late_blocks_upgrade_days,
            old.overdue_review_dpd, old.offer_validity_days, old.extension_max) then
        raise exception 'product_config % is %: its terms are frozen, add a new version', old.version, old.status;
    end if;
    return new;
end $$;

-- 2. The loan remembers its first due date, how often it was extended, and the late charge already
--    settled in earlier cycles: penal_days_used counts toward the lifetime 30-day cap (EXT-26), and
--    penal_carried_paise is late charge from earlier cycles that repayments (not the extension) paid.
alter table loan add column extension_count int not null default 0 check (extension_count >= 0);
alter table loan add column original_due_date date;
alter table loan add column penal_days_used int not null default 0 check (penal_days_used >= 0);
alter table loan add column penal_carried_paise bigint not null default 0 check (penal_carried_paise >= 0);
update loan set original_due_date = due_date where original_due_date is null;

-- 3. The extension itself. One non-terminal extension per loan: a second concurrent offer fails here
--    (EXT-01). seq numbers every offer on the loan.
create table loan_extension (
    id                    uuid primary key,
    loan_id               bigint not null references loan (id),
    collection_case_id    uuid not null,
    seq                   int not null check (seq >= 1),
    status                varchar(24) not null check (status in ('OFFERED', 'APPROVED', 'ACCEPTED',
                              'PAYMENT_PENDING', 'ACTIVE', 'REJECTED', 'CANCELLED', 'EXPIRED', 'DECLINED')),
    version               bigint not null default 0,
    -- The fixed part of the charge, from the loan's frozen pricing (the late charge follows paid_on).
    fee_paise             bigint not null check (fee_paise >= 0),
    gst_paise             bigint not null check (gst_paise >= 0),
    interest_paise        bigint not null check (interest_paise >= 0),
    pay_by                date not null,
    expires_at            timestamp(6) with time zone not null,
    old_due_date          date not null,
    new_due_date          date not null,
    call_note             text,
    recording_id          varchar(255),
    override_reason       varchar(255),
    proposed_by           bigint not null,
    proposed_at           timestamp(6) with time zone not null,
    approved_by           bigint,
    approved_at           timestamp(6) with time zone,
    rejected_by           bigint,
    rejected_at           timestamp(6) with time zone,
    reject_reason         varchar(64),
    reject_note           text,
    cancelled_by          bigint,
    cancelled_at          timestamp(6) with time zone,
    cancel_reason         varchar(64),
    accepted_at           timestamp(6) with time zone,
    consent_version       varchar(64),
    declined_at           timestamp(6) with time zone,
    declined_reason       varchar(64),
    seen_by_borrower_at   timestamp(6) with time zone,
    expired_at            timestamp(6) with time zone,
    payment_id            bigint references payment (id),
    activated_at          timestamp(6) with time zone,
    resend_day            date,
    resend_count          int not null default 0,
    unique (loan_id, seq)
);
create unique index uq_loan_extension_open on loan_extension (loan_id)
    where status in ('OFFERED', 'APPROVED', 'ACCEPTED', 'PAYMENT_PENDING');
create index idx_loan_extension_status on loan_extension (status, expires_at);

-- 4. Collections may log the extension payment; optimistic locking on the row (EXT-20).
alter table collection_payment drop constraint collection_payment_kind_check;
alter table collection_payment add constraint collection_payment_kind_check
    check (kind in ('PART_PAYMENT', 'FULL_PAYMENT', 'SETTLEMENT', 'EXTENSION'));
alter table collection_payment add column extension_id uuid references loan_extension (id);
alter table collection_payment add column version bigint not null default 0;
alter table collection_payment add constraint collection_payment_extension_check
    check ((kind = 'EXTENSION') = (extension_id is not null));

-- 5. A payment is a repayment or an extension charge. Extension money never reduces principal and is
--    never in the "verified" sum. One live payment per extension (CC-70).
alter table payment add column purpose varchar(16) not null default 'REPAYMENT'
    check (purpose in ('REPAYMENT', 'EXTENSION'));
alter table payment add column extension_id uuid references loan_extension (id);
alter table payment add constraint payment_extension_check check ((purpose = 'EXTENSION') = (extension_id is not null));
create unique index uq_payment_live_extension on payment (extension_id)
    where extension_id is not null and status <> 'REJECTED';

alter table payment_allocation drop constraint payment_allocation_component_check;
alter table payment_allocation add constraint payment_allocation_component_check
    check (component in ('PENAL', 'INTEREST', 'PRINCIPAL', 'EXCESS', 'EXT_FEE', 'EXT_GST', 'EXT_INTEREST'));

-- 6. Each due cycle, so DPD and lateness per cycle survive the move (EXT-28). Written when a loan is
--    first extended: the original cycle is closed and the next opened; the open row has to_at null.
create table loan_due_history (
    id           bigint generated by default as identity primary key,
    loan_id      bigint not null references loan (id),
    seq          int not null check (seq >= 1),
    due_date     date not null,
    due_at       timestamp(6) with time zone not null,
    from_at      timestamp(6) with time zone not null,
    to_at        timestamp(6) with time zone,
    penal_days   int not null default 0,
    extension_id uuid references loan_extension (id),
    unique (loan_id, seq)
);
create unique index uq_loan_due_history_open on loan_due_history (loan_id) where to_at is null;

-- 7. A loan with any activated extension closes without a level change.
alter table credit_level_history drop constraint credit_level_history_reason_check;
alter table credit_level_history add constraint credit_level_history_reason_check
    check (reason in ('FIRST_LOAN', 'ON_TIME_REPAID', 'LATE_REPAID_NO_CHANGE', 'EXTENDED_NO_CHANGE', 'MANUAL_EXCEPTION'));
drop index credit_level_history_one_per_closure;
create unique index credit_level_history_one_per_closure on credit_level_history (loan_id)
    where reason in ('ON_TIME_REPAID', 'LATE_REPAID_NO_CHANGE', 'EXTENDED_NO_CHANGE');

-- 8. balance_adjustment, minimal: written by every extension activation. Phase 7 extends this table
--    (reason_code lookup, reconciliation) rather than creating it.
create table balance_adjustment (
    id                     bigint generated by default as identity primary key,
    loan_id                bigint not null references loan (id),
    customer_id            bigint not null,
    kind                   varchar(32) not null,
    before                 jsonb not null,
    after                  jsonb not null,
    schedule_version_from  int,
    schedule_version_to    int,
    calc_version           bigint,
    approver_id            bigint,
    reason_code            varchar(64),
    acceptance_evidence    jsonb,
    reconciliation_id      varchar(128),
    at                     timestamp(6) with time zone not null default now()
);
create index idx_balance_adjustment_loan on balance_adjustment (loan_id);
