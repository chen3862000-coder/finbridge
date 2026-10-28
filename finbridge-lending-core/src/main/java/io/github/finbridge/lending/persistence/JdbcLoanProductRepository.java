package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.domain.LoanProduct;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLoanProductRepository implements LoanProductRepository {
    private final JdbcTemplate jdbc;

    public JdbcLoanProductRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<LoanProduct> findActive(String productCode) {
        return jdbc.query("SELECT product_code, product_version, currency, minimum_principal, "
                        + "maximum_principal, allowed_terms_months, annual_interest_rate_percent "
                        + "FROM loan_products WHERE product_code = ? AND active",
                this::mapProduct, productCode).stream().findFirst();
    }

    @Override
    public Optional<LoanProduct> findVersion(String productCode, int version) {
        return jdbc.query("SELECT product_code, product_version, currency, minimum_principal, "
                        + "maximum_principal, allowed_terms_months, annual_interest_rate_percent "
                        + "FROM loan_products WHERE product_code = ? AND product_version = ?",
                this::mapProduct, productCode, version).stream().findFirst();
    }

    @Override
    public List<LoanProduct> findAllActive() {
        return jdbc.query("SELECT product_code, product_version, currency, minimum_principal, "
                        + "maximum_principal, allowed_terms_months, annual_interest_rate_percent "
                        + "FROM loan_products WHERE active ORDER BY product_code",
                this::mapProduct);
    }

    private LoanProduct mapProduct(ResultSet row, int ignoredRowNumber) throws SQLException {
        Array termsArray = row.getArray("allowed_terms_months");
        if (termsArray == null) {
            throw new SQLException("Product is missing allowed terms");
        }
        Object[] terms;
        try {
            terms = (Object[]) termsArray.getArray();
        } finally {
            termsArray.free();
        }
        Set<Integer> allowedTerms = java.util.Arrays.stream(terms)
                .map(value -> ((Number) value).intValue())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new LoanProduct(row.getString("product_code"), row.getInt("product_version"),
                Currency.getInstance(row.getString("currency")), row.getBigDecimal("minimum_principal"),
                row.getBigDecimal("maximum_principal"), allowedTerms,
                row.getBigDecimal("annual_interest_rate_percent"));
    }
}
