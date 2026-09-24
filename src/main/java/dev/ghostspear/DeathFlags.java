package dev.ghostspear;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A white flag made of particles, floating high above where someone died from a ghost's hit.
 * It waves in the wind and stays for flag.duration-seconds (set with /ghostspear flagtime).
 */
public final class DeathFlags {

    private static final int REDRAW_TICKS = 4;
    private static final Color POLE = Color.fromRGB(0xD8D8D8);
    private static final Color POLE_SHADE = Color.fromRGB(0xA9A9A9);
    private static final Color FINIAL = Color.fromRGB(0xFFD54A);

    private final GhostSpearPlugin plugin;
    private final Deque<BukkitRunnable> active = new ArrayDeque<>();

    public DeathFlags(GhostSpearPlugin plugin) {
        this.plugin = plugin;
    }

    public void spawn(Location deathSpot, Vector facing) {
        Settings s = plugin.settings();
        if (!s.flagEnabled || s.flagDurationSeconds <= 0 || deathSpot.getWorld() == null) {
            return;
        }
        // Too many flags at once? Remove the oldest.
        while (active.size() >= s.flagMaxActive && !active.isEmpty()) {
            BukkitRunnable oldest = active.pollFirst();
            oldest.cancel();
        }

        final World world = deathSpot.getWorld();
        final Location poleBottom = deathSpot.clone().add(0, s.flagHeight, 0);
        final double poleHeight = 6.0;
        final Location poleTop = poleBottom.clone().add(0, poleHeight, 0);
        final Vector along = facing.clone().setY(0).lengthSquared() < 1.0E-4
                ? new Vector(1, 0, 0) : facing.clone().setY(0).normalize();
        final Vector wave = new Vector(-along.getZ(), 0, along.getX());   // sideways, for the waving motion
        final int totalTicks = s.flagDurationSeconds * 20;
        final double density = s.density;

        BukkitRunnable task = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= totalTicks) {
                    cancel();
                    active.remove(this);
                    return;
                }
                drawPole(world, poleBottom, poleHeight);
                drawCloth(world, poleTop, along, wave, t, density);
                t += REDRAW_TICKS;
            }
        };
        active.addLast(task);
        task.runTaskTimer(plugin, 0L, REDRAW_TICKS);
    }

    public void removeAll() {
        for (BukkitRunnable task : active) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
                // already stopped
            }
        }
        active.clear();
    }

    private static void drawPole(World world, Location bottom, double height) {
        for (double y = 0; y <= height; y += 0.3) {
            Color c = ((int) (y / 0.3)) % 3 == 0 ? POLE_SHADE : POLE;
            world.spawnParticle(Particle.DUST, bottom.clone().add(0, y, 0), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(c, 1.3f), true);
        }
        Location top = bottom.clone().add(0, height + 0.25, 0);
        world.spawnParticle(Particle.DUST, top, 3, 0.06, 0.06, 0.06, 0, new Particle.DustOptions(FINIAL, 1.8f), true);
        world.spawnParticle(Particle.END_ROD, top, 1, 0, 0, 0, 0, null, true);
    }

    /** A white cloth rectangle that ripples, more at the free end than near the pole. */
    private static void drawCloth(World world, Location poleTop, Vector along, Vector wave, int t, double density) {
        double width = 4.0;
        double height = 2.5;
        double spacing = 0.25 / Math.sqrt(density);
        for (double x = spacing; x <= width; x += spacing) {
            double phase = x * 1.5 - t * 0.12;
            double ripple = 0.35 * Math.sin(phase) * (x / width);
            double droop = 0.12 * (x / width) * (x / width);
            // Slightly darker where the cloth "folds" away from the light
            float brightness = (float) (0.86 + 0.14 * (0.5 + 0.5 * Math.cos(phase)));
            Color c = SoulBurst.hsv(0f, 0f, brightness);
            for (double y = 0; y <= height; y += spacing) {
                Location p = poleTop.clone()
                        .add(along.clone().multiply(x))
                        .add(wave.clone().multiply(ripple))
                        .add(0, -y - droop, 0);
                world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(c, 1.4f), true);
            }
        }
    }
}
