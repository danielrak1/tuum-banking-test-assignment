package com.danielrak.banking.persistence;

/** Whether an account exists, and whether it holds a balance in a given currency. */
public record Existence(boolean account, boolean balance) {
}
