-- DhanNow PLAN Phase 6c: recovery EMI conversion, closure type, payment reversal, refund actions and
-- overdue instalments. Flag emi-conversion (V95) stays OFF.

-- 1. The EMI plan is a versioned product rule: instalments every emi_gap_days days from the conversion
--    date; two instalments, or three from emi_three_from_paise of principal (Rs 7,000 and Rs 10,000).
alter table product_config add column emi_gap_days int not null default 10 check (emi_gap_days >= 1);
alter table product_config add column emi_three_from_paise bigint not null default 700000
    check (emi_three_from_paise > 0);

create or replace function product_config_terms_frozen() returns trigger language plpgsql as $$
begin
    if old.status <> 'DRAFT' and (new.version, new.allowed_amounts_paise, new.min_paise, new.max_paise,
            new.due_cutoff_time, new.timezone, new.one_live_loan, new.late_blocks_upgrade_days,
            new.overdue_review_dpd, new.offer_validity_days, new.extension_max, new.emi_gap_days,
            new.emi_three_from_paise)
            is distinct from (old.version, old.allowed_amounts_paise, old.min_paise, old.max_paise,
            old.due_cutoff_time, old.timezone, old.one_live_loan, old.late_blocks_upgrade_days,
            old.overdue_review_dpd, old.offer_validity_days, old.extension_max, old.emi_gap_days,
            old.emi_three_from_paise) then
        raise exception 'product_config % is %: its terms are frozen, add a new version', old.version, old.status;
    end if;
    return new;
end $$;

-- 2. The conversion. Collections requests, an ADMIN approves, the borrower accepts with an OTP, and the
--    acceptance activates it: the money owed that day is split into instalments (a new EMI schedule).
--    One open (REQUESTED or APPROVED) conversion per loan.
create table emi_conversion (
    id                 uuid primary key,
    loan_id            bigint not null references loan (id),
    status             varchar(16) not null check (status in ('REQUESTED', 'APPROVED', 'ACTIVE', 'REJECTED',
                           'CANCELLED', 'DECLINED', 'EXPIRED')),
    version            bigint not null default 0,
    instalment_count   int not null check (instalment_count >= 1),
    gap_days           int not null check (gap_days >= 1),
    accept_by          date not null,
    expires_at         timestamp(6) with time zone not null,
    note               text,
    requested_by       bigint not null,
    requested_at       timestamp(6) with time zone not null,
    approved_by        bigint,
    approved_at        timestamp(6) with time zone,
    rejected_by        bigint,
    rejected_at        timestamp(6) with time zone,
    reject_reason      varchar(64),
    reject_note        text,
    cancelled_by       bigint,
    cancelled_at       timestamp(6) with time zone,
    cancel_reason      varchar(64),
    accepted_at        timestamp(6) with time zone,
    consent_version    varchar(64),
    declined_at        timestamp(6) with time zone,
    declined_reason    varchar(64),
    expired_at         timestamp(6) with time zone,
    -- Frozen at activation: what was owed on the conversion date and how it was split.
    conversion_date    date,
    principal_paise    bigint check (principal_paise >= 0),
    charges_paise      bigint check (charges_paise >= 0),
    interest_mode      varchar(16),
    schedule_version   int,
    activated_at       timestamp(6) with time zone
);
create unique index uq_emi_conversion_open on emi_conversion (loan_id) where status in ('REQUESTED', 'APPROVED');
create index idx_emi_conversion_status on emi_conversion (status, expires_at);

-- 3. Instalments: an EMI instalment carries its share of the late charge converted, and an instalment
--    past its due_at reads OVERDUE (CC-17). The terms stay frozen.
alter table schedule_instalment add column penal_paise bigint not null default 0 check (penal_paise >= 0);
alter table schedule_instalment drop constraint schedule_instalment_status_check;
alter table schedule_instalment add constraint schedule_instalment_status_check
    check (status in ('DUE', 'OVERDUE', 'PAID'));

create or replace function schedule_instalment_frozen() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'schedule_instalment % cannot be deleted', old.id;
    end if;
    if (new.schedule_id, new.seq, new.due_date, new.due_at, new.principal_paise, new.interest_paise,
            new.fee_paise, new.gst_paise, new.penal_paise)
            is distinct from (old.schedule_id, old.seq, old.due_date, old.due_at, old.principal_paise,
            old.interest_paise, old.fee_paise, old.gst_paise, old.penal_paise) then
        raise exception 'schedule_instalment % is immutable: add a new schedule version', old.id;
    end if;
    return new;
end $$;

-- 4. How a loan closed (CC-18), and when it was restructured (no late charge accrues after that while
--    emi.penal is off).
alter table loan add column closure_type varchar(16) check (closure_type in ('REPAID', 'SETTLED'));
alter table loan add column restructured_at timestamp(6) with time zone;
update loan l set closure_type = case
    when exists (select 1 from settlement s join collection_case cc on cc.id = s.collection_case_id
                 where cc.loan_id = l.id and s.status = 'APPROVED') then 'SETTLED' else 'REPAID' end
where l.lifecycle_status = 'CLOSED' and l.closed_on is not null;

-- 5. A verified payment found bounced is REVERSED (CC-10): it no longer counts, and it keeps its UTR.
alter table payment drop constraint payment_status_check;
alter table payment add constraint payment_status_check
    check (status in ('PENDING_VERIFICATION', 'VERIFIED', 'REJECTED', 'REVERSED'));
alter table payment add column reversed_by bigint;
alter table payment add column reversed_at timestamp(6) with time zone;
alter table payment add column reversal_reason varchar(255);

-- 6. Refund case actions: paid back with a UTR, or cancelled with a reason.
alter table refund_case add column refund_utr varchar(64);
alter table refund_case add column refunded_on date;
alter table refund_case add column decided_by bigint;
alter table refund_case add column cancel_reason varchar(255);
alter table refund_case add constraint refund_case_refunded_check
    check (status <> 'REFUNDED' or refund_utr is not null);

-- 7. A reversed closure gives back any level it gave (a history row), and the loan may close again.
alter table credit_level_history drop constraint credit_level_history_reason_check;
alter table credit_level_history add constraint credit_level_history_reason_check
    check (reason in ('FIRST_LOAN', 'ON_TIME_REPAID', 'LATE_REPAID_NO_CHANGE', 'EXTENDED_NO_CHANGE',
                      'MANUAL_EXCEPTION', 'CLOSURE_REVERSED'));
alter table credit_level_history add column reversed_at timestamp(6) with time zone;
drop index credit_level_history_one_per_closure;
create unique index credit_level_history_one_per_closure on credit_level_history (loan_id)
    where reason in ('ON_TIME_REPAID', 'LATE_REPAID_NO_CHANGE', 'EXTENDED_NO_CHANGE') and reversed_at is null;
