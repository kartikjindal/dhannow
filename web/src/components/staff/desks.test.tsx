/**
 * PLAN Phase 10 desks: the new roles' permissions and nav, and the sanction dialog on the credit ladder with
 * the backend's figures (the ₹10,000 example: net ₹7,420, repay ₹10,000).
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { hasPermission } from "@/lib/auth/rbac";
import { NAV, navVisible } from "@/components/staff/staff-nav";
import { SanctionDialog } from "@/components/staff/sanction-dialog";
import { staffApi, type ApplicationView } from "@/lib/api/applications";
import { creditLevelApi } from "@/lib/api/staff-desks";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn(), push: vi.fn() }) }));

const items = NAV.flatMap((g) => g.items);
const visible = (role: Parameters<typeof navVisible>[1]) => items.filter((it) => navVisible(it, role, role)).map((it) => it.label);

describe("Phase 10 roles", () => {
  it("SUPPORT works complaints and holds but has no money rights", () => {
    expect(hasPermission("SUPPORT", "complaints:work")).toBe(true);
    expect(hasPermission("SUPPORT", "holds:manage")).toBe(true);
    expect(hasPermission("SUPPORT", "refunds:manage")).toBe(false);
    expect(hasPermission("SUPPORT", "loan:activate")).toBe(false);
    expect(visible("SUPPORT")).toContain("Complaints");
  });

  it("COMPLIANCE reads complaints, refunds, config and the adjustment log, and changes nothing", () => {
    for (const p of ["complaints:view", "refunds:view", "config:view", "adjustments:view"] as const) expect(hasPermission("COMPLIANCE", p)).toBe(true);
    for (const p of ["complaints:work", "refunds:manage", "config:manage", "holds:manage"] as const) expect(hasPermission("COMPLIANCE", p)).toBe(false);
    expect(visible("COMPLIANCE")).toEqual(expect.arrayContaining(["Complaints", "Adjustment log", "Pricing and product"]));
  });

  it("FINANCE makes pricing drafts and pays refunds; the Accountant confirms disbursals", () => {
    expect(hasPermission("FINANCE", "config:manage")).toBe(true);
    expect(hasPermission("FINANCE", "refunds:manage")).toBe(true);
    expect(visible("ACCOUNTANT")).toContain("Disbursal confirmation");
    expect(visible("FINANCE")).not.toContain("Disbursal confirmation");
  });
});

describe("Sanction dialog (ladder)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("offers ladder chips up to the level and shows the backend's figures", async () => {
    vi.spyOn(staffApi, "getProfile").mockResolvedValue({} as never);
    vi.spyOn(creditLevelApi, "get").mockResolvedValue({
      current: { level: 7, maxPaise: 1_000_000, nextLevelPaise: null, ladder: [100_000, 1_000_000] },
      history: [],
      quotes: [
        { principalPaise: 100_000, feePaise: 10_000, gstPaise: 1_800, interestPaise: 14_000, netPaise: 74_200, repayPaise: 100_000, tenureDays: 14 },
        { principalPaise: 1_000_000, feePaise: 100_000, gstPaise: 18_000, interestPaise: 140_000, netPaise: 742_000, repayPaise: 1_000_000, tenureDays: 14 },
      ],
    });
    const sanction = vi.spyOn(staffApi, "sanction").mockResolvedValue({} as never);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <SanctionDialog app={{ id: 9, customerId: 4 } as ApplicationView} open onClose={() => {}} />
      </QueryClientProvider>,
    );
    fireEvent.click(await screen.findByRole("radio", { name: "₹10,000" }));
    expect(screen.getByText("₹7,420")).toBeInTheDocument();
    expect(screen.getAllByText("₹10,000").length).toBeGreaterThan(1);
    expect(screen.getByText(/Due 14 days after the confirmed disbursal, 23:59 IST/)).toBeInTheDocument();
    expect(screen.queryByText(/Salary credit day/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Sanction/ }));
    await vi.waitFor(() => expect(sanction).toHaveBeenCalledWith(9, { sanctionedAmountPaise: 1_000_000, remarks: undefined }));
  });
});
