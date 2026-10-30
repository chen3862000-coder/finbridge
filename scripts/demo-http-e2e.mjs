import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

if (process.env.FINBRIDGE_DEMO_ALLOW_INSECURE_TLS !== "true") {
  throw new Error("Set FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true for the generated local-only certificate");
}
process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";

const baseUrl = process.env.FINBRIDGE_DEMO_URL ?? "https://localhost:8443";

class BrowserSession {
  cookies = new Map();
  cookieHeaders = new Map();

  async request(url, options = {}) {
    const headers = new Headers(options.headers ?? {});
    if (this.cookies.size > 0) {
      headers.set("cookie", [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; "));
    }
    const response = await fetch(url, { ...options, headers, redirect: "manual" });
    for (const cookie of response.headers.getSetCookie()) {
      const [pair] = cookie.split(";", 1);
      const separator = pair.indexOf("=");
      if (separator > 0) {
        const name = pair.slice(0, separator);
        const value = pair.slice(separator + 1);
        this.cookieHeaders.set(name, cookie);
        if (!value || /(?:^|;\s*)max-age=0(?:;|$)/i.test(cookie)) this.cookies.delete(name);
        else this.cookies.set(name, value);
      }
    }
    return response;
  }

  async login(username, password) {
    let response = await this.request(`${baseUrl}/oauth2/authorization/finbridge`);
    assert.equal(response.status, 302, "BFF should start OIDC authorization");
    const authorizationUrl = response.headers.get("location");
    assert.ok(authorizationUrl?.includes("code_challenge_method=S256"), "PKCE S256 must be used");
    assert.ok(authorizationUrl.includes("prompt=login"), "login must prompt rather than silently reuse SSO");

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
    body: JSON.stringify({ productCode: "CASH_LOAN_DEMO", amount, currency: "CNY", termMonths: 3,
      tenantId: "00000000-0000-0000-0000-000000000002", applicantId: "attacker-applicant",
      role: "FINBRIDGE_ADMIN", riskScenario: "FORCE_REJECT" }),
  });
  assert.equal(created.response.status, 201);
  assert.equal(created.body.requestedAmount, amount, "money must retain exact decimal text");
  assert.equal(created.body.tenantId, "00000000-0000-0000-0000-000000000001");
  assert.equal(created.body.applicantId, "demo-borrower-alpha-self");
  assert.notEqual(created.body.applicantId, "attacker-applicant",
    "caller-selected applicant must not override mapped identity");
  assert.notEqual(created.body.tenantId, "00000000-0000-0000-0000-000000000002",
    "caller-selected tenant must not override mapped membership");

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
  assert.equal(evaluation.body.creditScore, 720);
  assert.equal(evaluation.body.blacklistHit, false);

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
  await syntheticDecisionJourneys();
  return created.body.applicationId;
}

async function borrowerApplicationCreateJourney() {
  const borrower = new BrowserSession();
  await borrower.login("borrower-alpha", "alpha-local-only");
  const csrf = await borrower.csrf();
  const created = await borrower.json("/api/borrower/applications", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      [csrf.headerName]: csrf.token,
      "Idempotency-Key": `csrf-regression-${randomUUID()}`,
    },
    body: JSON.stringify({ productCode: "CASH_LOAN_DEMO", amount: "1234.56", currency: "CNY", termMonths: 3 }),
  });
  assert.equal(created.response.status, 201, JSON.stringify(created.body));
  assert.equal(created.body.requestedAmount, "1234.56");
  assert.equal(created.body.applicantId, "demo-borrower-alpha-self");
  console.log(`Borrower application-create HTTP E2E passed: ${created.body.applicationId}`);
}

async function consoleApplicationCreateJourney() {
  const borrower = new BrowserSession();
  await borrower.login("borrower-alpha", "alpha-local-only");
  const csrf = await borrower.csrf();
  const created = await borrower.json("/api/console/applications", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      [csrf.headerName]: csrf.token,
      "Idempotency-Key": `console-csrf-regression-${randomUUID()}`,
    },
    body: JSON.stringify({ applicantId: "synthetic-console-applicant", productCode: "CASH_LOAN_DEMO",
      amount: "1234.56", currency: "CNY", termMonths: 3 }),
  });
  assert.equal(created.response.status, 201, JSON.stringify(created.body));
  assert.equal(created.body.requestedAmount, "1234.56");
  assert.equal(created.body.applicantId, "synthetic-console-applicant");
  console.log(`Console application-create HTTP E2E passed: ${created.body.applicationId}`);
}

async function retryApplicationJourney(applicationId) {
  assert.ok(applicationId, "FINBRIDGE_DEMO_APPLICATION_ID is required");
  const borrower = new BrowserSession();
  await borrower.login("borrower-alpha", "alpha-local-only");
  const csrf = await borrower.csrf();
  const evaluation = await borrower.json(`/api/borrower/applications/${applicationId}/evaluate`, {
    method: "POST",
    headers: { [csrf.headerName]: csrf.token, "X-Correlation-Id": `retry-${randomUUID()}` },
  });
  assert.equal(evaluation.response.status, 200, JSON.stringify(evaluation.body));
  assert.equal(evaluation.body.decision, "REVIEW");
  const status = await borrower.json(`/api/borrower/applications/${applicationId}`);
  assert.equal(status.response.status, 200);
  assert.equal(status.body.application.status, "MANUAL_REVIEW");
  assert.equal(status.body.assessments[0].decision, "REVIEW");
  assert.equal(status.body.loan, null);
  console.log(`Interrupted risk assessment recovered to MANUAL_REVIEW: ${applicationId}`);
}

async function oidcCallbackRejectionJourney() {
  const browser = new BrowserSession();
  const authorization = await browser.request(`${baseUrl}/oauth2/authorization/finbridge`);
  assert.equal(authorization.status, 302);
  const authorizationUrl = new URL(authorization.headers.get("location"));
  const state = authorizationUrl.searchParams.get("state");
  assert.ok(state, "authorization request must persist state");
  assert.equal(authorizationUrl.searchParams.get("code_challenge_method"), "S256");

  const tamperedCallback = await browser.request(
    `${baseUrl}/login/oauth2/code/finbridge?code=invalid&state=${randomUUID()}`,
  );
  assert.ok(tamperedCallback.status >= 300 && tamperedCallback.status < 400,
    "callback with an uncorrelated state must be rejected");
  const afterTamperedState = await browser.json("/api/session");
  assert.equal(afterTamperedState.body.authenticated, false);
  assert.equal(afterTamperedState.body.administrator, false);
  assert.equal("subject" in afterTamperedState.body, false);

  const nextAuthorization = await browser.request(`${baseUrl}/oauth2/authorization/finbridge`);
  assert.equal(nextAuthorization.status, 302);
  const nextState = new URL(nextAuthorization.headers.get("location")).searchParams.get("state");
  assert.ok(nextState);
  const invalidCodeCallback = await browser.request(
    `${baseUrl}/login/oauth2/code/finbridge?code=not-a-valid-authorization-code&state=${nextState}`,
  );
  assert.ok(invalidCodeCallback.status >= 300 && invalidCodeCallback.status < 400,
    "callback with an invalid authorization code must be rejected");
  const afterInvalidCode = await browser.json("/api/session");
  assert.equal(afterInvalidCode.body.authenticated, false);
  assert.equal(afterInvalidCode.body.administrator, false);
  assert.equal("subject" in afterInvalidCode.body, false);

  const nonceBrowser = new BrowserSession();
  const nonceAuthorization = await nonceBrowser.request(`${baseUrl}/oauth2/authorization/finbridge`);
  assert.equal(nonceAuthorization.status, 302);
  const alteredAuthorizationUrl = new URL(nonceAuthorization.headers.get("location"));
  assert.ok(alteredAuthorizationUrl.searchParams.has("nonce"), "OIDC request must include a nonce");
  alteredAuthorizationUrl.searchParams.set("nonce", randomUUID());
  let response = await nonceBrowser.request(alteredAuthorizationUrl.toString());
  assert.equal(response.status, 200, "Keycloak should render its real login form");
  const loginPage = await response.text();
  const action = loginPage.match(/<form[^>]*action="([^"]+)"[^>]*>/i)?.[1];
  assert.ok(action, "Keycloak login form must have an action");
  const fields = [...loginPage.matchAll(
    /<input[^>]*type="hidden"[^>]*name="([^"]+)"[^>]*value="([^"]*)"/gi,
  )].map(match => [match[1], match[2]]);
  fields.push(["username", "borrower-alpha"], ["password", "alpha-local-only"]);
  response = await nonceBrowser.request(action.replaceAll("&amp;", "&"), {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(fields),
  });
  for (let redirect = 0; redirect < 8 && response.status >= 300 && response.status < 400; redirect++) {
    const location = response.headers.get("location");
    if (!location) break;
    response = await nonceBrowser.request(new URL(location, action).toString());
  }
  const afterNonceMismatch = await nonceBrowser.json("/api/session");
  assert.equal(afterNonceMismatch.body.authenticated, false);
  assert.equal(afterNonceMismatch.body.administrator, false);
  assert.equal("subject" in afterNonceMismatch.body, false);
  const protectedRoute = await nonceBrowser.json("/api/borrower/products");
  assert.equal(protectedRoute.response.status, 302,
    "real Keycloak callback with a modified nonce must not authorize borrower routes");
  console.log("OIDC negative callback E2E passed: tampered state, invalid code, and Keycloak-signed nonce mismatch denied");
}

async function sessionExpiryJourney() {
  const browser = new BrowserSession();
  await browser.login("borrower-alpha", "alpha-local-only");
  const active = await browser.json("/api/session");
  assert.equal(active.body.authenticated, true);
  await new Promise(resolve => setTimeout(resolve, 35000));
  const expired = await browser.json("/api/session");
  assert.equal(expired.response.status, 200);
  assert.equal(expired.body.authenticated, false, "expired server session must no longer authenticate");
  assert.equal(expired.body.administrator, false);
  console.log("Session expiry E2E passed: server-side session expired after configured timeout");
}

async function syntheticDecisionJourneys() {
  const scenarios = [
    { username: "borrower-alpha-review", password: "alpha-review-local-only",
      applicantId: "demo-borrower-alpha-review-self", decision: "REVIEW",
      applicationStatus: "MANUAL_REVIEW", creditScore: 640, blacklistHit: false },
    { username: "borrower-alpha-blacklist", password: "alpha-blacklist-local-only",
      applicantId: "demo-borrower-alpha-blacklist-self", decision: "REJECT",
      applicationStatus: "REJECTED", creditScore: 720, blacklistHit: true },
    { username: "borrower-alpha-fraud", password: "alpha-fraud-local-only",
      applicantId: "demo-borrower-alpha-critical-fraud-self", decision: "REJECT",
      applicationStatus: "REJECTED", creditScore: 720, blacklistHit: false },
  ];
  for (const scenario of scenarios) {
    const borrower = new BrowserSession();
    await borrower.login(scenario.username, scenario.password);
    const csrf = await borrower.csrf();
    const application = await borrower.json("/api/borrower/applications", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        [csrf.headerName]: csrf.token,
        "Idempotency-Key": `manual-e2e-fixture-${randomUUID()}`,
      },
      body: JSON.stringify({ productCode: "CASH_LOAN_DEMO", amount: "2000.00", currency: "CNY",
        termMonths: 3 }),
    });
    assert.equal(application.response.status, 201, JSON.stringify(application.body));
    const evaluateCsrf = await borrower.csrf();
    const evaluation = await borrower.json(`/api/borrower/applications/${application.body.applicationId}/evaluate`, {
      method: "POST",
      headers: {
        [evaluateCsrf.headerName]: evaluateCsrf.token,
        "X-Correlation-Id": `manual-e2e-fixture-${randomUUID()}`,
      },
    });
    assert.equal(evaluation.response.status, 200, JSON.stringify(evaluation.body));
    assert.equal(evaluation.body.decision, scenario.decision);
    assert.equal(evaluation.body.creditScore, scenario.creditScore);
    assert.equal(evaluation.body.blacklistHit, scenario.blacklistHit);
    const status = await borrower.json(`/api/borrower/applications/${application.body.applicationId}`);
    assert.equal(status.response.status, 200);
    assert.equal(status.body.application.status, scenario.applicationStatus);
    assert.equal(status.body.application.applicantId, scenario.applicantId,
      "application applicant reference must match the server-mapped fixture identity");
    assert.equal(status.body.loan, undefined, "non-approved application must not create a loan");
    const ownApplications = await borrower.json("/api/borrower/applications");
    assert.ok(ownApplications.body.some(item => item.applicationId === application.body.applicationId));
    assert.ok(ownApplications.body.every(item => item.applicantId === status.body.application.applicantId),
      "borrower list must not include other same-tenant applicants");
    const payment = await borrower.json(`/api/borrower/applications/${application.body.applicationId}/loan`);
    assert.equal(payment.response.status, 200);
    assert.equal(payment.body, null, "non-approved application must not create a payment");
    console.log(`Synthetic ${scenario.username} E2E passed: ${scenario.decision}, no loan or payment`);
  }
}

async function isolationJourney(applicationId) {
  const review = new BrowserSession();
  await review.login("borrower-alpha-review", "alpha-review-local-only");
  const sameTenantAccess = await review.json(`/api/borrower/applications/${applicationId}`);
  assert.equal(sameTenantAccess.response.status, 404,
    "another borrower in the same tenant must not read this application through borrower routes");

  const beta = new BrowserSession();
  await beta.login("borrower-beta", "beta-local-only");
  const session = await beta.json("/api/session");
  assert.deepEqual(session.body, { authenticated: true, subject: "borrower-beta", administrator: false });

  const access = await beta.json(`/api/borrower/applications/${applicationId}`);
  assert.equal(access.response.status, 404, "another tenant's application must be hidden as not found");

  const betaApplications = await beta.json("/api/borrower/applications");
  assert.ok(betaApplications.body.every(application => application.applicantId.startsWith("demo-borrower-beta-")),
    "borrower list must contain only the signed-in applicant's records");

  const admin = await beta.json("/api/admin/plugins");
  assert.equal(admin.response.status, 403, "borrower identity must not use admin routes");

  const csrf = await beta.csrf();
  const application = await beta.json("/api/borrower/applications", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      [csrf.headerName]: csrf.token,
      "Idempotency-Key": `manual-e2e-beta-${randomUUID()}`,
    },
    body: JSON.stringify({ productCode: "CASH_LOAN_DEMO", amount: "2000.00", currency: "CNY",
      termMonths: 3 }),
  });
  assert.equal(application.response.status, 201, JSON.stringify(application.body));
  assert.equal(application.body.tenantId, "00000000-0000-0000-0000-000000000002");
  const betaEvaluateCsrf = await beta.csrf();
  const evaluation = await beta.json(`/api/borrower/applications/${application.body.applicationId}/evaluate`, {
    method: "POST",
    headers: { [betaEvaluateCsrf.headerName]: betaEvaluateCsrf.token,
      "X-Correlation-Id": `manual-e2e-beta-${randomUUID()}` },
  });
  assert.equal(evaluation.response.status, 200);
  const originateCsrf = await beta.csrf();
  const originated = await beta.json(`/api/borrower/applications/${application.body.applicationId}/originate`, {
    method: "POST", headers: { [originateCsrf.headerName]: originateCsrf.token },
  });
  assert.equal(originated.response.status, 200);
  const disburseCsrf = await beta.csrf();
  const disbursement = await beta.json(`/api/borrower/loans/${originated.body.loanId}/disburse`, {
    method: "POST",
    headers: { [disburseCsrf.headerName]: disburseCsrf.token,
      "Idempotency-Key": `manual-e2e-beta-disburse-${randomUUID()}`,
      "X-Correlation-Id": `manual-e2e-beta-${randomUUID()}` },
  });
  assert.equal(disbursement.response.status, 200);
  assert.match(disbursement.body.transaction.providerTransactionId, /^betapay-transaction-/,
    "beta tenant route must use its selected BetaPay provider");
  console.log("Tenant-specific routing E2E passed: beta disbursement used BetaPay");
  console.log("Tenant isolation and borrower/admin denial E2E passed");
}

async function adminJourney() {
  const admin = new BrowserSession();
  await admin.login("demo-admin", "admin-local-only");
  const session = await admin.json("/api/session");
  assert.deepEqual(session.body, { authenticated: true, subject: "demo-admin", administrator: true });
  const sessionCookie = [...admin.cookieHeaders.values()].find(value => value.startsWith("JSESSIONID="));
  assert.ok(sessionCookie, "OIDC callback must establish an HTTP session cookie");
  assert.match(sessionCookie, /;\s*HttpOnly/i);
  assert.match(sessionCookie, /;\s*Secure/i);
  assert.match(sessionCookie, /;\s*SameSite=Lax/i);
  const borrowerApi = await admin.json("/api/borrower/products");
  assert.equal(borrowerApi.response.status, 403, "admin identity must not use borrower routes");
  const registry = await admin.json("/api/admin/plugins");
  assert.equal(registry.response.status, 200);
  assert.ok(registry.body.length >= 3);

  let fraudArtifact = registry.body.find(artifact => artifact.key.pluginId === "fraudsignalz");
  if (!fraudArtifact) {
    const csrfForInstall = await admin.csrf();
    const installed = await admin.json("/api/admin/plugins", {
      method: "POST",
      headers: { "content-type": "application/json", [csrfForInstall.headerName]: csrfForInstall.token },
      body: JSON.stringify({ artifactName: "fraudsignalz-1.0.0-jar-with-dependencies.jar" }),
    });
    assert.equal(installed.response.status, 200, JSON.stringify(installed.body));
    fraudArtifact = installed.body;
  }
  const installedPluginId = fraudArtifact.key.pluginId;
  const installedVersion = fraudArtifact.key.pluginVersion;
  assert.equal(installedPluginId, "fraudsignalz");
  if (fraudArtifact.desiredState !== "ACTIVE") {
    const activateCsrf = await admin.csrf();
    const activated = await admin.json(`/api/admin/plugins/${installedPluginId}/${installedVersion}/activate`, {
      method: "POST", headers: { [activateCsrf.headerName]: activateCsrf.token },
    });
    assert.equal(activated.response.status, 200, JSON.stringify(activated.body));
  }
  const alphaTenant = "00000000-0000-0000-0000-000000000001";
  const betaTenant = "00000000-0000-0000-0000-000000000002";
  const bindFraudCsrf = await admin.csrf();
  const alphaBindingsForFraud = await admin.json(`/api/admin/plugins/bindings?tenantId=${alphaTenant}`);
  const fraudRevision = alphaBindingsForFraud.body.filter(binding => binding.capability === "FRAUD_SIGNAL")
    .map(binding => binding.version).find(version => version !== undefined) ?? 0;
  const fraudBinding = await admin.json("/api/admin/plugins/bindings", {
    method: "PUT",
    headers: { "content-type": "application/json", [bindFraudCsrf.headerName]: bindFraudCsrf.token },
    body: JSON.stringify({ tenantId: alphaTenant, capability: "FRAUD_SIGNAL", pluginId: "fraudsignalz",
      pluginVersion: "1.0.0", expectedRevision: fraudRevision }),
  });
  assert.equal(fraudBinding.response.status, 200, JSON.stringify(fraudBinding.body));
  console.log("External risk plugin installation E2E passed: FraudSignalZ installed and bound without host rebuild");

  const betaArtifact = registry.body.find(artifact => artifact.key.pluginId === "betapay");
  let betaArtifactState = betaArtifact?.desiredState;
  if (!betaArtifact) {
    const betaInstallCsrf = await admin.csrf();
    const betaInstall = await admin.json("/api/admin/plugins", {
      method: "POST",
      headers: { "content-type": "application/json", [betaInstallCsrf.headerName]: betaInstallCsrf.token },
      body: JSON.stringify({ artifactName: "betapay-1.0.0-jar-with-dependencies.jar" }),
    });
    assert.equal(betaInstall.response.status, 200, JSON.stringify(betaInstall.body));
    betaArtifactState = betaInstall.body.desiredState;
  }
  if (betaArtifactState !== "ACTIVE") {
    const betaActivateCsrf = await admin.csrf();
    const betaActivated = await admin.json("/api/admin/plugins/betapay/1.0.0/activate", {
      method: "POST", headers: { [betaActivateCsrf.headerName]: betaActivateCsrf.token },
    });
    assert.equal(betaActivated.response.status, 200, JSON.stringify(betaActivated.body));
  }

  const betaBindingCsrf = await admin.csrf();
  const betaBindings = await admin.json(`/api/admin/plugins/bindings?tenantId=${betaTenant}`);
  const paymentRevision = betaBindings.body.filter(binding => binding.capability === "DISBURSE")
    .map(binding => binding.version).find(version => version !== undefined) ?? 0;
  const betaBinding = await admin.json("/api/admin/plugins/bindings", {
    method: "PUT",
    headers: { "content-type": "application/json", [betaBindingCsrf.headerName]: betaBindingCsrf.token },
    body: JSON.stringify({ tenantId: betaTenant, capability: "DISBURSE", pluginId: "betapay",
      pluginVersion: "1.0.0", expectedRevision: paymentRevision }),
  });
  assert.equal(betaBinding.response.status, 200, JSON.stringify(betaBinding.body));

  let alphaArtifacts = await admin.json("/api/admin/plugins");
  let alphaV2Artifact = alphaArtifacts.body.find(artifact => artifact.key.pluginId === "alphapay"
    && artifact.key.pluginVersion === "2.0.0");
  if (!alphaV2Artifact) {
    const installV2Csrf = await admin.csrf();
    const installV2 = await admin.json("/api/admin/plugins", {
      method: "POST",
      headers: { "content-type": "application/json", [installV2Csrf.headerName]: installV2Csrf.token },
      body: JSON.stringify({ artifactName: "alphapay-2.0.0-jar-with-dependencies.jar" }),
    });
    assert.equal(installV2.response.status, 200, JSON.stringify(installV2.body));
    alphaV2Artifact = installV2.body;
  }
  let alphaBindings = await admin.json(`/api/admin/plugins/bindings?tenantId=${alphaTenant}`);
  let alphaPaymentBinding = alphaBindings.body.find(binding => binding.capability === "DISBURSE");
  if (alphaPaymentBinding.pluginKey.pluginVersion !== "2.0.0") {
    if (alphaV2Artifact.desiredState === "RETIRED") {
      const restoreV2Csrf = await admin.csrf();
      const restoredV2 = await admin.json("/api/admin/plugins/rollback", {
        method: "POST",
        headers: { "content-type": "application/json", [restoreV2Csrf.headerName]: restoreV2Csrf.token },
        body: JSON.stringify({ tenantId: alphaTenant, capability: "DISBURSE",
          expectedRevision: alphaPaymentBinding.version, pluginId: "alphapay", pluginVersion: "2.0.0",
          timeout: "PT2S" }),
      });
      assert.equal(restoredV2.response.status, 200, JSON.stringify(restoredV2.body));
    } else {
      if (alphaV2Artifact.desiredState === "LOADED") {
        const activateV2Csrf = await admin.csrf();
        const activateV2 = await admin.json("/api/admin/plugins/alphapay/2.0.0/activate", {
          method: "POST", headers: { [activateV2Csrf.headerName]: activateV2Csrf.token },
        });
        assert.equal(activateV2.response.status, 200, JSON.stringify(activateV2.body));
      }
      const bindV2Csrf = await admin.csrf();
      const bindV2 = await admin.json("/api/admin/plugins/bindings", {
        method: "PUT",
        headers: { "content-type": "application/json", [bindV2Csrf.headerName]: bindV2Csrf.token },
        body: JSON.stringify({ tenantId: alphaTenant, capability: "DISBURSE", pluginId: "alphapay",
          pluginVersion: "2.0.0", expectedRevision: alphaPaymentBinding.version }),
      });
      assert.equal(bindV2.response.status, 200, JSON.stringify(bindV2.body));
    }
    alphaBindings = await admin.json(`/api/admin/plugins/bindings?tenantId=${alphaTenant}`);
    alphaPaymentBinding = alphaBindings.body.find(binding => binding.capability === "DISBURSE");
  }
  assert.equal(alphaPaymentBinding.pluginKey.pluginVersion, "2.0.0");
  const rollbackCsrf = await admin.csrf();
  const rollback = await admin.json("/api/admin/plugins/rollback", {
    method: "POST",
    headers: { "content-type": "application/json", [rollbackCsrf.headerName]: rollbackCsrf.token },
    body: JSON.stringify({ tenantId: alphaTenant, capability: "DISBURSE",
      expectedRevision: alphaPaymentBinding.version,
      pluginId: "alphapay", pluginVersion: "1.0.0", timeout: "PT2S" }),
  });
  assert.equal(rollback.response.status, 200, JSON.stringify(rollback.body));
  assert.equal(rollback.body.key.pluginVersion, "1.0.0");
  console.log("Provider binding rollback E2E passed: AlphaPay v1 -> v2 -> v1");

  const audit = await admin.json(`/api/admin/plugins/alphapay/1.0.0/audit`);
  assert.equal(audit.response.status, 200);
  const csrf = await admin.csrf();
  const logout = await admin.request(`${baseUrl}/logout`, {
    method: "POST",
    headers: { [csrf.headerName]: csrf.token },
  });
  assert.equal(logout.status, 302, "CSRF-protected logout should complete");
  const afterLogout = await admin.json("/api/admin/plugins");
  assert.equal(afterLogout.response.status, 302, "logged-out session must no longer authorize admin routes");
  console.log("Session cookie, CSRF logout, and post-logout denial E2E passed");
  console.log(`Admin role/registry E2E passed: ${registry.body.length} initial synthetic artifacts`);
}

if (process.env.FINBRIDGE_DEMO_E2E_SESSION_EXPIRY_ONLY === "true") {
  await sessionExpiryJourney();
} else if (process.env.FINBRIDGE_DEMO_E2E_APPLICATION_CREATE_ONLY === "true") {
  await borrowerApplicationCreateJourney();
} else if (process.env.FINBRIDGE_DEMO_E2E_CONSOLE_APPLICATION_ONLY === "true") {
  await consoleApplicationCreateJourney();
} else if (process.env.FINBRIDGE_DEMO_E2E_RETRY_APPLICATION === "true") {
  await retryApplicationJourney(process.env.FINBRIDGE_DEMO_APPLICATION_ID);
} else if (process.env.FINBRIDGE_DEMO_E2E_BORROWER_ONLY === "true") {
  const applicationId = await borrowerJourney();
  await isolationJourney(applicationId);
} else {
  await adminJourney();
  await oidcCallbackRejectionJourney();
  const applicationId = await borrowerJourney();
  await isolationJourney(applicationId);
}
