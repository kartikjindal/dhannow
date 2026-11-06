-- DhanNow PLAN Phase 3: one row per scheduled job, ShedLock style. An instance runs a job only while it
-- holds the lease (lock_until in the future), so several app instances never run the same sweep at once.
create table scheduler_lock (
    name        varchar(64)  primary key,
    lock_until  timestamp(6) with time zone not null,
    locked_at   timestamp(6) with time zone not null,
    locked_by   varchar(255) not null
);
