package com.enthusia.enthusiacurrency.skin;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SkinCache {

    private final EnthusiaCurrencyPlugin plugin;
    private final Map<UUID, ItemStack> cache = new ConcurrentHashMap<>();

    private File file;

    public SkinCache(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "skins.yml");
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            try {
                file.createNewFile();
            } catch (IOException ignored) {}
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        cache.clear();

        for (String key : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ItemStack head = config.getItemStack(key);
                if (head != null && head.getType() == Material.PLAYER_HEAD) {
                    cache.put(uuid, head);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        plugin.getLogger().info("[EnthusiaCurrency] Loaded " + cache.size() + " cached heads.");
    }

    public void save() {
        if (file == null) {
            file = new File(plugin.getDataFolder(), "skins.yml");
        }

        YamlConfiguration out = new YamlConfiguration();

        for (Map.Entry<UUID, ItemStack> entry : cache.entrySet()) {
            out.set(entry.getKey().toString(), entry.getValue());
        }

        try {
            out.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[EnthusiaCurrency] Failed to save skins.yml: " + e.getMessage());
        }
    }

    public void cacheFromOnline(Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(player);
        head.setItemMeta(meta);

        cache.put(player.getUniqueId(), head);
    }

    public ItemStack createHead(UUID uuid, String displayName) {
        ItemStack head = cache.get(uuid);

        if (head != null && head.getType() == Material.PLAYER_HEAD) {
            head = head.clone();
        } else {
            head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            meta.setOwningPlayer(offline);
            head.setItemMeta(meta);
        }

        if (displayName != null) {
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setDisplayName(displayName);
            head.setItemMeta(meta);
        }

        return head;
    }
}
