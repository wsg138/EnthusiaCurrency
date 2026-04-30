package com.enthusia.enthusiacurrency.storage;

import java.util.Map;
import java.util.UUID;

public interface PlayerProfileRepository extends AutoCloseable {

    void initialize() throws Exception;

    Map<UUID, PlayerProfile> loadAllProfiles() throws Exception;

    void saveProfiles(Map<UUID, PlayerProfile> profiles) throws Exception;

    @Override
    void close() throws Exception;
}
