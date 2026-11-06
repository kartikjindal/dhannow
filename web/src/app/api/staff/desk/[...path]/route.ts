import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { forbidden, joinPath, proxyToBackend, unauthorized } from "@/lib/api/bff-proxy";

/**
 * One proxy for the staff desks added in PLAN Phase 10: `/api/staff/desk/<path>` → backend `/api/<path>`,
 * only for the paths below (the backend still checks every role). Anything else is 403 here.
 */
const ALLOWED = [
  /^staff\/complaints(\/\d+(\/(acknowledge|assign|status|resolve|escalate))?)?$/,
  /^staff\/selfie-reviews(\/\d+\/(approve|reject))?$/,
  /^staff\/customers\/\d+\/credit-level$/,
  /^customers\/\d+\/credit-level$/,
  /^staff\/balance-adjustments$/,
  /^staff\/config(\/(pricing|product)(\/\d+\/(approve|discard))?)?$/,
  /^refund-cases(\/\d+\/(refunded|cancel))?$/,
  /^admin\/holidays(\/\d{4}-\d{2}-\d{2})?$/,
  /^admin\/staff\/\d+\/mfa\/reset$/,
  /^loans\/\d+\/(dispute(\/release)?|holds|documents(\/\d+\/url)?)$/,
  /^loan\/\d+\/repayments\/\d+\/(reverse|treat-as-extension)$/,
];

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");
  const suffix = joinPath((await ctx.params).path);
  if (!ALLOWED.some((re) => re.test(suffix))) return forbidden("Not a staff desk path.");
  return proxyToBackend(req, `/api/${suffix}`, session.token);
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
export const DELETE = handle;
