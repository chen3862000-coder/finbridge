import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "./api";

describe("Console API contract", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("creates synthetic applications with an idempotency key and no client tenant id", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ applicationId: "app-1" }), {
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

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    const body = JSON.parse(String(init.body)) as Record<string, unknown>;
    expect(path).toBe("/api/console/applications");
    expect(headers["Idempotency-Key"]).toMatch(/^console-/);
    expect(body.amount).toBe("1200.00");
    expect(body).not.toHaveProperty("tenantId");
  });

  it("preserves monetary decimal text when creating an application", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("{}", { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    await api.createApplication({
      applicantId: "synthetic-applicant",
      productCode: "CASH_LOAN_DEMO",
      amount: "123456789012345.67",
      currency: "CNY",
      termMonths: 3,
    });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(String(init.body)).amount).toBe("123456789012345.67");
  });

  it("surfaces safe host problem messages for failed commands", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: "CONFLICT",
      message: "Stale binding revision",
    }), { status: 409, headers: { "Content-Type": "application/json" } })));

    await expect(api.bind({ tenantId: "tenant-1", capability: "DISBURSE", pluginId: "pay",
      pluginVersion: "1", expectedRevision: 0 })).rejects.toThrow("Stale binding revision");
  });
});
