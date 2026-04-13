package com.enthusia.enthusiacurrency.event;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class BaltopTopEnterEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final int rank;
    private final double balance;

    public BaltopTopEnterEvent(UUID playerId, int rank, double balance) {
        this.playerId = playerId;
        this.rank = rank;
        this.balance = balance;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public OfflinePlayer getOfflinePlayer() {
        return Bukkit.getOfflinePlayer(playerId);
    }

    public int getRank() {
        return rank;
    }

    public double getBalance() {
        return balance;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
