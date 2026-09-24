package dev.ghostspear;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * Every tick: keeps ghost mode running, and if a player is holding right-click
 * (charging) with the Ghost Spear while in ghost mode, anything right in front
 * of them gets "Soul Pierced" for massive damage.
 */
public final class ChargeTask extends BukkitRunnable {

    private final GhostSpearPlugin plugin;

    public ChargeTask(GhostSpearPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        Settings s = plugin.settings();
        PlayerState state = plugin.playerState();
        int now = Bukkit.getCurrentTick();

        plugin.ghostModes().tick();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isHandRaised()) continue;
            if (!plugin.spearItem().is(player.getActiveItem())) continue;
            if (!player.hasPermission("ghostspear.use")) continue;
            if (player.getGameMode() == GameMode.SPECTATOR) continue;

            UUID id = player.getUniqueId();
            if (s.chargeRequireGhost && !plugin.ghostModes().isGhosted(id)) continue;
            if (!state.canPierce(id, now)) continue;

            LivingEntity target = findTarget(player, s);
            if (target == null) continue;

            pierce(player, target, s, state, now);
        }
    }

    private LivingEntity findTarget(Player player, Settings s) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();

        RayTraceResult hit = world.rayTraceEntities(eye, direction, s.reach, s.hitboxSize,
                entity -> isValidTarget(player, entity, s));
        if (hit == null || !(hit.getHitEntity() instanceof LivingEntity target)) {
            return null;
        }

        // Don't pierce through walls
        double distance = hit.getHitPosition().distance(eye.toVector());
        RayTraceResult wall = world.rayTraceBlocks(eye, direction, distance, FluidCollisionMode.NEVER, true);
        if (wall != null && wall.getHitBlock() != null) {
            return null;
        }
        return target;
    }

    private boolean isValidTarget(Player attacker, Entity entity, Settings s) {
        if (entity.equals(attacker)) return false;
        if (!(entity instanceof LivingEntity living)) return false;
        if (living.isDead() || !living.isValid()) return false;
        if (entity instanceof ArmorStand) return false;
        if (plugin.ghostSpawner().isGhost(entity)) return false;

        if (entity instanceof Player target) {
            if (!s.hitPlayers) return false;
            GameMode gm = target.getGameMode();
            return gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR;
        }
        return s.hitMobs;
    }

    private void pierce(Player player, LivingEntity target, Settings s, PlayerState state, int now) {
        UUID id = player.getUniqueId();
        state.markPierce(id, now);

        // Skip the usual "just got hit" immunity so the pierce always lands
        target.setNoDamageTicks(0);
        // Goes through the normal damage event, so PvP-off areas / region plugins still block it.
        // It also counts as an attack, so it reveals you (if end-on-attack is on).
        // The spike particles + warden sound are handled by SpearListener when the hit lands.
        plugin.soulBurst().setPiercingTarget(target.getUniqueId());
        try {
            target.damage(s.chargeDamage, player);
        } finally {
            plugin.soulBurst().setPiercingTarget(null);
        }

        player.sendActionBar(SpearItem.text(s.msgPierce));
    }
}
