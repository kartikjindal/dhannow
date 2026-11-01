-- DhanNow PLAN Phase 8: the borrower's signed-in devices (Settings > Devices) and their FCM push token.
-- A row is written at app sign-in and by PUT /api/devices/me; signing a device out deletes it.
create table device_token (
    customer_id  bigint       not null,
    device_id    varchar(128) not null,
    fcm_token    varchar(512),
    platform     varchar(16)  not null default 'android',
    app_version  varchar(32),
    device_name  varchar(128),
    created_at   timestamp(6) with time zone not null default now(),
    last_seen    timestamp(6) with time zone not null default now(),
    primary key (customer_id, device_id)
);
create index device_token_fcm_idx on device_token (fcm_token);
