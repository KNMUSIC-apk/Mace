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

        // Tạo config.yml mặc định nếu chưa có
        saveDefaultConfig();

        // NamespacedKey dùng để gắn UUID vào PDC của từng ItemStack Mace
        this.maceKey = new NamespacedKey(this, "mace_uuid");

        // Load data.yml -> HashSet<UUID>
        this.dataManager = new DataManager(this);
        this.dataManager.load();

        // Đăng ký listener
        getServer().getPluginManager().registerEvents(new MaceListener(this), this);

        // Đăng ký commands
        CommandHandler handler = new CommandHandler(this);
        for (String name : new String[]{"macelimit", "macereset", "macesync"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(handler);
                cmd.setTabCompleter(handler);
            }
        }

        getLogger().info("MaceLimiter đã bật. Hiện có "
                + dataManager.getCount() + "/" + getMaxMaces() + " Mace.");
    }

    @Override
    public void onDisable() {
        // Save lần cuối — bảo vệ dữ liệu khi /reload hoặc tắt server
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

    /**
     * Lấy message từ config, dịch mã màu legacy, thay placeholders.
     * Cú pháp: getMessage("limit-reached", "%current%", "2", "%max%", "8")
     */
    public String getMessage(String key, String... placeholders) {
        String raw = getConfig().getString("messages." + key,
                "&c[Missing message: " + key + "]");
        String msg = ChatColor.translateAlternateColorCodes('&', raw);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            msg = msg.replace(placeholders[i], placeholders[i + 1]);
        }
        return msg;
    }
}
