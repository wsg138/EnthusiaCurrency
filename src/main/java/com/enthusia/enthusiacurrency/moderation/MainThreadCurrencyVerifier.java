package com.enthusia.enthusiacurrency.moderation;

import com.enthusia.enthusiacurrency.api.moderation.CurrencyAccountSnapshot;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Schedules exact post-persistence account verification back onto the primary server thread. */
final class MainThreadCurrencyVerifier implements AutoCloseable {

    private final JavaPlugin plugin;
    private final CurrencyAccountCodec accounts;
    private final Set<CompletableFuture<CurrencyAccountSnapshot>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    MainThreadCurrencyVerifier(JavaPlugin plugin, CurrencyAccountCodec accounts) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.accounts = java.util.Objects.requireNonNull(accounts, "accounts");
    }

    CompletionStage<CurrencyAccountSnapshot> capture(Player player, UUID expectedPlayerId) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("currency moderation verifier is closed")
            );
        }
        if (Bukkit.isPrimaryThread()) {
            return captureImmediately(player, expectedPlayerId);
        }

        CompletableFuture<CurrencyAccountSnapshot> future = new CompletableFuture<>();
        pending.add(future);
        try {
            Bukkit.getScheduler().runTask(
                    plugin,
                    () -> completeCapture(future, player, expectedPlayerId)
            );
        } catch (RuntimeException exception) {
            pending.remove(future);
            future.completeExceptionally(exception);
        }
        return future;
    }

    private CompletionStage<CurrencyAccountSnapshot> captureImmediately(
            Player player,
            UUID expectedPlayerId
    ) {
        try {
            return CompletableFuture.completedFuture(captureNow(player, expectedPlayerId));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private void completeCapture(
            CompletableFuture<CurrencyAccountSnapshot> future,
            Player player,
            UUID expectedPlayerId
    ) {
        try {
            if (!future.isDone()) {
                future.complete(captureNow(player, expectedPlayerId));
            }
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        } finally {
            pending.remove(future);
        }
    }

    private CurrencyAccountSnapshot captureNow(Player player, UUID expectedPlayerId) {
        if (closed.get()) {
            throw new IllegalStateException("currency moderation verifier is closed");
        }
        if (player == null || !player.isOnline() || !player.getUniqueId().equals(expectedPlayerId)) {
            throw new IllegalStateException("player left before exact post-persistence verification");
        }
        return accounts.capture(player);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IllegalStateException failure = new IllegalStateException(
                "currency moderation verifier closed before exact verification"
        );
        for (CompletableFuture<CurrencyAccountSnapshot> future : pending) {
            future.completeExceptionally(failure);
        }
        pending.clear();
    }
}
