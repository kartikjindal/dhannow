/**
 * `/staff/customers/[customerId]` admin cards (Phase 2 polish):
 *  - the credit-level card replaces the navix limit and salary-day cards (PLAN Phase 10b);
 *  - the mobile number cannot be changed (CC-24), so there is no mobile-change card;
 *  - the salary inputs show a ₹ prefix and a live grouped preview, without changing the payload.
 *
 * The tabs, dialogs and the credit gauge have their own tests; they are stubbed here.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { customersApi, type ApplicationView, type CustomerDetail } from "@/lib/api/applications";
import { creditLevelApi } from "@/lib/api/staff-desks";
import CustomerDetailPage from "@/app/staff/customers/[customerId]/page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ customerId: "42" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  NoAccessNotice: () => null,
  errMessage: (e: unknown) => String(e),
  AdminForceDisbursementAction: () => null,
  SanctionedRejectAction: () => null,
}));
vi.mock("@/components/staff/customer-tabs", () => ({ CUSTOMER_TABS: [], CustomerTabBody: () => null }));
vi.mock("@/components/staff/application-detail-dialog", () => ({ ApplicationDetailDialog: () => null }));
vi.mock("@/components/staff/credit-score-gauge", () => ({ CreditScoreGauge: () => null }));

afterEach(() => {
  vi.restoreAllMocks();
});

const SANCTIONED = {
  id: 318,
  status: "SANCTIONED",
  loanId: null,
  salaryCreditDay: 30,
  eligibleLimitPaise: 1_050_000,
  sanctionedAmountPaise: 1_000_000,
} as unknown as ApplicationView;

function detail(overrides: Partial<CustomerDetail> = {}): CustomerDetail {
  return {
    customerId: 42,
    profile: { fullName: "Asha Verma", monthlySalaryPaise: 4_200_000 } as CustomerDetail["profile"],
    applications: [SANCTIONED],
    loans: [],
    payments: [],
    limitOverridePaise: null,
    ...overrides,
  };
}

async function renderPage(d: CustomerDetail) {
  vi.spyOn(customersApi, "get").mockResolvedValue(d);
  vi.spyOn(creditLevelApi, "get").mockResolvedValue({
    current: { level: 2, maxPaise: 200_000, nextLevelPaise: 300_000, ladder: [100_000, 200_000, 300_000, 400_000, 500_000, 700_000, 1_000_000] },
    history: [{ fromLevel: 1, toLevel: 2, reason: "ON_TIME_REPAID", loanId: 7, reasonCode: null, approvedByName: null, at: "2026-10-20T10:00:00Z", reversedAt: null }],
    quotes: [],
  });
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <CustomerDetailPage />
    </QueryClientProvider>,
  );
  await screen.findByText("Edit KYC / salary (admin)");
}

describe("credit level and locked details", () => {
  it("shows the ladder level and its history instead of the salary limit and salary-day cards", async () => {
    await renderPage(detail());
    expect(await screen.findByText(/Repaid on time: up one level/)).toBeInTheDocument();
    expect(screen.getByText("Credit level")).toBeInTheDocument();
    expect(screen.queryByText(/Salary credit day/)).not.toBeInTheDocument();
  });

  it("offers no mobile-number change (CC-24)", async () => {
    await renderPage(detail());
    expect(screen.queryByText(/Correct mobile number/)).not.toBeInTheDocument();
  });
});

describe("salary inputs", () => {
  it("previews the typed salary with Indian grouping and submits the same digits as before", async () => {
    const update = vi.spyOn(customersApi, "updateProfile").mockResolvedValue({} as never);
    await renderPage(detail());
    expect(screen.getByText("₹ 42,000")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Monthly salary (₹)"), { target: { value: "125000" } });
    expect(screen.getByText("₹ 1,25,000")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Save changes/ }));
    await vi.waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(update.mock.calls[0][1]).toMatchObject({ monthlySalaryPaise: 12_500_000 });
  });
});
