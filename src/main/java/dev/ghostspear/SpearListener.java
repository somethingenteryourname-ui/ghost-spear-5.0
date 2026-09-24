package dev.ghostspear;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public final class SpearListener implements Listener {

    private final GhostSpearPlugin plugin;

    public SpearListener(GhostSpearPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------ swing = toggle ghost mode

    /** Right-clicking a block (door, chest...) also swings the arm - remember it so that doesn't toggle. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRightClickBlock(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            plugin.playerState().markBlockUse(event.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.spearItem().is(player.getInventory().getItemInMainHand())) {
            return;
        }
        if (!player.hasPermission("ghostspear.use") || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (player.isHandRaised()) {
            return; // charging, not swinging
        }

        Settings s = plugin.settings();
        PlayerState state = plugin.playerState();
        GhostModeManager ghosts = plugin.ghostModes();
        UUID id = player.getUniqueId();
        int now = Bukkit.getCurrentTick();

        // A swing that was really an attack or a door click shouldn't toggle anything
        if (state.attackedRecently(id, now) || state.usedBlockRecently(id, now)) {
            return;
        }

        int left = state.toggleCooldownLeft(id, now, s.toggleCooldownTicks);
        if (left > 0) {
            String seconds = String.format("%.1f", left / 20.0);
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    s.msgCooldown, Placeholder.unparsed("seconds", seconds)));
            return;
        }

        if (ghosts.isGhosted(id)) {
            ghosts.deactivate(player, GhostModeManager.EndReason.TOGGLE);
        } else {
            ghosts.activate(player);
        }
    }

    // ------------------------------------------------ attacking reveals you

    /** Remember every attack attempt (even blocked ones) so the swing that comes with it is ignored. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttackAttempt(EntityDamageByEntityEvent event) {
        Player attacker = attackerOf(event.getDamager());
        if (attacker != null) {
            plugin.playerState().markAttack(attacker.getUniqueId(), Bukkit.getCurrentTick());
        }
    }

    /**
     * A hit that lands while you're a ghost (or a Soul Pierce) makes the soul spike burst,
     * gets watched for a kill / totem pop (warden sound), and then reveals you.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Player attacker = attackerOf(event.getDamager());
        if (attacker == null) {
            return;
        }
        Entity victim = event.getEntity();
        if (!(victim instanceof LivingEntity target) || plugin.ghostSpawner().isGhost(victim)) {
            return;
        }
        boolean ghosted = plugin.ghostModes().isGhosted(attacker.getUniqueId());
        boolean soulPierce = plugin.soulBurst().isPiercing(target.getUniqueId());
        if (!ghosted && !soulPierce) {
            return;
        }

        plugin.soulBurst().play(target, attacker);

        if (ghosted && plugin.settings().endOnAttack) {
            plugin.ghostModes().deactivate(attacker, GhostModeManager.EndReason.ATTACK);
        }
    }

    /**
     * Soul Pierce = guaranteed pop: drop them to 1 health (and strip golden-apple hearts / shield block)
     * right before the 30-heart hit lands. Runs last, so if PvP is off or a region plugin
     * cancelled the hit, nothing is changed.
     */
    @SuppressWarnings("deprecation") // DamageModifier is old but still the only way to undo absorption/blocking
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulPierceLands(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        if (!plugin.soulBurst().isPiercing(target.getUniqueId())) {
            return;
        }
        Settings s = plugin.settings();

        if (s.pierceSetHealth > 0 && target.getHealth() > s.pierceSetHealth) {
            target.setHealth(s.pierceSetHealth);
        }
        if (s.pierceRemoveAbsorption) {
            target.setAbsorptionAmount(0);
            if (event.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)) {
                event.setDamage(EntityDamageEvent.DamageModifier.ABSORPTION, 0);
            }
        }
        if (s.pierceIgnoreShields && event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
            event.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0);
        }
    }

    /** Killed right after a ghost hit -> warden death sound + white flag. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        plugin.soulBurst().onKilledOrPopped(event.getEntity(), true);
    }

    /** Totem popped right after a ghost hit -> warden death sound. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotemPop(EntityResurrectEvent event) {
        plugin.soulBurst().onKilledOrPopped(event.getEntity(), false);
    }

    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    // ------------------------------------------------ hitting the decoy hurts the real you

    /** The player currently receiving damage passed through from their decoy (their knockback is skipped). */
    private UUID sharingDamageTo;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDecoyDamaged(EntityDamageEvent event) {
        Entity decoy = event.getEntity();
        if (!plugin.ghostSpawner().isGhost(decoy)) {
            return;
        }
        // The decoy itself never loses health or dies
        event.setCancelled(true);

        Settings s = plugin.settings();
        UUID ownerId = plugin.ghostModes().ownerOfDecoy(decoy);
        Player owner = ownerId == null ? null : Bukkit.getPlayer(ownerId);
        EntityDamageByEntityEvent byEntity = event instanceof EntityDamageByEntityEvent e ? e : null;
        Player attacker = byEntity == null ? null : attackerOf(byEntity.getDamager());
        boolean ownerHitOwnDecoy = owner != null && owner.equals(attacker);

        // 1) Act like a real player getting hit
        if (byEntity != null && s.decoyReactToHits && decoy instanceof LivingEntity living) {
            Location from = byEntity.getDamager().getLocation();
            Location to = living.getLocation();
            living.playHurtAnimation(0f);
            living.getWorld().playSound(to, Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f);
            double dx = from.getX() - to.getX();
            double dz = from.getZ() - to.getZ();
            if (dx * dx + dz * dz > 1.0E-4) {
                living.knockback(0.4, dx, dz);
            }
        }

        // 2) Pass the damage on to the real player - they can die from it
        if (owner != null && !ownerHitOwnDecoy && s.shareDamage
                && !s.sharedDamageIgnore.contains(event.getCause().name())) {
            double amount = event.getDamage() * s.sharedDamageMultiplier;
            if (amount > 0) {
                sharingDamageTo = owner.getUniqueId();
                try {
                    // Same damage type as the decoy got, so the owner's own armor/enchants apply
                    // and death messages read normally ("was slain by ...", "was shot by ...")
                    owner.damage(amount, event.getDamageSource());
                } finally {
                    sharingDamageTo = null;
                }
                if (!owner.isDead() && plugin.ghostModes().isGhosted(owner.getUniqueId())) {
                    owner.sendActionBar(SpearItem.text(s.msgDecoyHit));
                }
            }
        }

        // 3) Optionally, a hit on the decoy also reveals you
        if (byEntity != null && s.decoyRevealWhenHit && owner != null && !ownerHitOwnDecoy) {
            plugin.ghostModes().deactivate(owner, GhostModeManager.EndReason.DECOY_HIT);
        }
    }

    /** Damage passed through from the decoy shouldn't also shove the real (far away) player around. */
    @EventHandler(ignoreCancelled = true)
    public void onSharedDamageKnockback(EntityKnockbackEvent event) {
        if (sharingDamageTo != null && sharingDamageTo.equals(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------ join / quit / death

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.ghostModes().handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.ghostModes().deactivate(player, GhostModeManager.EndReason.QUIT);
        plugin.playerState().clear(player.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        plugin.ghostModes().deactivate(event.getEntity(), GhostModeManager.EndReason.DEATH);
    }
}
