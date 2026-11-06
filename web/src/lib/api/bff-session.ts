/**
 * Server-side helpers for the staff BFF session (the borrower web is gone, PLAN Phase 10):
 * cookie `navix_staff` = { token, id, name, role }.
 *
 * The `token` is the backend-issued JWT. The proxy route handlers read it and
 * forward it as `Authorization: Bearer <token>`; it is never exposed to the
 * browser (the `/me` routes return the identity without the token).
 */

import { cookies } from "next/headers";
import type { StaffRole } from "@/lib/auth/rbac";

export const STAFF_COOKIE = "navix_staff";

export interface StaffBffSession {
  /** Backend-issued JWT (forwarded as a bearer; never sent to the browser). */
  token: string;
  id: string;
  name: string;
  role: StaffRole;
}

const COOKIE_OPTS = {
  httpOnly: true,
  path: "/",
  sameSite: "lax" as const,
  // Secure (HTTPS-only) in production; left off in dev so it works on http://localhost.
  secure: process.env.NODE_ENV === "production",
  maxAge: 60 * 60 * 24, // 1 day (staff)
};

// ---------------------------------------------------------------------------
// Staff
// ---------------------------------------------------------------------------

export async function setStaffSession(session: StaffBffSession): Promise<void> {
  const store = await cookies();
  store.set(STAFF_COOKIE, JSON.stringify(session), COOKIE_OPTS);
}

export async function clearStaffSession(): Promise<void> {
  const store = await cookies();
  store.set(STAFF_COOKIE, "", { ...COOKIE_OPTS, maxAge: 0 });
}

export async function getStaffSession(): Promise<StaffBffSession | null> {
  const store = await cookies();
  const raw = store.get(STAFF_COOKIE)?.value;
  if (!raw) return null;
  try {
    return JSON.parse(raw) as StaffBffSession;
  } catch {
    return null;
  }
}
