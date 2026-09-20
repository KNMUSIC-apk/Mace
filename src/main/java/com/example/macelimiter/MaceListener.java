package com.example.macelimiter;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Toàn bộ logic plugin chạy theo event. Không có task định kỳ.
 *
 * Các event được xử lý:
 *  - PrepareItemCraftEvent  : ẩn kết quả craft nếu hết slot
 *  - CraftItemEvent         : lock + sinh UUID + gắn PDC
 *  - EntityDamageEvent      : item Mace cháy/nổ/lava/void -> nhường slot
 *  - ItemDespawnEvent       : Mace despawn tự nhiên -> nhường slot
 *  - PlayerInteractEvent    : phát hiện Mace rác -> xóa
 *  - PlayerItemHeldEvent    : phát hiện Mace rác khi đổi hotbar -> xóa
 *  - PlayerJoinEvent        : validate inventory khi player vào server
 */
public class MaceListener implements Listener {

    private final MaceLimiter plugin;
    private final DataManager data;

    /**
     * Lock chống concurrent crafting.
     * 2 người craft cùng 1 tick -> lock serialize phần check + register.
     */
    private final Object craftLock = new Object();

    public MaceListener(MaceLimiter plugin) {
        this.plugin = plugin;
        this.data = plugin.getDataManager();
    }

    // ============================================================
    // 1. CRAFTING
    // ============================================================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (e.getInventory() == null) return;
        ItemStack result = e.getInventory().getResult();
        if (result == null || result.getType() != Material.MACE) return;

        if (data.getCount() >= plugin.getMaxMaces()) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        ItemStack recipeResult;
        try {
            recipeResult = e.getRecipe() != null ? e.getRecipe().getResult() : null;
        } catch (Throwable t) {
            return;
        }
        if (recipeResult == null || recipeResult.getType() != Material.MACE) return;

        CraftingInventory inv = e.getInventory();
        if (inv == null) return;

        ItemStack current = e.getCurrentItem();
        if (current == null || current.getType() != Material.MACE) return;

        synchronized (craftLock) {
            int max = plugin.getMaxMaces();
            int cur = data.getCount();

            if (cur >= max) {
                e.setCancelled(true);
                player.sendMessage(plugin.getMessage("limit-reached",
                        "%current%", String.valueOf(cur),
                        "%max%", String.valueOf(max)));
                return;
            }

            UUID uuid = UUID.randomUUID();

            // Clone để tránh mutate ItemStack gốc của server
            ItemStack tagged = current.clone();
            tagged.setAmount(1);

            if (!data.tagMace(tagged, uuid)) {
                // Không tag được -> hủy cho an toàn, tránh tạo Mace "rác"
                e.setCancelled(true);
                plugin.getLogger().warning("Không tag được Mace khi craft cho " + player.getName());
                return;
            }

            // Ghi đè item ở slot kết quả trước khi Bukkit move
            try {
                inv.setResult(tagged);
            } catch (Throwable ignored) { /* một số version không cho set */ }

            // Đăng ký UUID NGAY
            data.add(uuid);

            // Shift-click: buộc rollback nếu số lượng vượt (trường hợp đặc biệt)
            if (e.isShiftClick() && data.getCount() > max) {
                e.setCancelled(true);
                data.remove(uuid);
                player.sendMessage(plugin.getMessage("limit-reached",
                        "%current%", String.valueOf(max),
                        "%max%", String.valueOf(max)));
            }
        }
    }

    // ============================================================
    // 2. ITEM BỊ PHÁ HỦY (lava / fire / explosion / cactus / void)
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Item itemEntity)) return;

        ItemStack stack;
        try {
            stack = itemEntity.getItemStack();
        } catch (Throwable t) {
            return;
        }
        UUID uuid = data.extractMaceUUID(stack);
        if (uuid == null) return;

        double health;
        try {
            health = itemEntity.getHealth();
        } catch (Throwable t) {
            health = 5.0;
        }

        // Item sắp bị destroy (máu <= damage nhận vào)
        if (e.getFinalDamage() >= health) {
            data.remove(uuid);
        }
    }

    // ============================================================
    // 3. DESPAWN TỰ NHIÊN
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent e) {
        ItemStack stack;
        try {
            stack = e.getEntity().getItemStack();
        } catch (Throwable t) {
            return;
        }
        UUID uuid = data.extractMaceUUID(stack);
        if (uuid != null) {
            data.remove(uuid);
        }
    }

    // ============================================================
    // 4. VALIDATE MACE RÁC (sau /macereset, /give, hoặc plugin khác)
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.MACE) return;
        validateAndCleanup(item, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent e) {
        ItemStack item;
        try {
            item = e.getPlayer().getInventory().getItem(e.getNewSlot());
        } catch (Throwable t) {
            return;
        }
        if (item == null || item.getType() != Material.MACE) return;
        validateAndCleanup(item, e.getPlayer());
    }

    /**
     * Khi player vào server: validate inventory + ender chest.
     * Delay 1 tick để chắc chắn inventory đã load xong.
     * Đây KHÔNG phải periodic task — chỉ chạy 1 lần duy nhất.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            validateInventory(p.getInventory());
            validateInventory(p.getEnderChest());
        }, 1L);
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private void validateAndCleanup(ItemStack item, Player player) {
        if (item == null || item.getType() != Material.MACE) return;

        UUID uuid = data.extractMaceUUID(item);

        // Mace rác = không có PDC HOẶC UUID không nằm trong data.yml
        if (uuid == null || !data.contains(uuid)) {
            item.setAmount(0);
            try {
                player.updateInventory();
            } catch (Throwable ignored) { }
            player.sendMessage(plugin.getMessage("invalid-mace-removed"));
        }
    }

    private void validateInventory(org.bukkit.inventory.Inventory inv) {
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
            if (stack == null || stack.getType() != Material.MACE) continue;
            UUID uuid = data.extractMaceUUID(stack);
            if (uuid == null || !data.contains(uuid)) {
                inv.setItem(i, null);
            }
        }
    }
}
