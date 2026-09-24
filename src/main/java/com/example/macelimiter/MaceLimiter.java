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
        for (String name : new String[]{"macelimit", "macereset", "macesync", "mace", "macescan"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(handler);
                cmd.setTabCompleter(handler);
            }
        }

        // ============================================================
        // STARTUP SCAN — Auto-adopt Mace có sẵn trên server
        // Chạy sau 40 ticks (2s) để world + player load xong.
        // removeFake = false → KHÔNG xóa UUID (tránh xóa nhầm Mace
        // trong chunk chưa load).
        // ============================================================
        getServer().getScheduler().runTaskLater(this, () -> {
            DataManager.SyncResult r = dataManager.syncWithServer(false);
            getLogger().info("[Startup Scan] Quét xong. Adopt: " + r.adopted
                    + ", Orphan: " + r.orphans
                    + ", Tổng hiện tại: " + dataManager.getCount() + "/" + getMaxMaces());
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

    public boolean isDebug() {
        return getConfig().getBoolean("debug", false);
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

    /** In log debug nếu debug = true trong config.yml. */
    public void debug(String msg) {
        if (isDebug()) getLogger().info("[DEBUG] " + msg);
    }
}
