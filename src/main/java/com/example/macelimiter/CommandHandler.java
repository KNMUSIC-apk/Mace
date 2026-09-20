package com.example.macelimiter;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

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
                plugin.getLogger().info(sender.getName() + " đã reset toàn bộ dữ liệu Mace.");
            }

            case "macesync" -> {
                sender.sendMessage(plugin.getMessage("sync-start"));
                DataManager.SyncResult r = plugin.getDataManager().syncWithServer();
                int cur = plugin.getDataManager().getCount();
                sender.sendMessage(plugin.getMessage("sync-success",
                        "%fake%", String.valueOf(r.fakeRemoved),
                        "%tagged%", String.valueOf(r.tagged),
                        "%orphans%", String.valueOf(r.orphans),
                        "%current%", String.valueOf(cur),
                        "%max%", String.valueOf(plugin.getMaxMaces())));
            }

            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        return Collections.emptyList();
    }
}
