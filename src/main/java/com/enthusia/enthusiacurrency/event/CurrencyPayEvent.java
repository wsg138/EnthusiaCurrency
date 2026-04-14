package com.enthusia.enthusiacurrency.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class CurrencyPayEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID senderId;
    private final UUID targetId;
    private final long amount;

    public CurrencyPayEvent(UUID senderId, UUID targetId, long amount) {
        this.senderId = senderId;
        this.targetId = targetId;
        this.amount = amount;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public long getAmount() {
        return amount;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
