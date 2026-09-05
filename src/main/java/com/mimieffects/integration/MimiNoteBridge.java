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

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** Converts MIMI's authoritative note stream into effects. */
public final class MimiNoteBridge {
    private static final Map<UUID, Long> LAST_PURGE_TICK = new HashMap<>();
    private MimiNoteBridge() {}

    public static void onMimiNote(NoteEvent note) {
        if (note == null || note.type != MidiEventType.NOTE_ON || note.senderId == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) server.execute(() -> applyFor(note, server));
    }

    private static void applyFor(NoteEvent note, MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(note.senderId);
        if (player == null || note.instrumentId == null) return;
        ATransmitterBroadcastProducer transmitter = ServerTransmitterManager.getTransmitter(player.getUUID());
        if (transmitter == null) return;
        ServerMusicPlayerStatusPacket status = transmitter.getStatus();
        if (status == null || !Boolean.TRUE.equals(status.isPlaying) || status.fileId == null) return;
        TrackConfig track = MimiEffectsMod.TRACK_REGISTRY.find(status.fileId.toString());
        InstrumentSpec instrument = InstrumentConfig.getBydId(note.instrumentId);
        if (track == null || instrument == null || instrument.registryName == null) return;
        Arrangement arrangement = findMatchingArrangement(track, "mimi:" + instrument.registryName);
        if (arrangement == null || arrangement.effects == null) return;

        int durationTicks = (arrangement.duration_seconds != null ? arrangement.duration_seconds : GlobalConfig.DEFAULT_DURATION_SECONDS.get()) * 20;
        int radius = arrangement.radius != null ? arrangement.radius : GlobalConfig.BASE_RADIUS.get();
        List<LivingEntity> targets = findTargets(player, arrangement, radius);
        for (EffectEntry entry : arrangement.effects) {
            Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(entry.effect)).orElse(null);
            if (effect == null) {
                MimiEffectsMod.LOGGER.warn("Unknown mob effect '{}' in track '{}'.", entry.effect, track.display_name);
                continue;
            }
            int level = Math.max(1, Math.min(entry.base_level, entry.max_level));
            for (LivingEntity target : targets) target.addEffect(new MobEffectInstance(effect, durationTicks, level - 1, false, true, true));
        }
        purgeMobs(player, instrument, track.mob_purge);
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

    private static void purgeMobs(ServerPlayer player, InstrumentSpec instrument, MobPurge purge) {
        if (purge == null || (purge.requires_static_instrument && !Boolean.TRUE.equals(instrument.isBlock))) return;
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        long last = LAST_PURGE_TICK.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (now - last < Math.max(1, purge.purge_interval_ticks)) return;
        LAST_PURGE_TICK.put(player.getUUID(), now);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(purge.radius_blocks))) {
            // Added 2026-09-04: bosses are never purgeable, full stop —
            // checked before anything else, independent of environment/
            // hostile_only settings.
            if (BossProtection.isProtected(entity)) continue;
            boolean tamed = entity instanceof TamableAnimal animal && animal.isTame();
            if (!MobPurgeMatcher.shouldPurge(entity instanceof ServerPlayer, EntityHostilityUtil.isHostile(entity), entity.isInWater(), tamed, purge.environment, purge.hostile_only)) continue;
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
        }
    }

    private static Arrangement findMatchingArrangement(TrackConfig track, String instrumentId) {
        if (track.arrangements == null) return null;
        Map<String, Integer> available = Map.of(instrumentId, 1);
        for (Arrangement arrangement : track.arrangements) {
            if (arrangement != null && arrangement.coverageScore(available) >= 1.0) return arrangement;
        }
        return null;
    }
}
