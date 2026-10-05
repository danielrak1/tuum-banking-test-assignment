package com.danielrak.banking.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A transaction description is present and not blank. It's {@code @NotBlank} under its own name so
 * it maps to {@code DESCRIPTION_MISSING}, while a plain {@code @NotBlank} (e.g. on {@code customerId})
 * stays {@code VALIDATION_FAILED} (design.md §3).
 */
@NotBlank
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DescriptionPresent {

    String message() default "must not be blank";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
