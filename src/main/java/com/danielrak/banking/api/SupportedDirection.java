package com.danielrak.banking.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value is {@code IN} or {@code OUT}, case-sensitive. Null is invalid too: design.md §3 maps a
 * missing direction to {@code INVALID_DIRECTION}, which is the code this constraint produces.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SupportedDirectionValidator.class)
public @interface SupportedDirection {

    String message() default "must be IN or OUT";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
