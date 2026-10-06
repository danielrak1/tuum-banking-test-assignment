package com.danielrak.banking.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

/**
 * Counts an amount's digits with trailing zeros stripped, so {@code 10.500} is 10.50 and {@code 1e2}
 * is 100 (ADR-0001). Hibernate's {@code @Digits} can't do this: it counts a {@code BigDecimal} as written.
 */
public class ValidAmountValidator implements ConstraintValidator<ValidAmount, BigDecimal> {

    /** {@code NUMERIC(19,2)}. */
    private static final int MAX_INTEGER_DIGITS = 17;
    private static final int MAX_DECIMALS = 2;

    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
        if (value == null || value.signum() <= 0) {
            return false;
        }
        // Integer digits = precision − scale, which stripping trailing zeros leaves unchanged. Checking it
        // first, in long, keeps an extreme exponent from overflowing int (123e2147483645) or from making
        // stripTrailingZeros throw (100e2147483647); either would otherwise surface as a 500.
        long integerDigits = (long) value.precision() - value.scale();
        if (integerDigits > MAX_INTEGER_DIGITS) {
            return false;
        }
        int decimals = Math.max(0, value.stripTrailingZeros().scale());   // 10.500 → 10.5 → 1; 1E+2 → 0
        return decimals <= MAX_DECIMALS;
    }
}
