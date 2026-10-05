package com.danielrak.banking.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A free-text field: no control characters (U+0000–U+001F, U+007F), design.md §2. Postgres can't
 * store NUL in {@code varchar} or {@code jsonb}, so without this it would fail as a 500. Null is
 * valid; combine with {@code @NotBlank}.
 */
@Pattern(regexp = "\\P{Cntrl}*")
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface FreeText {

    String message() default "must not contain control characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
