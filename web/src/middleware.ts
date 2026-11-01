import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * The httpOnly cookie the BFF staff login (`/api/auth/staff/login`) sets — a JSON blob
 * `{ token, id, name, role }` where `token` is the backend-issued JWT.
 *
 * The authoritative authorization boundary is the **backend** (`JwtAuthFilter` validates the bearer on every
 * `/api/**` call, and services enforce roles). This middleware is the gate for the staff shell: it lets a request
 * through only with a staff JWT whose HS256 signature verifies under `AUTH_SECRET` (the backend's secret; the HMAC
 * key is its SHA-256, as in `JwtService`), whose audience is `staff` and which has not expired (PLAN Phase 9).
 * Anything else redirects to login and clears the cookie. Without `AUTH_SECRET` a production build refuses every
 * session; a local build falls back to the expiry check.
 */
const SESSION_COOKIE = "navix_staff";
const PUBLIC_STAFF_PATHS = ["/staff/login", "/staff/activate", "/staff/forgot-password", "/staff/reset-password"];

function base64UrlToBytes(s: string): Uint8Array {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

type Claims = { exp?: number; aud?: string | string[] };

/** The claims of a well-formed JWT, unverified; null if unparseable. */
function decode(token: string): { header: { alg?: string }; claims: Claims } | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const text = (p: string) => new TextDecoder().decode(base64UrlToBytes(p));
    return { header: JSON.parse(text(parts[0])), claims: JSON.parse(text(parts[1])) as Claims };
  } catch {
    return null;
  }
}

let cachedKey: { secret: string; key: Promise<CryptoKey> } | null = null;

function hmacKey(secret: string): Promise<CryptoKey> {
  if (cachedKey?.secret !== secret) {
    const key = crypto.subtle
      .digest("SHA-256", new TextEncoder().encode(secret))
      .then((raw) => crypto.subtle.importKey("raw", raw, { name: "HMAC", hash: "SHA-256" }, false, ["verify"]));
    cachedKey = { secret, key };
  }
  return cachedKey.key;
}

/** Whether `token` is a staff JWT signed with `secret` (HS256) and unexpired at `now` (ms). */
export async function verifyStaffJwt(token: string, secret: string, now = Date.now()): Promise<boolean> {
  const decoded = decode(token);
  if (!decoded || decoded.header.alg !== "HS256") return false;
  const [h, p, sig] = token.split(".");
  let valid: boolean;
  try {
    valid = await crypto.subtle.verify(
      "HMAC",
      await hmacKey(secret),
      base64UrlToBytes(sig),
      new TextEncoder().encode(`${h}.${p}`),
    );
  } catch {
    return false;
  }
  if (!valid) return false;
  const { exp, aud } = decoded.claims;
  const audiences = Array.isArray(aud) ? aud : aud ? [aud] : [];
  return typeof exp === "number" && exp * 1000 > now && audiences.includes("staff");
}

/** A staff session is usable if the cookie holds a verified, unexpired staff token. */
async function hasValidStaffSession(raw: string | undefined): Promise<boolean> {
  if (!raw) return false;
  let token: string | undefined;
  try {
    token = (JSON.parse(raw) as { token?: string }).token;
  } catch {
    return false;
  }
  if (!token) return false;
  const secret = process.env.AUTH_SECRET;
  if (secret) return verifyStaffJwt(token, secret);
  if (process.env.NODE_ENV === "production") return false; // fail closed: no secret, no session
  const exp = decode(token)?.claims.exp;
  return typeof exp === "number" && exp * 1000 > Date.now();
}

/** Gate the internal (staff) routes — all staff screens live under `/staff`. */
export async function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (PUBLIC_STAFF_PATHS.some((path) => pathname.startsWith(path))) {
    return NextResponse.next();
  }

  if (!(await hasValidStaffSession(request.cookies.get(SESSION_COOKIE)?.value))) {
    const loginUrl = new URL("/staff/login", request.url);
    loginUrl.searchParams.set("redirect", pathname);
    const res = NextResponse.redirect(loginUrl);
    res.cookies.delete(SESSION_COOKIE); // drop a malformed, forged or expired session cookie
    return res;
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/staff/:path*"],
};
