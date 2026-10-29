#!/bin/sh
set -eu

if [ "${1:-}" != "--confirm-data-reset" ]; then
  printf '%s\n' \
    "This refresh deletes the finbridge-local PostgreSQL volume, including all synthetic applications, loans, payments, and plugin audit history." \
    "Review that data loss is acceptable, then rerun: scripts/refresh-local-demo.sh --confirm-data-reset" >&2
  exit 2
fi

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

BACKUP_DIR="$ROOT/local-backups"
BACKUP_FILE="$BACKUP_DIR/finbridge-local-$(date -u +%Y%m%dT%H%M%SZ).sql"
mkdir -p "$BACKUP_DIR"
docker compose exec -T postgres pg_dump -U finbridge -d finbridge > "$BACKUP_FILE"
printf 'Saved current synthetic database to %s\n' "$BACKUP_FILE"

./deploy/generate-local-tls.sh
docker compose down -v
docker compose up -d --build --wait --wait-timeout 600
sleep 2

docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U finbridge -d finbridge <<'SQL'
DO $$
BEGIN
    IF (SELECT count(*) FROM plugin_artifacts WHERE desired_state = 'ACTIVE') <> 3 THEN
        RAISE EXCEPTION 'Expected three active bootstrap plugin artifacts';
    END IF;
    IF (SELECT count(*) FROM tenant_plugin_bindings) <> 8 THEN
        RAISE EXCEPTION 'Expected eight synthetic tenant capability bindings';
    END IF;
    IF EXISTS (SELECT 1 FROM plugin_lifecycle_events
               WHERE diagnostic_code IN ('ARTIFACT_IDENTITY_MISMATCH', 'BINDING_RESTORE_FAILED')) THEN
        RAISE EXCEPTION 'Plugin artifact or binding restoration failed';
    END IF;
END $$;

SELECT plugin_id, plugin_version, desired_state, sha256
FROM plugin_artifacts
ORDER BY plugin_id, plugin_version;

SELECT tenant_id, capability, plugin_id, plugin_version
FROM tenant_plugin_bindings
ORDER BY tenant_id, capability;
SQL

curl --fail --insecure --silent --output /dev/null https://localhost:8443/borrower
FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm --prefix frontend run test:e2e
printf '%s\n' "Local synthetic demo refreshed; risk providers and tenant bindings verified."
