# EnthusiaCurrency testing guide

This is the worker/reviewer entry point for EnthusiaCurrency repository-local testing.

The currency repository owns its handwritten financial, moderation, persistence and command/service regression tests. Sentinel Sim adds built-artifact/runtime compatibility evidence; it is not where the normal currency tests live.

Never use real production balances, player rows, credentials, live destructive moderation operations, or production inventories in tests.

## Test-hardening additions

This campaign adds direct tests for previously under-protected deterministic contracts:

- `CurrencyAmountParserTest`
  - positive whole amounts and whitespace;
  - whole-only decimal equivalence (`5.0`) versus true fractions;
  - decimal-mode round-down semantics;
  - zero/negative/malformed/overflow fail-closed behavior.
- `CurrencyModerationContractTest`
  - defensive copies/content equality for exact account snapshots;
  - negative/mismatched/overflow/checksum validation;
  - exact removal-plan player/debit/final-total/source-order invariants;
  - defensive replacement item arrays;
  - removal-result observable-state consistency and committed-success semantics;
  - restore-result success semantics.
- `ItemBalanceSnapshotTest`
  - empty dirty-state defaults;
  - mark-dirty/mark-scanning transitions preserving balances and identity.
- `PluginSurfaceContractTest`
  - reviewed plugin identity, Vault hard dependency, PlaceholderAPI/Plan soft dependencies, six-command surface and admin permission defaults.
- `FullFeatureCoverageContractTest`
  - self-policing inventory for the deterministic financial/moderation/persistence families that currently have concrete tests.

The coverage inventory is not a substitute for behavior tests. Do not satisfy it with empty or unrelated filenames.

## Existing coverage retained

The repository already includes behavioral tests for:

- `CurrencyModerationStateEvaluator`;
- `CurrencyRemovalAllocator`;
- `MovementLockRegistry`;
- `CurrencyInventoryWithdrawal`;
- `SqliteBalanceRepository`.

## Commands

Focused tests:

```bash
mvn -B -ntp -Dtest=CurrencyAmountParserTest test
mvn -B -ntp -Dtest=CurrencyModerationContractTest test
mvn -B -ntp -Dtest=ItemBalanceSnapshotTest test
mvn -B -ntp -Dtest=PluginSurfaceContractTest,FullFeatureCoverageContractTest test
```

All repository tests:

```bash
mvn -B -ntp test
```

Canonical validation from `AGENTS.md`:

```bash
mvn -B -ntp verify
```

The existing `.github/workflows/ci.yml` runs the canonical Maven verification on pull requests. The separate Sentinel artifact workflow validates/stages the exact built artifact. Artifact publication alone is not a substitute for repository test evidence.

## Results

Maven Surefire writes JUnit XML/text reports under:

- `target/surefire-reports/`

Build/package output is under:

- `target/`

Final review evidence must belong to the exact PR head; a green older SHA is stale after another commit.

## Failure triage

### Amount parsing

Do not loosen positive-only, integer/fraction or overflow behavior just to accept an input. Confirm the command/product contract first because parser behavior can directly affect financial operations.

### Moderation snapshot/plan/result

Treat checksum, exact-total, defensive-copy, stale-state and source-order failures as financial safety issues. Do not coerce malformed state into a successful result.

### Persistence

For SQLite/balance failures, distinguish repository/data-layer regressions from live Paper/Vault/provider behavior. Keep tests temporary and synthetic.

### CI infrastructure

A GitHub Actions job with no executed steps is neither a pass nor a Maven/JUnit failure. Preserve exact-head evidence and do not manufacture commits solely for reruns.

## Known gaps / next priorities

Important areas still needing deeper automated coverage include:

- command routing/permissions for `/balance`, `/deposit`, `/withdraw`, `/pay`, `/baltop`, and `/currency`;
- `CurrencyService` financial transaction/idempotency/error paths;
- `CurrencyRemovalPlanner`, account codec, durability verification and moderation service orchestration;
- offline payment notifications and player-profile SQLite persistence;
- analytics aggregation/storage;
- leaderboard export/R2 upload failure behavior;
- PlaceholderAPI/Plan provider-present and provider-missing modes;
- item-balance tracker scans and GUI behavior under a realistic Paper inventory harness;
- startup/reload/shutdown/provider registration;
- real Vault/Paper scheduler and inventory semantics.

The open legacy PR #2 currently owns `BaltopTracker`, `BaltopCommand`, `TokenEconomy`, `SkinCache`, `PlayerProfile`, and `PlayerProfileStorage`. This test-hardening branch intentionally does not modify those product paths.

## Review checklist

1. Tests must fail for the intended financial/moderation regression, not implementation trivia.
2. Positive/zero/negative/overflow/stale cases should be explicit where relevant.
3. Mutable byte arrays/collections crossing moderation boundaries must remain defensively protected.
4. No production balance, credential, player row, inventory or destructive live action may be used.
5. New financial behavior needs idempotency/failure/restart tests at the owning layer.
6. Do not weaken checks or analyzers merely to get green CI.
7. Reconcile open PR changed paths before modifying product tests near active work.
8. Final CI must match the exact reviewed head.
9. Use Sentinel/live Paper only for boundaries that cannot be proved deterministically in-process.
