package com.example.macelimiter;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MaceLimiter extends JavaPlugin {

    private static MaceLimiter instance;

    private DataManager dataManager;
    private NamespacedKey maceKey;

    // Cache config values (invalidate on reload)
    private volatile int cachedMaxMaces = 8;
    private volatile boolean cachedDebug = false;
    private volatile int cachedSaveDebounce = 40;
    private volatile long cachedStartupDelay = 40L;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        cacheConfigValues();

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

        // Startup scan (configurable)
        if (getConfig().getBoolean("scan-on-startup", true)) {
            long delay = cachedStartupDelay;
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!isEnabled()) return;
                DataManager.SyncResult r = dataManager.syncWithServer(false);
                getLogger().info("[Startup Scan] Quét xong. Adopt: " + r.adopted
                        + ", Orphan: " + r.orphans
                        + ", Tổng hiện tại: " + dataManager.getCount() + "/" + cachedMaxMaces);
            }, delay);
        }

        getLogger().info("MaceLimiter đã bật. Hiện có "
                + dataManager.getCount() + "/" + cachedMaxMaces + " Mace.");
    }

    @Override
    public void onDisable() {
        if (dataManager != null) {
            dataManager.flushAndSave(); // Flush pending + save ngay
        }
        getLogger().info("MaceLimiter đã tắt.");
    }

    private void cacheConfigValues() {
        cachedMaxMaces = Math.max(0, getConfig().getInt("max-maces", 8));
        cachedDebug = getConfig().getBoolean("debug", false);
        cachedSaveDebounce = Math.max(1, getConfig().getInt("save-debounce-ticks", 40));
        cachedStartupDelay = Math.max(1L, getConfig().getLong("startup-scan-delay-ticks", 40L));
    }

    // ============================================================
    // Getters
    // ============================================================

    public static MaceLimiter getInstance() { return instance; }
    public DataManager getDataManager() { return dataManager; }
    public NamespacedKey getMaceKey() { return maceKey; }

    public int getMaxMaces() { return cachedMaxMaces; }
    public boolean isDebug() { return cachedDebug; }
    public int getSaveDebounceTicks() { return cachedSaveDebounce; }

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
        cacheConfigValues();
    }

    public void debug(String msg) {
        if (cachedDebug) getLogger().info("[DEBUG] " + msg);
    }

    /**
     * Đổi max-maces runtime (dùng cho /mace setmax).
     */
    public void setMaxMaces(int value) {
        getConfig().set("max-maces", value);
        saveConfig();
        cachedMaxMaces = Math.max(0, value);
    }
}
