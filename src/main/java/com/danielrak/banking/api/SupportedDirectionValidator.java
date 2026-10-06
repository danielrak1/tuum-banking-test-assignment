package com.danielrak.banking.api;

import com.danielrak.banking.domain.Direction;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;

public class SupportedDirectionValidator implements ConstraintValidator<SupportedDirection, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value != null && Arrays.stream(Direction.values()).anyMatch(d -> d.name().equals(value));
    }
}
