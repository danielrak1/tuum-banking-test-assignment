package com.danielrak.banking.api;

import java.beans.PropertyEditorSupport;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Binds path IDs as canonical UUIDs only: 36 characters, 8-4-4-4-12 hex, either case (design.md §2).
 * {@link UUID#fromString} is lenient: it pads short groups, so {@code 1-2-3-4-5} would become a
 * well-formed but unknown ID (404) instead of a malformed one (400).
 *
 * <p>This is a custom editor, not a {@code Converter}: when a converter fails, Spring falls back to
 * its default (lenient) {@code UUIDEditor}, whereas a custom editor takes precedence and its failure
 * surfaces as a type mismatch, which {@link ApiExceptionHandler} maps to 400.
 */
@ControllerAdvice
class StrictUuidBinding {

    private static final Pattern CANONICAL =
            Pattern.compile("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");

    @InitBinder
    void registerStrictUuidEditor(WebDataBinder binder) {
        binder.registerCustomEditor(UUID.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                if (!CANONICAL.matcher(text).matches()) {
                    throw new IllegalArgumentException("Not a canonical UUID: " + text);
                }
                setValue(UUID.fromString(text));
            }
        });
    }
}
