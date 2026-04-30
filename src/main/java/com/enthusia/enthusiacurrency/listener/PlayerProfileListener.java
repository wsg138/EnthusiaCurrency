package com.enthusia.enthusiacurrency.listener;

import com.enthusia.enthusiacurrency.storage.PlayerProfileStorage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerProfileListener implements Listener {

    private final PlayerProfileStorage playerProfileStorage;

    public PlayerProfileListener(PlayerProfileStorage playerProfileStorage) {
        this.playerProfileStorage = playerProfileStorage;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        playerProfileStorage.recordOnlinePlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerProfileStorage.recordOnlinePlayer(event.getPlayer());
    }
}
