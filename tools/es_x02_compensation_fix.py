from pathlib import Path

path = Path('src/main/java/com/enthusia/enthusiacurrency/moderation/CurrencyModerationService.java')
text = path.read_text(encoding='utf-8')
replacements = {
'''        } catch (RuntimeException exception) {
            restorePhysical(player, current);
            return completedRemoval(CurrencyRemovalResult.Status.FAILED_ROLLED_BACK, 0L, current.authoritativeTotal(), Optional.of(capture(player)), "physical mutation failed before bank commit");
        }
''': '''        } catch (RuntimeException exception) {
            return compensateRemoval(
                    player,
                    current,
                    "physical mutation failed before bank commit"
            );
        }
''',
'''        )) {
            restorePhysical(player, current);
            CurrencyAccountSnapshot rolledBack = capture(player);
            return completedRemoval(CurrencyRemovalResult.Status.FAILED_ROLLED_BACK, 0L, rolledBack.authoritativeTotal(), Optional.of(rolledBack), "bank revision changed during apply; physical state was restored");
        }
''': '''        )) {
            return compensateRemoval(
                    player,
                    current,
                    "bank revision changed during apply"
            );
        }
''',
'''        } catch (RuntimeException exception) {
            restorePhysical(player, current);
            return CompletableFuture.completedFuture(new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.FAILED_ROLLED_BACK,
                    Optional.of(capture(player)),
                    "physical restore failed before bank commit"
            ));
        }
''': '''        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(compensateRestore(
                    player,
                    current,
                    "physical restore failed before bank commit"
            ));
        }
''',
'''        )) {
            restorePhysical(player, current);
            return CompletableFuture.completedFuture(new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.FAILED_ROLLED_BACK,
                    Optional.of(capture(player)),
                    "bank revision changed during restore; physical state was rolled back"
            ));
        }
''': '''        )) {
            return CompletableFuture.completedFuture(compensateRestore(
                    player,
                    current,
                    "bank revision changed during restore"
            ));
        }
''',
'''    private static void restorePhysical(Player player, CurrencyAccountSnapshot snapshot) {
        try {
            player.getInventory().setContents(decode(snapshot.inventory()));
            player.getEnderChest().setContents(decode(snapshot.enderChest()));
        } catch (RuntimeException ignored) {
            // The caller returns quarantine/rollback status; overwriting a second failure would lose evidence.
        }
    }
''': '''    private CompletionStage<CurrencyRemovalResult> compensateRemoval(
            Player player,
            CurrencyAccountSnapshot before,
            String failureDetail
    ) {
        Optional<CurrencyAccountSnapshot> observed = restorePhysicalAndObserve(player, before);
        if (observed.isPresent() && sameAssets(observed.orElseThrow(), before)) {
            CurrencyAccountSnapshot rolledBack = observed.orElseThrow();
            return completedRemoval(
                    CurrencyRemovalResult.Status.FAILED_ROLLED_BACK,
                    0L,
                    rolledBack.authoritativeTotal(),
                    observed,
                    failureDetail + "; exact physical state was restored"
            );
        }
        long finalTotal = observed.map(CurrencyAccountSnapshot::authoritativeTotal)
                .orElse(before.authoritativeTotal());
        return completedRemoval(
                CurrencyRemovalResult.Status.QUARANTINE_REQUIRED,
                0L,
                finalTotal,
                observed,
                failureDetail + "; exact rollback could not be verified"
        );
    }

    private CurrencyRestoreResult compensateRestore(
            Player player,
            CurrencyAccountSnapshot before,
            String failureDetail
    ) {
        Optional<CurrencyAccountSnapshot> observed = restorePhysicalAndObserve(player, before);
        if (observed.isPresent() && sameAssets(observed.orElseThrow(), before)) {
            return new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.FAILED_ROLLED_BACK,
                    observed,
                    failureDetail + "; exact physical state was restored"
            );
        }
        return new CurrencyRestoreResult(
                CurrencyRestoreResult.Status.QUARANTINE_REQUIRED,
                observed,
                failureDetail + "; exact rollback could not be verified"
        );
    }

    private Optional<CurrencyAccountSnapshot> restorePhysicalAndObserve(
            Player player,
            CurrencyAccountSnapshot snapshot
    ) {
        try {
            player.getInventory().setContents(decode(snapshot.inventory()));
            player.getEnderChest().setContents(decode(snapshot.enderChest()));
        } catch (RuntimeException exception) {
            plugin.getLogger().severe(
                    "ES-X02 physical compensation failed: " + exception.getMessage()
            );
        }
        try {
            return Optional.of(capture(player));
        } catch (RuntimeException exception) {
            plugin.getLogger().severe(
                    "ES-X02 could not observe state after compensation: " + exception.getMessage()
            );
            return Optional.empty();
        }
    }
'''
}
for old, new in replacements.items():
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected exactly one match, got {count}: {old[:80]!r}')
    text = text.replace(old, new)
path.write_text(text, encoding='utf-8')
