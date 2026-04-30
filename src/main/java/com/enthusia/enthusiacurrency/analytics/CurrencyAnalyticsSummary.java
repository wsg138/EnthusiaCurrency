package com.enthusia.enthusiacurrency.analytics;

public record CurrencyAnalyticsSummary(
        long deposited,
        long withdrawn
) {

    public long totalMoved() {
        return deposited + withdrawn;
    }
}
