package dev.ghostspear;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** Creates and recognises the Ghost Spear item (tagged with persistent data, so renaming it doesn't break it). */
public final class SpearItem {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final GhostSpearPlugin plugin;

    public SpearItem(GhostSpearPlugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack create() {
        Settings s = plugin.settings();
        ItemStack item = new ItemStack(s.material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.displayName(text(s.name));

        List<Component> lore = new ArrayList<>();
        for (String line : s.lore) {
            lore.add(text(line));
        }
        meta.lore(lore);

        meta.setUnbreakable(s.unbreakable);
        if (s.glint) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.getPersistentDataContainer().set(plugin.spearKey(), PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    public boolean is(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(plugin.spearKey(), PersistentDataType.BYTE);
    }

    static Component text(String miniMessage) {
        return MM.deserialize(miniMessage == null ? "" : miniMessage)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
