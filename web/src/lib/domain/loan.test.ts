import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";
import { LOAN_HOLD, LOAN_LIFECYCLE, REPAYMENT_STATUS } from "./loan";

const DOMAIN = path.resolve(__dirname, "../../../../backend/navix-loan/src/main/java/com/navix/loan/domain");

/** Enum constant names, in order: the 4-space-indented upper-case names that open the enum body. */
function javaEnum(file: string): string[] {
  const src = fs.readFileSync(path.join(DOMAIN, file), "utf8");
  return [...src.matchAll(/^ {4}([A-Z][A-Z0-9_]*)\b/gm)].map((m) => m[1]);
}

describe("loan states mirror the backend exactly (R5)", () => {
  it("lifecycle", () => expect(javaEnum("LoanLifecycle.java")).toEqual([...LOAN_LIFECYCLE]));
  it("hold", () => expect(javaEnum("LoanHold.java")).toEqual([...LOAN_HOLD]));
  it("repayment status", () => expect(javaEnum("RepaymentStatus.java")).toEqual([...REPAYMENT_STATUS]));
});
