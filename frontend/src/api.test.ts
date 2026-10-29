import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "./api";

describe("Console API contract", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("creates synthetic applications with an idempotency key and no client tenant id", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => String(input) === "/api/csrf"
      ? new Response(JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "csrf-token" }))
      : new Response(JSON.stringify({ applicationId: "app-1" }), {
      status: 201,
      headers: { "Content-Type": "application/json" },
    }));
    vi.stubGlobal("fetch", fetchMock);

    await api.createApplication({
      applicantId: "synthetic-applicant-1",
      productCode: "CASH_LOAN_DEMO",
      amount: "1200.00",
      currency: "CNY",
      termMonths: 3,
    });

    const [path, init] = fetchMock.mock.calls[1] as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    const body = JSON.parse(String(init.body)) as Record<string, unknown>;
    expect(path).toBe("/api/console/applications");
    expect(headers["Idempotency-Key"]).toMatch(/^console-/);
    expect(headers["X-CSRF-TOKEN"]).toBe("csrf-token");
    expect(body.amount).toBe("1200.00");
    expect(body).not.toHaveProperty("tenantId");
  });

  it("preserves monetary decimal text when creating an application", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => String(input) === "/api/csrf"
      ? new Response(JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "csrf-token" }))
      : new Response("{}", { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    await api.createApplication({
      applicantId: "synthetic-applicant",
      productCode: "CASH_LOAN_DEMO",
      amount: "123456789012345.67",
      currency: "CNY",
      termMonths: 3,
    });

    const [, init] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(JSON.parse(String(init.body)).amount).toBe("123456789012345.67");
  });

  it("surfaces safe host problem messages for failed commands", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => String(input) === "/api/csrf"
      ? new Response(JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "csrf-token" }))
      : new Response(JSON.stringify({ code: "CONFLICT", message: "Stale binding revision" }),
        { status: 409, headers: { "Content-Type": "application/json" } })));

    await expect(api.bind({ tenantId: "tenant-1", capability: "DISBURSE", pluginId: "pay",
      pluginVersion: "1", expectedRevision: 0 })).rejects.toThrow("Stale binding revision");
  });

  it("treats an empty successful optional-loan response as no loan", async () => {
    const application = {
      applicationId: "application-1", applicantId: "borrower-alpha-review", productCode: "CASH_LOAN_DEMO",
      productVersion: 1, requestedAmount: "1200.00", currency: "CNY", termMonths: 3,
      status: "MANUAL_REVIEW", createdAt: "2026-10-29T10:00:00Z", updatedAt: "2026-10-29T10:00:00Z",
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      const body = path.endsWith("/assessments") ? "[]"
        : path.endsWith("/loan") ? ""
          : JSON.stringify(application);
      return new Response(body, { status: 200, headers: { "Content-Type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(api.application("application-1")).resolves.toMatchObject({
      application: { status: "MANUAL_REVIEW" }, assessments: [], loan: null,
    });
  });

  it("keeps empty responses invalid for required JSON resources", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 200 })));

    await expect(api.applications()).rejects.toThrow("Request returned an empty response");
  });

  it("sends CSRF on borrower evaluation and returns persisted review evidence", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => String(input) === "/api/csrf"
      ? new Response(JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "csrf-token" }))
      : new Response(JSON.stringify({
        status: "COMPLETED", decision: "REVIEW", policyVersion: "demo-risk-policy-1",
        completedAt: "2026-10-29T10:00:00Z", creditScore: null, blacklistHit: null,
        fraudSignal: null, reasonCodes: ["MANDATORY_RISK_DATA_UNAVAILABLE"],
      }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await api.evaluateBorrower("application-1");
    const [path, init] = fetchMock.mock.calls[1] as [string, RequestInit];

    expect(path).toBe("/api/borrower/applications/application-1/evaluate");
    expect((init.headers as Record<string, string>)["X-CSRF-TOKEN"]).toBe("csrf-token");
    expect(result.reasonCodes).toContain("MANDATORY_RISK_DATA_UNAVAILABLE");
  });

  it("reports an expired session when an API request is redirected to an HTML login page", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<!DOCTYPE html><html></html>", {
      status: 200,
      headers: { "Content-Type": "text/html" },
    })));

    await expect(api.dashboard()).rejects.toThrow("Your session has expired or you are signed out");
  });
});
