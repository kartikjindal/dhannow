# k6

`journey.js` drives 100 requests/s at the borrower journey and staff dashboard reads on **staging** and fails unless p95 < 400 ms and errors < 1% (PLAN Phase 12): `k6 run tools/k6/journey.js -e BASE_URL=https://api.staging.dhannow.com -e BORROWER_TOKEN=… -e STAFF_TOKEN=…` (or the `load-test` workflow). Attach the summary to the release (PLAN Verification 8).
