# EnthusiaCurrency

Vault-backed token economy plugin with physical deposits, withdrawals, payments, balance leaderboards, and the supported EnthusiaStaff destructive-currency provider.

## Build and verification

```powershell
mvn -B -ntp verify
```

The standalone source is mirrored into `EnthusiaStaff` and must satisfy that aggregate repository's static-analysis policy without aggregate-only product-source edits, so component parity remains verifiable.

Vault is a required runtime dependency. If Vault is unavailable during startup, EnthusiaCurrency disables itself and returns from `onEnable` immediately instead of registering commands, listeners, placeholders, or scheduled runtime work after disablement.

## EnthusiaStaff moderation API

EnthusiaCurrency publishes `CurrencyModerationApi` version `1` through Bukkit's `ServicesManager`. The API has no player-facing command and does not grant moderation authority: EnthusiaStaff performs permission, case, and audit authorization before invoking it.

A destructive operation acquires an expiring operation-owned movement lease, snapshots bank/inventory/Ender Chest state, creates an exact source-ordered plan, and applies the plan only when the before checksum and persistent bank revision still match. Repeated apply calls are idempotent when the replacement state is already present. Concurrent or stale state is rejected instead of overwritten.

Restoration requires the same operation-owned lease and the checksum of the state being replaced. It restores the exact serialized item state and bank balance while advancing the persistent bank revision so old snapshots cannot become current again. Bank mutations complete successfully only after the SQLite writer flushes; ambiguous persistence failures return a quarantine-required result.

The provider fails closed when the player is offline, the lease is missing, the plan is stale/invalid, or exact denomination removal is impossible. EnthusiaStaff separately fails closed when this service is absent or its API version is incompatible.

Representative live destructive balances and production rows are deliberately outside this repository validation; the EnthusiaStaff package defers that acceptance to `ES-V03`.
