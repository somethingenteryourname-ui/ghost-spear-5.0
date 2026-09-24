package dev.ghostspear;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /ghostspear give [player]  |  /ghostspear reload  |  /ghostspear flagtime [time] */
public final class GhostSpearCommand implements CommandExecutor, TabCompleter {

    private final GhostSpearPlugin plugin;

    public GhostSpearCommand(GhostSpearPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/" + label + " give [player]  |  /" + label + " reload  |  /"
                    + label + " flagtime <time>", NamedTextColor.GRAY));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, label, args);
            case "reload" -> reload(sender);
            case "flagtime" -> flagTime(sender, label, args);
            default -> sender.sendMessage(Component.text("Unknown subcommand. Use give, reload or flagtime.", NamedTextColor.RED));
        }
        return true;
    }

    private void give(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("ghostspear.give")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player '" + args[1] + "' isn't online.", NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("From console use: /" + label + " give <player>", NamedTextColor.RED));
            return;
        }

        ItemStack spear = plugin.spearItem().create();
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(spear);
        for (ItemStack drop : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), drop);
        }

        target.sendMessage(Component.text("You received a Ghost Spear.", NamedTextColor.AQUA));
        if (sender != target) {
            sender.sendMessage(Component.text("Gave a Ghost Spear to " + target.getName() + ".", NamedTextColor.GREEN));
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("ghostspear.reload")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return;
        }
        plugin.reloadSettings();
        sender.sendMessage(Component.text("GhostSpear config reloaded. (Spears you already have keep their old name/lore.)", NamedTextColor.GREEN));
    }

    /** /ghostspear flagtime 90  |  90s  |  5m  |  1h  |  off */
    private void flagTime(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("ghostspear.flagtime")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            int current = plugin.settings().flagDurationSeconds;
            sender.sendMessage(Component.text("Death flags currently stay for " + describe(current)
                    + ". Change it with /" + label + " flagtime <time>  (examples: 30s, 2m, 1h, off)", NamedTextColor.GRAY));
            return;
        }
        Integer seconds = parseSeconds(args[1]);
        if (seconds == null) {
            sender.sendMessage(Component.text("Couldn't read '" + args[1] + "'. Try something like 45, 45s, 3m, 1h or off.",
                    NamedTextColor.RED));
            return;
        }
        if (seconds > 86400) {
            sender.sendMessage(Component.text("That's too long - the max is 24 hours.", NamedTextColor.RED));
            return;
        }
        plugin.getConfig().set("flag.duration-seconds", seconds);
        plugin.saveConfig();
        plugin.reloadSettings();
        sender.sendMessage(Component.text(seconds == 0
                ? "Death flags are now turned off."
                : "Death flags will now stay for " + describe(seconds) + ". (Flags already in the sky keep their old time.)",
                NamedTextColor.GREEN));
    }

    private static Integer parseSeconds(String input) {
        String in = input.trim().toLowerCase(Locale.ROOT);
        if (in.equals("off") || in.equals("none")) {
            return 0;
        }
        int multiplier = 1;
        if (in.endsWith("s")) {
            in = in.substring(0, in.length() - 1);
        } else if (in.endsWith("m")) {
            multiplier = 60;
            in = in.substring(0, in.length() - 1);
        } else if (in.endsWith("h")) {
            multiplier = 3600;
            in = in.substring(0, in.length() - 1);
        }
        try {
            double value = Double.parseDouble(in);
            if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
                return null;
            }
            return (int) Math.round(value * multiplier);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String describe(int seconds) {
        if (seconds <= 0) return "0 seconds (off)";
        if (seconds % 3600 == 0) return (seconds / 3600) + (seconds == 3600 ? " hour" : " hours");
        if (seconds % 60 == 0) return (seconds / 60) + (seconds == 60 ? " minute" : " minutes");
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            if (sender.hasPermission("ghostspear.give") && "give".startsWith(typed)) out.add("give");
            if (sender.hasPermission("ghostspear.reload") && "reload".startsWith(typed)) out.add("reload");
            if (sender.hasPermission("ghostspear.flagtime") && "flagtime".startsWith(typed)) out.add("flagtime");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("flagtime") && sender.hasPermission("ghostspear.flagtime")) {
            for (String option : List.of("30s", "60s", "2m", "5m", "10m", "off")) {
                if (option.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(option);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give") && sender.hasPermission("ghostspear.give")) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(typed)) out.add(p.getName());
            }
        }
        return out;
    }
}
