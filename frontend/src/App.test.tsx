import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./main";

describe("FinBridge Console navigation", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("loads persisted overview and navigates to the application workspace", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      const payload = path === "/api/session"
        ? { authenticated: true, subject: "demo-admin", administrator: true }
        : path.endsWith("/dashboard")
        ? { applications: 0, manualReview: 0, loans: 0, activeLoans: 0, unresolvedPayments: 0, ambiguousPayments: 0 }
        : [];
      return new Response(JSON.stringify(payload), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Operations at a glance" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Applications/ }));

    expect(await screen.findByRole("heading", { name: "Loan applications" })).toBeInTheDocument();
    expect(await screen.findByText("No applications found")).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(call => call[0] === "/api/console/products")).toBe(true);
  });

  it("opens the selected loan record from Inspect loan and renders its host detail response", async () => {
    const applicationId = "application-1";
    const loanId = "loan-1";
    const application = {
      applicationId, applicantId: "borrower-alpha-self", productCode: "CASH_LOAN_DEMO",
      productVersion: 1, requestedAmount: "1200.00", currency: "CNY", termMonths: 3,
      status: "APPROVED", createdAt: "2026-10-29T10:00:00Z", updatedAt: "2026-10-29T10:00:00Z",
    };
    const loan = {
      loanId, applicationId, principal: "1200.00", currency: "CNY", annualInterestRatePercent: "12.00",
      termMonths: 3, productCode: "CASH_LOAN_DEMO", status: "PENDING_DISBURSEMENT",
      disbursedAt: null, activatedAt: null, createdAt: "2026-10-29T10:01:00Z",
    };
    const plan = {
      planId: "plan-1", totalPrincipal: "1200.00", totalInterest: "24.00", totalRepayable: "1224.00",
      installmentCount: 3, status: "PENDING_DISBURSEMENT", installments: [],
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      let payload: unknown = [];
      if (path === "/api/session") payload = { authenticated: true, subject: "demo-admin", administrator: true };
      else if (path.endsWith("/dashboard")) payload = {
        applications: 1, manualReview: 0, loans: 1, activeLoans: 0, unresolvedPayments: 0, ambiguousPayments: 0,
      };
      else if (path.startsWith("/api/console/applications?") ) payload = [application];
      else if (path === `/api/console/applications/${applicationId}`) payload = application;
      else if (path.endsWith(`/applications/${applicationId}/assessments`)) payload = [];
      else if (path.endsWith(`/applications/${applicationId}/loan`)) payload = loan;
      else if (path === `/api/console/loans/${loanId}`) payload = { loan, repaymentPlan: plan, payment: null };
      return new Response(JSON.stringify(payload), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    await screen.findByRole("heading", { name: "Operations at a glance" });
    fireEvent.click(within(screen.getAllByRole("navigation", { name: "Console sections" })[0])
      .getByRole("button", { name: /Applications/ }));
    fireEvent.click(await screen.findByText("borrower-alpha-self"));
    fireEvent.click(await screen.findByRole("button", { name: /Inspect loan/ }));

    expect(await screen.findByRole("heading", { name: /1,200\.00/ })).toBeInTheDocument();
    expect(screen.getByText("Repayment plan")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(`/api/console/loans/${loanId}`, expect.anything());
  });

  it("shows a visible sign-out button and posts logout with CSRF", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === "/api/session") return new Response(JSON.stringify({
        authenticated: true, subject: "borrower-alpha", administrator: false,
      }), { status: 200, headers: { "Content-Type": "application/json" } });
      if (String(input) === "/api/csrf") return new Response(JSON.stringify({
        headerName: "X-CSRF-TOKEN", token: "csrf-token",
      }), { status: 200, headers: { "Content-Type": "application/json" } });
      if (String(input) === "/logout") return new Promise<Response>(() => undefined);
      return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith("/logout", expect.objectContaining({
      method: "POST", headers: { "X-CSRF-TOKEN": "csrf-token" },
    })));
  });

  it("shows a sign-in screen instead of loading dashboard data after logout", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      authenticated: false, subject: null, administrator: false,
    }), { status: 200, headers: { "Content-Type": "application/json" } })));
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Sign in to continue" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/oauth2/authorization/finbridge");
  });
});
