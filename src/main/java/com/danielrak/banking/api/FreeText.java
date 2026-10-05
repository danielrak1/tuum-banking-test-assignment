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
 * A free-text field (design.md §2): single-line, and storable as written. It rejects:
 * <ul>
 *   <li>control characters (U+0000–U+001F, U+007F–U+009F), which include tab, newline and NEL.
 *       Postgres can't store NUL in {@code varchar} or {@code jsonb}, so it would fail as a 500;</li>
 *   <li>the line and paragraph separators U+2028 and U+2029;</li>
 *   <li>unpaired UTF-16 surrogates, which the JDBC driver would silently store as {@code ?}.</li>
 * </ul>
 * Null is valid; combine with {@code @NotBlank} or {@code @DescriptionPresent}.
 */
@Pattern(regexp = "[^\\p{Cc}\\p{Cs}\\x{2028}\\x{2029}]*")
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface FreeText {

    String message() default "must be a single line without control characters or unpaired surrogates";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
