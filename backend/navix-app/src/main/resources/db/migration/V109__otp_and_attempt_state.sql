-- DhanNow PLAN Phase 8: OTP codes and attempt counters move out of process memory, so every app instance sees
-- the same state and a restart forgets nothing. Keys and codes are stored as HMAC-SHA256 (keyed by the auth
-- secret): no mobile, email or code is readable from the table.
create table otp_challenge (
    id            bigserial primary key,
    purpose       varchar(32) not null,
    subject_hash  char(64)    not null,
    code_hash     char(64)    not null,
    expires_at    timestamp(6) with time zone not null,
    attempts      int         not null default 0,
    created_at    timestamp(6) with time zone not null default now(),
    unique (purpose, subject_hash)
);
create index otp_challenge_expires_idx on otp_challenge (expires_at);

-- One fixed window per limiter key (login, OTP send, per-IP, per-device ...). The caller's window length is
-- counted from window_start; blocked_until is the cool-off after the cap was passed.
create table attempt_counter (
    key            char(64)    primary key,
    window_start   timestamp(6) with time zone not null,
    count          int         not null,
    blocked_until  timestamp(6) with time zone
);
create index attempt_counter_window_idx on attempt_counter (window_start);
