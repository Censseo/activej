package calculator;

/**
 * The parameter DTO of {@link CalculatorService}. A plain {@code record} — {@code JsonCodecFactory}
 * derives a codec for any record with no registration at all, so there is nothing else to write.
 * <p>
 * Its component <b>names</b> are the JSON keys and its canonical constructor <b>order</b> is the
 * emitted member order: renaming or reordering a component is a wire-format change that no compiler
 * will catch.
 */
public record Operands(double a, double b) {}
