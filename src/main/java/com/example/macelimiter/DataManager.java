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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DataManager {

    public enum MaceAction { NONE, ADOPTED, DELETED }

    public static class ScanResult {
        public int adopted = 0;
        public int deleted = 0;
    }

    public static class SyncResult {
        public int fakeRemoved = 0;
        public int adopted = 0;
        public int orphans = 0;
    }

    private final MaceLimiter plugin;
    private final Set<UUID> maces = ConcurrentHashMap.newKeySet();
    private final Object saveLock = new Object();

    // Debounce save
    private volatile boolean savePending = false;
    private final Object pendingLock = new Object();

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
            try { maces.add(UUID.fromString(s)); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    /**
     * Ghi file ngay lập tức (blocking, main thread).
     * Dùng cho onDisable hoặc admin commands.
     */
    public void flushAndSave() {
        synchronized (pendingLock) { savePending = false; }
        saveNow();
    }

    /**
     * Yêu cầu save — sẽ được debounce (gộp nhiều thay đổi trong 1 khoảng ngắn).
     * Dùng cho add/remove/processInventory (được gọi từ event).
     */
    public void markDirty() {
        synchronized (pendingLock) {
            if (savePending) return;
            savePending = true;
        }

        if (!plugin.isEnabled()) {
            // Plugin đang disable → save ngay để không mất data
            saveNow();
            return;
        }

        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                synchronized (pendingLock) { savePending = false; }
                saveNow();
            }, plugin.getSaveDebounceTicks());
        } catch (Throwable t) {
            // Fallback: save ngay nếu scheduler không khả dụng
            saveNow();
        }
    }

    private void saveNow() {
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

    /** Trả về unmodifiable set — không cho phép caller sửa. */
    public Set<UUID> getMaces() { return Collections.unmodifiableSet(maces); }

    public void add(UUID uuid) {
        if (uuid != null && maces.add(uuid)) markDirty();
    }

    public void remove(UUID uuid) {
        if (uuid != null && maces.remove(uuid)) markDirty();
    }

    public void reset() {
        maces.clear();
        flushAndSave();
    }

    // ============================================================
    // PDC HELPERS
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

        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
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
    // CORE
    // ============================================================

    public MaceAction processMace(ItemStack stack) {
        if (stack == null || stack.getType() != Material.MACE) return MaceAction.NONE;
        if (stack.getAmount() <= 0) return MaceAction.NONE;

        UUID uuid = extractMaceUUID(stack);

        if (uuid == null) {
            UUID newUuid = UUID.randomUUID();
            if (tagMace(stack, newUuid)) {
                add(newUuid);
                return MaceAction.ADOPTED;
            }
            stack.setAmount(0);
            return MaceAction.DELETED;
        }

        if (!maces.contains(uuid)) {
            stack.setAmount(0);
            return MaceAction.DELETED;
        }

        return MaceAction.NONE;
    }

    public ScanResult processInventory(Inventory inv) {
        ScanResult result = new ScanResult();
        if (inv == null) return result;

        int size;
        try { size = inv.getSize(); } catch (Throwable t) { return result; }

        for (int i = 0; i < size; i++) {
            ItemStack stack;
            try { stack = inv.getItem(i); } catch (Throwable t) { continue; }
            if (stack == null || stack.getType() != Material.MACE) continue;

            MaceAction action = processMace(stack);
            switch (action) {
                case ADOPTED -> {
                    try { inv.setItem(i, stack); } catch (Throwable ignored) {}
                    result.adopted++;
                }
                case DELETED -> {
                    try { inv.setItem(i, null); } catch (Throwable ignored) {}
                    result.deleted++;
                }
                default -> {}
            }
        }
        return result;
    }

    // ============================================================
    // SYNC
    // ============================================================

    public SyncResult syncWithServer(boolean removeFake) {
        SyncResult result = new SyncResult();
        Set<UUID> found = new HashSet<>();

        for (Player p : Bukkit.getOnlinePlayers()) {
            scanInventory(p.getInventory(), found, result);
            scanInventory(p.getEnderChest(), found, result);
        }

        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) {
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
                for (BlockState state : c.getTileEntities()) {
                    if (state instanceof Container container) {
                        scanInventory(container.getInventory(), found, result);
                    }
                }
            }
        }

        if (removeFake) {
            for (UUID u : new ArrayList<>(maces)) {
                if (!found.contains(u)) {
                    maces.remove(u);
                    result.fakeRemoved++;
                }
            }
        }

        if (result.adopted > 0 || result.fakeRemoved > 0) markDirty();
        return result;
    }

    public SyncResult syncWithServer() { return syncWithServer(true); }

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

    private boolean processStack(ItemStack stack, Set<UUID> found, SyncResult result) {
        if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) return false;

        boolean changed = false;

        if (stack.getType() == Material.MACE) {
            UUID uuid = extractMaceUUID(stack);
            if (uuid != null) {
                if (maces.contains(uuid)) found.add(uuid);
                else result.orphans++;
            } else {
                UUID newUuid = UUID.randomUUID();
                if (tagMace(stack, newUuid)) {
                    maces.add(newUuid);
                    found.add(newUuid);
                    result.adopted++;
                    changed = true;
                }
            }
        }

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
}
