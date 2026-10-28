export type LoanApplication = {
  applicationId: string;
  applicantId: string;
  productCode: string;
  productVersion: number;
  requestedAmount: string;
  currency: string;
  termMonths: number;
  status: string;
  createdAt: string;
  updatedAt: string;
};

export type Loan = {
  loanId: string;
  applicationId: string;
  principal: string;
  currency: string;
  annualInterestRatePercent: string;
  termMonths: number;
  productCode: string;
  status: string;
  disbursedAt: string | null;
  activatedAt: string | null;
  createdAt: string;
};

export type Installment = {
  installmentId: string;
  installmentNumber: number;
  dueDate: string | null;
  principalAmount: string;
  interestAmount: string;
  totalAmount: string;
  remainingPrincipal: string;
  status: string;
};

export type RepaymentPlan = {
  planId: string;
  totalPrincipal: string;
  totalInterest: string;
  totalRepayable: string;
  installmentCount: number;
  status: string;
  installments: Installment[];
};

export type Payment = {
  transaction: {
    paymentTransactionId: string;
    status: string;
    owner: { pluginId: string; pluginVersion: string };
    providerTransactionId: string | null;
    diagnosticCode: string | null;
    updatedAt: string;
  };
};

export type RiskAssessment = {
  assessmentId: string;
  status: string;
  decision: string | null;
  creditScore: number | null;
  blacklistHit: boolean | null;
  fraudSignal: string | null;
  reasonCodes: string[];
  policyVersion: string;
  createdAt: string;
  completedAt: string | null;
};

export type Workflow = {
  workflowId: string;
  applicationId: string;
  state: string;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type WorkflowStep = {
  attemptId: string;
  stepName: string;
  state: string;
  attemptNumber: number;
  startedAt: string;
  completedAt: string | null;
  assessmentId: string | null;
  paymentTransactionId: string | null;
  failureCode: string | null;
};

export type PluginArtifact = {
  artifactName: string;
  key: { pluginId: string; pluginVersion: string };
  sha256: string;
  type: string;
  capabilities: string[];
  desiredState: string;
};

export type Binding = {
  tenantId: string;
  capability: string;
  pluginKey: { pluginId: string; pluginVersion: string };
  version: number;
};

export type TenantSummary = { tenantId: string; tenantCode: string };
export type AuditEvent = {
  eventId: string;
  key: { pluginId: string; pluginVersion: string };
  eventType: string;
  actor: string;
  occurredAt: string;
  diagnosticCode: string;
};

export type Product = {
  productCode: string;
  version: number;
  currency: string;
  minimumPrincipal: string;
  maximumPrincipal: string;
  allowedTermsMonths: number[];
  annualInterestRatePercent: string;
};

export type Dashboard = {
  applications: number;
  manualReview: number;
  loans: number;
  activeLoans: number;
  unresolvedPayments: number;
  ambiguousPayments: number;
};

export type ApplicationDetail = {
  application: LoanApplication;
  assessments: RiskAssessment[];
  loan: Loan | null;
};

export type LoanDetail = { loan: Loan; repaymentPlan: RepaymentPlan; payment: Payment | null };
export type WorkflowDetail = { workflow: Workflow; steps: WorkflowStep[]; correlationId: string | null };

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      Accept: "application/json",
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as { code?: string; message?: string } | null;
    throw new Error(problem?.message ?? `Request failed (${response.status})`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

function correlationId() {
  return `console-${crypto.randomUUID()}`;
}

export const api = {
  dashboard: () => request<Dashboard>("/api/console/dashboard"),
  products: () => request<Product[]>("/api/console/products"),
  applications: () => request<LoanApplication[]>("/api/console/applications?limit=100"),
  application: async (id: string) => {
    const application = await request<LoanApplication>(`/api/console/applications/${id}`);
    const [assessments, loan] = await Promise.all([
      request<RiskAssessment[]>(`/api/console/applications/${id}/assessments`),
      request<Loan | null>(`/api/console/applications/${id}/loan`),
    ]);
    return { application, assessments, loan } satisfies ApplicationDetail;
  },
  createApplication: (input: { applicantId: string; productCode: string; amount: string; currency: string; termMonths: number }) =>
    request<LoanApplication>("/api/console/applications", {
      method: "POST",
      headers: { "Idempotency-Key": `console-${crypto.randomUUID()}` },
      body: JSON.stringify(input),
    }),
  evaluate: (id: string) => request<RiskAssessment>(`/api/console/applications/${id}/evaluate`, {
    method: "POST", headers: { "X-Correlation-Id": correlationId() },
  }),
  originate: (id: string) => request<Loan>(`/api/console/applications/${id}/originate`, { method: "POST" }),
  loans: () => request<Loan[]>("/api/console/loans?limit=100"),
  loan: async (id: string) => {
    const loan = await request<Loan>(`/api/console/loans/${id}`);
    const [repaymentPlan, payment] = await Promise.all([
      request<RepaymentPlan>(`/api/console/loans/${id}/repayment-plan`),
      request<Payment | null>(`/api/console/loans/${id}/payment`),
    ]);
    return { loan, repaymentPlan, payment } satisfies LoanDetail;
  },
  repaymentPlan: (id: string) => request<RepaymentPlan>(`/api/console/loans/${id}/repayment-plan`),
  payment: (id: string) => request<Payment>(`/api/console/loans/${id}/payment`),
  disburse: (id: string) => request<Payment>(`/api/console/loans/${id}/disburse`, {
    method: "POST",
    headers: { "Idempotency-Key": `console-disburse-${crypto.randomUUID()}`, "X-Correlation-Id": correlationId() },
  }),
  reconcile: (id: string) => request<Payment>(`/api/console/loans/${id}/reconcile`, {
    method: "POST", headers: { "X-Correlation-Id": correlationId() },
  }),
  workflow: (id: string) => request<WorkflowDetail>(`/api/console/applications/${id}/workflow`),
  artifacts: () => request<PluginArtifact[]>("/api/admin/plugins"),
  tenants: () => request<TenantSummary[]>("/api/admin/plugins/tenants"),
  bindings: (tenantId: string) => request<Binding[]>(`/api/admin/plugins/bindings?tenantId=${tenantId}`),
  audit: (pluginId: string, version: string) => request<AuditEvent[]>(`/api/admin/plugins/${pluginId}/${version}/audit`),
  install: (artifactName: string) => request<PluginArtifact>("/api/admin/plugins", {
    method: "POST", body: JSON.stringify({ artifactName }),
  }),
  activate: (pluginId: string, version: string) => request<PluginArtifact>(
    `/api/admin/plugins/${pluginId}/${version}/activate`, { method: "POST" }),
  bind: (input: { tenantId: string; capability: string; pluginId: string; pluginVersion: string; expectedRevision: number }) =>
    request<Binding>("/api/admin/plugins/bindings", { method: "PUT", body: JSON.stringify(input) }),
  unbind: (input: { tenantId: string; capability: string; expectedRevision: number }) =>
    request<number>("/api/admin/plugins/unbind", { method: "POST", body: JSON.stringify(input) }),
  upgrade: (input: { tenantId: string; capability: string; expectedRevision: number; artifactName: string; timeout: string }) =>
    request<PluginArtifact>("/api/admin/plugins/upgrade", { method: "POST", body: JSON.stringify(input) }),
  rollback: (input: { tenantId: string; pluginId: string; pluginVersion: string; capability: string; expectedRevision: number; timeout: string }) =>
    request<PluginArtifact>("/api/admin/plugins/rollback", { method: "POST", body: JSON.stringify(input) }),
  drain: (pluginId: string, version: string) => request<boolean>(
    `/api/admin/plugins/${pluginId}/${version}/drain?timeout=PT3S`, { method: "POST" }),
  unload: (pluginId: string, version: string) => request<void>(
    `/api/admin/plugins/${pluginId}/${version}/unload?timeout=PT3S`, { method: "POST" }),
};
