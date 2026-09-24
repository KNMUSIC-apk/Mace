package com.example.macelimiter;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MaceLimiter extends JavaPlugin {

    private static MaceLimiter instance;

    private DataManager dataManager;
    private NamespacedKey maceKey;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        this.maceKey = new NamespacedKey(this, "mace_uuid");

        this.dataManager = new DataManager(this);
        this.dataManager.load();

        getServer().getPluginManager().registerEvents(new MaceListener(this), this);

        CommandHandler handler = new CommandHandler(this);
        for (String name : new String[]{"macelimit", "macereset", "macesync", "mace"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(handler);
                cmd.setTabCompleter(handler);
            }
        }

        // ============================================================
        // STARTUP SCAN — Auto-adopt Mace có sẵn trên server
        // Chạy sau 40 ticks (2s) để chắc chắn world + player đã load xong.
        // removeFake = false → KHÔNG xóa UUID (tránh xóa nhầm Mace
        // trong chunk chưa load).
        // ============================================================
        getServer().getScheduler().runTaskLater(this, () -> {
            DataManager.SyncResult r = dataManager.syncWithServer(false);
            if (r.adopted > 0) {
                getLogger().info("[Startup Scan] Đã auto-adopt " + r.adopted
                        + " Mace có sẵn. Tổng hiện tại: "
                        + dataManager.getCount() + "/" + getMaxMaces());
            }
        }, 40L);

        getLogger().info("MaceLimiter đã bật. Hiện có "
                + dataManager.getCount() + "/" + getMaxMaces() + " Mace.");
    }

    @Override
    public void onDisable() {
        if (dataManager != null) {
            dataManager.save();
        }
        getLogger().info("MaceLimiter đã tắt.");
    }

    // ============================================================
    // Getters & Helpers
    // ============================================================

    public static MaceLimiter getInstance() { return instance; }
    public DataManager getDataManager() { return dataManager; }
    public NamespacedKey getMaceKey() { return maceKey; }

    public int getMaxMaces() {
        return Math.max(0, getConfig().getInt("max-maces", 8));
    }

    public String getMessage(String key, String... placeholders) {
        String raw = getConfig().getString("messages." + key,
                "&c[Missing message: " + key + "]");
        String msg = ChatColor.translateAlternateColorCodes('&', raw);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            msg = msg.replace(placeholders[i], placeholders[i + 1]);
        }
        return msg;
    }

    public void reloadPluginConfig() {
        reloadConfig();
    }
}
