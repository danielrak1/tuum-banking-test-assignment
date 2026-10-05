package com.danielrak.banking.domain;

import java.math.BigDecimal;

/** One currency's balance on an account; {@code availableAmount} has scale 2 (ADR-0001). */
public record Balance(Currency currency, BigDecimal availableAmount) {
}
