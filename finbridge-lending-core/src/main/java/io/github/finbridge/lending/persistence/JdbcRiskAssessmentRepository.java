package io.github.finbridge.lending.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.finbridge.lending.application.LendingCoreException;
import io.github.finbridge.lending.domain.AssessmentDecision;
import io.github.finbridge.lending.domain.AssessmentStatus;
import io.github.finbridge.lending.domain.RiskAssessment;
import java.sql.ResultSet;
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

    @Override
    public Optional<RiskAssessment> findLatest(UUID tenantId, UUID applicationId) {
        return jdbc.query("SELECT * FROM risk_assessments WHERE tenant_id = ? AND application_id = ? "
                        + "ORDER BY created_at DESC LIMIT 1", this::mapAssessment,
                tenantId, applicationId).stream().findFirst();
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
