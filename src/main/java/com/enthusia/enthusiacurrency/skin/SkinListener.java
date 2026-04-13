package com.enthusia.enthusiacurrency.skin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class SkinListener implements Listener {

    private final SkinCache skinCache;

    public SkinListener(SkinCache skinCache) {
        this.skinCache = skinCache;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        skinCache.cacheFromOnline(event.getPlayer());
    }
}
