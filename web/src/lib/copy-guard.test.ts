import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";

/**
 * PLAN Phase 10b guard: navix product wording and the borrower web must not come back into the staff
 * console. Scans every source file under `web/src` and `web/e2e` (this file excepted).
 */
const ROOT = path.resolve(__dirname, "../..");
const BANNED = [/per day for the days held/i, /salary day/i, /25% of/i, /pay early/i, /save interest/i, /dhanboost/i];
/** Legacy folders allowed to keep old wording. Empty: add a folder only with a reason. */
const ALLOW: string[] = [];

function files(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) return e.name === "node_modules" ? [] : files(p);
    return /\.(ts|tsx|css|json)$/.test(e.name) ? [p] : [];
  });
}

describe("copy guard (PLAN Phase 10b)", () => {
  it("no navix product wording in staff sources", () => {
    const hits: string[] = [];
    for (const f of [...files(path.join(ROOT, "src")), ...files(path.join(ROOT, "e2e"))]) {
      const rel = path.relative(ROOT, f).split(path.sep).join("/");
      if (rel === "src/lib/copy-guard.test.ts" || ALLOW.some((a) => rel.startsWith(a))) continue;
      fs.readFileSync(f, "utf8").split("\n").forEach((line, i) => {
        if (BANNED.some((re) => re.test(line))) hits.push(`${rel}:${i + 1}: ${line.trim().slice(0, 120)}`);
      });
    }
    expect(hits).toEqual([]);
  });

  it("the borrower web stays deleted (PLAN Phase 10)", () => {
    for (const gone of ["src/app/(borrower)", "src/app/(marketing)", "src/app/api/borrower", "src/app/api/auth/borrower", "src/components/borrower", "src/components/site"]) {
      expect(fs.existsSync(path.join(ROOT, gone)), gone).toBe(false);
    }
  });
});
