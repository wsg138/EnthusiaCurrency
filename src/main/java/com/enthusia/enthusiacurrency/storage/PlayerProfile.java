package com.enthusia.enthusiacurrency.storage;

import java.util.UUID;

public record PlayerProfile(
        UUID uuid,
        String username,
        String displayName,
        long firstSeenAt,
        long lastSeenAt,
        long updatedAt
) {
}
