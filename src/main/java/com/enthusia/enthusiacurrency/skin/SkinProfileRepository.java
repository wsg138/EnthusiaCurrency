package com.enthusia.enthusiacurrency.skin;

import java.util.Map;
import java.util.UUID;

interface SkinProfileRepository extends AutoCloseable {

    void initialize() throws Exception;

    Map<UUID, SkinProfile> loadAll() throws Exception;

    void saveAll(Map<UUID, SkinProfile> profiles) throws Exception;

    @Override
    void close() throws Exception;
}
