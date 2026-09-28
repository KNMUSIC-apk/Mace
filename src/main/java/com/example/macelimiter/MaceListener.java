package com.example.macelimiter;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;
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

            boolean bypass = player.hasPermission("macelimiter.bypass");

            if (!bypass && cur >= max) {
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
                    + " → count = " + data.getCount() + "/" + max
                    + (bypass ? " (BYPASS)" : ""));

            if (e.isShiftClick() && !bypass && data.getCount() > max) {
                e.setCancelled(true);
                data.remove(uuid);
                player.sendMessage(plugin.getMessage("limit-reached",
                        "%current%", String.valueOf(max),
                        "%max%", String.valueOf(max)));
            }
        }
    }

    // ============================================================
    // 2. ITEM DESTRUCTION
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
            plugin.debug("Remove Mace (damage) → count = " + data.getCount());
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

    /**
     * ⚡ FIX #3: EntityRemoveEvent (Paper 1.19.4+) — catch-all.
     * Bắt mọi trường hợp item bị remove (void, /kill, plugin khác, discard...).
     * BỎ QUA cause UNLOADED (chunk unload → item sẽ quay lại khi chunk load lại).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveEvent e) {
        if (!(e.getEntity() instanceof Item itemEntity)) return;

        // Bỏ qua chunk unload — item sẽ quay lại
        try {
            if (e.getCause() == EntityRemoveEvent.Cause.UNLOADED) return;
        } catch (Throwable ignored) {}

        ItemStack stack;
        try { stack = itemEntity.getItemStack(); } catch (Throwable t) { return; }
        UUID uuid = data.extractMaceUUID(stack);
        if (uuid != null) {
            data.remove(uuid);
            plugin.debug("Remove Mace (EntityRemoveEvent/" + e.getCause() + ") → count = " + data.getCount());
        }
    }

    /**
     * ⚡ FIX #4: Adopt khi player nhặt Mace từ ground.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        ItemStack stack = e.getItem().getItemStack();
        if (stack == null || stack.getType() != Material.MACE) return;

        UUID uuid = data.extractMaceUUID(stack);
        if (uuid == null) {
            // Untagged → đánh dấu cần adopt sau khi vào balo
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                DataManager.ScanResult r = data.processInventory(p.getInventory());
                if (r.adopted > 0) {
                    plugin.debug(p.getName() + " → Adopt " + r.adopted + " Mace (pickup)");
                }
            }, 1L);
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
    // 4. JOIN
    // ============================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!plugin.isEnabled() || !p.isOnline()) return;

            DataManager.ScanResult r1 = data.processInventory(p.getInventory());
            DataManager.ScanResult r2 = data.processInventory(p.getEnderChest());

            int adopted = r1.adopted + r2.adopted;
            int deleted = r1.deleted + r2.deleted;

            if (adopted > 0) plugin.debug(p.getName() + " → Adopt " + adopted + " Mace (onJoin)");
            if (deleted > 0) p.sendMessage(plugin.getMessage("invalid-mace-removed"));
        }, 10L);
    }

    // ============================================================
    // 5. INVENTORY EVENTS
    // ============================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Inventory inv = e.getInventory();
        if (inv == null) return;

        DataManager.ScanResult r = data.processInventory(inv);
        if (r.adopted > 0) plugin.debug(p.getName() + " → Adopt " + r.adopted + " (onInventoryOpen)");
        if (r.deleted > 0) p.sendMessage(plugin.getMessage("invalid-mace-removed"));
    }

    /**
     * ⚡ FIX #2: Priority HIGHEST (không phải MONITOR).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // Slot item
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

        // Cursor item
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
                    return;
                }
                default -> {}
            }
        }

        // Creative destroy detection
        handleCreativeDestroy(e, p);
    }

    /**
     * ⚡ NEW: Xử lý drag item.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        ItemStack oldCursor = e.getOldCursor();
        if (oldCursor == null || oldCursor.getType() != Material.MACE) return;

        DataManager.MaceAction action = data.processMace(oldCursor);
        switch (action) {
            case ADOPTED -> {
                e.setCursor(oldCursor);
                plugin.debug(p.getName() + " → Adopt Mace (drag)");
            }
            case DELETED -> {
                e.setCancelled(true);
                p.sendMessage(plugin.getMessage("invalid-mace-removed"));
            }
            default -> {
                // Đã có UUID hợp lệ, nhưng có thể item trong slot cũng cần adopt
                for (Map.Entry<Integer, ItemStack> entry : e.getNewItems().entrySet()) {
                    ItemStack s = entry.getValue();
                    if (s == null || s.getType() != Material.MACE) continue;
                    DataManager.MaceAction a2 = data.processMace(s);
                    if (a2 == DataManager.MaceAction.ADOPTED) {
                        plugin.debug(p.getName() + " → Adopt Mace (drag slot)");
                    }
                }
            }
        }
    }

    /**
     * ⚡ FIX: Priority MONITOR → vẫn OK vì không modify event.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!plugin.isEnabled() || !player.isOnline()) return;

            DataManager.ScanResult r1 = data.processInventory(player.getInventory());
            DataManager.ScanResult r2 = data.processInventory(player.getEnderChest());

            if (r1.adopted + r2.adopted > 0) {
                plugin.debug(player.getName() + " → Adopt "
                        + (r1.adopted + r2.adopted) + " Mace (onInventoryClose)");
            }
            if (r1.deleted + r2.deleted > 0) {
                player.sendMessage(plugin.getMessage("invalid-mace-removed"));
            }
        }, 1L);
    }

    // ============================================================
    // CREATIVE DESTROY DETECTION
    // ============================================================

    private void handleCreativeDestroy(InventoryClickEvent e, Player player) {
        Inventory clicked = e.getClickedInventory();
        if (clicked == null) return;

        InventoryType type;
        try { type = clicked.getType(); } catch (Throwable t) { return; }
        if (type != InventoryType.CREATIVE) return;

        ItemStack cursor = e.getCursor();
        if (cursor == null || cursor.getType() != Material.MACE) return;

        UUID uuid = data.extractMaceUUID(cursor);
        if (uuid == null) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!plugin.isEnabled() || !player.isOnline()) return;
            if (!isMaceStillOnPlayer(player, uuid)) {
                data.remove(uuid);
                plugin.debug(player.getName()
                        + " → Remove Mace (creative destroy) → count = " + data.getCount());
            }
        }, 1L);
    }

    private boolean isMaceStillOnPlayer(Player player, UUID uuid) {
        if (containsUuid(player.getInventory().getContents(), uuid)) return true;
        if (containsUuid(player.getEnderChest().getContents(), uuid)) return true;
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && cursor.getType() == Material.MACE) {
            if (uuid.equals(data.extractMaceUUID(cursor))) return true;
        }
        return false;
    }

    private boolean containsUuid(ItemStack[] contents, UUID uuid) {
        if (contents == null || uuid == null) return false;
        for (ItemStack s : contents) {
            if (s == null) continue;
            if (uuid.equals(data.extractMaceUUID(s))) return true;
        }
        return false;
    }
}
