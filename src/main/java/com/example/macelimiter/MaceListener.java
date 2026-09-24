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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;

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
            plugin.debug("Craft Mace cho " + player.getName()
                    + " → count = " + data.getCount() + "/" + max);

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
    // 2. ITEM DAMAGE / DESPAWN
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
            plugin.debug("Remove Mace (damage event) → count = " + data.getCount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent e) {
        ItemStack stack;
        try { stack = e.getEntity().getItemStack(); } catch (Throwable t) { return; }
        UUID uuid = data.extractMaceUUID(stack);
        if (uuid != null) {
            data.remove(uuid);
            plugin.debug("Remove Mace (despawn) → count = " + data.getCount());
        }
    }

    // ============================================================
    // 3. HELD / INTERACT
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
            case ADOPTED -> {
                inv.setItem(hand, item);
                plugin.debug(player.getName() + " → Adopt Mace (onInteract)");
            }
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
            case ADOPTED -> {
                inv.setItem(e.getNewSlot(), item);
                plugin.debug(player.getName() + " → Adopt Mace (onItemHeld)");
            }
            case DELETED -> {
                inv.setItem(e.getNewSlot(), null);
                player.sendMessage(plugin.getMessage("invalid-mace-removed"));
            }
            default -> {}
        }
    }

    // ============================================================
    // 4. JOIN — Delay 10 ticks cho chắc
    // ============================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;

            DataManager.ScanResult r1 = data.processInventory(p.getInventory());
            DataManager.ScanResult r2 = data.processInventory(p.getEnderChest());

            int totalAdopted = r1.adopted + r2.adopted;
            int totalDeleted = r1.deleted + r2.deleted;

            if (totalAdopted > 0) {
                plugin.debug(p.getName() + " → Adopt " + totalAdopted + " Mace (onJoin)");
            }
            if (totalDeleted > 0) {
                p.sendMessage(plugin.getMessage("invalid-mace-removed"));
            }
        }, 10L);
    }

    // ============================================================
    // 5. INVENTORY OPEN — Adopt khi mở chest/shulker/balo
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;

        Inventory inv = e.getInventory();
        if (inv == null) return;

        DataManager.ScanResult r = data.processInventory(inv);
        if (r.adopted > 0) {
            plugin.debug(p.getName() + " → Adopt " + r.adopted + " Mace (onInventoryOpen)");
        }
        if (r.deleted > 0) {
            p.sendMessage(plugin.getMessage("invalid-mace-removed"));
        }
    }

    // ============================================================
    // 6. INVENTORY CLICK — Adopt khi click slot / cursor
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // Item ở slot click
        ItemStack slotItem = e.getCurrentItem();
        if (slotItem != null && slotItem.getType() == Material.MACE) {
            DataManager.MaceAction action = data.processMace(slotItem);
            switch (action) {
                case ADOPTED -> {
                    e.setCurrentItem(slotItem);
                    plugin.debug(p.getName() + " → Adopt Mace (click slot)");
                }
                case DELETED -> {
                    e.setCurrentItem(null);
                    p.sendMessage(plugin.getMessage("invalid-mace-removed"));
                }
                default -> {}
            }
        }

        // Item đang cầm trên cursor
        ItemStack cursor = e.getCursor();
        if (cursor != null && cursor.getType() == Material.MACE) {
            DataManager.MaceAction action = data.processMace(cursor);
            switch (action) {
                case ADOPTED -> {
                    e.setCursor(cursor);
                    plugin.debug(p.getName() + " → Adopt Mace (cursor)");
                }
                case DELETED -> {
                    e.setCursor(null);
                    p.sendMessage(plugin.getMessage("invalid-mace-removed"));
                }
                default -> {}
            }
        }
    }
}
