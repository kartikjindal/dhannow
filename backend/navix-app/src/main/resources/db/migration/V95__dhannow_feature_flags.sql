-- DhanNow PLAN Phase 1: stage-gated feature flags, on the navix feature_flag table (V31). Changed by SQL
-- only, like every other row there. Features not built yet ship OFF and are switched on by the phase
-- that builds them. `do nothing` on conflict so a redeploy never overrides an operator's choice.
insert into feature_flag (flag_key, enabled, description, created_at)
values ('loan-extension', true, 'Collections-led loan extension (+14 days, cap 3). Phase 6', now()),
       ('emi-conversion', false, 'Recovery-only EMI conversion. Phase 6; OFF at launch', now()),
       ('penal-charges', true, 'Late charge on overdue principal (rate and cap from pricing_config)', now()),
       ('prepay-rebate', false, 'Refund of unused upfront interest on early repayment. OFF: none', now()),
       ('payment-gateway', false, 'Payment gateway port (manual UPI/bank transfer is the v1 path)', now()),
       ('cooling-off-exit', false, 'Cooling-off exit. Not in v1; unused', now()),
       ('play-integrity', false, 'Play Integrity check on app auth. Phase 8', now()),
       ('push-fcm', false, 'FCM push notifications. Phase 8', now())
on conflict (flag_key) do nothing;
