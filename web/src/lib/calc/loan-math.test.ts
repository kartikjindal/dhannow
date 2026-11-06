import { describe, expect, it } from "vitest";
import { daysBetween, istDate } from "./loan-math";

describe("IST date helpers (R11)", () => {
  it("reads the India calendar day, not the browser's", () => {
    // 19:00 UTC on 1 Nov is 00:30 on 2 Nov in India.
    expect(istDate(new Date("2026-11-01T19:00:00Z"))).toBe("2026-11-02");
    expect(istDate("2026-11-01T18:00:00Z")).toBe("2026-11-01");
  });

  it("counts IST calendar days", () => {
    expect(daysBetween(new Date("2026-11-01T18:00:00Z"), new Date("2026-11-01T19:00:00Z"))).toBe(1);
    expect(daysBetween(new Date("2026-11-03"), new Date("2026-11-17"))).toBe(14);
  });
});
