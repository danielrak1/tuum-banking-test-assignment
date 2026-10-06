package com.danielrak.banking.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value has at most {@code value} Unicode code points, which is how Postgres counts {@code varchar(n)}
 * and JSON Schema counts {@code maxLength} (design.md §2). {@code @Size} counts UTF-16 units instead, so it
 * would reject 64 emoji in a 64-character field, although the column stores them. Null is valid.
 * Not in {@code ApiExceptionHandler.FIELD_CODES}, so a violation is {@code VALIDATION_FAILED}.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxCodePointsValidator.class)
public @interface MaxCodePoints {

    int value();

    String message() default "must be at most {value} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
