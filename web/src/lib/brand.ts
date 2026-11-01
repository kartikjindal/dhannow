/**
 * The one brand config for the staff console (PLAN Phase 10). Contacts mirror the backend
 * `navix.brand.*` / `navix.grievance.*` defaults; Phase 10a W5 generates both from `docs/brand.json`.
 * The lender of record is a backend config value and is never named here.
 */
export const BRAND = {
  name: "DhanNow",
  wordmark: "DHAN NOW",
  fileSlug: "dhannow",
  website: "https://dhannow.com",
  staffUrl: "https://staff.dhannow.com",
  support: { phone: "9211331169", email: "support@dhannow.com" },
  grievanceOfficer: { name: "Dharmendra Rajput", phone: "9211331170", email: "grievance@dhannow.com" },
} as const;
