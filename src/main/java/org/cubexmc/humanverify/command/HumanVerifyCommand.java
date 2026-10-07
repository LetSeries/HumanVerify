package org.cubexmc.humanverify.command;

import org.cubexmc.humanverify.HumanVerifyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class HumanVerifyCommand implements CommandExecutor, TabCompleter {
    private final HumanVerifyPlugin plugin;

    public HumanVerifyCommand(HumanVerifyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("verify")) {
            if (args.length >= 2) return verifyOther(sender, args[1]);
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "控制台请使用 /humanverify verify <玩家>。");
                return true;
            }
            // Self-service verify requires the declared command permission.
            if (!player.hasPermission("humanverify.use")) {
                player.sendMessage(ChatColor.RED + "你没有权限使用此命令。");
                return true;
            }
            // Bypass permission: inform and skip
            if (player.hasPermission("humanverify.bypass")) {
                player.sendMessage(plugin.message("bypassed"));
                return true;
            }
            if (plugin.isVerified(player)) {
                sender.sendMessage(plugin.message("already-verified"));
                return true;
            }
            // Non-force call reuses the active session when one exists
            // (no attempt reset); terminal states are messaged by the plugin itself.
            plugin.requestVerification(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("humanverify.admin")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此操作。");
                return true;
            }
            plugin.reloadPluginConfig();
            sender.sendMessage(plugin.message("reloaded"));
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "/humanverify verify [玩家]");
        sender.sendMessage(ChatColor.YELLOW + "/humanverify reload");
        return true;
    }

    private boolean verifyOther(CommandSender sender, String playerName) {
        if (!sender.hasPermission("humanverify.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限验证其他玩家。");
            return true;
        }
        // Case-insensitive lookup: getPlayerExact is case-sensitive on some
        // server implementations, so match online players ourselves first.
        Player target = null;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(playerName)) {
                target = online;
                break;
            }
        }
        if (target == null) {
            target = Bukkit.getPlayerExact(playerName);
        }
        if (target == null) {
            sender.sendMessage(plugin.message("player-not-found").replace("{player}", playerName));
            return true;
        }
        // Admin-triggered verification should show the challenge, not bypass it.
        plugin.revokeVerification(target.getUniqueId());
        plugin.requestVerification(target, true);
        sender.sendMessage(plugin.message("verification-started").replace("{player}", target.getName()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = List.of("verify", "reload");
            return partial(values, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("verify") && sender.hasPermission("humanverify.admin")) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            return partial(names, args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> partial(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
