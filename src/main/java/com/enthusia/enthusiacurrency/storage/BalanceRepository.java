package com.enthusia.enthusiacurrency.storage;

import java.util.Map;
import java.util.UUID;

public interface BalanceRepository extends AutoCloseable {

    void initialize() throws Exception;

    Map<UUID, Long> loadAllBalances() throws Exception;

    void saveBalances(Map<UUID, Long> balances) throws Exception;

    @Override
    void close() throws Exception;
}
