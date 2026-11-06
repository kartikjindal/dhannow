-- DhanNow PLAN Phase 1: the pricing/product versions frozen on the offer (set at sanction) and copied to
-- the loan at disbursal. Rows created before this migration carry none and keep the navix terms
-- (LoanMath.NAVIX_PRICING).
alter table loan_application
    add column pricing_version_id bigint references pricing_config (id),
    add column product_version_id bigint references product_config (id);

alter table loan
    add column pricing_version_id     bigint references pricing_config (id),
    add column product_version_id     bigint references product_config (id),
    -- Interest deducted at disbursal (UPFRONT mode); null on navix loans.
    add column upfront_interest_paise bigint,
    add column tenure_days            int,
    -- The instant the loan falls due: due_date at product_config.due_cutoff_time in its timezone.
    add column due_at                 timestamp(6) with time zone;
