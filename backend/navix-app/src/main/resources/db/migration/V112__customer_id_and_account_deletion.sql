-- V112 (PLAN Phase 9).
--
-- 1. CC-24: the customer id is server-generated, shown as DN + seven digits (DN0000001). borrower_mobile (V40) is
--    now the registry of which id each mobile signs in to; the mobile is only an attribute. No DhanNow database
--    exists yet, so no derived id is carried over.
create sequence customer_id_seq start with 1 increment by 1;

-- 2. CC-45: account deletion from the app. Blocked while a loan is open; on request the sign-in is closed, sessions
--    and push tokens end, offers are switched off and the consent withdrawal is recorded. KYC and loan records stay
--    until purge_after (navix.account-deletion.retention-years), then AccountPurgeJob blanks the identity fields.
create table account_deletion_request (
    id                   bigserial primary key,
    customer_id          bigint      not null unique,
    status               varchar(16) not null default 'REQUESTED',
    requested_at         timestamptz not null default now(),
    consent_withdrawn_at timestamptz not null default now(),
    purge_after          date        not null,
    purged_at            timestamptz,
    constraint chk_account_deletion_status check (status in ('REQUESTED', 'PURGED'))
);
create index idx_account_deletion_due on account_deletion_request (purge_after) where status = 'REQUESTED';
