import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public final class RepaymentSpike {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal TWELVE = new BigDecimal("12");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private RepaymentSpike() {
    }

    public static void main(String[] args) {
        List<Installment> zeroRate = schedule(new BigDecimal("1000.00"), ZERO, 3);
        verify(zeroRate, new BigDecimal("1000.00"));
        require(zeroRate.stream().map(Installment::payment).toList()
                .equals(List.of(new BigDecimal("333.33"), new BigDecimal("333.33"),
                        new BigDecimal("333.34"))), "zero-rate residual adjustment");

        List<Installment> standard = schedule(new BigDecimal("10000.00"),
                new BigDecimal("12.00"), 12);
        verify(standard, new BigDecimal("10000.00"));
        require(standard.equals(schedule(new BigDecimal("10000.00"),
                new BigDecimal("12.00"), 12)), "deterministic schedule");

        require(dueDate(LocalDate.of(2025, 1, 31), 1).equals(LocalDate.of(2025, 2, 28)),
                "February month-end clamp");
        require(dueDate(LocalDate.of(2025, 1, 31), 2).equals(LocalDate.of(2025, 3, 31)),
                "month-end anchor must not drift");
        require(dueDate(LocalDate.of(2024, 1, 31), 1).equals(LocalDate.of(2024, 2, 29)),
                "leap-year February month-end clamp");

        System.out.println("PASS: zero-rate and amortizing schedules reconcile principal");
        System.out.println("PASS: deterministic output and anchored month-end due dates");
        System.out.println("12-month CNY 10,000 at 12%: payment=" + standard.getFirst().payment()
                + ", totalInterest=" + standard.stream().map(Installment::interest)
                        .reduce(ZERO, BigDecimal::add));
    }

    private static List<Installment> schedule(BigDecimal principal, BigDecimal annualPercent,
                                               int count) {
        require(principal.signum() > 0 && count > 0, "positive principal and term required");
        BigDecimal monthlyRate = annualPercent.divide(HUNDRED, MC).divide(TWELVE, MC);
        BigDecimal payment;
        if (monthlyRate.signum() == 0) {
            payment = principal.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        } else {
            BigDecimal factor = BigDecimal.ONE.add(monthlyRate, MC).pow(count, MC);
            payment = principal.multiply(monthlyRate, MC).multiply(factor, MC)
                    .divide(factor.subtract(BigDecimal.ONE, MC), MC)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        List<Installment> installments = new ArrayList<>();
        BigDecimal remaining = principal.setScale(2, RoundingMode.UNNECESSARY);
        for (int number = 1; number <= count; number++) {
            BigDecimal interest = remaining.multiply(monthlyRate, MC)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal principalPart = number == count
                    ? remaining
                    : payment.subtract(interest).setScale(2, RoundingMode.HALF_UP);
            require(principalPart.signum() >= 0, "negative principal installment");
            BigDecimal actualPayment = principalPart.add(interest).setScale(2, RoundingMode.UNNECESSARY);
            remaining = remaining.subtract(principalPart).setScale(2, RoundingMode.UNNECESSARY);
            require(remaining.signum() >= 0, "negative remaining principal");
            installments.add(new Installment(principalPart, interest, actualPayment, remaining));
        }
        return List.copyOf(installments);
    }

    private static LocalDate dueDate(LocalDate disbursedAt, int installmentNumber) {
        int anchorDay = disbursedAt.getDayOfMonth();
        YearMonth month = YearMonth.from(disbursedAt).plusMonths(installmentNumber);
        return month.atDay(Math.min(anchorDay, month.lengthOfMonth()));
    }

    private static void verify(List<Installment> installments, BigDecimal principal) {
        BigDecimal principalTotal = installments.stream().map(Installment::principal)
                .reduce(ZERO, BigDecimal::add);
        require(principalTotal.compareTo(principal) == 0, "principal does not reconcile");
        require(installments.getLast().remaining().signum() == 0, "ending principal is not zero");
        require(installments.stream().allMatch(row -> row.remaining().signum() >= 0),
                "negative remaining principal");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Installment(BigDecimal principal, BigDecimal interest, BigDecimal payment,
                               BigDecimal remaining) {
    }
}
