package dev.ghostspear;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Small per-player timers, measured in server ticks. */
public final class PlayerState {

    private final Map<UUID, Integer> lastToggle = new HashMap<>();
    private final Map<UUID, Integer> lastAttack = new HashMap<>();
    private final Map<UUID, Integer> lastBlockUse = new HashMap<>();
    private final Map<UUID, Integer> lastPierce = new HashMap<>();

    /** Ticks left before this player can toggle ghost mode again (0 = ready). */
    public int toggleCooldownLeft(UUID id, int now, int cooldown) {
        Integer last = lastToggle.get(id);
        if (last == null) return 0;
        return Math.max(0, (last + cooldown) - now);
    }

    public void markToggle(UUID id, int now) {
        lastToggle.put(id, now);
    }

    /** Hitting something also swings your arm - this stops that swing from toggling ghost mode. */
    public void markAttack(UUID id, int now) {
        lastAttack.put(id, now);
    }

    public boolean attackedRecently(UUID id, int now) {
        Integer last = lastAttack.get(id);
        return last != null && now - last <= 2;
    }

    /** Right-clicking doors/chests also swings your arm - same idea. */
    public void markBlockUse(UUID id, int now) {
        lastBlockUse.put(id, now);
    }

    public boolean usedBlockRecently(UUID id, int now) {
        Integer last = lastBlockUse.get(id);
        return last != null && now - last <= 1;
    }

    /** Internal cooldown so one charge can't hit ten times in ten ticks. */
    public boolean canPierce(UUID id, int now) {
        Integer last = lastPierce.get(id);
        return last == null || now - last >= 10;
    }

    public void markPierce(UUID id, int now) {
        lastPierce.put(id, now);
    }

    public void clear(UUID id) {
        lastToggle.remove(id);
        lastAttack.remove(id);
        lastBlockUse.remove(id);
        lastPierce.remove(id);
    }
}
