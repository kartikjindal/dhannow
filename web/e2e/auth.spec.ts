import { test, expect } from "@playwright/test";
import { loginStaff } from "./_fixtures";

test.describe("auth", () => {
  test("staff email+password login lands on the dashboard", async ({ page }) => {
    await page.goto("/staff/login");
    await page.getByLabel("Email").fill(process.env.E2E_ADMIN_EMAIL ?? "admin@dhannow.com");
    await page.getByLabel("Password").fill(process.env.E2E_ADMIN_PASSWORD ?? "");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/staff\/dashboard/, { timeout: 15_000 });
    await expect(page.getByText(/Welcome,/)).toBeVisible();
  });

  test("unauthenticated /staff/* redirects to the staff login", async ({ page }) => {
    await page.goto("/staff/dashboard");
    await expect(page).toHaveURL(/\/staff\/login/);
  });

  test("logout clears the staff session", async ({ page }) => {
    expect(await loginStaff(page, "ADMIN")).toBeTruthy();
    await page.request.post("/api/auth/staff/logout");
    await page.goto("/staff/dashboard");
    await expect(page).toHaveURL(/\/staff\/login/);
  });

  test("the borrower web is gone: its routes are not served", async ({ page }) => {
    expect((await page.request.get("/api/borrower/applications/mine")).status()).toBe(404);
    await page.goto("/");
    await expect(page).toHaveURL(/\/staff\/login/);
  });
});
