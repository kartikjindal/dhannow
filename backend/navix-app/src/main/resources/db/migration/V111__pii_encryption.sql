-- V111 (PLAN Phase 9): Aadhaar, PAN and bank account numbers are stored encrypted (PiiCipher, AES-256-GCM,
-- "pii1:<base64>"). No DhanNow database exists yet, so there is no backfill; the converter still reads a plain
-- value as it is for the migration window.
--
-- 1. The ciphertext is longer than the number: widen every column that holds one.
-- 2. Equality lookups (PAN dedupe, the Aadhaar duplicate rule, search by a full PAN, lead attribution) move to a
--    blind index: <col>_hash = hex HMAC-SHA256 of the normalised value, written by the entity on every save.
-- 3. pii_access_log: one row per staff request that was shown a full value (PII_VIEW).

alter table customer_profile alter column pan type varchar(255);
alter table customer_profile alter column aadhaar type varchar(255);
alter table customer_profile alter column salary_account_number type varchar(255);
alter table customer_profile add column pan_hash varchar(64);
alter table customer_profile add column aadhaar_hash varchar(64);
drop index if exists ix_customer_profile_pan;
drop index if exists ix_customer_profile_aadhaar;
create index ix_customer_profile_pan_hash on customer_profile (pan_hash) where pan_hash is not null;
create index ix_customer_profile_aadhaar_hash on customer_profile (aadhaar_hash) where aadhaar_hash is not null;

alter table loan_application alter column disbursal_account_number type varchar(255);

alter table penny_drop_attempt alter column account_number type varchar(255);

alter table onboarding_waitlist alter column pan type varchar(255);
alter table onboarding_waitlist alter column aadhaar type varchar(255);
alter table onboarding_waitlist add column pan_hash varchar(64);
alter table onboarding_waitlist add column aadhaar_hash varchar(64);
drop index if exists idx_onboarding_waitlist_pan;
drop index if exists idx_onboarding_waitlist_aadhaar;
create index idx_onboarding_waitlist_pan_hash on onboarding_waitlist (pan_hash);
create index idx_onboarding_waitlist_aadhaar_hash on onboarding_waitlist (aadhaar_hash);

-- The format check cannot see through the ciphertext; DsaDtos and the import parser validate the PAN on the way in.
alter table lead drop constraint if exists chk_lead_pan;
alter table lead alter column pan type varchar(255);
alter table lead add column pan_hash varchar(64);
drop index if exists uq_lead_dsa_pan;
drop index if exists idx_lead_pan;
create unique index uq_lead_dsa_pan_hash on lead (pan_hash) where owner_dsa_id is not null;
create index idx_lead_pan_hash on lead (pan_hash) where pan_hash is not null;

alter table dsa_lead_rejection alter column pan type varchar(255);

create table pii_access_log (
    id          bigserial primary key,
    staff_id    varchar(64)  not null,
    role        varchar(32)  not null,
    method      varchar(8)   not null,
    path        varchar(500) not null,
    fields      varchar(200) not null,
    at          timestamptz  not null default now()
);
create index idx_pii_access_log_staff on pii_access_log (staff_id, at);
create trigger pii_access_log_append_only before update or delete on pii_access_log
    for each row execute function append_only();
