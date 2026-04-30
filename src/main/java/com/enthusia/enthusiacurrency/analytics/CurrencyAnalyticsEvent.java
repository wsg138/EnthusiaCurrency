package com.enthusia.enthusiacurrency.analytics;

import java.util.UUID;

public record CurrencyAnalyticsEvent(
        long occurredAt,
        UUID actorUuid,
        String actorName,
        UUID targetUuid,
        String targetName,
        CurrencyAnalyticsAction action,
        boolean success,
        long amount,
        long balanceAfter,
        String reason
) {
}
