-- DhanNow PLAN Phase 8: app sessions. A short access JWT (15 min) plus a 30-day refresh token per device.
-- Only the SHA-256 of a refresh token is stored. Each use rotates it: the old row is revoked (ROTATED) and a
-- new row joins the same family. Presenting a revoked token again is theft (CC-39): the whole family is revoked.
create table refresh_token (
    id             bigserial primary key,
    customer_id    bigint       not null,
    device_id      varchar(128) not null,
    family_id      uuid         not null,
    token_hash     char(64)     not null unique,
    created_at     timestamp(6) with time zone not null default now(),
    expires_at     timestamp(6) with time zone not null,
    revoked_at     timestamp(6) with time zone,
    revoke_reason  varchar(24) check (revoke_reason in ('ROTATED','REUSED','LOGOUT','DEVICE_SIGNED_OUT','ALL_DEVICES'))
);
create index refresh_token_customer_idx on refresh_token (customer_id, device_id);
create index refresh_token_family_idx on refresh_token (family_id);
