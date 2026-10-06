package com.danielrak.banking.api;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of {@code POST /accounts/{accountId}/transactions}. Currency and direction are bound as
 * strings so a bad value fails validation with its own code, not Jackson parsing (design.md §2).
 * Each field's constraint maps to that field's §3 code, including when the field is missing. The custom
 * constraints are invisible to springdoc, so {@code @Schema} restates them; {@code OpenApiIT} checks the
 * enums against {@code Currency} and {@code Direction}.
 */
public record CreateTransactionRequest(
        @Schema(requiredMode = REQUIRED, example = "10.50",
                description = "A JSON number greater than 0, with at most 2 decimals")
        @ValidAmount BigDecimal amount,
        @Schema(requiredMode = REQUIRED, allowableValues = {"EUR", "SEK", "GBP", "USD"})
        @SupportedCurrency String currency,
        @Schema(requiredMode = REQUIRED, allowableValues = {"IN", "OUT"})
        @SupportedDirection String direction,
        @Schema(requiredMode = REQUIRED)
        @DescriptionPresent @Size(max = 255) @FreeText String description) {
}
