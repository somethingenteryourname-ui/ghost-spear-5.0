package dev.ghostspear;

import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class GhostSpearPlugin extends JavaPlugin {

    /** Bump this when config.yml changes in a way old configs can't handle. */
    private static final int CONFIG_VERSION = 6;

    private NamespacedKey spearKey;
    private NamespacedKey ghostKey;
    private NamespacedKey ghostModeKey;
    private Settings settings;
    private SpearItem spearItem;
    private GhostSpawner ghostSpawner;
    private GhostModeManager ghostModes;
    private PlayerState playerState;
    private SoulBurst soulBurst;
    private DeathFlags deathFlags;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        upgradeConfigIfOld();

        spearKey = new NamespacedKey(this, "ghost_spear");
        ghostKey = new NamespacedKey(this, "ghost_decoy");
        ghostModeKey = new NamespacedKey(this, "ghost_mode_active");

        reloadSettings();

        playerState = new PlayerState();
        spearItem = new SpearItem(this);
        ghostSpawner = new GhostSpawner(this);
        ghostModes = new GhostModeManager(this);
        deathFlags = new DeathFlags(this);
        soulBurst = new SoulBurst(this, deathFlags);

        getServer().getPluginManager().registerEvents(new SpearListener(this), this);
        new ChargeTask(this).runTaskTimer(this, 1L, 1L);

        GhostSpearCommand command = new GhostSpearCommand(this);
        PluginCommand pluginCommand = getCommand("ghostspear");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        // In case of /reload: tidy up anyone left marked as a ghost
        getServer().getOnlinePlayers().forEach(ghostModes::handleJoin);

        getLogger().info("GhostSpear enabled. Spear material: " + settings.material
                + ", invisibility mode: " + (settings.hideMode ? "HIDE" : "POTION"));
    }

    @Override
    public void onDisable() {
        if (ghostModes != null) {
            ghostModes.deactivateAll(GhostModeManager.EndReason.SHUTDOWN);
        }
        if (deathFlags != null) {
            deathFlags.removeAll();
        }
    }

    /** Old configs get backed up to config-old.yml and replaced with the new default one. */
    private void upgradeConfigIfOld() {
        reloadConfig();
        if (getConfig().getInt("config-version", 0) >= CONFIG_VERSION) {
            return;
        }
        File current = new File(getDataFolder(), "config.yml");
        File backup = new File(getDataFolder(), "config-old.yml");
        if (backup.exists() && !backup.delete()) {
            getLogger().warning("Couldn't delete old config-old.yml");
        }
        if (current.renameTo(backup)) {
            saveDefaultConfig();
            getLogger().warning("Your config.yml was from an older GhostSpear version. It was replaced with the new one"
                    + " (your old settings are saved in config-old.yml).");
        } else {
            getLogger().warning("Couldn't update config.yml - delete it and restart to get the new settings.");
        }
    }

    public void reloadSettings() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
    }

    public NamespacedKey spearKey() { return spearKey; }
    public NamespacedKey ghostKey() { return ghostKey; }
    public NamespacedKey ghostModeKey() { return ghostModeKey; }
    public Settings settings() { return settings; }
    public SpearItem spearItem() { return spearItem; }
    public GhostSpawner ghostSpawner() { return ghostSpawner; }
    public GhostModeManager ghostModes() { return ghostModes; }
    public PlayerState playerState() { return playerState; }
    public SoulBurst soulBurst() { return soulBurst; }
    public DeathFlags deathFlags() { return deathFlags; }
}
