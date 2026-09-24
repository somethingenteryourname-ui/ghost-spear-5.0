package dev.ghostspear;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What happens when a ghost lands a hit:
 *  1. A glowing orb shoots straight up out of the person who got hit.
 *  2. Where it stops: a big pulsing rainbow sphere, lots of dripstone-shaped spikes shooting out,
 *     a ground shockwave, and a giant netherite spear made of particles behind the target.
 *  3. If the hit killed them or popped their totem: the warden death sound.
 *     If it killed them: a white flag appears high in the sky above where they died.
 */
public final class SoulBurst {

    /** A spike color theme: base hue for the rainbow shading plus textured particles for its core. */
    private record Theme(float hue, Particle coreA, Particle coreB) { }

    private static final Theme[] THEMES = {
            new Theme(0.58f, Particle.SOUL_FIRE_FLAME, Particle.SCRAPE),                           // blue
            new Theme(0.07f, Particle.FLAME, Particle.WAX_ON),                                     // orange
            new Theme(0.14f, Particle.TRIAL_SPAWNER_DETECTION, Particle.WAX_ON),                   // yellow
            new Theme(0.50f, Particle.GLOW, Particle.TRIAL_SPAWNER_DETECTION_OMINOUS),             // cyan
            new Theme(0.78f, Particle.SOUL_FIRE_FLAME, Particle.END_ROD),                          // purple
    };

    private static final Color BLUE = Color.fromRGB(0x3FA9FF);
    private static final Color ORANGE = Color.fromRGB(0xFF8A1F);
    private static final Color YELLOW = Color.fromRGB(0xFFE23D);

    // Netherite colors for the giant spear
    private static final Color NETH_DARK = Color.fromRGB(0x2B2425);
    private static final Color NETH_MID = Color.fromRGB(0x463C3D);
    private static final Color NETH_LIGHT = Color.fromRGB(0x716566);
    private static final Color NETH_EDGE = Color.fromRGB(0xA09192);
    private static final Color GLINT = Color.fromRGB(0xA46BFF);

    /** One ghost hit being tracked until its effect finishes. */
    private static final class Hit {
        final UUID targetId;
        final boolean targetIsPlayer;
        final int tick;
        final Vector away;           // horizontal direction from the attacker toward the target
        Location center;             // last known middle of the target's body
        Location feet;               // last known feet position
        boolean killed;
        boolean popped;
        boolean waitingForEffect;
        boolean finisherDone;

        Hit(LivingEntity target, int tick, Vector away) {
            this.targetId = target.getUniqueId();
            this.targetIsPlayer = target instanceof Player;
            this.tick = tick;
            this.away = away;
            update(target);
        }

        void update(LivingEntity target) {
            this.feet = target.getLocation();
            this.center = feet.clone().add(0, target.getHeight() * 0.55, 0);
        }
    }

    private final GhostSpearPlugin plugin;
    private final DeathFlags flags;
    private final Map<UUID, Hit> hits = new HashMap<>();
    private UUID piercingTarget;

    public SoulBurst(GhostSpearPlugin plugin, DeathFlags flags) {
        this.plugin = plugin;
        this.flags = flags;
    }

    // ---------------------------------------------------- Soul Pierce tracking

    /** Set while ChargeTask is dealing Soul Pierce damage, so the listener knows the hit is a pierce. */
    public void setPiercingTarget(UUID target) {
        this.piercingTarget = target;
    }

    public boolean isPiercing(UUID target) {
        return target != null && target.equals(piercingTarget);
    }

    // ---------------------------------------------------- entry points

    /** A ghost (or Soul Pierce) hit just landed on this target. */
    public void play(LivingEntity target, Player attacker) {
        Settings s = plugin.settings();
        int now = Bukkit.getCurrentTick();

        Vector away = target.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1.0E-4) {
            double a = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            away = new Vector(Math.cos(a), 0, Math.sin(a));
        }
        away.normalize();

        Hit hit = new Hit(target, now, away);
        hits.put(hit.targetId, hit);

        if (!s.burstEnabled) {
            // No visuals: finisher/flag happen right at death (see onKilledOrPopped). Forget the hit shortly after.
            hit.waitingForEffect = false;
            Bukkit.getScheduler().runTaskLater(plugin, () -> hits.remove(hit.targetId, hit), 5L);
            return;
        }

        hit.waitingForEffect = true;
        if (s.orbEnabled) {
            launchOrb(hit);
        } else {
            // Wait one tick so a death from this hit is already known
            Bukkit.getScheduler().runTaskLater(plugin, () -> arrive(hit, hit.center), 1L);
        }
    }

    /** Called on death (died = true) or totem pop (died = false). */
    public void onKilledOrPopped(LivingEntity entity, boolean died) {
        Hit hit = hits.get(entity.getUniqueId());
        if (hit == null || Bukkit.getCurrentTick() - hit.tick > 1) {
            return;
        }
        if (died) {
            hit.killed = true;
        } else {
            hit.popped = true;
        }
        hit.update(entity);
        if (!hit.waitingForEffect) {
            finisher(hit, hit.feet);
        }
    }

    // ---------------------------------------------------- 1) the orb

    /** The orb shoots straight up out of the person who got hit, then the explosion happens where it stops. */
    private void launchOrb(Hit hit) {
        Settings s = plugin.settings();
        final Location from = hit.center.clone();
        World world = from.getWorld();
        if (world == null) {
            arrive(hit, from);
            return;
        }

        // Stop under a ceiling instead of flying through the roof
        double height = s.orbHeight;
        RayTraceResult ceiling = world.rayTraceBlocks(from, new Vector(0, 1, 0), height, FluidCollisionMode.NEVER, true);
        if (ceiling != null) {
            height = Math.max(1.0, ceiling.getHitPosition().getY() - from.getY() - 1.0);
        }
        final double rise = height;
        final int travelTicks = (int) Math.max(6, Math.min(40, Math.ceil(rise / s.orbSpeed)));

        world.playSound(from, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 1.6f);
        world.playSound(from, Sound.ENTITY_BREEZE_SHOOT, 1.0f, 0.8f);
        world.spawnParticle(Particle.SOUL, from, 30, 0.3, 0.5, 0.3, 0.05, null, true);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                double progress = (double) t / travelTicks;
                double eased = 1 - (1 - progress) * (1 - progress);   // fast at first, slowing near the top
                Location pos = from.clone().add(0, rise * eased, 0);
                drawOrb(world, pos, t, s);

                if (t >= travelTicks) {
                    cancel();
                    arrive(hit, pos);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static void drawOrb(World world, Location pos, int t, Settings s) {
        double r = s.orbRadius;
        int points = Math.max(12, (int) (40 * s.density));
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < points; i++) {
            double y = 1 - (i / (double) (points - 1)) * 2;
            double ring = Math.sqrt(1 - y * y);
            double theta = golden * i + t * 0.6;
            Location p = pos.clone().add(Math.cos(theta) * ring * r, y * r, Math.sin(theta) * ring * r);
            Color c = hsv(0.5f + (float) ((y + 1) * 0.12) + t * 0.03f, 0.45f, 1f);   // glowing white-cyan-blue
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(c, 1.3f), true);
        }
        world.spawnParticle(Particle.END_ROD, pos, 3, 0.08, 0.08, 0.08, 0.0, null, true);
        world.spawnParticle(Particle.GLOW, pos, 2, 0.15, 0.15, 0.15, 0.0, null, true);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, pos, 3, 0.12, 0.12, 0.12, 0.01, null, true);
        world.spawnParticle(Particle.ELECTRIC_SPARK, pos, 3, 0.2, 0.2, 0.2, 0.05, null, true);
    }

    // ---------------------------------------------------- 2) the burst

    /** Runs the big animation centered where the orb stopped. */
    private void arrive(Hit hit, Location burstCenter) {
        hits.remove(hit.targetId, hit);
        hit.waitingForEffect = false;
        Settings s = plugin.settings();

        Location burstFeet = burstCenter.clone().add(0, -1.1, 0);   // shockwave ring height
        burst(burstCenter, burstFeet, hit.away, s);
        finisher(hit, burstCenter);
    }

    private void burst(Location center, Location feet, Vector away, Settings s) {
        World world = center.getWorld();
        if (world == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        coreExplosion(world, center, rnd);
        world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 1.3f);
        world.playSound(center, Sound.ITEM_TRIDENT_HIT, 1.0f, 0.6f);
        world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.7f);

        // Lots of smaller spikes spread evenly around (golden-angle spiral) with a little randomness
        List<Spike> spikes = new ArrayList<>();
        int themeStart = rnd.nextInt(THEMES.length);
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < s.spikeCount; i++) {
            double y = 0.95 - 1.25 * (i + 0.5) / s.spikeCount;       // from nearly straight up to slightly down
            y += (rnd.nextDouble() - 0.5) * 0.1;
            y = Math.max(-0.35, Math.min(0.98, y));
            double angle = golden * i + rnd.nextDouble() * 0.3;
            double flat = Math.sqrt(Math.max(0, 1 - y * y));
            Vector dir = new Vector(Math.cos(angle) * flat, y, Math.sin(angle) * flat).normalize();
            double length = s.spikeLength * (0.7 + rnd.nextDouble() * 0.6);
            spikes.add(new Spike(dir, length, THEMES[(themeStart + i) % THEMES.length]));
        }

        final int spikeTicks = s.spikeTicks;
        final int sphereTicks = 5;
        final int shockTicks = 8;
        final int total = Math.max(spikeTicks, Math.max(sphereTicks, shockTicks)) + 1;

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= total) {
                    cancel();
                    return;
                }
                if (t < sphereTicks) {
                    sphere(world, center, s, t, sphereTicks);
                }
                if (s.shockwave && t < shockTicks) {
                    shockwave(world, feet, s, t, shockTicks);
                }
                if (t < spikeTicks) {
                    double from = (double) t / spikeTicks;
                    double to = (double) (t + 1) / spikeTicks;
                    for (Spike spike : spikes) {
                        spike.draw(world, center, s, from, to);
                        if (t == spikeTicks - 1) {
                            spike.launchShards(world, center);
                        }
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        if (s.bigSpear) {
            giantSpear(world, feet, away, s);
        }
    }

    private static void coreExplosion(World world, Location center, ThreadLocalRandom rnd) {
        world.spawnParticle(Particle.FLASH, center, 1, 0, 0, 0, 0, BLUE, true);
        world.spawnParticle(Particle.FLASH, center, 1, 0, 0, 0, 0, ORANGE, true);
        world.spawnParticle(Particle.FLASH, center, 1, 0, 0, 0, 0, YELLOW, true);
        world.spawnParticle(Particle.SONIC_BOOM, center, 1, 0, 0, 0, 0, null, true);
        world.spawnParticle(Particle.EXPLOSION, center, 3, 0.4, 0.4, 0.4, 0, null, true);
        world.spawnParticle(Particle.SOUL, center, 160, 0.5, 0.8, 0.5, 0.2, null, true);
        world.spawnParticle(Particle.SCULK_SOUL, center, 80, 0.4, 0.7, 0.4, 0.15, null, true);
        world.spawnParticle(Particle.CRIT, center, 60, 0.4, 0.6, 0.4, 0.9, null, true);

        Particle[] throwables = {
                Particle.SOUL_FIRE_FLAME, Particle.FLAME, Particle.END_ROD, Particle.ELECTRIC_SPARK, Particle.WAX_ON
        };
        for (int i = 0; i < 90; i++) {
            Vector v = randomUnit(rnd);
            Particle p = throwables[i % throwables.length];
            world.spawnParticle(p, center, 0, v.getX(), v.getY(), v.getZ(), 0.35 + rnd.nextDouble() * 0.35, null, true);
        }
    }

    /** A big pulsing rainbow sphere around the target, with glowing textured particles mixed in. */
    private static void sphere(World world, Location center, Settings s, int t, int frames) {
        double radius = s.sphereRadius * (0.6 + 0.4 * (t + 1) / frames);
        int points = Math.max(30, (int) (s.spherePoints * s.density));
        double golden = Math.PI * (3 - Math.sqrt(5));
        float hueShift = t * 0.09f;

        for (int i = 0; i < points; i++) {
            double y = 1 - (i / (double) (points - 1)) * 2;
            double ring = Math.sqrt(1 - y * y);
            double theta = golden * i + t * 0.4;
            Location p = center.clone().add(Math.cos(theta) * ring * radius, y * radius, Math.sin(theta) * ring * radius);

            if (i % 5 == 0) {
                Particle textured = switch ((i / 5) % 4) {
                    case 0 -> Particle.SOUL_FIRE_FLAME;
                    case 1 -> Particle.FLAME;
                    case 2 -> Particle.GLOW;
                    default -> Particle.WAX_ON;
                };
                world.spawnParticle(textured, p, 1, 0, 0, 0, 0, null, true);
            } else {
                float hue = (float) ((y + 1) / 2) * 0.6f + hueShift;
                world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(hsv(hue, 1f, 1f), 2.0f), true);
            }
        }
    }

    /** A flat ring rushing outward along the ground. */
    private static void shockwave(World world, Location feet, Settings s, int t, int frames) {
        double radius = 1.0 + (s.sphereRadius + s.spikeLength * 0.6) * (t + 1) / frames;
        double spacing = 0.5 / s.density;
        int points = Math.max(12, (int) Math.ceil(2 * Math.PI * radius / spacing));
        Location base = feet.clone().add(0, 0.15, 0);

        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            Location p = base.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            switch (i % 4) {
                case 0 -> world.spawnParticle(Particle.SOUL_FIRE_FLAME, p, 1, 0, 0.05, 0, 0.01, null, true);
                case 1 -> world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(hsv((float) i / points + t * 0.05f, 1f, 1f), 2.0f), true);
                case 2 -> world.spawnParticle(Particle.FLAME, p, 1, 0, 0.05, 0, 0.01, null, true);
                default -> world.spawnParticle(Particle.SOUL, p, 1, 0, 0.1, 0, 0.02, null, true);
            }
        }
    }

    /** One dripstone-shaped cone: thick at the target, tapering to a sharp point. */
    private static final class Spike {
        final Vector dir;
        final double length;
        final Theme theme;

        Spike(Vector dir, double length, Theme theme) {
            this.dir = dir;
            this.length = length;
            this.theme = theme;
        }

        /** Draws the part of the spike between fractions [from, to) of its length. */
        void draw(World world, Location center, Settings s, double from, double to) {
            double step = 0.35;
            double start = Math.max(0.8, from * length);
            double end = to * length;

            for (double d = start; d < end; d += step) {
                double frac = d / length;
                double radius = s.spikeThickness * Math.pow(1 - frac, 1.1);
                Location axis = center.clone().add(dir.clone().multiply(d));

                float hue = theme.hue() + (float) frac * 0.18f;
                float saturation = (float) (1.0 - 0.65 * frac * frac);
                Color color = hsv(hue, saturation, 1f);
                Color color2 = hsv(hue + 0.12f, saturation, 1f);
                float size = (float) (0.8 + 1.2 * (1 - frac));

                // A cluster of particles around the spike's center line makes the rough cone shape
                int count = Math.max(1, (int) Math.round(radius * 14 * s.density));
                double spread = radius * 0.45;
                world.spawnParticle(Particle.DUST, axis, count, spread, spread, spread, 0,
                        new Particle.DustOptions(color, size), true);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, axis, Math.max(1, count / 3), spread, spread, spread, 0,
                        new Particle.DustTransition(color, color2, size), true);

                // Glowing textured core
                world.spawnParticle(theme.coreA(), axis, 1, 0.02, 0.02, 0.02, 0, null, true);
                if (((int) (d / step)) % 2 == 0) {
                    world.spawnParticle(theme.coreB(), axis, 1, 0.04, 0.04, 0.04, 0, null, true);
                }
            }
            Location tip = center.clone().add(dir.clone().multiply(end));
            world.spawnParticle(Particle.END_ROD, tip, 2, 0.03, 0.03, 0.03, 0.01, null, true);
        }

        /** Shards that keep flying past the tip once the spike is fully out. */
        void launchShards(World world, Location center) {
            Location tip = center.clone().add(dir.clone().multiply(length));
            for (int i = 0; i < 3; i++) {
                double speed = 0.5 + i * 0.25;
                world.spawnParticle(Particle.END_ROD, tip, 0, dir.getX(), dir.getY(), dir.getZ(), speed, null, true);
                world.spawnParticle(theme.coreA(), tip, 0, dir.getX(), dir.getY(), dir.getZ(), speed * 0.8, null, true);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, tip, 0, dir.getX(), dir.getY(), dir.getZ(), speed * 0.7, null, true);
                world.spawnParticle(Particle.FLAME, tip, 0, dir.getX(), dir.getY(), dir.getZ(), speed * 0.6, null, true);
            }
            world.spawnParticle(Particle.FIREWORK, tip, 4, 0.1, 0.1, 0.1, 0.1, null, true);
        }
    }

    // ---------------------------------------------------- giant netherite spear

    /** A huge netherite spear made of particles, standing behind the explosion (as seen by the attacker). */
    private void giantSpear(World world, Location feet, Vector away, Settings s) {
        final double length = s.bigSpearLength;
        final Location base = feet.clone().add(away.clone().multiply(s.sphereRadius + 2.5)).add(0, -1.0, 0);
        double tilt = Math.toRadians(18);
        final Vector axis = new Vector(0, Math.cos(tilt), 0).add(away.clone().multiply(Math.sin(tilt))).normalize();
        Vector sideRaw = axis.clone().crossProduct(away);
        final Vector side = sideRaw.lengthSquared() < 1.0E-4 ? new Vector(1, 0, 0) : sideRaw.normalize();
        final int frames = Math.max(1, s.bigSpearTicks / 4);

        world.playSound(base, Sound.ITEM_TRIDENT_THUNDER, 0.8f, 1.2f);
        world.playSound(base, Sound.BLOCK_ANVIL_LAND, 0.6f, 0.5f);

        new BukkitRunnable() {
            int frame = 0;

            @Override
            public void run() {
                if (frame >= frames) {
                    cancel();
                    return;
                }
                // First frame "grows" it up out of the ground quickly, then it stays solid
                double reveal = frame == 0 ? 0.5 : 1.0;
                drawSpear(world, base, axis, side, length, reveal, s.density);
                frame++;
            }
        }.runTaskTimer(plugin, 0L, 4L);
    }

    private static void drawSpear(World world, Location base, Vector axis, Vector side, double length,
                                  double reveal, double density) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double headLength = length * 0.28;
        double shaftLength = length - headLength;
        double maxDraw = length * reveal;

        // Pommel
        world.spawnParticle(Particle.DUST, base, 6, 0.15, 0.15, 0.15, 0,
                new Particle.DustOptions(NETH_DARK, 2.2f), true);

        // Shaft, with lighter grip bands
        for (double d = 0; d < Math.min(shaftLength, maxDraw); d += 0.3) {
            Location p = base.clone().add(axis.clone().multiply(d));
            boolean band = d > shaftLength * 0.12 && d < shaftLength * 0.45 && ((int) (d / 0.3)) % 4 == 0;
            Color c = band ? NETH_LIGHT : (((int) (d / 0.3)) % 2 == 0 ? NETH_MID : NETH_DARK);
            world.spawnParticle(Particle.DUST, p, Math.max(1, (int) (3 * density)), 0.06, 0.06, 0.06, 0,
                    new Particle.DustOptions(c, 1.7f), true);
        }
        if (maxDraw <= shaftLength) {
            return;
        }

        // Collar where the head meets the shaft
        Location collar = base.clone().add(axis.clone().multiply(shaftLength));
        Vector across = axis.clone().crossProduct(side).normalize();
        for (int i = 0; i < 12; i++) {
            double a = 2 * Math.PI * i / 12;
            Vector off = side.clone().multiply(Math.cos(a) * 0.45).add(across.clone().multiply(Math.sin(a) * 0.45));
            world.spawnParticle(Particle.DUST, collar.clone().add(off), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(NETH_EDGE, 1.5f), true);
        }

        // Blade: flat, pointed head (widens quickly, then tapers to a sharp tip)
        double maxWidth = Math.max(0.6, length * 0.075);
        double spacing = 0.22 / Math.sqrt(density);
        for (double t = 0; t <= 1.0; t += 0.06) {
            double along = shaftLength + t * headLength;
            if (along > maxDraw) break;
            double w = maxWidth * (t < 0.22 ? t / 0.22 : Math.pow((1 - t) / 0.78, 0.9));
            Location mid = base.clone().add(axis.clone().multiply(along));
            for (double off = -w; off <= w + 1.0E-6; off += spacing) {
                boolean edge = Math.abs(off) > w - spacing * 1.2;
                boolean ridge = Math.abs(off) < spacing * 0.5;
                Color c = edge ? NETH_EDGE : ridge ? NETH_LIGHT : (((int) ((off + w) / spacing)) % 2 == 0 ? NETH_MID : NETH_DARK);
                world.spawnParticle(Particle.DUST, mid.clone().add(side.clone().multiply(off)), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(c, 1.6f), true);
            }
        }

        // Purple enchantment glint sparkles
        for (int i = 0; i < 8; i++) {
            double d = rnd.nextDouble() * length;
            Location p = base.clone().add(axis.clone().multiply(d))
                    .add(side.clone().multiply((rnd.nextDouble() - 0.5) * (d > shaftLength ? maxWidth : 0.2)));
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(GLINT, 1.1f), true);
        }
        Location tip = base.clone().add(axis.clone().multiply(length));
        world.spawnParticle(Particle.END_ROD, tip, 1, 0, 0, 0, 0, null, true);
    }

    // ---------------------------------------------------- 3) finisher + flag

    private void finisher(Hit hit, Location soundAt) {
        if (hit.finisherDone || !(hit.killed || hit.popped)) {
            return;
        }
        hit.finisherDone = true;
        Settings s = plugin.settings();
        World world = hit.feet.getWorld();
        if (world == null) return;

        if (s.finisherEnabled && (!s.finisherPlayersOnly || hit.targetIsPlayer)) {
            world.playSound(soundAt, Sound.ENTITY_WARDEN_DEATH, s.finisherVolume, s.finisherPitch);
        }
        if (hit.killed && (!s.flagPlayersOnly || hit.targetIsPlayer)) {
            flags.spawn(hit.feet, hit.away);
        }
    }

    // ---------------------------------------------------- helpers

    private static Vector randomUnit(ThreadLocalRandom rnd) {
        double y = rnd.nextDouble() * 2 - 1;
        double a = rnd.nextDouble() * Math.PI * 2;
        double r = Math.sqrt(1 - y * y);
        return new Vector(Math.cos(a) * r, y, Math.sin(a) * r);
    }

    /** Hue/saturation/value (all 0..1, hue wraps) to a Bukkit Color. */
    static Color hsv(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        s = Math.max(0f, Math.min(1f, s));
        v = Math.max(0f, Math.min(1f, v));
        float c = v * s;
        float x = c * (1 - Math.abs((h * 6) % 2 - 1));
        float m = v - c;
        float r, g, b;
        int sector = (int) (h * 6) % 6;
        switch (sector) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        return Color.fromRGB(
                Math.round((r + m) * 255),
                Math.round((g + m) * 255),
                Math.round((b + m) * 255));
    }
}
