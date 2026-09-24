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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;

/**
 * Toàn bộ logic plugin chạy theo event. Không có task định kỳ (ngoại trừ
 * 1-shot startup scan + 1-shot join scan).
 */
public class MaceListener implements Listener {

    private final MaceLimiter plugin;
    private final DataManager data;
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
        } catch (Throwable t) { return; }
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
            ItemStack tagged = current.clone();
            tagged.setAmount(1);

            if (!data.tagMace(tagged, uuid)) {
                e.setCancelled(true);
                return;
            }

            try { inv.setResult(tagged); } catch (Throwable ignored) {}

            data.add(uuid);

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
        try { stack = itemEntity.getItemStack(); } catch (Throwable t) { return; }
        UUID uuid = data.extractMaceUUID(stack);
        if (uuid == null) return;

        double health;
        try { health = itemEntity.getHealth(); } catch (Throwable t) { health = 5.0; }

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
        try { stack = e.getEntity().getItemStack(); } catch (Throwable t) { return; }
        UUID uuid = data.extractMaceUUID(stack);
        if (uuid != null) data.remove(uuid);
    }

    // ============================================================
    // 4. VALIDATE / ADOPT MACE TRÊN NGƯỜI CHƠI
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;

        Player player = e.getPlayer();
        PlayerInventory inv = player.getInventory();
        ItemStack item = inv.getItem(hand);
        if (item == null || item.getType() != Material.MACE) return;

        DataManager.MaceAction action = data.processMace(item);
        switch (action) {
            case ADOPTED -> inv.setItem(hand, item);
            case DELETED -> {
                inv.setItem(hand, null);
                player.sendMessage(plugin.getMessage("invalid-mace-removed"));
            }
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent e) {
        Player player = e.getPlayer();
        PlayerInventory inv = player.getInventory();
        ItemStack item = inv.getItem(e.getNewSlot());
        if (item == null || item.getType() != Material.MACE) return;

        DataManager.MaceAction action = data.processMace(item);
        switch (action) {
            case ADOPTED -> inv.setItem(e.getNewSlot(), item);
            case DELETED -> {
                inv.setItem(e.getNewSlot(), null);
                player.sendMessage(plugin.getMessage("invalid-mace-removed"));
            }
            default -> {}
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // Delay 1 tick để inventory chắc chắn đã load
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            processInventory(p.getInventory(), p);
            processInventory(p.getEnderChest(), p);
        }, 1L);
    }

    // ============================================================
    // HELPERS
    // ============================================================

    /**
     * Duyệt toàn bộ inventory, xử lý Mace untagged (adopt)
     * và Mace orphan (delete).
     */
    private void processInventory(org.bukkit.inventory.Inventory inv, Player notify) {
        if (inv == null) return;
        int size;
        try { size = inv.getSize(); } catch (Throwable t) { return; }

        boolean removedSomething = false;

        for (int i = 0; i < size; i++) {
            ItemStack stack;
            try { stack = inv.getItem(i); } catch (Throwable t) { continue; }
            if (stack == null || stack.getType() != Material.MACE) continue;

            DataManager.MaceAction action = data.processMace(stack);
            switch (action) {
                case ADOPTED -> inv.setItem(i, stack);
                case DELETED -> {
                    inv.setItem(i, null);
                    removedSomething = true;
                }
                default -> {}
            }
        }

        if (removedSomething && notify != null && notify.isOnline()) {
            notify.sendMessage(plugin.getMessage("invalid-mace-removed"));
        }
    }
}
