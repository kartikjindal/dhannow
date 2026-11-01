# DhanNow staff console

Next.js (App Router) staff console for DhanNow (`staff.dhannow.com`), forked from navix (PLAN Phase 10).
Borrowers use the Android app (`app/`); the public site is static (`site/`). There is no borrower web.

## Getting started

```bash
# 1. Configure environment
cp .env.example .env.local
# edit .env.local and set AUTH_SECRET, etc.

# 2. Install dependencies
npm install

# 3. Run the dev server (http://localhost:3000)
npm run dev
```

The console talks to the Spring Boot backend at `http://localhost:8080` through its BFF (`src/app/api`).

## Layout

- `src/app/staff` — every console screen; `/` redirects to `/staff/dashboard`, and the edge middleware gates
  `/staff/*` on a verified staff JWT.
- `src/app/api/staff/*`, `src/app/api/auth/staff/*` — the BFF; `/api/staff/desk/*` proxies the Phase 10 desks
  (complaints, selfie review, refunds, holidays, config, adjustment log, holds) to an allowlist of backend paths.
- `src/lib/api/staff-desks.ts` — clients for those desks; `src/lib/collection-buckets.ts` and
  `src/lib/domain/loan.ts` mirror the backend enums (tests read the Java files).
- `src/lib/copy-guard.test.ts` fails the build on navix wording or if the borrower web comes back; eslint
  `no-restricted-imports` blocks borrower imports.

## Middleware & role gating

`src/middleware.ts` guards the `(staff)` routes. It reads the session cookie,
redirects unauthenticated users to `/login`, and redirects authenticated users
whose role does not permit a given staff route away from it. See the `matcher`
in `export const config`.
