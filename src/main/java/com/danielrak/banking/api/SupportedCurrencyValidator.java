package com.danielrak.banking.api;

import com.danielrak.banking.domain.Currency;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;

public class SupportedCurrencyValidator implements ConstraintValidator<SupportedCurrency, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value != null && Arrays.stream(Currency.values()).anyMatch(c -> c.name().equals(value));
    }
}
