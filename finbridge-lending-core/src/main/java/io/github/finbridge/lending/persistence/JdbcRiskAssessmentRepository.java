package io.github.finbridge.lending.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.finbridge.lending.application.LendingCoreException;
import io.github.finbridge.lending.domain.AssessmentDecision;
import io.github.finbridge.lending.domain.AssessmentStatus;
import io.github.finbridge.lending.domain.RiskAssessment;
import java.sql.ResultSet;
import java.time.Instant;
import java.sql.Array;
import java.sql.Connection;
import java.util.Arrays;
import java.sql.Timestamp;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiskAssessmentRepository implements RiskAssessmentRepository {
    private static final TypeReference<List<RiskAssessment.ProviderExecutionReference>> REFERENCES =
            new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcRiskAssessmentRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper.copy();
    }

    @Override
    public RiskAssessment save(RiskAssessment assessment) {
        String sql = "INSERT INTO risk_assessments (assessment_id, application_id, tenant_id, status, "
                + "credit_score, blacklist_hit, fraud_signal, decision, reason_codes, "
                + "provider_execution_references, policy_version, created_at, completed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            jdbc.execute((ConnectionCallback<Void>) connection -> insert(connection, sql, assessment));
        } catch (DataAccessException failure) {
            throw new LendingCoreException("Could not persist risk assessment", failure);
        }
        return assessment;
    }

    @Override
    public RiskAssessment completeAndRecord(RiskAssessment assessment,
            AssessmentDecision decision, UUID attemptId,
            long expectedApplicationVersion, long expectedWorkflowVersion,
            Instant occurredAt) {
        return jdbc.execute((ConnectionCallback<RiskAssessment>) connection -> {
            connection.setAutoCommit(false);
            try {
                updateAssessment(connection, assessment, decision, occurredAt);
                updateApplication(connection, assessment, decision, expectedApplicationVersion,
                        occurredAt);
                updateWorkflow(connection, assessment, attemptId, expectedWorkflowVersion, occurredAt);
                connection.commit();
                return assessment;
            } catch (Exception failure) {
                rollback(connection, failure);
                throw new LendingCoreException("Could not atomically complete risk assessment", failure);
            }
        });
    }

    @Override
    public RiskAssessmentClaim claimAssessment(UUID tenantId, UUID applicationId, UUID assessmentId,
            UUID workflowId, UUID attemptId, long workflowVersion,
            List<RiskAssessment.ProviderExecutionReference> providers, Instant now) {
        return jdbc.execute((ConnectionCallback<RiskAssessmentClaim>) connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT * FROM risk_assessments WHERE tenant_id = ? AND application_id = ? "
                            + "AND status = 'COMPLETED' ORDER BY created_at DESC LIMIT 1")) {
                query.setObject(1, tenantId);
                query.setObject(2, applicationId);
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) {
                        return new RiskAssessmentClaim(mapAssessment(result, 0), true);
                    }
                }
            }
            verifyActiveClaim(connection, tenantId, applicationId, assessmentId, workflowId,
                    attemptId, workflowVersion);
            RiskAssessment pending = loadPending(connection, tenantId, applicationId, assessmentId);
            updatePendingReferences(connection, pending, providers);
            return new RiskAssessmentClaim(pending, false);
        });
    }

    private void verifyActiveClaim(Connection connection, UUID tenantId, UUID applicationId,
            UUID assessmentId, UUID workflowId, UUID attemptId, long workflowVersion)
            throws SQLException {
        String sql = "SELECT 1 FROM origination_workflows WHERE tenant_id = ? AND application_id = ? "
                + "AND workflow_id = ? AND state = 'RISK_RUNNING' AND active_risk_attempt_id = ? "
                + "AND active_assessment_id = ? AND version = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, applicationId);
            statement.setObject(3, workflowId);
            statement.setObject(4, attemptId);
            statement.setObject(5, assessmentId);
            statement.setLong(6, workflowVersion);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new LendingCoreException("Risk assessment claim was superseded");
                }
            }
        }
    }

    private RiskAssessment loadPending(Connection connection, UUID tenantId, UUID applicationId,
            UUID assessmentId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT * FROM risk_assessments WHERE tenant_id = ? AND application_id = ? "
                        + "AND assessment_id = ? AND status = 'PENDING' FOR UPDATE")) {
            select.setObject(1, tenantId);
            select.setObject(2, applicationId);
            select.setObject(3, assessmentId);
            try (ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    return mapAssessment(row, 0);
                }
            }
        }
        throw new LendingCoreException("Reserved pending risk assessment is missing");
    }

    private void updatePendingReferences(Connection connection, RiskAssessment pending,
            List<RiskAssessment.ProviderExecutionReference> providers) throws SQLException {
        String sql = "UPDATE risk_assessments SET provider_execution_references = ? "
                + "WHERE tenant_id = ? AND assessment_id = ? AND status = 'PENDING'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, jsonb(encode(providers)));
            statement.setObject(2, pending.tenantId());
            statement.setObject(3, pending.assessmentId());
            requireOne(statement.executeUpdate(), "Pending risk assessment changed concurrently");
        }
    }

    private Void insert(Connection connection, String sql, RiskAssessment assessment)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindInsert(statement, connection, assessment);
            statement.executeUpdate();
        }
        return null;
    }

    private void bindInsert(PreparedStatement statement, Connection connection,
                            RiskAssessment assessment) throws SQLException {
        statement.setObject(1, assessment.assessmentId());
        statement.setObject(2, assessment.applicationId());
        statement.setObject(3, assessment.tenantId());
        statement.setString(4, assessment.status().name());
        statement.setObject(5, assessment.creditScore());
        statement.setObject(6, assessment.blacklistHit());
        statement.setString(7, assessment.fraudSignal());
        statement.setString(8, enumName(assessment.decision()));
        statement.setArray(9, connection.createArrayOf("text",
                assessment.reasonCodes().toArray(String[]::new)));
        statement.setObject(10, jsonb(encode(assessment.providers())));
        statement.setString(11, assessment.policyVersion());
        statement.setTimestamp(12, Timestamp.from(assessment.createdAt()));
        statement.setTimestamp(13, assessment.completedAt() == null ? null
                : Timestamp.from(assessment.completedAt()));
    }

    private void updateAssessment(Connection connection, RiskAssessment assessment,
            AssessmentDecision decision, Instant now)
            throws SQLException {
        String sql = "UPDATE risk_assessments SET status = 'COMPLETED', credit_score = ?, "
                + "blacklist_hit = ?, fraud_signal = ?, decision = ?, reason_codes = ?, "
                + "provider_execution_references = ?, policy_version = ?, completed_at = ? "
                + "WHERE assessment_id = ? AND tenant_id = ? AND status = 'PENDING'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, assessment.creditScore());
            statement.setObject(2, assessment.blacklistHit());
            statement.setString(3, assessment.fraudSignal());
            statement.setString(4, decision.name());
            statement.setArray(5, connection.createArrayOf("text",
                    assessment.reasonCodes().toArray(String[]::new)));
            statement.setObject(6, jsonb(encode(assessment.providers())));
            statement.setString(7, assessment.policyVersion());
            statement.setTimestamp(8, Timestamp.from(now));
            statement.setObject(9, assessment.assessmentId());
            statement.setObject(10, assessment.tenantId());
            requireOne(statement.executeUpdate(), "Risk assessment was already completed");
        }
    }

    private void updateWorkflow(Connection connection, RiskAssessment assessment, UUID attemptId,
            long expectedWorkflowVersion, Instant now) throws SQLException {
        String workflowSql = "UPDATE origination_workflows SET state = 'RISK_COMPLETED', "
                + "active_risk_attempt_id = NULL, active_assessment_id = NULL, "
                + "completed_assessment_id = ?, version = version + 1, "
                + "updated_at = ? WHERE tenant_id = ? AND application_id = ? AND version = ? "
                + "AND active_risk_attempt_id = ? AND state = 'RISK_RUNNING'";
        try (PreparedStatement statement = connection.prepareStatement(workflowSql)) {
            statement.setObject(1, assessment.assessmentId());
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, assessment.tenantId());
            statement.setObject(4, assessment.applicationId());
            statement.setLong(5, expectedWorkflowVersion);
            statement.setObject(6, attemptId);
            requireOne(statement.executeUpdate(), "Risk workflow attempt was superseded");
        }
        String stepSql = "UPDATE origination_workflow_steps SET state = 'COMPLETED', completed_at = ?, "
                + "assessment_id = ? WHERE attempt_id = ? AND state = 'RUNNING'";
        try (PreparedStatement statement = connection.prepareStatement(stepSql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, assessment.assessmentId());
            statement.setObject(3, attemptId);
            requireOne(statement.executeUpdate(), "Risk workflow step was already closed");
        }
        if (assessment.decision() == AssessmentDecision.APPROVE) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE origination_workflows SET state = 'PAYMENT_PENDING', version = version + 1, "
                            + "updated_at = ? WHERE tenant_id = ? AND application_id = ? "
                            + "AND state = 'RISK_COMPLETED' AND completed_assessment_id = ?")) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setObject(2, assessment.tenantId());
                statement.setObject(3, assessment.applicationId());
                statement.setObject(4, assessment.assessmentId());
                requireOne(statement.executeUpdate(), "Approved workflow could not advance to payment pending");
            }
        }
    }

    private static void requireOne(int rows, String message) {
        if (rows != 1) {
            throw new LendingCoreException(message);
        }
    }

    static void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    @Override
    public Optional<RiskAssessment> findLatest(UUID tenantId, UUID applicationId) {
        return jdbc.query("SELECT * FROM risk_assessments WHERE tenant_id = ? AND application_id = ? "
                        + "ORDER BY created_at DESC LIMIT 1", this::mapAssessment,
                tenantId, applicationId).stream().findFirst();
    }

    @Override
    public Optional<RiskAssessment> findById(UUID tenantId, UUID assessmentId) {
        return jdbc.query("SELECT * FROM risk_assessments WHERE tenant_id = ? AND assessment_id = ?",
                this::mapAssessment, tenantId, assessmentId).stream().findFirst();
    }

    private void updateApplication(Connection connection, RiskAssessment assessment,
            AssessmentDecision decision,
            long expectedApplicationVersion, Instant now) throws SQLException {
        var status = switch (decision) {
            case APPROVE -> io.github.finbridge.lending.domain.ApplicationStatus.APPROVED;
            case REJECT -> io.github.finbridge.lending.domain.ApplicationStatus.REJECTED;
            case REVIEW -> io.github.finbridge.lending.domain.ApplicationStatus.MANUAL_REVIEW;
        };
        String sql = "UPDATE loan_applications SET status = ?, updated_at = ?, version = version + 1 "
                + "WHERE application_id = ? AND tenant_id = ? AND version = ? AND status = 'RISK_EVALUATING'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, assessment.applicationId());
            statement.setObject(4, assessment.tenantId());
            statement.setLong(5, expectedApplicationVersion);
            requireOne(statement.executeUpdate(), "Application status/version changed during risk evaluation");
        }
    }

    private RiskAssessment mapAssessment(ResultSet row, int ignoredRowNumber) throws SQLException {
        Array reasonArray = row.getArray("reason_codes");
        List<String> reasons;
        try {
            reasons = Arrays.asList((String[]) reasonArray.getArray());
        } finally {
            reasonArray.free();
        }
        return new RiskAssessment(row.getObject("assessment_id", UUID.class),
                row.getObject("application_id", UUID.class), row.getObject("tenant_id", UUID.class),
                AssessmentStatus.valueOf(row.getString("status")),
                (Integer) row.getObject("credit_score"), (Boolean) row.getObject("blacklist_hit"),
                row.getString("fraud_signal"), enumDecision(row.getString("decision")),
                reasons,
                decode(row.getString("provider_execution_references"), REFERENCES),
                row.getString("policy_version"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("completed_at") == null ? null
                        : row.getTimestamp("completed_at").toInstant());
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new LendingCoreException("Could not encode risk assessment values", failure);
        }
    }

    static PGobject jsonb(String value) {
        PGobject result = new PGobject();
        result.setType("jsonb");
        try {
            new ObjectMapper().readTree(value);
            result.setValue(value);
        } catch (JsonProcessingException | SQLException failure) {
            throw new LendingCoreException("Could not create JSONB parameter", failure);
        }
        return result;
    }

    private <T> T decode(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException failure) {
            throw new LendingCoreException("Could not decode risk assessment values", failure);
        }
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static AssessmentDecision enumDecision(String value) {
        return value == null ? null : AssessmentDecision.valueOf(value);
    }
}
