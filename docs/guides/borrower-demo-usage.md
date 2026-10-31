# Borrower Demo Usage

## Runtime Topology

Compose runs PostgreSQL, the runnable backend API, Keycloak, the Console development server, and an HTTPS reverse proxy. There is no separate API-client container. The borrower page and admin Console are routes in the same frontend application; they call the backend over the same-origin HTTPS route.

The system is local-only and synthetic. The seeded passwords, generated TLS certificate, risk outcomes, and payment provider behavior are fixtures, not production credentials or services.

## Start

From a clean checkout, install JDK 21, Docker Compose, Node/npm, OpenSSL, and a Java `keytool`. Then run:

```sh
./deploy/generate-local-tls.sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

Wait for PostgreSQL, Keycloak, and `demo-api` to report healthy. The Console and HTTPS proxy should be running. Open `https://localhost:8443/borrower`; trust `deploy/tls/localhost.crt` in the local browser/OS trust store if prompted. Do not use the script's Node TLS-verification bypass outside the local E2E command.

If local sign-in fails, Keycloak keeps the browser on its credential form and displays a generic invalid-credentials message. A successful callback returns to the borrower Console. If authentication fails after Keycloak returns to FinBridge (for example, because an identity has no enabled tenant membership), the borrower page displays a generic sign-in failure message instead of an API Whitelabel error. Use one of the seeded accounts below; the local demo does not support self-registration or password reset.

After signing out, choose **Restart Login**. It performs OIDC RP-initiated logout to clear any remaining Keycloak SSO session, then starts a new Authorization Code + PKCE request. Keycloak may ask you to confirm logout before showing a fresh login form, where you can select another seeded account. The local Keycloak client allowlists the same-origin FinBridge authorization path as its post-logout return URI.

## Borrower Journeys

1. Sign in as `borrower-alpha` / `alpha-local-only` for the standard approval path. The identity maps server-side to the alpha tenant and its own synthetic applicant reference; the borrower page lists only that account's applications. The browser does not submit a tenant ID or applicant ID.
2. Enter an amount such as `1234.56`, select product/currency/term, and submit. The UI obtains a CSRF token and generates an idempotency key.
3. Review the application and risk result. Application amount is transported as an exact decimal string. Approval alone does not create an active loan.
4. For fixed synthetic review/rejection outcomes, sign out and use `borrower-alpha-review` / `alpha-review-local-only` (score 640 -> manual review), `borrower-alpha-blacklist` / `alpha-blacklist-local-only` (blacklist hit -> reject), or `borrower-alpha-fraud` / `alpha-fraud-local-only` (critical fraud -> reject). Each identity is server-mapped to its fixed synthetic applicant reference. The application form does not select the outcome.
5. Sign in as `borrower-beta` / `beta-local-only` to observe a separate mapped tenant and only Beta's borrower application list. Alpha application IDs remain inaccessible to beta.
6. Use the automated E2E for the full successful lifecycle, because the simple borrower page demonstrates apply/status while the script also calls the authenticated origination, repayment, payment, and reconciliation routes:

```sh
cd frontend
FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm run test:e2e
```

## Admin Console

Open `https://localhost:8443/console` and sign in as `demo-admin` / `admin-local-only`. Plugin registry, tenant routing, install, upgrade, rollback, and lifecycle actions require the mapped admin identity and CSRF tokens. The internal Console is tenant-wide and can show applications from all borrowers in its mapped tenant; applicant references are displayed so records are attributable. The borrower page and its APIs are scoped to the signed-in borrower. Borrower accounts cannot access admin routes, and the admin account cannot access borrower routes.

### Demo Tenant Capability Bindings

The demo has two synthetic tenants, `DEMO_ALPHA` and `DEMO_BETA`. The tenant rows are seeded by `V1__lending_core.sql`. Plugin artifacts and tenant routes are initialized separately: Flyway creates the control-plane tables, while the optional `DemoPluginBootstrap` application runner installs available fixture JARs, activates them, and creates missing capability bindings at startup.

Compose enables this runner with `FINBRIDGE_DEMO_BOOTSTRAP_PLUGINS=true` and supplies the plugin directory at `/app/plugins`. The default in `application.yml` is `false`, so running the app outside the supplied Compose configuration does not automatically bootstrap plugin routes.

The bootstrap fixture list currently specifies these mappings for each tenant:

| Capability | Provider | Artifact identity |
|---|---|---|
| `DISBURSE` | AlphaPay | `alphapay` 1.0.0 |
| `QUERY_STATUS` | AlphaPay | `alphapay` 1.0.0 |
| `CREDIT_SCORE` | CreditScoreX | `creditscorex` 1.0.0 |
| `BLACKLIST` | BlacklistY | `blacklisty` 1.0.0 |

That fixture list is the startup default, not an unconditional reset of routing. Bootstrap only binds a fixture when its artifact is registered and that tenant does not already have a binding for the capability. It does not overwrite an existing route. It also skips fixture JARs that are not present in the plugin directory. Other artifacts packaged in the demo image, such as BetaPay, FraudSignalZ, and newer AlphaPay versions, are available for lifecycle demonstrations but are not part of this default fixture mapping. In particular, FraudSignalZ is not automatically bound to `FRAUD_SIGNAL` in this default setup.

To inspect effective routes, sign in as the demo administrator, open **Tenant Routing** in the Console, and select `DEMO_ALPHA` or `DEMO_BETA`. The page reads persisted PostgreSQL bindings, so it shows the effective routes after any manual changes, not just the bootstrap defaults. The read-only admin endpoint is `GET /api/admin/plugins/bindings?tenantId=<tenant-uuid>`.

The table `tenant_plugin_bindings` is defined in `V2__plugin_control_plane.sql`. It is populated by the bootstrap runner or by an administrator, not by a SQL seed in the migration. For local inspection, this read-only query shows the persisted route and revision for each seeded tenant:

```sql
SELECT tenants.tenant_code,
       bindings.capability,
       bindings.plugin_id,
       bindings.plugin_version,
       bindings.revision
FROM tenant_plugin_bindings AS bindings
JOIN tenants USING (tenant_id)
ORDER BY tenants.tenant_code, bindings.capability;
```

Run the query against the local Compose database with:

```sh
docker compose exec -T postgres psql -U finbridge -d finbridge
```

Then paste the `SELECT` above at the `psql` prompt. The expected fresh-bootstrap result is four bindings for each tenant (eight total). If the result differs, check whether bootstrap was enabled, whether the three fixture JARs were present, and whether routes already existed before startup. Bootstrap intentionally preserves existing bindings.

An administrator can change a route in **Tenant Routing** by selecting a tenant, capability, and active provider that advertises that capability, then committing with the displayed expected revision. A stale revision is rejected. Changes affect future calls; an existing payment continues to be reconciled by the provider version that originally owned it. See [Plugin Lifecycle Operations](plugin-lifecycle-operations.md) for upgrade, rollback, drain, and unload behavior.

Do not install arbitrary files or real provider credentials. Only allowlisted synthetic artifacts packaged outside the backend classpath are available in the local runtime.

## Stop and Reset

Stop services but preserve synthetic data with:

```sh
docker compose down
```

The control plane pins artifact checksums. Plugin JARs use a fixed Maven output timestamp so identical source builds produce stable artifact bytes. When upgrading plugin contents, the host still correctly rejects old registered identities. To intentionally refresh local plugins, run `scripts/refresh-local-demo.sh --confirm-data-reset`; the script first backs up the current synthetic database under ignored `local-backups/`, then removes/recreates Compose volumes, rebuilds/re-registers providers, and verifies active artifacts, tenant bindings, and the borrower route. The refresh resets local records, but the SQL backup is retained. The explicit confirmation flag is required. For a manual reset, use `docker compose down -v`, then repeat TLS generation and startup.

The refresh script also runs the full authenticated E2E suite. It validates standard approval, review, blacklist/fraud rejection, plugin install/rollback, tenant-specific payment routing, and tenant isolation after the providers have been restored.

## Boundaries

- Keycloak and credentials are local fixtures; no production identity provider is selected.
- TLS is self-signed local development TLS, not a production certificate setup.
- All tenants, borrowers, risk outcomes, loans, and payment effects are synthetic.
- The borrower application cannot select tenant, applicant identity, role, or risk outcome.
- Risk results demonstrate workflow behavior, not validated underwriting.
- Payment routes use synthetic providers and do not move money.
