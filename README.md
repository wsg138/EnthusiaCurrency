# EnthusiaCurrency

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/1c66db867f544c83a793edac09a3dee6)](https://app.codacy.com/gh/wsg138/EnthusiaCurrency/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

Vault-backed token economy plugin with physical deposits, withdrawals, payments, and balance leaderboards.

## Build

```powershell
mvn -q -DskipTests package
```

## Testing and coverage

Run the complete local verification, including unit tests, SQLite restart integration tests, JaCoCo reports, threshold enforcement, and packaging:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

On systems where the Windows wrapper cannot locate PowerShell, use the equivalent Maven command:

```powershell
mvn --batch-mode --no-transfer-progress clean verify
```

Unit tests use `*Test.java`; integration tests use `*IT.java` and run during `verify`. The combined JaCoCo XML and HTML reports are written to `target/site/jacoco/`. Current enforced thresholds apply to the tested currency amount parser and SQLite balance repository: 80% line coverage and 70% branch coverage.

Mutation testing is intentionally limited to those critical deterministic/persistence classes and runs nightly on `main` or on demand:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -Pmutation-testing clean test pitest:mutationCoverage
```

Every push runs the GitHub Actions verification workflow and uploads test reports, JaCoCo output, and the packaged plugin. Codacy upload is enabled only when the repository secret `CODACY_PROJECT_TOKEN` is configured; without it, the build and coverage checks still run and the upload is skipped. The current tests cover integer amount validation and SQLite creation, upsert, schema migration, and restart persistence. Bukkit adapters, live inventory transactions, and async balance flushing still require focused follow-up tests.
