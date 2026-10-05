package com.danielrak.banking.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value is one of the supported currency codes, case-sensitive. Unlike most constraints, null is
 * invalid too: design.md §3 maps a missing currency to {@code INVALID_CURRENCY}, which is the code
 * this constraint produces.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SupportedCurrencyValidator.class)
public @interface SupportedCurrency {

    String message() default "must be one of EUR, SEK, GBP, USD";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
