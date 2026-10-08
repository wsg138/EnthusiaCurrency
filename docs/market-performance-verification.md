# Market and Currency performance investigation

## Spec and provenance

Investigate repeated balance reads/sales and auction settlement using isolated synthetic players. Historical watchdog traces are evidence of an older event, not proof of current server latency. No production balances, player rows, deployment or reset work are included.

BadgersMC/EnthusiaCurrency main is 3d32bb50ae08024c100b75f099a77309ede370e9 (version 1.3.0, no ItemBalanceTracker). The maintained 1.4.4 tracker source is wsg138/EnthusiaCurrency main 3a5a2f57ed67a75b86ac3099fbc3d65e33294642; its AGENTS.md declares the standalone provider boundary. Network main 559bfabc pins Currency 9696501, which is also version 1.4.4 with this tracker. This review targets maintained provider source; no silent replacement of the older BadgersMC main or network pin is intended. The previously inspected startup reported Currency 1.4.4, but exact installed binary/source and current runtime timing are not verified here.

Requirements CUR-PERF-001/002 preserve cached read results, real inventory-change debounce, exact withdrawals and financial semantics. A package-private clock seam permits deterministic stale-cache proof without sleeping or private-field fixture writes. It does not change the public constructor's production clock. No local EARS/state helpers exist; this is a manual SPEAR record.

## Prove

With only the package-private clock seam and test dependency added, the unchanged stale-read logic fails four of six initial cases, zero errors/skips. Actual TokenEconomy -> CurrencyService -> ItemBalanceTracker entry points issue 10,000 registrations and 9,999 cancellations for 10,000 reads; one read per tick prevents the 40-tick refresh from executing through tick 80. Synthetic cached values are preserved and real inventory-change debounce already passes. Baseline elapsed time was 2,296.781 ms, including mocked scheduler overhead; it is not server latency.

## Engine, architecture and refine

The stale-read path checks the actual pending task before calling markDirty. It does not use the dirty flag alone, which would prevent retry after a thrown scan. Real mutation events still cancel/reschedule through the unchanged markDirty path. The targeted fix belongs to the Currency infrastructure cache. Inventory scans and Bukkit scheduler calls stay on the existing server-thread path. No async economy calls, storage migration, balance changes or auction ownership changes are authorized by this investigation. Maven Java 21 and hosted checks remain required.

## Local refinement

Java 21 Maven clean verify passes all 23 tests, zero failures/errors/skips, including eight new read/deadline/retry/lifecycle/affordability cases. The same 10,000 stale reads now register one task and cancel none. Synthetic elapsed time was 974.459 ms; operation counts are the supported result, and the timing is not a production speedup claim. Existing withdrawal planning, storage, moderation, lock and removal-allocation tests also execute. Cached return values remain identical. Unmerged local test JAR target/enthusia-currency-1.4.4.jar SHA-256: 2aff71357d2616a231aff9459c7e910dbe3def03472a12acc9cedbab0a90672d. It was not uploaded. Hosted review and canonical/network reconciliation remain external gates.
