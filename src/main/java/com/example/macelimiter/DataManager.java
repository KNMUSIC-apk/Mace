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
 * File I/O lock bằng saveLock.
 *
 * Core: processMace() xử lý ADOPT / DELETE / KEEP cho 1 ItemStack.
 */
public class DataManager {

    /**
     * Kết quả xử lý 1 ItemStack Mace.
     */
    public enum MaceAction {
        NONE,       // Không thay đổi gì
        ADOPTED,    // Đã gắn PDC + thêm vào data (caller cần write-back vào inventory)
        DELETED     // Mace orphan, đã set amount = 0 (caller cần write-back)
    }

    private final MaceLimiter plugin;
    private final Set<UUID> maces = ConcurrentHashMap.newKeySet();
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
    // CORE LOGIC: XỬ LÝ 1 MACE (ADOPT / DELETE / KEEP)
    // ============================================================

    /**
     * Xử lý 1 ItemStack Mace:
     *  - Untagged → ADOPT (sinh UUID, gắn PDC, thêm vào data). KHÔNG check max.
     *  - Tagged + UUID không trong data → DELETE (set amount 0).
     *  - Tagged + UUID trong data → NONE.
     *
     * Caller PHẢI write-back ItemStack vào inventory nếu kết quả != NONE.
     */
    public MaceAction processMace(ItemStack stack) {
        if (stack == null || stack.getType() != Material.MACE) return MaceAction.NONE;
        if (stack.getAmount() <= 0) return MaceAction.NONE;

        UUID uuid = extractMaceUUID(stack);

        if (uuid == null) {
            // ---- Untagged → ADOPT ----
            UUID newUuid = UUID.randomUUID();
            if (tagMace(stack, newUuid)) {
                add(newUuid);
                return MaceAction.ADOPTED;
            }
            // Không tag được → xóa để tránh Mace vô chủ exploitable
            stack.setAmount(0);
            return MaceAction.DELETED;
        }

        if (!maces.contains(uuid)) {
            // ---- Orphan (từ /macereset hoặc plugin khác gỡ data) ----
            stack.setAmount(0);
            return MaceAction.DELETED;
        }

        return MaceAction.NONE;
    }

    // ============================================================
    // SYNC
    // ============================================================

    /**
     * Quét người chơi online + chunk đang load.
     *
     * @param removeFake  true  → xóa UUID ảo (dùng cho /macesync)
     *                    false → chỉ auto-adopt (dùng khi plugin enable —
     *                            tránh xóa nhầm UUID của Mace nằm trong chunk chưa load)
     */
    public SyncResult syncWithServer(boolean removeFake) {
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
                        if (processStack(stack, found, result)) {
                            try { itemEntity.setItemStack(stack); } catch (Throwable ignored) {}
                        }
                    } else if (e instanceof ItemFrame frame) {
                        ItemStack stack = frame.getItem();
                        if (processStack(stack, found, result)) {
                            try { frame.setItem(stack, false); } catch (Throwable ignored) {}
                        }
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

        // ---- 3. Loại bỏ UUID ảo (chỉ khi removeFake) ----
        if (removeFake) {
            for (UUID u : new ArrayList<>(maces)) {
                if (!found.contains(u)) {
                    maces.remove(u);
                    result.fakeRemoved++;
                }
            }
        }

        if (result.adopted > 0 || result.fakeRemoved > 0) save();
        return result;
    }

    /** Backward-compat: mặc định là full cleanup. */
    public SyncResult syncWithServer() {
        return syncWithServer(true);
    }

    private void scanInventory(Inventory inv, Set<UUID> found, SyncResult result) {
        if (inv == null) return;
        int size;
        try { size = inv.getSize(); } catch (Throwable t) { return; }
        for (int i = 0; i < size; i++) {
            ItemStack stack;
            try { stack = inv.getItem(i); } catch (Throwable t) { continue; }
            if (processStack(stack, found, result)) {
                try { inv.setItem(i, stack); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * @return true nếu stack bị thay đổi → caller cần write-back.
     */
    private boolean processStack(ItemStack stack, Set<UUID> found, SyncResult result) {
        if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) return false;

        boolean changed = false;

        // ---- Mace ----
        if (stack.getType() == Material.MACE) {
            UUID uuid = extractMaceUUID(stack);
            if (uuid != null) {
                if (maces.contains(uuid)) {
                    found.add(uuid);
                } else {
                    // Orphan — chờ player chạm → xóa
                    result.orphans++;
                }
            } else {
                // Untagged → auto-adopt
                UUID newUuid = UUID.randomUUID();
                if (tagMace(stack, newUuid)) {
                    maces.add(newUuid);
                    found.add(newUuid);
                    result.adopted++;
                    changed = true;
                }
            }
        }

        // ---- Bundle (chứa Mace) ----
        if (stack.getType() == Material.BUNDLE) {
            ItemMeta meta = stack.getItemMeta();
            if (meta instanceof BundleMeta bm) {
                List<ItemStack> contents = new ArrayList<>(bm.getItems());
                boolean bundleChanged = false;
                for (ItemStack inner : contents) {
                    if (processStack(inner, found, result)) bundleChanged = true;
                }
                if (bundleChanged) {
                    try {
                        bm.setItems(contents);
                        stack.setItemMeta(bm);
                        changed = true;
                    } catch (Throwable ignored) {}
                }
            }
        }

        return changed;
    }

    // ============================================================
    // KẾT QUẢ SYNC
    // ============================================================

    public static class SyncResult {
        public int fakeRemoved = 0;   // UUID ảo bị xóa khỏi data.yml
        public int adopted = 0;       // Mace untagged đã được cấp PDC + tính vào data
        public int orphans = 0;       // Mace có PDC nhưng UUID không trong data
    }
}
