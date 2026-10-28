package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.domain.LoanProduct;
import java.util.List;
import java.util.Optional;

public interface LoanProductRepository {
    Optional<LoanProduct> findActive(String productCode);

    Optional<LoanProduct> findVersion(String productCode, int version);

    List<LoanProduct> findAllActive();
}
