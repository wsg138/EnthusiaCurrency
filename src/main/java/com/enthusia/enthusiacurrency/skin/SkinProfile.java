package com.enthusia.enthusiacurrency.skin;

import java.util.UUID;

public record SkinProfile(
        UUID uuid,
        String textureValue,
        String textureSignature,
        long updatedAt
) {
}
