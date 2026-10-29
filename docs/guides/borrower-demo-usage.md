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

## Borrower Journeys

1. Sign in as `borrower-alpha` / `alpha-local-only` for the standard approval path. The identity maps server-side to the alpha tenant; the browser does not submit a tenant ID.
2. Enter an amount such as `1234.56`, select product/currency/term, and submit. The UI obtains a CSRF token and generates an idempotency key.
3. Review the application and risk result. Application amount is transported as an exact decimal string. Approval alone does not create an active loan.
4. For fixed synthetic review/rejection outcomes, sign out and use the separately seeded `borrower-alpha-review` / `alpha-review-local-only`, `borrower-alpha-blacklist` / `alpha-blacklist-local-only`, or `borrower-alpha-fraud` / `alpha-fraud-local-only` account. Each identity is server-mapped to its fixed synthetic applicant reference. The application form does not select the outcome.
5. Sign in as `borrower-beta` / `beta-local-only` to observe a separate mapped tenant. Alpha application IDs remain inaccessible to beta.
6. Use the automated E2E for the full successful lifecycle, because the simple borrower page demonstrates apply/status while the script also calls the authenticated origination, repayment, payment, and reconciliation routes:

```sh
cd frontend
FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm run test:e2e
```

## Admin Console

Open `https://localhost:8443/console` and sign in as `demo-admin` / `admin-local-only`. Plugin registry, tenant routing, install, upgrade, rollback, and lifecycle actions require the mapped admin identity and CSRF tokens. The borrower accounts cannot access these routes, and the admin account cannot access borrower routes.

Do not install arbitrary files or real provider credentials. Only allowlisted synthetic artifacts packaged outside the backend classpath are available in the local runtime.

## Stop and Reset

Stop services but preserve synthetic data with:

```sh
docker compose down
```

The control plane pins artifact checksums. If a rebuilt image contains different plugin bytes, the API refuses stale database identities. Only when intentionally discarding the local synthetic database, reset its volume with `docker compose down -v`, then repeat TLS generation and startup.

## Boundaries

- Keycloak and credentials are local fixtures; no production identity provider is selected.
- TLS is self-signed local development TLS, not a production certificate setup.
- All tenants, borrowers, risk outcomes, loans, and payment effects are synthetic.
- The borrower application cannot select tenant, applicant identity, role, or risk outcome.
- Risk results demonstrate workflow behavior, not validated underwriting.
- Payment routes use synthetic providers and do not move money.
