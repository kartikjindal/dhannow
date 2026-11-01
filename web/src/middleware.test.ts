import { createHmac, createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { verifyStaffJwt } from "./middleware";

/** A token as the backend's JwtService signs it: HS256 under SHA-256(secret). */
function sign(claims: object, secret: string, alg = "HS256"): string {
  const enc = (o: object) => Buffer.from(JSON.stringify(o)).toString("base64url");
  const head = `${enc({ alg })}.${enc(claims)}`;
  const key = createHash("sha256").update(secret).digest();
  return `${head}.${createHmac("sha256", key).update(head).digest("base64url")}`;
}

const SECRET = "test-only-secret-generated-for-this-suite";
const LATER = Math.floor(Date.now() / 1000) + 600;

describe("verifyStaffJwt (PLAN Phase 9)", () => {
  it("accepts a staff token signed with the shared secret", async () => {
    expect(await verifyStaffJwt(sign({ sub: "1", aud: ["staff"], exp: LATER }, SECRET), SECRET)).toBe(true);
  });

  it("refuses a forged signature, a borrower token, an expired token and alg none", async () => {
    expect(await verifyStaffJwt(sign({ aud: ["staff"], exp: LATER }, "another-secret"), SECRET)).toBe(false);
    expect(await verifyStaffJwt(sign({ aud: ["borrower"], exp: LATER }, SECRET), SECRET)).toBe(false);
    expect(await verifyStaffJwt(sign({ aud: ["staff"], exp: 1 }, SECRET), SECRET)).toBe(false);
    const none = sign({ aud: ["staff"], exp: LATER }, SECRET, "none").split(".").slice(0, 2).join(".") + ".";
    expect(await verifyStaffJwt(none, SECRET)).toBe(false);
    expect(await verifyStaffJwt("not-a-jwt", SECRET)).toBe(false);
  });

  it("refuses a token whose claims were edited after signing", async () => {
    const [h, , s] = sign({ aud: ["staff"], exp: LATER, role: "ACCOUNTANT" }, SECRET).split(".");
    const p = Buffer.from(JSON.stringify({ aud: ["staff"], exp: LATER, role: "ADMIN" })).toString("base64url");
    expect(await verifyStaffJwt(`${h}.${p}.${s}`, SECRET)).toBe(false);
  });
});
