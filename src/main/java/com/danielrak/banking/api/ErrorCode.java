package com.danielrak.banking.api;

/**
 * Machine-readable error codes (design.md §3). The field-validation codes are declared in priority
 * order, currency > direction > amount > description > other, so when several fields fail the
 * top-level {@code code} is the lowest ordinal among them.
 */
public enum ErrorCode {
    INVALID_CURRENCY,
    INVALID_DIRECTION,
    INVALID_AMOUNT,
    DESCRIPTION_MISSING,
    VALIDATION_FAILED,
    INSUFFICIENT_FUNDS,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_MISSING,
    INVALID_ACCOUNT,
    // Protocol errors (design.md §3): from the HTTP layer, coded by status.
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    UNSUPPORTED_MEDIA_TYPE,
    BAD_REQUEST,
    INTERNAL_ERROR
}
