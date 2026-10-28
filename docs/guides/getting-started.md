# Getting Started

## Prerequisites

- JDK 21. The repository Maven Enforcer rejects other Java versions.
- Docker Desktop with Compose for PostgreSQL, Keycloak, and local API E2E.
- Node/npm as pinned by the frontend lockfile, or Docker Compose's Node console container.
- Local TLS tooling (`openssl`) for the generated development certificate.

## Build and Tests

From the repository root, set `JAVA_HOME` to a JDK 21 installation and run:

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
```

This runs the Maven reactor tests, Enforcer, PMD, SpotBugs, and JaCoCo. From `frontend/`, run `npm ci`, `npm run build`, `npm test`, and `npm audit --omit=dev` for the frontend gates. The production-only audit command does not audit development dependencies.

## Start the Local Authenticated Demo

```sh
./deploy/generate-local-tls.sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

Open `https://localhost:8443/borrower` after trusting `deploy/tls/localhost.crt` in the local machine/browser. See the [README local demo section](../../README.md#authenticated-local-demo) for synthetic demo accounts and environment configuration. These credentials and generated certificates are local-only.

## Run Live HTTP E2E

The E2E deliberately disables Node TLS certificate validation for the generated local self-signed certificate. Only use this against the isolated local Compose stack:

```sh
cd frontend
FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm run test:e2e
```

The script logs in to Keycloak and exercises application/risk, origination, repayment plan, synthetic disbursement/status/reconciliation, tenant isolation, and borrower/admin route separation. It never uses real borrowers or providers.

## Stop and Reset

Stop containers while preserving local data with `docker compose down`. The fixture artifact checksums are immutable. If the API image rebuilds plugin JARs whose checksums differ from those persisted in the local control plane, startup safely refuses the mismatch. To intentionally reset only this synthetic demo database and import current artifacts afresh, use `docker compose down -v`, then start again. Do not remove volumes unless discarding the synthetic local data is intended.
