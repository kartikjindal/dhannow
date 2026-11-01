-- DhanNow PLAN Phase 7: balance_adjustment (minimal since V103) gets its reason-code lookup, a free-text note,
-- a check on kind, and is append-only. Every money or schedule change outside the payment waterfall writes one.

create table balance_adjustment_reason (
    code   varchar(64) primary key,
    label  varchar(255) not null
);
insert into balance_adjustment_reason (code, label) values
    ('EXTENSION', 'Loan extended by collections'),
    ('EMI', 'Converted to a recovery EMI'),
    ('PAID_ON_TIME', 'Paid by the due date, verified later: late state reversed'),
    ('PAYMENT_REVERSED', 'Verified payment bounced or reversed by the bank'),
    ('SETTLEMENT_APPROVED', 'Settlement approved'),
    ('REFUND_PAID', 'Excess money paid back to the borrower'),
    ('REFUND_CANCELLED', 'Refund case cancelled: nothing owed back'),
    ('MANUAL_CORRECTION', 'Manual correction by finance'),
    ('WRITE_OFF', 'Written off');

alter table balance_adjustment add column note text;

-- 6c wrote the reversal's free-text reason into reason_code: move it to note.
update balance_adjustment set note = reason_code, reason_code = 'PAYMENT_REVERSED'
    where kind = 'PAYMENT_REVERSAL';
update balance_adjustment set reason_code = kind
    where reason_code is null and kind in ('EXTENSION', 'EMI');

alter table balance_adjustment
    add constraint balance_adjustment_reason_fk foreign key (reason_code) references balance_adjustment_reason (code);
alter table balance_adjustment alter column reason_code set not null;
alter table balance_adjustment add constraint balance_adjustment_kind_check check (kind in ('EXTENSION', 'EMI',
    'LATE_REVERSED', 'PAYMENT_REVERSAL', 'SETTLEMENT', 'REFUND', 'MANUAL_CORRECTION', 'WRITE_OFF'));

create trigger trg_balance_adjustment_append_only before update or delete on balance_adjustment
    for each row execute function append_only();
