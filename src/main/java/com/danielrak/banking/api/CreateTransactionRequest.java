package com.danielrak.banking.api;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of {@code POST /accounts/{accountId}/transactions}. Currency and direction are bound as
 * strings so a bad value fails validation with its own code, not Jackson parsing (design.md §2).
 * Each field's constraint maps to that field's §3 code, including when the field is missing.
 */
public record CreateTransactionRequest(
        @ValidAmount BigDecimal amount,
        @SupportedCurrency String currency,
        @SupportedDirection String direction,
        @DescriptionPresent @Size(max = 255) @FreeText String description) {
}
