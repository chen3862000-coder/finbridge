package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.application.LendingCoreException;
import io.github.finbridge.lending.domain.ApplicationStatus;
import io.github.finbridge.lending.domain.LoanApplication;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLoanApplicationRepository implements LoanApplicationRepository {
    private final JdbcTemplate jdbc;

    public JdbcLoanApplicationRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public LoanApplication insert(LoanApplication application, String idempotencyKey,
                                  String requestFingerprint) {
        try {
            jdbc.update("INSERT INTO loan_applications (application_id, tenant_id, applicant_id, "
                            + "product_code, product_version, requested_amount, currency, term_months, "
                            + "status, idempotency_key, request_fingerprint, created_at, updated_at, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    application.applicationId(), application.tenantId(), application.applicantId(),
                    application.productCode(), application.productVersion(), application.requestedAmount(),
                    application.currency().getCurrencyCode(), application.termMonths(),
                    application.status().name(), idempotencyKey, requestFingerprint,
                    Timestamp.from(application.createdAt()), Timestamp.from(application.updatedAt()),
                    application.version());
            return application;
        } catch (DuplicateKeyException duplicate) {
            return findByIdempotencyKey(application.tenantId(), idempotencyKey)
                    .filter(existing -> requestFingerprint.equals(existing.requestFingerprint()))
                    .map(IdempotentApplication::application)
                    .orElseThrow(() -> new LendingCoreException(
                            "Idempotency key was already used with a different request", duplicate));
        }
    }

    @Override
    public Optional<LoanApplication> find(UUID tenantId, UUID applicationId) {
        return jdbc.query("SELECT * FROM loan_applications WHERE tenant_id = ? AND application_id = ?",
                this::mapApplication, tenantId, applicationId).stream().findFirst();
    }

    @Override
    public LoanApplication updateStatus(LoanApplication application, long expectedVersion) {
        int updated = jdbc.update("UPDATE loan_applications SET status = ?, updated_at = ?, version = ? "
                        + "WHERE tenant_id = ? AND application_id = ? AND version = ?",
                application.status().name(), Timestamp.from(application.updatedAt()), application.version(),
                application.tenantId(), application.applicationId(), expectedVersion);
        if (updated != 1) {
            throw new LendingCoreException("Loan application changed concurrently");
        }
        return application;
    }

    private Optional<IdempotentApplication> findByIdempotencyKey(UUID tenantId, String key) {
        return jdbc.query("SELECT * FROM loan_applications WHERE tenant_id = ? AND idempotency_key = ?",
                (row, rowNumber) -> new IdempotentApplication(mapApplication(row, rowNumber),
                        row.getString("request_fingerprint")), tenantId, key).stream().findFirst();
    }

    private LoanApplication mapApplication(ResultSet row, int ignoredRowNumber) throws SQLException {
        return new LoanApplication(row.getObject("application_id", UUID.class),
                row.getObject("tenant_id", UUID.class), row.getString("applicant_id"),
                row.getString("product_code"), row.getInt("product_version"),
                row.getBigDecimal("requested_amount"), Currency.getInstance(row.getString("currency")),
                row.getInt("term_months"), ApplicationStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }

    private record IdempotentApplication(LoanApplication application, String requestFingerprint) {
    }
}
