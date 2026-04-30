package com.enthusia.enthusiacurrency.analytics;

public record CurrencyAnalyticsTotals(
        long deposited,
        long withdrawn,
        long lastActivityAt
) {
}
