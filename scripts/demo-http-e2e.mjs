import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

if (process.env.FINBRIDGE_DEMO_ALLOW_INSECURE_TLS !== "true") {
  throw new Error("Set FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true for the generated local-only certificate");
}
process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";

const baseUrl = process.env.FINBRIDGE_DEMO_URL ?? "https://localhost:8443";

class BrowserSession {
  cookies = new Map();

  async request(url, options = {}) {
    const headers = new Headers(options.headers ?? {});
    if (this.cookies.size > 0) {
      headers.set("cookie", [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; "));
    }
    const response = await fetch(url, { ...options, headers, redirect: "manual" });
    for (const cookie of response.headers.getSetCookie()) {
      const [pair] = cookie.split(";", 1);
      const separator = pair.indexOf("=");
      if (separator > 0) this.cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
    }
    return response;
  }

  async login(username, password) {
    let response = await this.request(`${baseUrl}/oauth2/authorization/finbridge`);
    assert.equal(response.status, 302, "BFF should start OIDC authorization");
    const authorizationUrl = response.headers.get("location");
    assert.ok(authorizationUrl?.includes("code_challenge_method=S256"), "PKCE S256 must be used");

    response = await this.request(authorizationUrl);
    assert.equal(response.status, 200, "OIDC provider should return login page");
    const loginPage = await response.text();
    const action = loginPage.match(/<form[^>]*action="([^"]+)"[^>]*>/i)?.[1];
    assert.ok(action, "OIDC login form must have an action");
    const fields = [...loginPage.matchAll(
      /<input[^>]*type="hidden"[^>]*name="([^"]+)"[^>]*value="([^"]*)"/gi,
    )].map(match => [match[1], match[2]]);
    fields.push(["username", username], ["password", password]);

    response = await this.request(action.replaceAll("&amp;", "&"), {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(fields),
    });
    for (let redirect = 0; redirect < 8 && response.status >= 300 && response.status < 400; redirect++) {
      const location = response.headers.get("location");
      if (!location) break;
      response = await this.request(new URL(location, action).toString());
    }
    assert.ok(response.status >= 200 && response.status < 300, "OIDC callback should establish BFF session");
    return response;
  }

  async csrf() {
    const response = await this.request(`${baseUrl}/api/csrf`);
    assert.equal(response.status, 200);
    return response.json();
  }

  async json(path, options = {}) {
    const response = await this.request(`${baseUrl}${path}`, options);
    const body = await response.json().catch(() => null);
    return { response, body };
  }
}

async function borrowerJourney() {
  const alpha = new BrowserSession();
  await alpha.login("borrower-alpha", "alpha-local-only");

  const session = await alpha.json("/api/session");
  assert.equal(session.response.status, 200);
  assert.deepEqual(session.body, { authenticated: true, subject: "borrower-alpha", administrator: false });

  const products = await alpha.json("/api/borrower/products");
  assert.equal(products.response.status, 200);
  assert.ok(products.body.some(product => product.productCode === "CASH_LOAN_DEMO"));

  const csrf = await alpha.csrf();
  const amount = "1234.56";
  const created = await alpha.json("/api/borrower/applications", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      [csrf.headerName]: csrf.token,
      "Idempotency-Key": `manual-e2e-${randomUUID()}`,
    },
    body: JSON.stringify({ productCode: "CASH_LOAN_DEMO", amount, currency: "CNY", termMonths: 3 }),
  });
  assert.equal(created.response.status, 201);
  assert.equal(created.body.requestedAmount, amount, "money must retain exact decimal text");
  assert.equal(created.body.tenantId, "00000000-0000-0000-0000-000000000001");
  assert.equal(created.body.applicantId, "demo-borrower-alpha-self");

  const evaluateCsrf = await alpha.csrf();
  const evaluation = await alpha.json(`/api/borrower/applications/${created.body.applicationId}/evaluate`, {
    method: "POST",
    headers: {
      [evaluateCsrf.headerName]: evaluateCsrf.token,
      "X-Correlation-Id": `manual-e2e-${randomUUID()}`,
    },
  });
  assert.equal(evaluation.response.status, 200, JSON.stringify(evaluation.body));
  assert.equal(evaluation.body.decision, "APPROVE");

  const status = await alpha.json(`/api/borrower/applications/${created.body.applicationId}`);
  assert.equal(status.response.status, 200);
  assert.equal(status.body.application.status, "APPROVED");
  assert.equal(status.body.assessments[0].decision, "APPROVE");

  const originateCsrf = await alpha.csrf();
  const originated = await alpha.json(`/api/borrower/applications/${created.body.applicationId}/originate`, {
    method: "POST",
    headers: { [originateCsrf.headerName]: originateCsrf.token },
  });
  assert.equal(originated.response.status, 200, JSON.stringify(originated.body));
  assert.equal(originated.body.status, "PENDING_DISBURSEMENT");

  const loanId = originated.body.loanId;
  const loan = await alpha.json(`/api/borrower/loans/${loanId}`);
  assert.equal(loan.response.status, 200);
  const plan = await alpha.json(`/api/borrower/loans/${loanId}/repayment-plan`);
  assert.equal(plan.response.status, 200);
  assert.ok(plan.body.installments.length > 0);

  const disburseCsrf = await alpha.csrf();
  const payment = await alpha.json(`/api/borrower/loans/${loanId}/disburse`, {
    method: "POST",
    headers: {
      [disburseCsrf.headerName]: disburseCsrf.token,
      "Idempotency-Key": `manual-e2e-disbursement-${randomUUID()}`,
      "X-Correlation-Id": `manual-e2e-${randomUUID()}`,
    },
  });
  assert.equal(payment.response.status, 200, JSON.stringify(payment.body));
  assert.equal(payment.body.transaction.status, "SUCCEEDED");

  const loanAfterDisbursement = await alpha.json(`/api/borrower/loans/${loanId}`);
  assert.equal(loanAfterDisbursement.response.status, 200);
  assert.equal(loanAfterDisbursement.body.status, "ACTIVE");
  const repaymentPlan = await alpha.json(`/api/borrower/loans/${loanId}/repayment-plan`);
  assert.equal(repaymentPlan.response.status, 200);
  assert.equal(repaymentPlan.body.status, "ACTIVE");
  const paymentStatus = await alpha.json(`/api/borrower/loans/${loanId}/payment`);
  assert.equal(paymentStatus.response.status, 200);
  assert.equal(paymentStatus.body.transaction.status, "SUCCEEDED");

  const reconcileCsrf = await alpha.csrf();
  const reconciliation = await alpha.json(`/api/borrower/loans/${loanId}/reconcile`, {
    method: "POST",
    headers: {
      [reconcileCsrf.headerName]: reconcileCsrf.token,
      "X-Correlation-Id": `manual-e2e-${randomUUID()}`,
    },
  });
  assert.equal(reconciliation.response.status, 200, JSON.stringify(reconciliation.body));
  assert.equal(reconciliation.body.transaction.status, "SUCCEEDED");
  console.log(`Borrower origination/disbursement E2E passed: ${loanId}, ${payment.body.transaction.status}`);
  console.log(`Borrower application/risk E2E passed: ${created.body.applicationId}, ${amount}, APPROVE`);
  return created.body.applicationId;
}

async function isolationJourney(applicationId) {
  const beta = new BrowserSession();
  await beta.login("borrower-beta", "beta-local-only");
  const session = await beta.json("/api/session");
  assert.deepEqual(session.body, { authenticated: true, subject: "borrower-beta", administrator: false });

  const access = await beta.json(`/api/borrower/applications/${applicationId}`);
  assert.equal(access.response.status, 404, "another tenant's application must be hidden as not found");

  const admin = await beta.json("/api/admin/plugins");
  assert.equal(admin.response.status, 403, "borrower identity must not use admin routes");
  console.log("Tenant isolation and borrower/admin denial E2E passed");
}

async function adminJourney() {
  const admin = new BrowserSession();
  await admin.login("demo-admin", "admin-local-only");
  const session = await admin.json("/api/session");
  assert.deepEqual(session.body, { authenticated: true, subject: "demo-admin", administrator: true });
  const borrowerApi = await admin.json("/api/borrower/products");
  assert.equal(borrowerApi.response.status, 403, "admin identity must not use borrower routes");
  const registry = await admin.json("/api/admin/plugins");
  assert.equal(registry.response.status, 200);
  assert.ok(registry.body.length >= 4);
  console.log(`Admin role/registry E2E passed: ${registry.body.length} synthetic artifacts`);
}

const applicationId = await borrowerJourney();
await isolationJourney(applicationId);
await adminJourney();
