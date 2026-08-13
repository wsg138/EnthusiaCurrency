# EnthusiaCurrency agent instructions

These instructions apply to the entire `wsg138/EnthusiaCurrency` repository.

## Repository boundary

EnthusiaCurrency is the standalone currency provider for Enthusia SMP. Treat this repository as independently reviewable source; do not copy private or production data, credentials, balances, logs, or player rows into GitHub, tests, or AI conversations.

When work is performed for an EnthusiaStaff external package, also read and obey that package's current contract and the current `wsg138/EnthusiaStaff` worker/process rules. If the two repositories disagree about factual state, reconcile live GitHub before changing code.

## Development rules

- Java 21 is required.
- Build and validate with Maven. The baseline hosted gate is `mvn -B -ntp verify`.
- Preserve Paper lifecycle/thread-safety requirements and keep storage/network work bounded and off unsafe server-thread paths.
- Financial/destructive operations must be explicit, authorized, idempotent, auditable, restart-safe, and fail closed on uncertain state.
- Do not invent an EnthusiaStaff or provider API. Define/version the shared contract deliberately and keep standalone and aggregate copies synchronized when a package requires parity.
- Do not use real production balances or destructive production testing for development validation.
- Do not rewrite published history or silently alter durable storage semantics.

## Git and review

- Never push directly to `main` for package work.
- Use the temporary branch required by the selected package; ES-X02 uses `package/es-x02-currency-provider` unless the current package contract is changed legitimately.
- Use normal merge commits only. Do not squash, rebase shared branches, force-push, or enable auto-merge.
- Run all applicable repository checks and resolve every valid review/static-analysis finding before merge.
- For multi-repository packages, cross-reference the standalone and EnthusiaStaff PRs and require the package's final aggregate/standalone parity check before calling the package complete.

## Production authority

Repository changes and staging tests do not authorize production balance changes, production deployment, destructive live testing, or cutover. Those actions require the separate package/owner authorization documented by EnthusiaStaff.
