package com.danielrak.banking.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The code an endpoint returns when its path account ID is malformed (400) or unknown (404).
 * Each endpoint uses the PDF's own name for it (design.md §3 rule 5).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NotFoundCode {

    ErrorCode value();
}
