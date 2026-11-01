/**
 * Date helpers for staff screens (PLAN Phase 10b R7, R11). Every money figure comes from the backend
 * (`LoanMath` over the loan's frozen pricing snapshot, in paise); the console only formats it, so no rate,
 * tenure or salary-day rule lives here. Dates are India dates: a day is the Asia/Kolkata calendar day,
 * never the browser's local one.
 */

const DAY_MS = 24 * 60 * 60 * 1000;
/** IST is UTC+05:30 all year (no daylight saving). */
const IST_OFFSET_MS = 330 * 60 * 1000;

/** The IST calendar day number of an instant. */
function istDay(d: Date): number {
  return Math.floor((d.getTime() + IST_OFFSET_MS) / DAY_MS);
}

/** Whole IST calendar days from `a` to `b` (b − a). */
export function daysBetween(a: Date, b: Date): number {
  return istDay(b) - istDay(a);
}

/** The IST calendar date (yyyy-mm-dd) of an instant; today by default. */
export function istDate(at: Date | string = new Date()): string {
  return new Date(istDay(typeof at === "string" ? new Date(at) : at) * DAY_MS).toISOString().slice(0, 10);
}
