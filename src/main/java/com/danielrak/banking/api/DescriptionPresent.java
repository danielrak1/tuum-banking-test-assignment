package com.danielrak.banking.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A transaction description is present and not blank, where blank includes Unicode spaces such as
 * U+00A0 that {@code @NotBlank} lets through. Its own constraint, so it maps to
 * {@code DESCRIPTION_MISSING} while {@code @NotBlank} (e.g. on {@code customerId}) stays
 * {@code VALIDATION_FAILED} (design.md §3).
 */
@Constraint(validatedBy = DescriptionPresentValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DescriptionPresent {

    String message() default "must not be blank";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
