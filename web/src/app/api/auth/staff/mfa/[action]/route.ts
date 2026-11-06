import { NextResponse, type NextRequest } from "next/server";
import { config } from "@/lib/config";

/**
 * Staff MFA enrolment before the first MFA sign-in (PLAN Phase 7): `enrol {email, password}` →
 * `{secret, otpauthUri}`, `confirm {email, password, code}`. No session exists yet, so the body is passed
 * through as it is; the backend checks the password.
 */
export async function POST(req: NextRequest, ctx: { params: Promise<{ action: string }> }) {
  const { action } = await ctx.params;
  if (action !== "enrol" && action !== "confirm") return NextResponse.json({ error: "Not found." }, { status: 404 });
  let res: Response;
  try {
    res = await fetch(`${config.backendBaseUrl}/api/auth/staff/mfa/${action}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: await req.text(),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ error: "Could not reach the authentication service." }, { status: 502 });
  }
  return new NextResponse(await res.text(), {
    status: res.status,
    headers: { "Content-Type": res.headers.get("Content-Type") ?? "application/json" },
  });
}
