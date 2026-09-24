package com.example.macelimiter;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
                plugin.getLogger().info(sender.getName() + " đã reset toàn bộ dữ liệu Mace.");
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

            case "mace" -> handleMaceCommand(sender, args);

            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Xử lý /mace <subcommand>.
     */
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
            default -> sender.sendMessage(plugin.getMessage("usage-mace"));
        }
    }

    // ============================================================
    // TAB COMPLETER
    // ============================================================

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (!sender.hasPermission(PERM)) return Collections.emptyList();

        if (command.getName().equalsIgnoreCase("mace") && args.length == 1) {
            String partial = args[0].toLowerCase();
            return Stream.of("reload")
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
