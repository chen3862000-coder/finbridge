package io.github.finbridge.lending.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

public final class MonthlyDueDatePolicy {
    private MonthlyDueDatePolicy() {
    }

    public static LocalDate dueDate(LocalDate disbursementDate, int installmentNumber) {
        Objects.requireNonNull(disbursementDate, "disbursementDate");
        if (installmentNumber < 1) {
            throw new IllegalArgumentException("installmentNumber must be positive");
        }
        int anchorDay = disbursementDate.getDayOfMonth();
        YearMonth dueMonth = YearMonth.from(disbursementDate).plusMonths(installmentNumber);
        return dueMonth.atDay(Math.min(anchorDay, dueMonth.lengthOfMonth()));
    }
}
