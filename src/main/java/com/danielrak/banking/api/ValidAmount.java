package com.danielrak.banking.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A transaction amount (ADR-0001): present, > 0, at most 17 integer digits and 2 decimals, with
 * trailing zeros not counted ({@code 10.500} is 10.50). One constraint, so every failure, missing
 * included, maps to {@code INVALID_AMOUNT} (design.md §3).
 */
@Constraint(validatedBy = ValidAmountValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAmount {

    String message() default "must be greater than 0, with at most 17 integer digits and 2 decimals";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
