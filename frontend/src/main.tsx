import React, { startTransition, useDeferredValue, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { api, type ApplicationDetail, type AuditEvent, type Binding, type Dashboard,
  type Loan, type LoanDetail, type LoanApplication, type PluginArtifact, type Product, type RepaymentPlan,
  type TenantSummary, type WorkflowDetail } from "./api";
import "./styles.css";

type Section = "dashboard" | "applications" | "loans" | "repayment" | "plugins" | "routing" | "workflow" | "architecture";

const navigation: { id: Section; title: string; eyebrow: string }[] = [
  { id: "dashboard", title: "Operations", eyebrow: "01" },
  { id: "applications", title: "Applications", eyebrow: "02" },
  { id: "loans", title: "Loan book", eyebrow: "03" },
  { id: "repayment", title: "Repayment plans", eyebrow: "04" },
  { id: "plugins", title: "Plugin registry", eyebrow: "05" },
  { id: "routing", title: "Tenant routing", eyebrow: "06" },
  { id: "workflow", title: "Workflow explorer", eyebrow: "07" },
  { id: "architecture", title: "Architecture", eyebrow: "08" },
];

const money = (amount: string | null | undefined, currency = "CNY") => amount == null
  ? "Not available"
  : (() => {
      const formatter = new Intl.NumberFormat("en-US", { style: "currency", currency });
      const digits = formatter.resolvedOptions().maximumFractionDigits ?? 2;
      const [whole, fraction = ""] = amount.split(".");
      const formattedWhole = BigInt(whole).toLocaleString("en-US");
      const formattedFraction = fraction.padEnd(digits, "0").slice(0, digits);
      return formatter.formatToParts(0).map(part => part.type === "integer" ? formattedWhole
        : part.type === "fraction" ? formattedFraction : part.value).join("");
    })();

const stamp = (value: string | null | undefined) => value
  ? new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value))
  : "Not recorded";

function useResource<T>(loader: () => Promise<T>, dependencies: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const reload = async () => {
    setLoading(true);
    setError(null);
    try { setData(await loader()); } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Unable to load persisted data");
    } finally { setLoading(false); }
  };
  useEffect(() => { void reload(); }, dependencies);
  return { data, error, loading, reload };
}

export function App() {
  if (window.location.pathname === "/borrower") return <BorrowerPage />;
  const [section, setSection] = useState<Section>("dashboard");
  const [isAdmin, setIsAdmin] = useState(false);
  const [selectedApplication, setSelectedApplication] = useState<string | null>(null);
  const [selectedLoan, setSelectedLoan] = useState<string | null>(null);
  const [toast, setToast] = useState("");
  const title = navigation.find(item => item.id === section)?.title ?? "Operations";

  useEffect(() => {
    void fetch("/api/session").then(response => response.ok ? response.json() : null)
      .then(value => setIsAdmin(Boolean(value?.administrator))).catch(() => setIsAdmin(false));
  }, []);

  const notify = (message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(""), 4200);
  };

  return <div className="console-shell">
    <aside className="sidebar">
      <a className="brand" href="#dashboard" onClick={() => setSection("dashboard")}>
        <span className="brand-mark"><i /><i /><i /></span>
        <span><b>finbridge</b><small>lending systems</small></span>
      </a>
      <div className="workspace-switch"><span className="workspace-dot" /><span>AUTHENTICATED WORKSPACE</span></div>
      <div className="nav-caption">WORKSPACE</div>
      <nav aria-label="Console sections">
        {navigation.filter(item => isAdmin || (item.id !== "plugins" && item.id !== "routing"))
          .map(item => <button key={item.id} className={`nav-item ${section === item.id ? "active" : ""}`}
          onClick={() => { startTransition(() => setSection(item.id)); setSelectedApplication(null); setSelectedLoan(null); }}>
          <span className="nav-index">{item.eyebrow}</span><span>{item.title}</span>
          {item.id === "workflow" && <span className="nav-live" />}
        </button>)}
      </nav>
      <div className="sidebar-bottom">
        <div className="environment"><span className="environment-dot" />SINGLE-HOST DEMO</div>
        <ConsoleIdentity />
      </div>
    </aside>

    <main className="main-area">
      <header className="topbar">
        <div className="crumb"><span>FinBridge</span><span className="slash">/</span><b>{selectedApplication ? "Application record" : selectedLoan ? "Loan record" : title}</b></div>
        <div className="top-actions"><span className="clock-label">CONTROL PLANE <i /></span><button className="icon-button" aria-label="Refresh" onClick={() => window.location.reload()}>↻</button><button className="help-button">?</button></div>
      </header>
      <div className="content">
        {selectedApplication
          ? <ApplicationRecord id={selectedApplication} onBack={() => setSelectedApplication(null)} notify={notify} />
          : selectedLoan
            ? <LoanRecord id={selectedLoan} onBack={() => setSelectedLoan(null)} notify={notify} />
            : <>
              {section === "dashboard" && <DashboardPage openApplications={() => setSection("applications")} openWorkflow={() => setSection("workflow")} />}
              {section === "applications" && <ApplicationsPage onSelect={setSelectedApplication} onCreate={notify} />}
              {section === "loans" && <LoansPage onSelect={setSelectedLoan} />}
              {section === "repayment" && <RepaymentPage onSelect={setSelectedLoan} />}
              {isAdmin && section === "plugins" && <PluginsPage notify={notify} />}
              {isAdmin && section === "routing" && <RoutingPage notify={notify} />}
              {section === "workflow" && <WorkflowPage />}
              {section === "architecture" && <ArchitecturePage />}
            </>}
      </div>
      {toast && <div role="status" className="toast"><span>✓</span>{toast}</div>}
      <footer className="footer"><span>FINBRIDGE INTERNAL CONSOLE</span><span>Build 0.1.0 <i /> synthetic environment</span></footer>
    </main>
  </div>;
}

function ConsoleIdentity() {
  const [session, setSession] = useState<{ subject: string; administrator: boolean } | null>(null);
  useEffect(() => {
    void fetch("/api/session").then(response => response.ok ? response.json() : null)
      .then(value => setSession(value?.authenticated ? value : null)).catch(() => setSession(null));
  }, []);
  const signOut = async () => {
    const csrf = await fetch("/api/csrf").then(response => response.json());
    await fetch("/logout", { method: "POST", headers: { [csrf.headerName]: csrf.token } });
    window.location.assign("/");
  };
  return <div className="principal"><div className="avatar">{session?.administrator ? "AD" : "TN"}</div>
    <div><b>{session?.subject ?? "Sign-in required"}</b>
      <span>{session?.administrator ? "FinBridge administrator" : "Mapped tenant identity"}</span></div>
    {session ? <button className="more" onClick={() => void signOut()} aria-label="Sign out">↗</button>
      : <a className="more" href="/oauth2/authorization/finbridge" aria-label="Sign in">→</a>}</div>;
}

function BorrowerPage() {
  const [session, setSession] = useState<{ authenticated: boolean; subject: string | null } | null>(null);
  const [applications, setApplications] = useState<Array<{ applicationId: string; status: string;
    requestedAmount: string; currency: string; productCode: string }> | null>(null);
  const [products, setProducts] = useState<Array<{ productCode: string; currency: string;
    minimumPrincipal: string; maximumPrincipal: string; allowedTermsMonths: number[] }> | null>(null);
  const [selectedStatus, setSelectedStatus] = useState<Record<string, string>>({});
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const refresh = async () => {
    try {
      const sessionResponse = await fetch("/api/session");
      const current = sessionResponse.ok ? await sessionResponse.json() : { authenticated: false, subject: null };
      setSession(current);
      if (current.authenticated) {
        const [applicationResponse, productResponse] = await Promise.all([
          fetch("/api/borrower/applications"), fetch("/api/borrower/products"),
        ]);
        if (!applicationResponse.ok || !productResponse.ok) throw new Error("Could not load borrower data.");
        setApplications(await applicationResponse.json());
        setProducts(await productResponse.json());
      }
    } catch { setError("Could not reach the demo API."); }
  };

  useEffect(() => { void refresh(); }, []);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setBusy(true); setError("");
    try {
      const csrf = await fetch("/api/csrf").then(response => response.json());
      const form = new FormData(event.currentTarget);
      const response = await fetch("/api/borrower/applications", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json", [csrf.headerName]: csrf.token,
          "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ applicantReference: "self",
          productCode: String(form.get("productCode")), amount: String(form.get("amount")),
          currency: String(form.get("currency")),
          termMonths: Number(form.get("term")) }),
      });
      if (!response.ok) throw new Error(`Application failed (${response.status})`);
      const created = await response.json();
      const evaluateCsrf = await fetch("/api/csrf").then(result => result.json());
      const evaluation = await fetch(`/api/borrower/applications/${created.applicationId}/evaluate`, {
        method: "POST",
        headers: { [evaluateCsrf.headerName]: evaluateCsrf.token,
          "X-Correlation-Id": `borrower-${crypto.randomUUID()}` },
      });
      if (!evaluation.ok) throw new Error(`Risk evaluation failed (${evaluation.status})`);
      const decision = await evaluation.json();
      await refresh();
      setSelectedStatus(previous => ({ ...previous, [created.applicationId]:
        `${created.status} / ${decision.decision ?? decision.status}` }));
    } catch (failure) { setError(failure instanceof Error ? failure.message : "Application failed."); }
    finally { setBusy(false); }
  };

  if (!session?.authenticated) return <main className="borrower-shell">
    <div className="borrower-card"><div className="kicker">FINBRIDGE / SYNTHETIC BORROWER DEMO</div>
      <h1>Explore a loan application</h1><p>Sign in with a seeded demo account to see only that account's tenant data.</p>
      {error && <div role="alert" className="inline-error">{error}</div>}
      <a className="button primary" href="/oauth2/authorization/finbridge">Sign in securely</a>
      <p className="muted">Synthetic data only. This local identity provider is not production authentication.</p>
    </div></main>;

  return <main className="borrower-shell"><div className="borrower-card">
    <div className="kicker">BORROWER WORKSPACE</div><h1>Your loan journey</h1>
    <p>Signed in as <b>{session.subject}</b>. Your records are scoped to your mapped tenant.</p>
    {error && <div role="alert" className="inline-error">{error}</div>}
    <form className="borrower-form" onSubmit={submit}>
      <label>Loan product<select name="productCode" required>{(products ?? []).map(product =>
        <option key={product.productCode} value={product.productCode}>{product.productCode} ({product.currency})</option>)}</select></label>
      <label>Amount<input name="amount" type="text" inputMode="decimal" defaultValue="1200.00" required /></label>
      <label>Currency<select name="currency">{(products ?? []).map(product =>
        <option key={product.currency} value={product.currency}>{product.currency}</option>)}</select></label>
      <label>Term<select name="term" defaultValue="3">{(products?.[0]?.allowedTermsMonths ?? []).map(term =>
        <option key={term} value={term}>{term} months</option>)}</select></label>
      <button className="button primary" disabled={busy}>{busy ? "Submitting…" : "Apply for synthetic loan"}</button>
    </form>
    <h2>Your applications</h2>
    {applications?.length ? applications.map(application => <article className="borrower-application"
      key={application.applicationId}><div><b>{application.productCode}</b><small>{application.applicationId}</small></div>
      <strong>{application.currency} {application.requestedAmount}</strong>
      <Status value={selectedStatus[application.applicationId] ?? application.status} />
      <button className="text-button" onClick={async () => {
        const status = await fetch(`/api/borrower/applications/${application.applicationId}`).then(r => r.json());
        setSelectedStatus(previous => ({ ...previous, [application.applicationId]:
          `${status.application.status}${status.assessments?.[0]?.decision
            ? ` / ${status.assessments[0].decision}` : ""} / ${status.workflow?.state ?? "NO_WORKFLOW"}`
          + (status.loan ? ` / LOAN ${status.loan.status}` : "") }));
      }}>Check status</button></article>) : <p>No applications yet.</p>}
    <button className="button secondary" onClick={async () => {
      const csrf = await fetch("/api/csrf").then(response => response.json());
      await fetch("/logout", { method: "POST", headers: { [csrf.headerName]: csrf.token } });
      window.location.assign("/borrower");
    }}>Sign out</button>
  </div></main>;
}

function PageHeading({ kicker, title, description, actions }: { kicker: string; title: string; description: string; actions?: React.ReactNode }) {
  return <div className="page-heading"><div><div className="kicker">{kicker}</div><h1>{title}</h1><p>{description}</p></div>{actions && <div className="heading-actions">{actions}</div>}</div>;
}

function Loading({ error, retry }: { error: string | null; retry: () => void }) {
  if (error) return <div className="notice error-notice"><div><b>Could not load persisted data</b><p>{error}</p></div><button className="button secondary" onClick={retry}>Retry</button></div>;
  return <div className="loading"><span className="spinner" /> Loading from host services…</div>;
}

function Status({ value }: { value?: string | null }) {
  const normalized = value?.toLowerCase().replaceAll("_", "-") ?? "unknown";
  const mood = normalized.includes("unknown") || normalized.includes("reconciliation") || normalized.includes("review")
    ? "warning" : normalized.includes("failed") || normalized.includes("rejected") || normalized.includes("blocked")
      ? "danger" : normalized.includes("active") || normalized.includes("succeeded") || normalized.includes("approved")
        || normalized.includes("completed") || normalized.includes("originated") ? "success" : "neutral";
  return <span className={`status ${mood}`}><i />{value?.replaceAll("_", " ") ?? "Not started"}</span>;
}

function DashboardPage({ openApplications, openWorkflow }: { openApplications: () => void; openWorkflow: () => void }) {
  const resource = useResource(api.dashboard);
  const applications = useResource(api.applications);
  if (resource.loading) return <><PageHeading kicker="PORTFOLIO / OVERVIEW" title="Operations at a glance" description="A live view of persisted lending and workflow state." /><Loading error={resource.error} retry={resource.reload} /></>;
  if (!resource.data) return <><PageHeading kicker="PORTFOLIO / OVERVIEW" title="Operations at a glance" description="A live view of persisted lending and workflow state." /><Loading error={resource.error} retry={resource.reload} /></>;
  const metrics = [
    ["APPLICATIONS", resource.data.applications, "latest tenant records", "metric-ink"],
    ["MANUAL REVIEW", resource.data.manualReview, "needs a human decision", "metric-amber"],
    ["ACTIVE LOANS", resource.data.activeLoans, `of ${resource.data.loans} recorded loans`, "metric-green"],
    ["PAYMENT AMBIGUITY", resource.data.ambiguousPayments, "unknown or reconciliation required", "metric-red"],
  ] as const;
  return <>
    <PageHeading kicker="PORTFOLIO / OVERVIEW" title="Operations at a glance" description="A live view of persisted lending and workflow state. Each count is tenant-scoped to your authenticated principal." actions={<button className="button secondary" onClick={resource.reload}>↻ Refresh</button>} />
    {resource.error && <div className="inline-error">{resource.error}</div>}
    <div className="metric-grid">{metrics.map(([label, value, note, className], index) => <article className={`metric-card ${className}`} key={label}>
      <div className="metric-top"><span>{label}</span><span className="metric-glyph">{["◫", "◎", "↗", "! "][index]}</span></div>
      <strong>{String(value).padStart(2, "0")}</strong><small>{note}</small>
      <div className="metric-rule"><span style={{ width: `${Math.min(100, Number(value) * 8 + 14)}%` }} /></div>
    </article>)}</div>
    <div className="section-row"><div><div className="kicker">RECENT ACTIVITY</div><h2>Application stream</h2></div><button className="text-button" onClick={openApplications}>View all applications <span>↗</span></button></div>
    <ApplicationTable applications={applications.data ?? []} loading={applications.loading} error={applications.error} onSelect={() => openApplications()} compact />
    <div className="lower-grid">
      <section className="panel action-panel"><div className="panel-overline">OPERATOR NOTE</div><h3>Uncertainty stays visible.</h3><p>Processing and unknown payments are not treated as failures. Open the workflow explorer to inspect durable step history and provider ownership.</p><button className="button dark" onClick={openWorkflow}>Open workflow explorer <span>→</span></button></section>
      <section className="panel stack-panel"><div className="panel-overline">CONTROL PLANE HEALTH</div><div className="stack-row"><span className="stack-led good" /><span>REST API</span><b>available</b></div><div className="stack-row"><span className="stack-led good" /><span>POSTGRESQL</span><b>persisted</b></div><div className="stack-row"><span className="stack-led warn" /><span>COORDINATION</span><b>single host</b></div><small>Health describes this process only; no multi-host guarantee.</small></section>
    </div>
  </>;
}

function ApplicationsPage({ onSelect, onCreate }: { onSelect: (id: string) => void; onCreate: (message: string) => void }) {
  const apps = useResource(api.applications);
  const products = useResource(api.products);
  const [showForm, setShowForm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true); setError("");
    try {
      const application = await api.createApplication({
        applicantId: String(form.get("applicantId")), productCode: String(form.get("productCode")),
        amount: String(form.get("amount")), currency: String(form.get("currency")),
        termMonths: Number(form.get("termMonths")),
      });
      setShowForm(false); onCreate("Application persisted successfully.");
      await apps.reload(); onSelect(application.applicationId);
    } catch (failure) { setError(failure instanceof Error ? failure.message : "Could not create application"); }
    finally { setBusy(false); }
  };
  return <>
    <PageHeading kicker="ORIGINATION / INTAKE" title="Loan applications" description="Synthetic applicants, product snapshots, and persisted risk decisions." actions={<button className="button primary" onClick={() => setShowForm(true)}>＋ New application</button>} />
    <div className="filter-strip"><span className="filter-selected">All records</span><span>Recent first</span><span className="filter-count">{apps.data?.length ?? 0} records</span></div>
    {showForm && <div className="modal-backdrop" role="presentation" onClick={event => { if (event.target === event.currentTarget) setShowForm(false); }}><form className="modal" onSubmit={submit}>
      <div className="modal-head"><div><div className="kicker">NEW / SYNTHETIC</div><h2>Start an application</h2></div><button type="button" className="icon-button" aria-label="Close" onClick={() => setShowForm(false)}>×</button></div>
      <p className="modal-copy">The authenticated tenant is selected by the host. A fresh idempotency key is generated for this submission.</p>
      <label>Applicant reference<input name="applicantId" required placeholder="synthetic-applicant-017" /></label>
      <label>Loan product<select name="productCode" required>{(products.data ?? []).map(product => <option key={product.productCode} value={product.productCode}>{product.productCode} · {product.currency} · {product.annualInterestRatePercent}% p.a.</option>)}</select></label>
      <div className="form-two"><label>Requested amount<input name="amount" type="number" min="1000" step="0.01" required placeholder="12000.00" /></label><label>Term<select name="termMonths" required><option value="3">3 months</option><option value="6">6 months</option><option value="12">12 months</option></select></label></div>
      <input type="hidden" name="currency" value={products.data?.[0]?.currency ?? "CNY"} />
      {error && <div className="inline-error">{error}</div>}
      <div className="modal-actions"><button type="button" className="button secondary" onClick={() => setShowForm(false)}>Cancel</button><button className="button primary" disabled={busy || products.loading}>{busy ? "Saving…" : "Create application"}</button></div>
    </form></div>}
    <ApplicationTable applications={apps.data ?? []} loading={apps.loading} error={apps.error} onSelect={onSelect} />
  </>;
}

function ApplicationTable({ applications, loading, error, onSelect, compact = false }: { applications: LoanApplication[]; loading: boolean; error: string | null; onSelect: (id: string) => void; compact?: boolean }) {
  const [query, setQuery] = useState("");
  const deferredQuery = useDeferredValue(query);
  const filtered = applications.filter(application => `${application.applicantId} ${application.productCode} ${application.applicationId} ${application.status}`.toLowerCase().includes(deferredQuery.toLowerCase()));
  if (loading) return <div className="panel"><Loading error={error} retry={() => undefined} /></div>;
  if (error) return <div className="panel"><div className="empty"><b>Application feed unavailable</b><p>{error}</p></div></div>;
  return <section className="panel table-panel">
    {!compact && <div className="table-toolbar"><label className="search-box"><span>⌕</span><input aria-label="Filter applications" placeholder="Filter by applicant, product or status" value={query} onChange={event => setQuery(event.target.value)} /></label><span className="table-count">{filtered.length} shown</span></div>}
    <div className="table-scroll"><table><thead><tr><th>APPLICANT / REFERENCE</th><th>PRODUCT</th><th>REQUESTED</th><th>SUBMITTED</th><th>STATUS</th><th /></tr></thead><tbody>
      {filtered.map((application, index) => <tr key={application.applicationId} onClick={() => onSelect(application.applicationId)} className="click-row">
        <td><span className="applicant-cell"><span className={`row-avatar avatar-${index % 4}`}>{application.applicantId.slice(0, 2).toUpperCase()}</span><span><b>{application.applicantId}</b><small>{application.applicationId.slice(0, 8)}…</small></span></span></td>
        <td><b>{application.productCode}</b><small className="table-sub">v{application.productVersion}</small></td>
        <td className="numeric">{money(application.requestedAmount, application.currency)}</td>
        <td>{stamp(application.createdAt)}</td>
        <td><Status value={application.status} /></td><td className="row-arrow">↗</td>
      </tr>)}
      {!filtered.length && <tr><td colSpan={6}><div className="empty"><b>No applications found</b><p>New records will appear here after they are persisted.</p></div></td></tr>}
    </tbody></table></div>
  </section>;
}

function ApplicationRecord({ id, onBack, notify }: { id: string; onBack: () => void; notify: (message: string) => void }) {
  const resource = useResource(() => api.application(id), [id]);
  const [actionError, setActionError] = useState("");
  const [busy, setBusy] = useState(false);
  const run = async (operation: () => Promise<unknown>, message: string) => {
    setBusy(true); setActionError("");
    try { await operation(); await resource.reload(); notify(message); }
    catch (failure) { setActionError(failure instanceof Error ? failure.message : "Action failed"); }
    finally { setBusy(false); }
  };
  if (resource.loading || !resource.data) return <><button className="back-link" onClick={onBack}>← Applications</button><Loading error={resource.error} retry={resource.reload} /></>;
  const { application, assessments, loan } = resource.data;
  return <>
    <button className="back-link" onClick={onBack}>← Applications</button>
    <PageHeading kicker={`APPLICATION / ${application.applicationId.slice(0, 8).toUpperCase()}`} title={application.applicantId} description={`${application.productCode} · ${application.termMonths} month term · submitted ${stamp(application.createdAt)}`} actions={<Status value={application.status} />} />
    {actionError && <div className="inline-error">{actionError}</div>}
    <div className="detail-grid"><section className="panel detail-main"><div className="panel-header"><div><div className="panel-overline">RISK ASSESSMENT</div><h2>Decision record</h2></div>{application.status === "SUBMITTED" && <button className="button dark" disabled={busy} onClick={() => run(() => api.evaluate(id), "Risk assessment persisted.")}>{busy ? "Working…" : "Evaluate application"}</button>}</div>
      {assessments.length ? assessments.map(item => <div className="assessment" key={item.assessmentId}><div className="assessment-decision"><span className={`decision-stamp ${(item.decision ?? "PENDING").toLowerCase()}`}>{item.decision ?? item.status}</span><div><b>{item.policyVersion}</b><small>completed {stamp(item.completedAt)}</small></div></div><div className="evidence-grid"><DataPoint label="CREDIT SCORE" value={item.creditScore ?? "Unavailable"} /><DataPoint label="BLACKLIST" value={item.blacklistHit == null ? "Unavailable" : item.blacklistHit ? "Hit" : "Clear"} /><DataPoint label="FRAUD SIGNAL" value={item.fraudSignal ?? "Unavailable"} /></div><div className="reason-list">{item.reasonCodes.map(reason => <span key={reason}>{reason}</span>)}</div></div>) : <div className="empty"><b>No risk assessment is persisted</b><p>Evaluation is a separate, read-only provider step. Approval does not originate or activate a loan.</p></div>}
      <div className="panel-subsection"><div className="panel-overline">ORIGINATION</div>{loan ? <div className="origination-card"><span>Loan created</span><b>{loan.loanId}</b><Status value={loan.status} /><button className="text-button" onClick={() => notify("Open the loan book to inspect its repayment plan and payment state.")}>Inspect loan ↗</button></div> : application.status === "APPROVED" ? <div className="action-callout"><div><b>Approved and eligible for origination</b><p>Loan and repayment schedule creation are separate from payment disbursement.</p></div><button className="button primary" disabled={busy} onClick={() => run(() => api.originate(id), "Pending-disbursement loan originated.")}>{busy ? "Working…" : "Originate loan"}</button></div> : <p className="muted-copy">No loan has been originated from this application.</p>}</div>
    </section><aside className="panel side-panel"><div className="panel-overline">APPLICATION SNAPSHOT</div><DataPoint label="REQUESTED PRINCIPAL" value={money(application.requestedAmount, application.currency)} /><DataPoint label="TERM" value={`${application.termMonths} months`} /><DataPoint label="PRODUCT SNAPSHOT" value={`${application.productCode} · v${application.productVersion}`} /><DataPoint label="UPDATED" value={stamp(application.updatedAt)} /><div className="side-divider"/><div className="panel-overline">IDENTIFIERS</div><CopyValue label="APPLICATION ID" value={application.applicationId} /></aside></div>
  </>;
}

function LoansPage({ onSelect }: { onSelect: (id: string) => void }) {
  const resource = useResource(api.loans);
  return <><PageHeading kicker="PORTFOLIO / SERVICING" title="Loan book" description="Persisted loan lifecycle, with activation distinguished from approval and origination." />
    {resource.loading || resource.error ? <div className="panel"><Loading error={resource.error} retry={resource.reload} /></div> : <div className="loan-grid">{resource.data?.map(loan => <button className="loan-card panel" key={loan.loanId} onClick={() => onSelect(loan.loanId)}><div className="loan-card-top"><span className="panel-overline">{loan.productCode}</span><Status value={loan.status} /></div><strong>{money(loan.principal, loan.currency)}</strong><span className="loan-ref">{loan.loanId.slice(0, 8)}… · {loan.termMonths} months</span><div className="loan-card-bottom"><span>Created {stamp(loan.createdAt)}</span><b>↗</b></div></button>)}{!resource.data?.length && <div className="panel empty"><b>No persisted loans yet</b><p>Loans appear after an approved application is originated.</p></div>}</div>}
  </>;
}

function LoanRecord({ id, onBack, notify }: { id: string; onBack: () => void; notify: (message: string) => void }) {
  const resource = useResource(() => api.loan(id), [id]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const act = async (operation: () => Promise<unknown>, message: string) => {
    setBusy(true); setError("");
    try { await operation(); await resource.reload(); notify(message); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "Payment operation failed"); }
    finally { setBusy(false); }
  };
  if (resource.loading || !resource.data) return <><button className="back-link" onClick={onBack}>← Loan book</button><Loading error={resource.error} retry={resource.reload} /></>;
  const { loan, repaymentPlan, payment } = resource.data;
  const paymentStatus = payment?.transaction.status;
  const ambiguity = paymentStatus === "UNKNOWN" || paymentStatus === "RECONCILIATION_REQUIRED";
  return <><button className="back-link" onClick={onBack}>← Loan book</button><PageHeading kicker={`SERVICING / ${loan.loanId.slice(0, 8).toUpperCase()}`} title={money(loan.principal, loan.currency)} description={`${loan.productCode} · ${loan.termMonths} installments · created ${stamp(loan.createdAt)}`} actions={<Status value={loan.status} />} />
    {error && <div className="inline-error">{error}</div>}
    <div className="detail-grid"><div className="detail-main-column"><section className="panel"><div className="panel-header"><div><div className="panel-overline">PAYMENT OWNERSHIP</div><h2>Disbursement state</h2></div><Status value={paymentStatus ?? "NOT_STARTED"} /></div>{payment ? <><div className="payment-summary"><div><span>OWNER</span><b>{payment.transaction.owner.pluginId} · v{payment.transaction.owner.pluginVersion}</b></div><div><span>PROVIDER REFERENCE</span><b>{payment.transaction.providerTransactionId ?? "Not returned"}</b></div><div><span>LAST UPDATED</span><b>{stamp(payment.transaction.updatedAt)}</b></div></div>{ambiguity && <div className="notice warning-notice"><b>Outcome is ambiguous</b><span>This payment has not been treated as a failure. Reconciliation queries the original provider owner; it never resubmits the payment.</span></div>}{paymentStatus === "SUBMISSION_IN_PROGRESS" && <div className="notice warning-notice"><b>Dispatch is unresolved</b><span>Retry is disabled. Query the pinned provider to resolve whether the external side effect completed.</span></div>}{payment.transaction.diagnosticCode && <div className="diagnostic">{payment.transaction.diagnosticCode}</div>}</> : <div className="empty"><b>No payment intent persisted</b><p>Disbursement remains separate from approval and loan origination.</p></div>}<div className="action-row">{loan.status === "PENDING_DISBURSEMENT" && !paymentStatus && <button className="button primary" disabled={busy} onClick={() => act(() => api.disburse(id), "Disbursement result persisted.")}>Submit disbursement</button>}{payment && (paymentStatus === "SUBMISSION_IN_PROGRESS" || paymentStatus === "PROCESSING" || ambiguity) ? <button className="button primary" disabled={busy} onClick={() => act(() => api.reconcile(id), "Provider status query completed.")}>{busy ? "Querying…" : "Query original provider"}</button> : null}<span className="muted-copy">Automatic resubmission is not available.</span></div></section>
      <section className="panel repayment-panel"><div className="panel-header"><div><div className="panel-overline">SCHEDULE / {repaymentPlan.installmentCount} PERIODS</div><h2>Repayment plan</h2></div><Status value={repaymentPlan.status} /></div><div className="repayment-totals"><DataPoint label="PRINCIPAL" value={money(repaymentPlan.totalPrincipal, loan.currency)} /><DataPoint label="TOTAL INTEREST" value={money(repaymentPlan.totalInterest, loan.currency)} /><DataPoint label="TOTAL REPAYABLE" value={money(repaymentPlan.totalRepayable, loan.currency)} /></div><InstallmentTable plan={repaymentPlan} currency={loan.currency} /></section></div>
      <aside className="panel side-panel"><div className="panel-overline">LOAN RECORD</div><DataPoint label="ANNUAL RATE" value={`${loan.annualInterestRatePercent}%`} /><DataPoint label="DISBURSED" value={stamp(loan.disbursedAt)} /><DataPoint label="ACTIVATED" value={stamp(loan.activatedAt)} /><div className="side-divider"/><CopyValue label="APPLICATION ID" value={loan.applicationId} /><CopyValue label="LOAN ID" value={loan.loanId} /></aside></div>
  </>;
}

function InstallmentTable({ plan, currency }: { plan: RepaymentPlan; currency: string }) {
  return <div className="table-scroll"><table><thead><tr><th>#</th><th>DUE DATE</th><th>PRINCIPAL</th><th>INTEREST</th><th>TOTAL</th><th>REMAINING</th><th>STATE</th></tr></thead><tbody>{plan.installments.map(item => <tr key={item.installmentId}><td>{String(item.installmentNumber).padStart(2, "0")}</td><td>{item.dueDate ?? "After disbursement"}</td><td className="numeric">{money(item.principalAmount, currency)}</td><td className="numeric">{money(item.interestAmount, currency)}</td><td className="numeric">{money(item.totalAmount, currency)}</td><td className="numeric">{money(item.remainingPrincipal, currency)}</td><td><Status value={item.status} /></td></tr>)}</tbody></table></div>;
}

function RepaymentPage({ onSelect }: { onSelect: (id: string) => void }) {
  const loans = useResource(api.loans);
  return <><PageHeading kicker="SERVICING / SCHEDULES" title="Repayment plans" description="Installments are persisted as pending until confirmed disbursement activates and dates the schedule." />
    {loans.loading || loans.error ? <div className="panel"><Loading error={loans.error} retry={loans.reload} /></div> : <div className="repayment-list">{(loans.data ?? []).map(loan => <RepaymentSummary key={loan.loanId} loan={loan} onSelect={onSelect} />)}{!loans.data?.length && <div className="panel empty"><b>No repayment schedules</b><p>Originate an approved application to generate a pending schedule.</p></div>}</div>}
  </>;
}

function RepaymentSummary({ loan, onSelect }: { loan: Loan; onSelect: (id: string) => void }) {
  const plan = useResource<RepaymentPlan>(() => api.repaymentPlan(loan.loanId), [loan.loanId]);
  if (plan.loading) return <div className="panel compact-panel"><span className="spinner" /> Loading schedule…</div>;
  if (plan.error || !plan.data) return <div className="panel compact-panel"><b>{loan.productCode}</b><span className="muted-copy">Schedule unavailable: {plan.error}</span></div>;
  return <button className="panel repayment-summary click-row" onClick={() => onSelect(loan.loanId)}><div><div className="panel-overline">{loan.productCode} · {loan.termMonths} PAYMENTS</div><b>{money(plan.data.totalRepayable, loan.currency)}</b><small>{loan.loanId.slice(0, 8)}…</small></div><div className="repayment-summary-right"><Status value={plan.data.status} /><span>{plan.data.status === "ACTIVE" ? `${plan.data.installments.filter(item => item.dueDate).length} due dates established` : "Dates finalize after disbursement"}</span></div><b className="row-arrow">↗</b></button>;
}

function PluginsPage({ notify }: { notify: (message: string) => void }) {
  const resource = useResource(api.artifacts);
  const [selected, setSelected] = useState<PluginArtifact | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [artifactName, setArtifactName] = useState("");
  const act = async (action: () => Promise<unknown>, message: string) => {
    setBusy(true); setError("");
    try { await action(); await resource.reload(); notify(message); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "Lifecycle operation failed"); }
    finally { setBusy(false); }
  };
  return <><PageHeading kicker="CONTROL PLANE / LIFECYCLE" title="Plugin registry" description="Approved local artifacts, immutable checksums, desired lifecycle state, and auditable actions." actions={<button className="button primary" onClick={() => document.getElementById("artifact-name")?.focus()}>＋ Register artifact</button>} />
    <form className="register-bar" onSubmit={event => { event.preventDefault(); void act(() => api.install(artifactName), "Artifact loaded and registered.").then(() => setArtifactName("")); }}><label><span>LOCAL ALLOWLIST FILENAME</span><input id="artifact-name" value={artifactName} onChange={event => setArtifactName(event.target.value)} placeholder="provider-1.0.0-jar-with-dependencies.jar" required /></label><button className="button dark" disabled={busy}>{busy ? "Validating…" : "Load artifact"}</button><small>Only prebuilt filenames inside the configured plugin directory are accepted.</small></form>
    {error && <div className="inline-error">{error}</div>}
    {resource.loading || resource.error ? <div className="panel"><Loading error={resource.error} retry={resource.reload} /></div> : <div className="plugin-layout"><section className="panel plugin-list"><div className="table-toolbar"><div><div className="panel-overline">PERSISTED ARTIFACTS</div><b>{resource.data?.length ?? 0} registered</b></div><span className="muted-copy">not live loader state</span></div>{resource.data?.map(item => <button key={`${item.key.pluginId}:${item.key.pluginVersion}`} className={`plugin-row ${selected?.key.pluginId === item.key.pluginId && selected.key.pluginVersion === item.key.pluginVersion ? "selected" : ""}`} onClick={() => setSelected(item)}><span className={`plugin-icon ${item.type.toLowerCase()}`}>{item.type === "PAYMENT" ? "⇄" : "⌁"}</span><span className="plugin-identity"><b>{item.key.pluginId}</b><small>v{item.key.pluginVersion} · {item.type.toLowerCase()}</small></span><Status value={item.desiredState} /><span className="row-arrow">→</span></button>)}</section>
      <section className="panel plugin-detail">{selected ? <PluginDetail artifact={selected} busy={busy} onAction={act} refresh={resource.reload} /> : <div className="empty"><span className="large-ghost">⌘</span><b>Select an artifact</b><p>Inspect persisted metadata and lifecycle history.</p></div>}</section></div>}
  </>;
}

function PluginDetail({ artifact, busy, onAction, refresh }: { artifact: PluginArtifact; busy: boolean; onAction: (action: () => Promise<unknown>, message: string) => Promise<void>; refresh: () => Promise<void> }) {
  const audit = useResource(() => api.audit(artifact.key.pluginId, artifact.key.pluginVersion), [artifact.key.pluginId, artifact.key.pluginVersion]);
  const lifecycleAction = (action: () => Promise<unknown>, message: string) => async () => {
    await onAction(action, message); await refresh(); await audit.reload();
  };
  return <><div className="plugin-detail-head"><div className="plugin-icon large">{artifact.type === "PAYMENT" ? "⇄" : "⌁"}</div><div><div className="panel-overline">{artifact.type} PROVIDER</div><h2>{artifact.key.pluginId}</h2><span>Version {artifact.key.pluginVersion}</span></div><Status value={artifact.desiredState} /></div><div className="plugin-facts"><DataPoint label="ARTIFACT FILE" value={artifact.artifactName} /><DataPoint label="SHA-256" value={`${artifact.sha256.slice(0, 20)}…`} /><div className="capabilities"><span>CAPABILITIES</span><div>{artifact.capabilities.map(capability => <i key={capability}>{capability}</i>)}</div></div></div>
    <div className="action-row wrap-actions">{artifact.desiredState !== "ACTIVE" && <button className="button secondary" disabled={busy} onClick={lifecycleAction(() => api.activate(artifact.key.pluginId, artifact.key.pluginVersion), "Artifact activated.")}>Activate</button>}<button className="button secondary" disabled={busy} onClick={lifecycleAction(() => api.drain(artifact.key.pluginId, artifact.key.pluginVersion), "Drain request completed.")}>Drain</button><button className="button danger-button" disabled={busy} onClick={lifecycleAction(() => api.unload(artifact.key.pluginId, artifact.key.pluginVersion), "Unload request completed.")}>Unload</button></div>
    <div className="audit-block"><div className="panel-overline">LIFECYCLE AUDIT</div>{audit.loading ? <div className="loading small-loading"><span className="spinner" /> Loading immutable event history…</div> : audit.error ? <p className="muted-copy">Audit not available: {audit.error}</p> : audit.data?.length ? audit.data.map(event => <div className="audit-row" key={event.eventId}><span className="audit-dot" /><div><b>{event.eventType.replaceAll("_", " ")}</b><small>{event.actor} · {stamp(event.occurredAt)}</small></div><span>{event.diagnosticCode}</span></div>) : <p className="muted-copy">No lifecycle events persisted.</p>}</div>
    <p className="boundary-note">Lifecycle actions affect future routing only. The host blocks unload while bindings, invocation leases, or unresolved payment ownership remain.</p>
  </>;
}

function RoutingPage({ notify }: { notify: (message: string) => void }) {
  const tenants = useResource(api.tenants);
  const artifacts = useResource(api.artifacts);
  const [tenantId, setTenantId] = useState("");
  const bindings = useResource(() => tenantId ? api.bindings(tenantId) : Promise.resolve([] as Binding[]), [tenantId]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [capability, setCapability] = useState("CREDIT_SCORE");
  const [artifactIdentity, setArtifactIdentity] = useState("");
  const [expectedRevision, setExpectedRevision] = useState(0);
  const activeArtifacts = (artifacts.data ?? []).filter(item => item.desiredState === "ACTIVE" && item.capabilities.includes(capability));
  useEffect(() => {
    const tenant = tenants.data?.[0];
    if (tenant && !tenantId) setTenantId(tenant.tenantId);
  }, [tenants.data, tenantId]);
  useEffect(() => { setArtifactIdentity(""); }, [capability]);
  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const selected = activeArtifacts.find(item => `${item.key.pluginId}:${item.key.pluginVersion}` === artifactIdentity);
    if (!selected || !tenantId) return;
    setBusy(true); setError("");
    try {
      const updated = await api.bind({ tenantId, capability, pluginId: selected.key.pluginId,
        pluginVersion: selected.key.pluginVersion, expectedRevision });
      setExpectedRevision(updated.version); await bindings.reload(); notify(`Future ${capability} calls route to ${selected.key.pluginId} v${selected.key.pluginVersion}.`);
    } catch (failure) { setError(failure instanceof Error ? failure.message : "Binding update failed"); }
    finally { setBusy(false); }
  };
  const removeBinding = async (binding: Binding) => {
    setBusy(true); setError("");
    try {
      const revision = await api.unbind({ tenantId: binding.tenantId, capability: binding.capability, expectedRevision: binding.version });
      setExpectedRevision(revision); await bindings.reload(); notify(`${binding.capability} binding removed at revision ${revision}.`);
    } catch (failure) { setError(failure instanceof Error ? failure.message : "Unbind failed"); }
    finally { setBusy(false); }
  };
  const loading = tenants.loading || artifacts.loading || bindings.loading;
  const loadError = tenants.error ?? artifacts.error ?? bindings.error;
  return <><PageHeading kicker="CONTROL PLANE / ROUTING" title="Tenant routing" description="Capability-level bindings are durable, revisioned, and affect future invocations only." actions={<div className="scope-pill"><span className="environment-dot" />ADMIN SCOPE</div>} />
    {loading || loadError ? <div className="panel"><Loading error={loadError} retry={() => { void tenants.reload(); void artifacts.reload(); void bindings.reload(); }} /></div> : <>
      <section className="panel routing-panel"><div className="panel-header"><div><div className="panel-overline">ROUTE EDITOR</div><h2>Change a capability binding</h2></div><span className="revision-chip">OPTIMISTIC REVISION</span></div><form onSubmit={submit} className="routing-form"><label>Tenant<select value={tenantId} onChange={event => { setTenantId(event.target.value); setExpectedRevision(0); }}>{tenants.data?.map(tenant => <option key={tenant.tenantId} value={tenant.tenantId}>{tenant.tenantCode} · {tenant.tenantId.slice(0, 8)}</option>)}</select></label><label>Capability<select value={capability} onChange={event => setCapability(event.target.value)}>{["CREDIT_SCORE", "BLACKLIST", "FRAUD_SIGNAL", "DISBURSE", "COLLECT", "QUERY_STATUS"].map(value => <option key={value}>{value}</option>)}</select></label><label>Provider<select value={artifactIdentity} onChange={event => setArtifactIdentity(event.target.value)} required><option value="">Select active capable provider</option>{activeArtifacts.map(item => <option key={`${item.key.pluginId}:${item.key.pluginVersion}`} value={`${item.key.pluginId}:${item.key.pluginVersion}`}>{item.key.pluginId} · v{item.key.pluginVersion}</option>)}</select></label><label>Expected revision<input type="number" min="0" value={expectedRevision} onChange={event => setExpectedRevision(Number(event.target.value))} /></label><button className="button primary" disabled={busy || !artifactIdentity}>{busy ? "Committing…" : "Commit route"}</button></form>{error && <div className="inline-error">{error}</div>}<p className="muted-copy">A stale revision is rejected. Successful updates are committed before the in-memory routing snapshot is published.</p></section>
      <section className="panel"><div className="panel-header"><div><div className="panel-overline">CURRENT ROUTES</div><h2>{tenants.data?.find(tenant => tenant.tenantId === tenantId)?.tenantCode ?? "Tenant"}</h2></div><span>{bindings.data?.length ?? 0} bindings</span></div><div className="table-scroll"><table><thead><tr><th>CAPABILITY</th><th>PROVIDER</th><th>REVISION</th><th /></tr></thead><tbody>{bindings.data?.map(binding => <tr key={binding.capability}><td><b>{binding.capability}</b></td><td>{binding.pluginKey.pluginId} · v{binding.pluginKey.pluginVersion}</td><td>r{binding.version}</td><td><button className="text-button danger-text" disabled={busy} onClick={() => void removeBinding(binding)}>Unbind</button></td></tr>)}{!bindings.data?.length && <tr><td colSpan={4}><div className="empty"><b>No persisted routes</b><p>Choose an active plugin that implements the selected capability.</p></div></td></tr>}</tbody></table></div></section>
    </>}
  </>;
}

function WorkflowPage() {
  const applications = useResource(api.applications);
  const [id, setId] = useState("");
  const workflow = useResource(() => id ? api.workflow(id) : Promise.reject(new Error("Select an application to inspect its persisted workflow")), [id]);
  const [filter, setFilter] = useState("");
  const selectedApp = applications.data?.find(item => item.applicationId === id);
  return <><PageHeading kicker="OPERATIONS / DURABLE STATE" title="Workflow explorer" description="Inspect durable workflow transitions, attempts, timestamps, entity IDs, and pinned provider versions. Correlation IDs are not persisted in the current workflow schema." />
    <section className="panel workflow-selector"><label>Application<select value={id} onChange={event => setId(event.target.value)}><option value="">Select application</option>{applications.data?.map(item => <option key={item.applicationId} value={item.applicationId}>{item.applicantId} · {item.applicationId.slice(0, 8)} · {item.status}</option>)}</select></label><label>Find step<input value={filter} onChange={event => setFilter(event.target.value)} placeholder="Risk, payment, state…" /></label></section>
    {applications.loading ? <div className="panel"><Loading error={applications.error} retry={applications.reload} /></div> : applications.error ? <div className="panel"><Loading error={applications.error} retry={applications.reload} /></div> : !id ? <div className="panel empty"><span className="large-ghost">⌁</span><b>Choose an application</b><p>Workflow data is loaded from PostgreSQL, not generated by the Console.</p></div> : workflow.loading ? <div className="panel"><Loading error={workflow.error} retry={workflow.reload} /></div> : workflow.error ? <div className="panel"><div className="empty"><b>No workflow is persisted for this application</b><p>{workflow.error}</p></div></div> : workflow.data ? <><section className="workflow-overview panel"><div className="workflow-orbit"><span className="orbit-core">{selectedApp?.applicantId.slice(0, 2).toUpperCase() ?? "WF"}</span><span className="orbit-node n1">R</span><span className="orbit-node n2">P</span><span className="orbit-node n3">L</span></div><div className="workflow-meta"><div className="panel-overline">CURRENT WORKFLOW</div><h2>{workflow.data.workflow.state.replaceAll("_", " ")}</h2><div className="workflow-pills"><span>v{workflow.data.workflow.version}</span><span>{workflow.data.steps.length} persisted attempts</span><span>updated {stamp(workflow.data.workflow.updatedAt)}</span></div><div className="workflow-ids"><CopyValue label="WORKFLOW ID" value={workflow.data.workflow.workflowId} /><CopyValue label="APPLICATION ID" value={workflow.data.workflow.applicationId} /></div><div className="notice info-notice"><b>Correlation ID unavailable</b><span>The current schema does not persist workflow correlation IDs. Step and entity identifiers below come from persisted records.</span></div></div></section><section className="panel timeline-panel"><div className="panel-header"><div><div className="panel-overline">EXECUTION HISTORY</div><h2>Workflow steps</h2></div><span>{workflow.data.steps.length} records</span></div>{workflow.data.steps.filter(step => `${step.stepName} ${step.state} ${step.failureCode ?? ""}`.toLowerCase().includes(filter.toLowerCase())).map((step, index) => <div className="timeline-step" key={step.attemptId}><div className="timeline-rail"><span className={`timeline-node ${step.state.toLowerCase()}`}>{step.state === "COMPLETED" ? "✓" : String(index + 1).padStart(2, "0")}</span>{index < workflow.data!.steps.length - 1 && <i />}</div><div className="timeline-content"><div className="timeline-head"><div><span className="panel-overline">{step.stepName} · ATTEMPT {step.attemptNumber}</span><h3>{step.state.replaceAll("_", " ")}</h3></div><span>{stamp(step.startedAt)}</span></div><div className="timeline-identifiers">{step.assessmentId && <CopyValue label="ASSESSMENT" value={step.assessmentId} />}{step.paymentTransactionId && <CopyValue label="PAYMENT TRANSACTION" value={step.paymentTransactionId} />}{step.failureCode && <span className="diagnostic">{step.failureCode}</span>}<span className="duration">Duration {step.completedAt ? formatDuration(Date.parse(step.completedAt) - Date.parse(step.startedAt)) : "in progress / unresolved"}</span></div></div></div>)}{!workflow.data.steps.length && <div className="empty"><b>No step attempts yet</b><p>A persisted step appears when evaluation or payment is claimed.</p></div>}</section></> : null}
  </>;
}

function formatDuration(milliseconds: number) {
  return milliseconds < 1000 ? `${milliseconds} ms` : `${(milliseconds / 1000).toFixed(2)} s`;
}

function ArchitecturePage() {
  const layers = [
    { number: "01", name: "FinBridge Console", detail: "React operator and tenant experience", modules: "frontend/ · static client", color: "arch-cyan" },
    { number: "02", name: "Host composition", detail: "MVC adapters, principal checks, lifecycle and recovery", modules: "finbridge-host", color: "arch-violet" },
    { number: "03", name: "Lending Core", detail: "Tenant-scoped domain commands, durable workflows and repositories", modules: "finbridge-lending-core", color: "arch-amber" },
    { number: "04", name: "Capability gateways", detail: "Provider invocation, deadlines, outcomes and bounded metrics", modules: "risk / payment gateways", color: "arch-green" },
    { number: "05", name: "Plugin runtime + SPI", detail: "Leases, routing snapshots, isolated artifact loaders and typed contracts", modules: "runtime / spi", color: "arch-blue" },
    { number: "06", name: "PostgreSQL", detail: "Applications, workflows, payments, schedules and lifecycle control plane", modules: "Flyway migrations", color: "arch-slate" },
  ];
  return <><PageHeading kicker="SYSTEM MAP / TRUSTED BOUNDARIES" title="Architecture explorer" description="The Console calls host adapters; durable domain rules remain in Lending Core. Provider implementations remain external artifacts." />
    <div className="architecture-layout"><section className="architecture-stack">{layers.map((layer, index) => <article className={`arch-layer ${layer.color}`} key={layer.number}><div className="arch-number">{layer.number}</div><div className="arch-copy"><h2>{layer.name}</h2><p>{layer.detail}</p><small>{layer.modules}</small></div><div className="arch-direction">{index === 0 ? "UI" : index === layers.length - 1 ? "DATA" : "↓"}</div></article>)}</section><aside className="panel boundary-panel"><div className="panel-overline">BOUNDARIES THAT MATTER</div><div className="boundary-item"><span className="boundary-mark">A</span><div><b>Authentication stays external</b><p>HTTP requests require the containing host to provide a trusted FinBridge principal. This Console does not mint credentials.</p></div></div><div className="boundary-item"><span className="boundary-mark">P</span><div><b>Providers are not bundled</b><p>Lifecycle admin selects an allowlisted prebuilt artifact. No browser uploads, signatures, or in-process sandbox are claimed.</p></div></div><div className="boundary-item"><span className="boundary-mark">1</span><div><b>Single-host consistency</b><p>Persisted bindings are committed before local routing snapshots update. No multi-host coordination is implemented.</p></div></div><div className="boundary-item"><span className="boundary-mark">?</span><div><b>Payment ambiguity is honest</b><p>PROCESSING and UNKNOWN are visible. Reconciliation queries the immutable original provider owner; never blindly resends.</p></div></div></aside></div>
  </>;
}

function DataPoint({ label, value }: { label: string; value: React.ReactNode }) {
  return <div className="data-point"><span>{label}</span><b>{value}</b></div>;
}

function CopyValue({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false);
  return <div className="copy-value"><span>{label}</span><code>{value}</code><button aria-label={`Copy ${label}`} onClick={() => { void navigator.clipboard?.writeText(value); setCopied(true); window.setTimeout(() => setCopied(false), 1200); }}>{copied ? "✓" : "⧉"}</button></div>;
}

const rootElement = document.getElementById("root");
if (rootElement) {
  createRoot(rootElement).render(<React.StrictMode><App /></React.StrictMode>);
}
