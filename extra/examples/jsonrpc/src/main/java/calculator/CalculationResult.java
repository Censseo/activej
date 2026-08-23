package calculator;

/**
 * The result DTO of {@link CalculatorService} — the second half of the record round trip: a record
 * goes out as {@code params}, a record comes back as {@code result}, and neither needed a registered
 * codec.
 *
 * @param expression the calculation that was performed, e.g. {@code "2.0 + 3.0"}
 * @param value      its value
 */
public record CalculationResult(String expression, double value) {}
