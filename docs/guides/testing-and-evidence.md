# Testing and Evidence

## Java

Run the blocking reactor gate from the repository root under JDK 21:

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
```

Relevant output reports live under each module's `target/surefire-reports`, `target/site/jacoco/`, `target/spotbugsXml.xml`, and `target/pmd.xml`. Production coverage is checked per bundle, class, and concrete method. The integration-tests module contains tests only; its Surefire tests run but JaCoCo production coverage is not applicable.

PostgreSQL/Testcontainers tests verify constraints, transaction boundaries, concurrency, restart recovery, and real external plugin JAR loading. A mock-only test is not a substitute for those architecture claims.

## Frontend

From `frontend/`:

```sh
npm ci
npm run build
npm test
npm audit --omit=dev
```

The audit scope excludes development dependencies. `npm ci` may still report advisories in the complete dependency tree.

## Compose and Live HTTP

```sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl --insecure --silent --show-error --output /dev/null --write-out 'HTTP %{http_code}\n' https://localhost:8443/borrower
```

The local `--insecure` curl and the E2E's `NODE_TLS_REJECT_UNAUTHORIZED=0` apply only to the generated self-signed development certificate. Inspect startup with `docker compose logs`; verify synthetic DB rows with PostgreSQL queries. The repeatable authenticated E2E is run from `frontend/` with `FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm run test:e2e`.

After the final verification, preserve local data with `docker compose down`. Use `docker compose down -v` only when intentionally discarding the synthetic DB volume, such as after changing immutable external plugin artifact bytes.

## Claims and Limits

Record exact commands and observed output in `docs/quality/architecture-review-checklist.md`. The local E2E covers successful Keycloak login and selected borrower/admin flows; it does not yet test the full negative OIDC token matrix or production infrastructure. Do not describe this local demo as a production identity, underwriting, or payment system.
