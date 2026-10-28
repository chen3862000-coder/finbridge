import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./main";

describe("FinBridge Console navigation", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("loads persisted overview and navigates to the application workspace", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      const payload = path.endsWith("/dashboard")
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
});
