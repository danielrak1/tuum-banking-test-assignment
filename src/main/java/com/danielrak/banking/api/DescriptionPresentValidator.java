package com.danielrak.banking.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DescriptionPresentValidator implements ConstraintValidator<DescriptionPresent, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value != null && !value.codePoints().allMatch(DescriptionPresentValidator::isBlank);
    }

    /** {@code isWhitespace} misses no-break spaces (U+00A0, U+2007, U+202F); {@code isSpaceChar} covers them. */
    private static boolean isBlank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
