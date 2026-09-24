package dev.ghostspear;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/** Snapshot of config.yml values. Rebuilt on /ghostspear reload. */
public final class Settings {

    // item
    public final Material material;
    public final String name;
    public final List<String> lore;
    public final boolean unbreakable;
    public final boolean glint;

    // ghost mode
    public final boolean hideMode;
    public final int toggleCooldownTicks;
    public final int maxDurationTicks;
    public final boolean endOnAttack;
    public final boolean showStatus;

    // decoy
    public final boolean decoyShowName;
    public final boolean decoyCopySneaking;
    public final boolean decoyReactToHits;
    public final boolean decoyRevealWhenHit;
    public final boolean decoyPoofOnEnd;
    public final boolean shareDamage;
    public final double sharedDamageMultiplier;
    public final Set<String> sharedDamageIgnore;

    // charge
    public final boolean chargeRequireGhost;
    public final double chargeDamage;
    public final double pierceSetHealth;
    public final boolean pierceRemoveAbsorption;
    public final boolean pierceIgnoreShields;
    public final double reach;
    public final double hitboxSize;
    public final boolean hitPlayers;
    public final boolean hitMobs;

    // hit effect
    public final boolean burstEnabled;
    public final double sphereRadius;
    public final int spherePoints;
    public final int spikeCount;
    public final double spikeLength;
    public final double spikeThickness;
    public final int spikeTicks;
    public final boolean shockwave;
    public final double density;
    public final boolean orbEnabled;
    public final double orbSpeed;
    public final double orbHeight;
    public final double orbRadius;
    public final boolean bigSpear;
    public final double bigSpearLength;
    public final int bigSpearTicks;

    // death flag
    public final boolean flagEnabled;
    public final boolean flagPlayersOnly;
    public final double flagHeight;
    public final int flagDurationSeconds;
    public final int flagMaxActive;

    // finisher
    public final boolean finisherEnabled;
    public final boolean finisherPlayersOnly;
    public final float finisherVolume;
    public final float finisherPitch;

    // messages
    public final String msgGhostOn;
    public final String msgGhostOff;
    public final String msgGhostOffAttack;
    public final String msgGhostOffDecoyHit;
    public final String msgGhostOffTimeout;
    public final String msgGhostOffDecoyGone;
    public final String msgGhostStatus;
    public final String msgCooldown;
    public final String msgDecoyFailed;
    public final String msgPierce;
    public final String msgDecoyHit;

    public Settings(FileConfiguration c, Logger log) {
        material = resolveMaterial(
                c.getString("item.material", "NETHERITE_SPEAR"),
                c.getString("item.fallback-material", "TRIDENT"),
                log);
        name = c.getString("item.name", "<aqua>Ghost Spear");
        lore = c.getStringList("item.lore");
        unbreakable = c.getBoolean("item.unbreakable", true);
        glint = c.getBoolean("item.glint", true);

        String mode = c.getString("ghost-mode.invisibility", "HIDE").trim().toUpperCase(Locale.ROOT);
        if (!mode.equals("POTION") && !mode.equals("HIDE")) {
            log.warning("ghost-mode.invisibility must be HIDE or POTION (got '" + mode + "'), using HIDE.");
        }
        hideMode = !mode.equals("POTION");
        toggleCooldownTicks = Math.max(0, c.getInt("ghost-mode.toggle-cooldown-ticks", 20));
        maxDurationTicks = Math.max(0, c.getInt("ghost-mode.max-duration-ticks", 0));
        endOnAttack = c.getBoolean("ghost-mode.end-on-attack", true);
        showStatus = c.getBoolean("ghost-mode.show-status", true);

        decoyShowName = c.getBoolean("decoy.show-name", true);
        decoyCopySneaking = c.getBoolean("decoy.copy-sneaking", true);
        decoyReactToHits = c.getBoolean("decoy.react-to-hits", true);
        decoyRevealWhenHit = c.getBoolean("decoy.reveal-when-hit", false);
        decoyPoofOnEnd = c.getBoolean("decoy.poof-on-end", true);
        shareDamage = c.getBoolean("decoy.share-damage", true);
        sharedDamageMultiplier = Math.max(0, c.getDouble("decoy.shared-damage-multiplier", 1.0));
        Set<String> ignore = new HashSet<>();
        List<String> ignoreList = c.isList("decoy.shared-damage-ignore")
                ? c.getStringList("decoy.shared-damage-ignore")
                : List.of("FALL", "VOID", "SUFFOCATION", "CRAMMING", "DROWNING", "STARVATION", "KILL", "WORLD_BORDER", "SUICIDE");
        for (String cause : ignoreList) {
            ignore.add(cause.trim().toUpperCase(Locale.ROOT));
        }
        sharedDamageIgnore = Set.copyOf(ignore);

        chargeRequireGhost = c.getBoolean("charge.require-ghost-mode", true);
        chargeDamage = c.getDouble("charge.damage", 60.0);
        pierceSetHealth = c.getDouble("charge.set-health-to", 1.0);
        pierceRemoveAbsorption = c.getBoolean("charge.remove-absorption", true);
        pierceIgnoreShields = c.getBoolean("charge.ignore-shields", true);
        reach = c.getDouble("charge.reach", 3.5);
        hitboxSize = c.getDouble("charge.hitbox-size", 0.6);
        hitPlayers = c.getBoolean("charge.hit-players", true);
        hitMobs = c.getBoolean("charge.hit-mobs", true);

        burstEnabled = c.getBoolean("hit-effect.enabled", true);
        sphereRadius = Math.max(0.5, c.getDouble("hit-effect.sphere-radius", 3.5));
        spherePoints = Math.max(20, c.getInt("hit-effect.sphere-points", 320));
        spikeCount = Math.max(0, c.getInt("hit-effect.spikes", 32));
        spikeLength = Math.max(1.0, c.getDouble("hit-effect.spike-length", 8.0));
        spikeThickness = Math.max(0.1, c.getDouble("hit-effect.spike-thickness", 0.6));
        spikeTicks = Math.max(1, c.getInt("hit-effect.spike-ticks", 7));
        shockwave = c.getBoolean("hit-effect.shockwave", true);
        density = Math.max(0.2, Math.min(3.0, c.getDouble("hit-effect.density", 1.0)));
        orbEnabled = c.getBoolean("hit-effect.orb", true);
        orbSpeed = Math.max(0.1, c.getDouble("hit-effect.orb-speed", 0.8));
        orbHeight = Math.max(1.0, c.getDouble("hit-effect.orb-height", 8.0));
        orbRadius = Math.max(0.1, c.getDouble("hit-effect.orb-size", 0.55));
        bigSpear = c.getBoolean("hit-effect.giant-spear", true);
        bigSpearLength = Math.max(3.0, c.getDouble("hit-effect.giant-spear-length", 16.0));
        bigSpearTicks = Math.max(4, c.getInt("hit-effect.giant-spear-ticks", 40));

        flagEnabled = c.getBoolean("flag.enabled", true);
        flagPlayersOnly = c.getBoolean("flag.players-only", false);
        flagHeight = c.getDouble("flag.height", 22.0);
        flagDurationSeconds = Math.max(0, c.getInt("flag.duration-seconds", 60));
        flagMaxActive = Math.max(1, c.getInt("flag.max-active", 10));

        finisherEnabled = c.getBoolean("finisher.enabled", true);
        finisherPlayersOnly = c.getBoolean("finisher.players-only", false);
        finisherVolume = (float) c.getDouble("finisher.volume", 2.0);
        finisherPitch = (float) c.getDouble("finisher.pitch", 1.0);

        msgGhostOn = c.getString("messages.ghost-on", "<aqua>Ghost mode on.");
        msgGhostOff = c.getString("messages.ghost-off", "<gray>Ghost mode off.");
        msgGhostOffAttack = c.getString("messages.ghost-off-attack", "<red>You attacked - you've been revealed!");
        msgGhostOffDecoyHit = c.getString("messages.ghost-off-decoy-hit", "<red>Someone hit your decoy - you've been revealed!");
        msgGhostOffTimeout = c.getString("messages.ghost-off-timeout", "<gray>Ghost mode wore off.");
        msgGhostOffDecoyGone = c.getString("messages.ghost-off-decoy-gone", "<gray>Your decoy disappeared, so ghost mode ended.");
        msgGhostStatus = c.getString("messages.ghost-status", "<dark_aqua>Ghost mode");
        msgCooldown = c.getString("messages.cooldown", "<gray>Ghost mode ready in <white><seconds>s");
        msgDecoyFailed = c.getString("messages.decoy-failed", "<red>Couldn't create your decoy.");
        msgPierce = c.getString("messages.pierce", "<dark_aqua>Soul Pierce!");
        msgDecoyHit = c.getString("messages.decoy-hit", "<red>Your decoy is being attacked!");
    }

    private static Material resolveMaterial(String primary, String fallback, Logger log) {
        Material m = primary == null ? null : Material.matchMaterial(primary);
        if (m != null && m.isItem()) {
            return m;
        }
        log.warning("item.material '" + primary + "' isn't a valid item on this server, using fallback '" + fallback + "'.");
        Material f = fallback == null ? null : Material.matchMaterial(fallback);
        return (f != null && f.isItem()) ? f : Material.TRIDENT;
    }
}
