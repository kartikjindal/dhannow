-- DhanNow PLAN Phase 7: four new staff roles and staff TOTP.
--   RECOVERY   field/legal recovery (no money rights of its own yet)
--   SUPPORT    the grievance desk: complaints and disputes, never a balance or a due date
--   FINANCE    refunds and payment reversals, alongside the Accountant
--   COMPLIANCE read-only across the console
alter table staff_user   drop constraint if exists staff_user_role_check;
alter table staff_invite drop constraint if exists staff_invite_role_check;

alter table staff_user add constraint staff_user_role_check check (role in (
    'CREDIT_EXECUTIVE','CREDIT_HEAD','DISBURSEMENT_HEAD','ACCOUNTANT',
    'COLLECTION_HEAD','COLLECTION_EXECUTIVE','TELECALLER','DSA','ADMIN',
    'RECOVERY','SUPPORT','FINANCE','COMPLIANCE'));

alter table staff_invite add constraint staff_invite_role_check check (role in (
    'CREDIT_EXECUTIVE','CREDIT_HEAD','DISBURSEMENT_HEAD','ACCOUNTANT',
    'COLLECTION_HEAD','COLLECTION_EXECUTIVE','TELECALLER','DSA','ADMIN',
    'RECOVERY','SUPPORT','FINANCE','COMPLIANCE'));

-- One TOTP secret per staff member, AES-GCM encrypted with NAVIX_MFA_ENCRYPTION_KEY (never stored in clear).
-- confirmed_at is set once the first code verifies; last_step stops a code being replayed in its window.
create table staff_totp_secret (
    staff_id      bigint primary key references staff_user (id),
    secret_enc    varchar(255) not null,
    confirmed_at  timestamp(6) with time zone,
    last_step     bigint,
    created_at    timestamp(6) with time zone not null default now()
);
