package dev.ghostspear;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ghost mode: the player turns invisible and a decoy copy of them is left standing
 * exactly where they were. Ends when they swing the spear again, attack something,
 * or (optionally) when their decoy gets hit or time runs out.
 */
public final class GhostModeManager {

    public enum EndReason { TOGGLE, ATTACK, DECOY_HIT, TIMEOUT, DECOY_GONE, DEATH, QUIT, SHUTDOWN }

    private static final class Session {
        final Mannequin decoy;
        final UUID decoyId;
        final boolean hideMode;
        final PotionEffect previousInvisibility; // invisibility they already had, if any
        final int startTick;

        Session(Mannequin decoy, boolean hideMode, PotionEffect previousInvisibility, int startTick) {
            this.decoy = decoy;
            this.decoyId = decoy.getUniqueId();
            this.hideMode = hideMode;
            this.previousInvisibility = previousInvisibility;
            this.startTick = startTick;
        }
    }

    private final GhostSpearPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, UUID> decoyOwners = new HashMap<>();

    public GhostModeManager(GhostSpearPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isGhosted(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    /** @return the owner's UUID if this entity is someone's active decoy, else null. */
    public UUID ownerOfDecoy(Entity entity) {
        return decoyOwners.get(entity.getUniqueId());
    }

    // ------------------------------------------------------------------ on

    public boolean activate(Player player) {
        if (isGhosted(player.getUniqueId())) {
            return true;
        }
        Settings s = plugin.settings();

        Mannequin decoy = plugin.ghostSpawner().spawnDecoy(player);
        if (decoy == null) {
            player.sendActionBar(SpearItem.text(s.msgDecoyFailed));
            return false;
        }

        int now = Bukkit.getCurrentTick();
        PotionEffect previous = player.getPotionEffect(PotionEffectType.INVISIBILITY);
        Session session = new Session(decoy, s.hideMode, previous, now);

        if (session.hideMode) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(player)) {
                    viewer.hidePlayer(plugin, player);
                }
            }
        } else {
            player.addPotionEffect(ghostInvisibility());
            sendEquipment(player, emptyEquipment());
        }

        player.getPersistentDataContainer().set(plugin.ghostModeKey(), PersistentDataType.BYTE, (byte) 1);
        sessions.put(player.getUniqueId(), session);
        decoyOwners.put(session.decoyId, player.getUniqueId());
        plugin.playerState().markToggle(player.getUniqueId(), now);

        // Only the ghost themself hears/sees this - nobody else gets a hint
        player.playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 1.4f);
        player.sendActionBar(SpearItem.text(s.msgGhostOn));
        return true;
    }

    // ----------------------------------------------------------------- off

    public void deactivate(Player player, EndReason reason) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        decoyOwners.remove(session.decoyId);
        Settings s = plugin.settings();
        int now = Bukkit.getCurrentTick();

        removeDecoy(session, s.decoyPoofOnEnd && reason != EndReason.SHUTDOWN);

        if (session.hideMode) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(player)) {
                    viewer.showPlayer(plugin, player);
                }
            }
        } else {
            restoreInvisibility(player, session, now);
            sendEquipment(player, realEquipment(player));
        }

        player.getPersistentDataContainer().remove(plugin.ghostModeKey());
        plugin.playerState().markToggle(player.getUniqueId(), now);

        if (reason == EndReason.QUIT || reason == EndReason.SHUTDOWN || reason == EndReason.DEATH) {
            return;
        }
        String msg = switch (reason) {
            case ATTACK -> s.msgGhostOffAttack;
            case DECOY_HIT -> s.msgGhostOffDecoyHit;
            case TIMEOUT -> s.msgGhostOffTimeout;
            case DECOY_GONE -> s.msgGhostOffDecoyGone;
            default -> s.msgGhostOff;
        };
        player.sendActionBar(SpearItem.text(msg));
        player.playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 0.8f);
    }

    public void deactivateAll(EndReason reason) {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                deactivate(p, reason);
            } else {
                Session session = sessions.remove(id);
                decoyOwners.remove(session.decoyId);
                removeDecoy(session, false);
            }
        }
    }

    // ---------------------------------------------------------------- tick

    /** Runs every tick: keeps ghosts hidden, handles timeouts and missing decoys. */
    public void tick() {
        if (sessions.isEmpty()) {
            return;
        }
        Settings s = plugin.settings();
        int now = Bukkit.getCurrentTick();

        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Session session = sessions.get(id);
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                sessions.remove(id);
                decoyOwners.remove(session.decoyId);
                removeDecoy(session, false);
                continue;
            }
            if (!session.decoy.isValid()) {
                deactivate(player, EndReason.DECOY_GONE);
                continue;
            }
            if (s.maxDurationTicks > 0 && now - session.startTick >= s.maxDurationTicks) {
                deactivate(player, EndReason.TIMEOUT);
                continue;
            }
            if (!session.hideMode) {
                // Milk or another plugin could remove it - put it back
                PotionEffect current = player.getPotionEffect(PotionEffectType.INVISIBILITY);
                if (current == null || !current.isInfinite()) {
                    player.addPotionEffect(ghostInvisibility());
                }
                // Keep armor + held items hidden from everyone else (the game re-sends them when they change)
                sendEquipment(player, emptyEquipment());
            }
            if (s.showStatus && (now - session.startTick) % 20 == 10) {
                player.sendActionBar(SpearItem.text(s.msgGhostStatus));
            }
        }
    }

    /** New players joining shouldn't be able to see anyone who's hidden. */
    public void handleJoin(Player joined) {
        // Clean up if the server crashed while this player was a ghost
        if (joined.getPersistentDataContainer().has(plugin.ghostModeKey(), PersistentDataType.BYTE)) {
            PotionEffect current = joined.getPotionEffect(PotionEffectType.INVISIBILITY);
            if (current != null && current.isInfinite()) {
                joined.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
            joined.getPersistentDataContainer().remove(plugin.ghostModeKey());
        }
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            if (!entry.getValue().hideMode) continue;
            Player ghost = Bukkit.getPlayer(entry.getKey());
            if (ghost != null && !ghost.equals(joined)) {
                joined.hidePlayer(plugin, ghost);
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private void removeDecoy(Session session, boolean poof) {
        Mannequin decoy = session.decoy;
        if (decoy.isValid()) {
            Location at = decoy.getLocation();
            decoy.remove();
            if (poof) {
                plugin.ghostSpawner().poof(at);
            }
        } else {
            Entity e = Bukkit.getEntity(session.decoyId);
            if (e != null) e.remove();
        }
    }

    private void restoreInvisibility(Player player, Session session, int now) {
        PotionEffect prev = session.previousInvisibility;
        if (prev != null && prev.isInfinite()) {
            return; // they already had permanent invisibility from something else - leave it
        }
        PotionEffect current = player.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (current != null && current.isInfinite()) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
        if (prev != null) {
            int remaining = prev.getDuration() - (now - session.startTick);
            if (remaining > 0) {
                player.addPotionEffect(prev.withDuration(remaining));
            }
        }
    }

    private static PotionEffect ghostInvisibility() {
        // no particles, no icon, so nothing gives it away
        return new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false);
    }

    private static Map<EquipmentSlot, ItemStack> emptyEquipment() {
        Map<EquipmentSlot, ItemStack> map = new EnumMap<>(EquipmentSlot.class);
        map.put(EquipmentSlot.HAND, ItemStack.empty());
        map.put(EquipmentSlot.OFF_HAND, ItemStack.empty());
        map.put(EquipmentSlot.HEAD, ItemStack.empty());
        map.put(EquipmentSlot.CHEST, ItemStack.empty());
        map.put(EquipmentSlot.LEGS, ItemStack.empty());
        map.put(EquipmentSlot.FEET, ItemStack.empty());
        return map;
    }

    private static Map<EquipmentSlot, ItemStack> realEquipment(Player player) {
        PlayerInventory inv = player.getInventory();
        Map<EquipmentSlot, ItemStack> map = new EnumMap<>(EquipmentSlot.class);
        map.put(EquipmentSlot.HAND, orEmpty(inv.getItemInMainHand()));
        map.put(EquipmentSlot.OFF_HAND, orEmpty(inv.getItemInOffHand()));
        map.put(EquipmentSlot.HEAD, orEmpty(inv.getHelmet()));
        map.put(EquipmentSlot.CHEST, orEmpty(inv.getChestplate()));
        map.put(EquipmentSlot.LEGS, orEmpty(inv.getLeggings()));
        map.put(EquipmentSlot.FEET, orEmpty(inv.getBoots()));
        return map;
    }

    private static ItemStack orEmpty(ItemStack item) {
        return item == null ? ItemStack.empty() : item;
    }

    /** Sends a (fake or real) equipment view of this player to everyone else in their world. */
    private static void sendEquipment(Player player, Map<EquipmentSlot, ItemStack> items) {
        for (Player viewer : player.getWorld().getPlayers()) {
            if (!viewer.equals(player)) {
                viewer.sendEquipmentChange(player, items);
            }
        }
    }
}
