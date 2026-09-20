package com.example.macelimiter;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý tập UUID của các Mace đang tồn tại trên server.
 *
 * Thread-safe: dùng ConcurrentHashMap.newKeySet() cho HashSet<UUID>.
 * File I/O được lock bằng saveLock để tránh ghi đè khi nhiều thread.
 */
public class DataManager {

    private final MaceLimiter plugin;

    // Thread-safe set — hỗ trợ đọc/ghi đồng thời không cần synchronize
    private final Set<UUID> maces = ConcurrentHashMap.newKeySet();

    // Lock cho thao tác file I/O
    private final Object saveLock = new Object();

    private File file;
    private YamlConfiguration config;

    public DataManager(MaceLimiter plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    // LOAD / SAVE
    // ============================================================

    public void load() {
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Không thể tạo thư mục plugin!");
        }

        file = new File(folder, "data.yml");
        if (!file.exists()) {
            try {
                if (!file.createNewFile()) {
                    plugin.getLogger().warning("Không thể tạo data.yml!");
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Lỗi tạo data.yml: " + e.getMessage());
            }
        }

        config = YamlConfiguration.loadConfiguration(file);
        maces.clear();

        List<String> list = config.getStringList("maces");
        for (String s : list) {
            if (s == null || s.isEmpty()) continue;
            try {
                maces.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // Bỏ qua UUID hỏng trong file
            }
        }
    }

    public void save() {
        synchronized (saveLock) {
            if (config == null || file == null) return;
            List<String> out = new ArrayList<>(maces.size());
            for (UUID u : maces) out.add(u.toString());
            config.set("maces", out);
            try {
                config.save(file);
            } catch (IOException e) {
                plugin.getLogger().severe("Không thể lưu data.yml: " + e.getMessage());
            }
        }
    }

    // ============================================================
    // PUBLIC API
    // ============================================================

    public int getCount() { return maces.size(); }
    public boolean contains(UUID uuid) { return uuid != null && maces.contains(uuid); }
    public Set<UUID> getMaces() { return maces; }

    public void add(UUID uuid) {
        if (uuid != null && maces.add(uuid)) save();
    }

    public void remove(UUID uuid) {
        if (uuid != null && maces.remove(uuid)) save();
    }

    public void reset() {
        maces.clear();
        save();
    }

    // ============================================================
    // PDC HELPERS — Null-safe tuyệt đối
    // ============================================================

    /**
     * Trích xuất UUID từ PDC của ItemStack nếu đó là Mace.
     * Trả về null nếu: stack null / AIR / không phải Mace / không có meta / không có PDC / UUID hỏng.
     */
    public UUID extractMaceUUID(ItemStack stack) {
        if (stack == null) return null;
        if (stack.getType() != Material.MACE) return null;
        if (stack.getAmount() <= 0) return null;
        if (!stack.hasItemMeta()) return null;

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;

        String raw;
        try {
            raw = meta.getPersistentDataContainer()
                    .get(plugin.getMaceKey(), PersistentDataType.STRING);
        } catch (Throwable t) {
            return null;
        }
        if (raw == null) return null;

        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Gắn UUID vào PDC của Mace. Trả về true nếu thành công.
     */
    public boolean tagMace(ItemStack stack, UUID uuid) {
        if (stack == null || uuid == null) return false;
        if (stack.getType() != Material.MACE) return false;
        if (stack.getAmount() <= 0) return false;

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;

        try {
            meta.getPersistentDataContainer().set(
                    plugin.getMaceKey(), PersistentDataType.STRING, uuid.toString());
            stack.setItemMeta(meta);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Không thể tag Mace: " + t.getMessage());
            return false;
        }
    }

    // ============================================================
    // SYNC
    // ============================================================

    /**
     * Quét người chơi online + chunk đang load.
     * - Loại bỏ UUID "ảo" (có trong data.yml nhưng không tìm thấy Mace thực tế).
     * - Tự cấp PDC cho các Mace "untagged" (Mace không có PDC) nếu còn slot.
     * - Báo cáo số lượng Mace "orphan" (có PDC nhưng UUID không nằm trong data.yml).
     */
    public SyncResult syncWithServer() {
        SyncResult result = new SyncResult();
        Set<UUID> found = new HashSet<>();

        // ---- 1. Người chơi online ----
        for (Player p : Bukkit.getOnlinePlayers()) {
            scanInventory(p.getInventory(), found, result);
            scanInventory(p.getEnderChest(), found, result);
        }

        // ---- 2. Chunk đang load ----
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) {
                // Item entity (rơi dưới đất)
                for (Entity e : c.getEntities()) {
                    if (e instanceof Item itemEntity) {
                        ItemStack stack = itemEntity.getItemStack();
                        boolean changed = scanStack(stack, found, result);
                        if (changed) itemEntity.setItemStack(stack);
                    } else if (e instanceof ItemFrame frame) {
                        ItemStack stack = frame.getItem();
                        boolean changed = scanStack(stack, found, result);
                        if (changed) frame.setItem(stack, false);
                    }
                }
                // Container (chest, barrel, shulker, hopper, furnace, ...)
                for (BlockState state : c.getTileEntities()) {
                    if (state instanceof Container container) {
                        scanInventory(container.getInventory(), found, result);
                    }
                }
            }
        }

        // ---- 3. Loại bỏ UUID ảo ----
        for (UUID u : new ArrayList<>(maces)) {
            if (!found.contains(u)) {
                maces.remove(u);
                result.fakeRemoved++;
            }
        }

        // ---- 4. Auto-tag Mace untagged (nếu còn slot) ----
        int max = plugin.getMaxMaces();
        for (ItemStack stack : result.untaggedItems) {
            if (maces.size() >= max) break;
            if (stack == null || stack.getType() != Material.MACE) continue;

            UUID newUuid = UUID.randomUUID();
            if (tagMace(stack, newUuid)) {
                maces.add(newUuid);
                result.tagged++;
            }
        }

        if (result.fakeRemoved > 0 || result.tagged > 0) save();
        return result;
    }

    /**
     * Quét 1 Inventory (player inv, ender chest, container).
     * Dùng getItem(slot) để lấy reference "live" -> tag được tại chỗ.
     */
    private void scanInventory(Inventory inv, Set<UUID> found, SyncResult result) {
        if (inv == null) return;
        int size;
        try {
            size = inv.getSize();
        } catch (Throwable t) {
            return;
        }
        for (int i = 0; i < size; i++) {
            ItemStack stack;
            try {
                stack = inv.getItem(i);
            } catch (Throwable t) {
                continue;
            }
            // scanStack không cần setItem vì getItem(i) là live reference
            scanStack(stack, found, result);
        }
    }

    /**
     * Xử lý 1 ItemStack. Trả về true nếu ItemStack bị thay đổi (đã tag).
     */
    private boolean scanStack(ItemStack stack, Set<UUID> found, SyncResult result) {
        if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) return false;

        boolean changed = false;

        // ---- Mace ----
        if (stack.getType() == Material.MACE) {
            UUID uuid = extractMaceUUID(stack);
            if (uuid != null) {
                if (maces.contains(uuid)) {
                    // Hợp lệ
                    found.add(uuid);
                } else {
                    // Orphan (từ /macereset hoặc plugin khác ghi PDC)
                    result.orphans++;
                }
            } else {
                // Untagged -> cho vào danh sách chờ tag
                result.untaggedItems.add(stack);
            }
        }

        // ---- Bundle (chứa item bên trong) ----
        if (stack.getType() == Material.BUNDLE && stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta instanceof BundleMeta bundleMeta) {
                boolean bundleChanged = false;
                for (ItemStack inner : bundleMeta.getItems()) {
                    if (scanStack(inner, found, result)) bundleChanged = true;
                }
                if (bundleChanged) {
                    bundleMeta.setItems(bundleMeta.getItems());
                    stack.setItemMeta(bundleMeta);
                    changed = true;
                }
            }
        }

        return changed;
    }

    // ============================================================
    // KẾT QUẢ SYNC
    // ============================================================

    public static class SyncResult {
        public int fakeRemoved = 0;      // UUID ảo bị xóa khỏi data.yml
        public int tagged = 0;           // Mace untagged đã được cấp PDC
        public int orphans = 0;          // Mace có PDC nhưng không nằm trong data.yml
        public final List<ItemStack> untaggedItems = new ArrayList<>();
    }
}
