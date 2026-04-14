package com.enthusia.enthusiacurrency.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class CurrencyPaySelfAttemptEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;

    public CurrencyPaySelfAttemptEvent(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
