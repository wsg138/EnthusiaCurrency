package com.enthusia.enthusiacurrency.storage;

import java.util.Map;
import java.util.UUID;

public interface BalanceRepository extends AutoCloseable {

    record StoredBalance(long amount, long revision) {
        private static final long MINIMUM_REVISION = 0L;

        public StoredBalance {
            if (revision < MINIMUM_REVISION) {
                throw new IllegalArgumentException("revision cannot be negative");
            }
        }
    }

    void initialize() throws Exception;

    Map<UUID, StoredBalance> loadAllBalances() throws Exception;

    void saveBalances(Map<UUID, StoredBalance> balances) throws Exception;

    @Override
    void close() throws Exception;
}
