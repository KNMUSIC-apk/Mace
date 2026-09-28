package com.example.macelimiter;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CommandHandler implements CommandExecutor, TabCompleter {

    private static final String PERM = "macelimiter.admin";
    private final MaceLimiter plugin;

    public CommandHandler(MaceLimiter plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(plugin.getMessage("no-permission"));
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "macelimit" -> {
                int cur = plugin.getDataManager().getCount();
                int max = plugin.getMaxMaces();
                sender.sendMessage(plugin.getMessage("limit-info",
                        "%current%", String.valueOf(cur),
                        "%max%", String.valueOf(max)));
            }
            case "macereset" -> {
                plugin.getDataManager().reset();
                sender.sendMessage(plugin.getMessage("reset-success"));
                plugin.getLogger().info(sender.getName() + " đã reset data Mace.");
            }
            case "macesync" -> {
                sender.sendMessage(plugin.getMessage("sync-start"));
                DataManager.SyncResult r = plugin.getDataManager().syncWithServer(true);
                int cur = plugin.getDataManager().getCount();
                sender.sendMessage(plugin.getMessage("sync-success",
                        "%fake%",    String.valueOf(r.fakeRemoved),
                        "%adopted%", String.valueOf(r.adopted),
                        "%orphans%", String.valueOf(r.orphans),
                        "%current%", String.valueOf(cur),
                        "%max%",     String.valueOf(plugin.getMaxMaces())));
            }
            case "macescan" -> handleMaceScan(sender, args);
            case "mace" -> handleMaceCommand(sender, args);
            default -> { return false; }
        }
        return true;
    }

    private void handleMaceScan(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(plugin.getMessage("usage-macescan"));
            return;
        }

        // /macescan all
        if (args[0].equalsIgnoreCase("all")) {
            int totalAdopted = 0, totalDeleted = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                DataManager.ScanResult r1 = plugin.getDataManager().processInventory(p.getInventory());
                DataManager.ScanResult r2 = plugin.getDataManager().processInventory(p.getEnderChest());
                totalAdopted += r1.adopted + r2.adopted;
                totalDeleted += r1.deleted + r2.deleted;
            }
            sender.sendMessage(plugin.getMessage("scan-all-success",
                    "%players%", String.valueOf(Bukkit.getOnlinePlayers().size()),
                    "%adopted%", String.valueOf(totalAdopted),
                    "%deleted%", String.valueOf(totalDeleted),
                    "%current%", String.valueOf(plugin.getDataManager().getCount()),
                    "%max%",     String.valueOf(plugin.getMaxMaces())));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(plugin.getMessage("player-not-found",
                    "%player%", args[0]));
            return;
        }

        DataManager.ScanResult r1 = plugin.getDataManager().processInventory(target.getInventory());
        DataManager.ScanResult r2 = plugin.getDataManager().processInventory(target.getEnderChest());

        sender.sendMessage(plugin.getMessage("scan-success",
                "%player%",  target.getName(),
                "%adopted%", String.valueOf(r1.adopted + r2.adopted),
                "%deleted%", String.valueOf(r1.deleted + r2.deleted),
                "%current%", String.valueOf(plugin.getDataManager().getCount()),
                "%max%",     String.valueOf(plugin.getMaxMaces())));
    }

    private void handleMaceCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.getMessage("usage-mace"));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage(plugin.getMessage("reload-success",
                        "%max%", String.valueOf(plugin.getMaxMaces())));
                plugin.getLogger().info(sender.getName() + " đã reload config.yml.");
            }
            case "info" -> {
                int cur = plugin.getDataManager().getCount();
                int max = plugin.getMaxMaces();
                sender.sendMessage("§6=== MaceLimiter Info ===");
                sender.sendMessage("§eSố Mace hiện có: §c" + cur + "§e/§c" + max);
                sender.sendMessage("§eDebug: §c" + plugin.isDebug());
                sender.sendMessage("§eSave debounce: §c" + plugin.getSaveDebounceTicks() + " ticks");
                sender.sendMessage("§eUUID đang track: §c" + plugin.getDataManager().getMaces().size());
            }
            case "setmax" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cSử dụng: /mace setmax <số>");
                    return;
                }
                try {
                    int val = Integer.parseInt(args[1]);
                    if (val < 0) throw new NumberFormatException();
                    plugin.setMaxMaces(val);
                    sender.sendMessage(plugin.getMessage("setmax-success",
                            "%max%", String.valueOf(val)));
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§cGiá trị không hợp lệ: " + args[1]);
                }
            }
            default -> sender.sendMessage(plugin.getMessage("usage-mace"));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (!sender.hasPermission(PERM)) return Collections.emptyList();

        String cmd = command.getName().toLowerCase();

        if (cmd.equals("mace") && args.length == 1) {
            return Stream.of("reload", "info", "setmax")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (cmd.equals("mace") && args.length == 2 && args[0].equalsIgnoreCase("setmax")) {
            return Stream.of("1", "5", "8", "16", "32", "64")
                    .filter(s -> s.startsWith(args[1]))
                    .collect(Collectors.toList());
        }
        if (cmd.equals("macescan") && args.length == 1) {
            String partial = args[0].toLowerCase();
            List<String> names = new ArrayList<>();
            names.add("all");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) names.add(p.getName());
            }
            return names;
        }
        return Collections.emptyList();
    }
}
