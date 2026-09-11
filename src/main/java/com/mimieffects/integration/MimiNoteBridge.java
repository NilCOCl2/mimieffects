package com.mimieffects.integration;

import com.mimieffects.MimiEffectsMod;
import com.mimieffects.config.GlobalConfig;
import com.mimieffects.track.Arrangement;
import com.mimieffects.track.EffectEntry;
import com.mimieffects.track.EffectTargets;
import com.mimieffects.track.MobPurge;
import com.mimieffects.track.TrackConfig;
import com.mimieffects.orchestrator.MobPurgeMatcher;
import io.github.tofodroid.mods.mimi.common.api.event.MidiEventType;
import io.github.tofodroid.mods.mimi.common.api.event.note.NoteEvent;
import io.github.tofodroid.mods.mimi.common.config.instrument.InstrumentConfig;
import io.github.tofodroid.mods.mimi.common.config.instrument.InstrumentSpec;
import io.github.tofodroid.mods.mimi.common.network.ServerMusicPlayerStatusPacket;
import io.github.tofodroid.mods.mimi.server.events.broadcast.producer.transmitter.ATransmitterBroadcastProducer;
import io.github.tofodroid.mods.mimi.server.events.broadcast.producer.transmitter.ServerTransmitterManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/**
 * Converts MIMI's authoritative note stream into effects.
 *
 * ADDED 2026-09-06: step-by-step debug tracing, toggled via
 * GlobalConfig [Debug] (debug_logging / debug_chat_feedback /
 * debug_throttle_ticks). Every early-return point in applyFor() and
 * purgeMobs() now records WHY it stopped there, and the whole trace is
 * emitted as one line (throttled per player) instead of requiring manual
 * step-by-step diagnosis each time something "just doesn't work" — this
 * exact bug pattern (an unresolved track, a non-matching arrangement, an
 * instrument/environment gate, a cooldown window) has come up repeatedly.
 */
public final class MimiNoteBridge {
    private static final Map<UUID, Long> LAST_PURGE_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_DEBUG_TICK = new HashMap<>();

    private MimiNoteBridge() {}

    public static void onMimiNote(NoteEvent note) {
        if (note == null || note.type != MidiEventType.NOTE_ON || note.senderId == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) server.execute(() -> applyFor(note, server));
    }

    private static void applyFor(NoteEvent note, MinecraftServer server) {
        List<String> trace = new ArrayList<>();
        trace.add("sender=" + note.senderId);

        ServerPlayer player = server.getPlayerList().getPlayer(note.senderId);
        if (player == null || note.instrumentId == null) {
            trace.add("STOP: player" + (player == null ? " not online" : "") + (note.instrumentId == null ? ", instrumentId null" : ""));
            debug(note.senderId, null, trace);
            return;
        }
        trace.add("player=" + player.getGameProfile().getName());

        ATransmitterBroadcastProducer transmitter = ServerTransmitterManager.getTransmitter(player.getUUID());
        if (transmitter == null) {
            trace.add("STOP: no transmitter for this player (not registered as a MIDI source at all)");
            debug(note.senderId, player, trace);
            return;
        }

        ServerMusicPlayerStatusPacket status = transmitter.getStatus();
        if (status == null || !Boolean.TRUE.equals(status.isPlaying) || status.fileId == null) {
            trace.add("STOP: transmitter status=" + (status == null ? "null" : ("isPlaying=" + status.isPlaying + ", fileId=" + status.fileId))
                    + " (live improvisation with no loaded file looks like this too)");
            debug(note.senderId, player, trace);
            return;
        }
        trace.add("fileId=" + status.fileId);

        TrackConfig track = MimiEffectsMod.TRACK_REGISTRY.find(status.fileId.toString());
        InstrumentSpec instrument = InstrumentConfig.getBydId(note.instrumentId);

        if (track == null || instrument == null || instrument.registryName == null) {
            trace.add("STOP: track=" + (track == null ? "NOT FOUND for this fileId — check track_id in your JSON matches EXACTLY, including no placeholder text" : "found")
                    + ", instrument=" + (instrument == null ? "unresolved" : instrument.registryName));
            debug(note.senderId, player, trace);
            return;
        }
        trace.add("track=" + track.display_name + ", instrument=mimi:" + instrument.registryName + ", isBlock=" + instrument.isBlock);

        // mob_purge is track-level, NOT arrangement-level — run it
        // independently of arrangement matching (see FIX 2026-09-05).
        purgeMobs(player, instrument, track.mob_purge, trace);

        String instrumentId = "mimi:" + instrument.registryName;
        Arrangement arrangement = findMatchingArrangement(track, instrumentId);
        if (arrangement == null || arrangement.effects == null || arrangement.effects.length == 0) {
            trace.add("STOP (effects): " + (arrangement == null
                    ? "no arrangement's required_instruments matched {" + instrumentId + "}"
                    : "matched arrangement '" + arrangement.name + "' but it has no effects"));
            debug(note.senderId, player, trace);
            return;
        }
        trace.add("arrangement='" + arrangement.name + "' matched");

        int durationTicks = (arrangement.duration_seconds != null ? arrangement.duration_seconds : GlobalConfig.DEFAULT_DURATION_SECONDS.get()) * 20;
        int radius = arrangement.radius != null ? arrangement.radius : GlobalConfig.BASE_RADIUS.get();
        List<LivingEntity> targets = findTargets(player, arrangement, radius);
        trace.add("targets=" + targets.size() + " within radius=" + radius);

        int applied = 0;
        for (EffectEntry entry : arrangement.effects) {
            Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(entry.effect)).orElse(null);
            if (effect == null) {
                MimiEffectsMod.LOGGER.warn("Unknown mob effect '{}' in track '{}'.", entry.effect, track.display_name);
                continue;
            }
            int level = Math.max(1, Math.min(entry.base_level, entry.max_level));
            for (LivingEntity target : targets) target.addEffect(new MobEffectInstance(effect, durationTicks, level - 1, false, true, true));
            applied++;
        }
        trace.add("applied " + applied + " effect(s) to " + targets.size() + " target(s)");
        debug(note.senderId, player, trace);
    }

    private static List<LivingEntity> findTargets(ServerPlayer source, Arrangement arrangement, int radius) {
        EffectTargets groups = arrangement.targets;
        if (groups == null) {
            groups = new EffectTargets();
            groups.players = !"ensemble".equals(arrangement.affects);
            groups.orchestra = "ensemble".equals(arrangement.affects);
        }
        final EffectTargets selected = groups;
        return source.serverLevel().getEntitiesOfClass(LivingEntity.class, source.getBoundingBox().inflate(radius), entity -> {
            // Added 2026-09-04: bosses are exempt from ANY track effect —
            // positive or negative — regardless of targeting flags, so a
            // healing track can't be abused to buff a boss fight's outcome
            // and a harmful one can't cheese a boss kill. See BossProtection.
            if (BossProtection.isProtected(entity)) return false;
            if (entity == source) return selected.orchestra || selected.players;
            if (entity instanceof ServerPlayer) return selected.players;
            // FIX (2026-09-05): was "instanceof Enemy" alone, which missed
            // several real modded threats (vampires/bats/maggots; Born in
            // Chaos anglerfish) that don't implement that marker interface.
            if (EntityHostilityUtil.isHostile(entity)) return selected.hostile;
            if (entity instanceof NeutralMob) return selected.neutral;
            return selected.friendly;
        });
    }

    private static void purgeMobs(ServerPlayer player, InstrumentSpec instrument, MobPurge purge, List<String> trace) {
        if (purge == null) {
            trace.add("purge: no mob_purge configured for this track");
            return;
        }
        if (purge.requires_static_instrument && !Boolean.TRUE.equals(instrument.isBlock)) {
            trace.add("purge: SKIPPED — requires_static_instrument=true but this instrument isBlock=" + instrument.isBlock);
            return;
        }

        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        long last = LAST_PURGE_TICK.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (now - last < Math.max(1, purge.purge_interval_ticks)) {
            trace.add("purge: on cooldown (" + (Math.max(1, purge.purge_interval_ticks) - (now - last)) + " ticks left)");
            return;
        }
        LAST_PURGE_TICK.put(player.getUUID(), now);

        int scanned = 0, skippedBoss = 0, skippedRule = 0, killed = 0;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(purge.radius_blocks))) {
            scanned++;
            // Added 2026-09-04: bosses are never purgeable, full stop —
            // checked before anything else, independent of environment/
            // hostile_only settings.
            if (BossProtection.isProtected(entity)) {
                skippedBoss++;
                continue;
            }
            boolean tamed = entity instanceof TamableAnimal animal && animal.isTame();
            if (!MobPurgeMatcher.shouldPurge(entity instanceof ServerPlayer, EntityHostilityUtil.isHostile(entity), entity.isInWater(), tamed, purge.environment, purge.hostile_only)) {
                skippedRule++;
                continue;
            }
            // FIX (2026-09-04): "silent" used to call entity.discard(),
            // which unconditionally removes the entity WITHOUT going
            // through LivingEntity#hurt() — that bypasses damage
            // immunity/invulnerability windows entirely (e.g. a boss's
            // brief post-spawn invulnerability, or any entity explicitly
            // flagged invulnerable), which is exactly the kind of
            // "abuse" vector the user flagged. Always damage through the
            // normal combat pipeline now, which respects
            // isInvulnerableTo() and any other vanilla/modded immunity
            // logic; a finite (not Float.MAX_VALUE) but still massively
            // lethal amount avoids any theoretical float-overflow edge
            // case in damage-modifier math further down the pipeline.
            entity.hurt(player.damageSources().playerAttack(player), 1.0E6F);
            killed++;
        }
        trace.add("purge: scanned=" + scanned + " skipped(boss)=" + skippedBoss + " skipped(rule)=" + skippedRule + " killed=" + killed
                + " [env=" + purge.environment + ", hostileOnly=" + purge.hostile_only + ", radius=" + purge.radius_blocks + "]");
    }

    private static Arrangement findMatchingArrangement(TrackConfig track, String instrumentId) {
        if (track.arrangements == null) return null;
        Map<String, Integer> available = Map.of(instrumentId, 1);
        for (Arrangement arrangement : track.arrangements) {
            if (arrangement != null && arrangement.coverageScore(available) >= 1.0) return arrangement;
        }
        return null;
    }

    /**
     * Emits the accumulated trace if debug_logging is on, throttled per
     * player (keyed by note.senderId so it still works even in the rare
     * case the player object itself never resolved) to avoid flooding
     * the log/chat with one line per note during a fast song.
     */
    private static void debug(UUID senderId, ServerPlayer player, List<String> trace) {
        if (!GlobalConfig.DEBUG_LOGGING.get()) {
            return;
        }
        long nowTick = player != null ? player.serverLevel().getGameTime()
                : (ServerLifecycleHooks.getCurrentServer() != null ? ServerLifecycleHooks.getCurrentServer().overworld().getGameTime() : 0);
        long throttleTicks = GlobalConfig.DEBUG_THROTTLE_TICKS.get();
        long last = LAST_DEBUG_TICK.getOrDefault(senderId, Long.MIN_VALUE);
        if (nowTick - last < throttleTicks) {
            return;
        }
        LAST_DEBUG_TICK.put(senderId, nowTick);

        String line = "[MimiEffects DEBUG] " + String.join(" | ", trace);
        MimiEffectsMod.LOGGER.info(line);

        if (player != null && GlobalConfig.DEBUG_CHAT_FEEDBACK.get()) {
            player.sendSystemMessage(Component.literal(line));
        }
    }
}
