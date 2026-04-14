package com.enthusia.enthusiacurrency.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class CurrencyDepositEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final long amount;
    private final long newBalance;

    public CurrencyDepositEvent(UUID playerId, long amount, long newBalance) {
        this.playerId = playerId;
        this.amount = amount;
        this.newBalance = newBalance;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public long getAmount() {
        return amount;
    }

    public long getNewBalance() {
        return newBalance;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
