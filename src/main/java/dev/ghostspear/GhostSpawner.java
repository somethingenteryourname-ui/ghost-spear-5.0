package dev.ghostspear;

import com.destroystokyo.paper.ClientOption;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.function.Consumer;

/**
 * Spawns the decoy: a Mannequin (added in 1.21.9) that uses the player's own skin,
 * armor, held items, skin layers and facing direction, standing exactly where they were.
 */
public final class GhostSpawner {

    private final GhostSpearPlugin plugin;

    public GhostSpawner(GhostSpearPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return the decoy, or null if it couldn't be spawned. */
    public Mannequin spawnDecoy(Player player) {
        Settings s = plugin.settings();
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return null;
        }
        float bodyYaw = player.getBodyYaw();
        boolean sneaking = s.decoyCopySneaking && player.isSneaking();

        Consumer<Mannequin> setup = m -> {
            m.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
            m.setMainHand(player.getMainHand());
            try {
                m.setSkinParts(player.getClientOption(ClientOption.SKIN_PARTS));
            } catch (Throwable ignored) {
                // keep default skin layers
            }
            m.setDescription(null); // removes the "NPC" text under the name
            if (s.decoyShowName) {
                m.customName(Component.text(player.getName()));
                m.setCustomNameVisible(true);
            } else {
                m.setCustomNameVisible(false);
            }
            m.setRotation(loc.getYaw(), loc.getPitch());
            m.setBodyYaw(bodyYaw);
            m.setGravity(!player.isFlying());
            m.setImmovable(false); // so it can be knocked back like a real player
            m.setSilent(true);
            m.setPersistent(false); // never saved to disk, so no leftovers after a crash
            m.getPersistentDataContainer().set(plugin.ghostKey(), PersistentDataType.BYTE, (byte) 1);

            if (sneaking) {
                try {
                    if (Mannequin.validPoses().contains(Pose.SNEAKING)) {
                        m.setPose(Pose.SNEAKING, true);
                    }
                } catch (Throwable ignored) {
                    // standing is fine
                }
            }

            EntityEquipment eq = m.getEquipment();
            PlayerInventory inv = player.getInventory();
            if (eq != null) {
                eq.setItemInMainHand(inv.getItemInMainHand().clone());
                eq.setItemInOffHand(inv.getItemInOffHand().clone());
                eq.setHelmet(inv.getHelmet());
                eq.setChestplate(inv.getChestplate());
                eq.setLeggings(inv.getLeggings());
                eq.setBoots(inv.getBoots());
            }
        };

        try {
            return world.spawn(loc, Mannequin.class, setup);
        } catch (Throwable t) {
            plugin.getLogger().warning("Couldn't spawn decoy for " + player.getName() + ": " + t);
            return null;
        }
    }

    public void poof(Location at) {
        World world = at.getWorld();
        if (world == null) return;
        world.spawnParticle(Particle.SOUL, at.clone().add(0, 1, 0), 12, 0.3, 0.6, 0.3, 0.02);
        world.spawnParticle(Particle.CLOUD, at.clone().add(0, 1, 0), 8, 0.25, 0.5, 0.25, 0.01);
    }

    /** True for decoys spawned by this plugin. */
    public boolean isGhost(Entity entity) {
        return entity.getPersistentDataContainer().has(plugin.ghostKey(), PersistentDataType.BYTE);
    }
}
