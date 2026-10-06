package com.danielrak.banking.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;

/**
 * Body of {@code POST /accounts}. Currencies are bound as strings so a bad value fails validation
 * with {@code INVALID_CURRENCY}, not Jackson parsing (design.md §2). springdoc can't see
 * {@code @SupportedCurrency}, so {@code @ArraySchema} restates its values; {@code OpenApiIT} checks them.
 */
public record CreateAccountRequest(
        @Schema(maxLength = 64)
        @NotBlank @MaxCodePoints(64) @FreeText String customerId,
        @NotNull @Pattern(regexp = "[A-Z]{2}") String country,
        @ArraySchema(schema = @Schema(allowableValues = {"EUR", "SEK", "GBP", "USD"}), uniqueItems = true)
        @NotEmpty @UniqueElements List<@SupportedCurrency String> currencies) {
}
