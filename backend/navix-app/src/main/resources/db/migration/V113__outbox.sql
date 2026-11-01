-- V113 (PLAN Phase 9, EXT-22): after-commit side effects (schedule PDF, closure receipt, extension addendum and their
-- notifications) are queued here in the state change's transaction, tried right after the commit and retried by
-- OutboxPoller with backoff. done_at is set on success; attempts and last_error record the failures.
create table outbox (
    id              bigserial primary key,
    kind            varchar(40)  not null,
    payload         jsonb        not null,
    attempts        int          not null default 0,
    next_attempt_at timestamptz  not null default now(),
    last_error      varchar(500),
    done_at         timestamptz,
    created_at      timestamptz  not null default now()
);
create index idx_outbox_due on outbox (next_attempt_at) where done_at is null;
