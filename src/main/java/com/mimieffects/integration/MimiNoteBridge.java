package com.mimieffects.integration;

import com.mimieffects.MimiEffectsMod;
import com.mimieffects.config.DissonanceBehavior;
import com.mimieffects.config.GlobalConfig;
import com.mimieffects.item.ModItems;
import com.mimieffects.item.NoteScrollItem;
import com.mimieffects.track.Arrangement;
import com.mimieffects.track.EffectEntry;
import com.mimieffects.track.EffectTargets;
import com.mimieffects.track.MobPurge;
import com.mimieffects.track.TrackConfig;
import com.mimieffects.orchestrator.ClusterAnalyzer;
import com.mimieffects.orchestrator.EffectCalculator;
import com.mimieffects.orchestrator.MobPurgeMatcher;
import com.mimieffects.orchestrator.PlayerSession;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TieredItem;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
    /** Horizontal blocks/tick of panic-flee velocity — about 10 blocks/sec, faster than a sprinting player. */
    private static final double FLEE_VELOCITY = 0.5;
    /** Extra ticks a mob keeps fleeing past its next expected purge hit, so it doesn't snap back to normal AI in the gap between intervals. */
    private static final long PANIC_GRACE_TICKS = 40;
    /**
     * Duration of the handheld-instrument Slowness II, refreshed on every
     * note. CHANGED (2026-09-12, user request): was tied to
     * GlobalConfig.SESSION_TIMEOUT_SECONDS (10s, and coupled to an
     * unrelated ensemble-clustering setting for no real reason) — split
     * into its own constant and shortened to 4s specifically.
     */
    private static final int PLAYING_SLOWNESS_DURATION_TICKS = 4 * 20;

    /**
     * Mobs currently overridden into fleeing, refreshed every purge
     * interval that still finds them qualifying. FIX (2026-09-12): a
     * one-shot setTarget(null) + velocity push per purge interval wasn't
     * enough — the mob's own AI goal selector (e.g.
     * NearestAttackableTargetGoal) just re-acquires the player as a target
     * on its own schedule, which is far more often than once a second, so
     * it walked right back at the player between purge hits ("мобы ...
     * идут в сторону меня"). onServerTick below reasserts both every tick
     * for anything in this map instead, which is cheap (no pathfinding,
     * just two field writes) even for a few dozen entries.
     */
    private static final Map<Mob, PanicState> PANICKING = new HashMap<>();

    private record PanicState(UUID sourcePlayerId, long expiresAtTick) {}

    /**
     * Set around the entity.hurt() call in purgeMobs() so
     * onExperienceDrop() (registered on NeoForge.EVENT_BUS in
     * MimiEffectsMod) knows a death it's seeing was caused by purge, not a
     * normal kill. Safe as a plain static: the whole call chain runs
     * synchronously on the single server thread, same as every other
     * NeoForge event dispatch.
     */
    private static boolean purgeKillInProgress = false;

    /**
     * Who's actively playing what, where, refreshed on every note that
     * reaches a resolved track+instrument. Feeds ClusterAnalyzer for
     * ensemble grouping (ТЗ §4.3) — see liveSessionsSnapshot() for pruning.
     */
    private static final Map<UUID, PlayerSession> ACTIVE_SESSIONS = new HashMap<>();

    private MimiNoteBridge() {}

    public static void onMimiNote(NoteEvent note) {
        if (note == null) return;
        // ADDED 2026-09-11: applyFor()'s trace never ran for events filtered
        // out right here — a note with type != NOTE_ON or a null senderId
        // (e.g. how MIMI might report events for some instrument setups)
        // vanished with zero log output, indistinguishable from the mixin
        // never firing at all. Log the raw event BEFORE that filter so this
        // silent-drop case is visible instead of looking identical to "mixin
        // isn't hooking".
        if (GlobalConfig.DEBUG_LOGGING.get()) {
            boolean willProcess = note.type == MidiEventType.NOTE_ON && note.senderId != null;
            debug(note.senderId, null, List.of(
                    "RAW note event: type=" + note.type + ", senderId=" + note.senderId
                            + ", instrumentId=" + note.instrumentId + ", pos=" + note.pos
                            + (willProcess ? "" : " -- DROPPED HERE (not NOTE_ON and/or senderId null)")));
        }
        if (note.type != MidiEventType.NOTE_ON || note.senderId == null) return;
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

        InstrumentSpec instrument = InstrumentConfig.getBydId(note.instrumentId);

        // ADDED 2026-09-12 (user request, doc §5): portable instruments slow
        // the player down while playing — MIMI itself already immobilizes
        // players on BLOCK instruments via its own EntitySeat riding
        // mechanic, so only the handheld case needs anything from us.
        // Slowness II is exactly vanilla's -30%/level step (15% × 2), and a
        // short duration refreshed on every note needs no separate
        // "stopped playing" bookkeeping — it just lapses shortly after the
        // last note. This runs regardless of whether fileId resolves to a
        // known track, matching the doc's framing of it as per-instrument,
        // not per-track.
        if (instrument != null && !Boolean.TRUE.equals(instrument.isBlock)) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                    PLAYING_SLOWNESS_DURATION_TICKS, 1, false, false, true));
        }

        TrackConfig track = MimiEffectsMod.TRACK_REGISTRY.find(status.fileId.toString());
        if (track == null || instrument == null || instrument.registryName == null) {
            trace.add("STOP: track=" + (track == null ? "NOT FOUND for this fileId — check track_id in your JSON matches EXACTLY, including no placeholder text" : "found")
                    + ", instrument=" + (instrument == null ? "unresolved" : instrument.registryName));
            debug(note.senderId, player, trace);
            return;
        }
        trace.add("track=" + track.display_name + ", instrument=mimi:" + instrument.registryName + ", isBlock=" + instrument.isBlock);

        String instrumentId = "mimi:" + instrument.registryName;
        long now = player.serverLevel().getGameTime();
        PlayerSession mySession = new PlayerSession(player.getUUID(), status.fileId.toString(), instrumentId,
                player.getX(), player.getY(), player.getZ(), now);
        ACTIVE_SESSIONS.put(player.getUUID(), mySession);

        // ADDED 2026-09-12 (user request): wires up the orchestrator
        // package (ClusterAnalyzer/EffectCalculator/PlayerSession) that
        // already implements the original ТЗ §4.3-4.5 ensemble design —
        // it existed as tested pure logic but nothing ever called it before
        // now ("ансамбли" being a known-incomplete feature). Clustering
        // distance is General.base_radius, already documented as "Radius of
        // the ensemble detection area" — this is exactly what it was for.
        // Built before purgeMobs (moved ahead of it) so purge can also use
        // ensemble size for its own radius scaling.
        List<PlayerSession> liveSessions = liveSessionsSnapshot(server, now);
        List<List<PlayerSession>> clusters = new ClusterAnalyzer<PlayerSession>(GlobalConfig.BASE_RADIUS.get()).cluster(liveSessions);
        List<PlayerSession> myCluster = List.of(mySession);
        for (List<PlayerSession> cluster : clusters) {
            if (cluster.contains(mySession)) {
                myCluster = cluster;
                break;
            }
        }

        // mob_purge is track-level, NOT arrangement-level — run it
        // independently of arrangement matching (see FIX 2026-09-05).
        purgeMobs(player, instrument, track.mob_purge, myCluster.size(), trace);

        // coverageScore expects "a flat multiset of available instruments"
        // for the whole cluster (its own javadoc), not just this one
        // player's instrument — so a duet track requiring two players on
        // piano2 only matches once BOTH are actually present and in range.
        Map<String, Integer> available = new HashMap<>();
        for (PlayerSession s : myCluster) {
            available.merge(s.instrumentId(), 1, Integer::sum);
        }

        Arrangement arrangement = findMatchingArrangement(track, available);
        if (arrangement == null || arrangement.effects == null || arrangement.effects.length == 0) {
            trace.add("STOP (effects): " + (arrangement == null
                    ? "no arrangement's required_instruments matched cluster instruments " + available
                    : "matched arrangement '" + arrangement.name + "' but it has no effects"));
            debug(note.senderId, player, trace);
            return;
        }
        // ADDED 2026-09-12 (user request): a hard headcount floor,
        // independent of instrument coverage — for a track that should do
        // nothing short of a full ensemble regardless of which instruments
        // make it up. See Arrangement.min_ensemble_size's javadoc.
        if (arrangement.min_ensemble_size != null && myCluster.size() < arrangement.min_ensemble_size) {
            trace.add("STOP (effects): arrangement '" + arrangement.name + "' needs min_ensemble_size="
                    + arrangement.min_ensemble_size + " but only " + myCluster.size() + " nearby");
            debug(note.senderId, player, trace);
            return;
        }
        // ADDED 2026-09-12 (user request): playing a track's effects at all
        // — solo or ensemble — requires the TRIGGERING player to be
        // carrying a Note Scroll bound to THIS track anywhere in their
        // inventory (not consumed, just checked — see NoteScrollItem). Not
        // craftable; obtained via the instrumentalist villager trade or
        // creative/give. Deliberately doesn't gate mob_purge — the user's
        // own framing was specifically about the buff/effect system.
        boolean hasScroll = player.getInventory().contains(stack ->
                stack.getItem() == ModItems.NOTE_SCROLL.get() && track.track_id.equals(NoteScrollItem.boundTrackId(stack)));
        if (!hasScroll) {
            trace.add("STOP (effects): no Note Scroll bound to this track (" + track.track_id + ") in " + player.getGameProfile().getName() + "'s inventory");
            debug(note.senderId, player, trace);
            return;
        }
        trace.add("arrangement='" + arrangement.name + "' matched, ensemble size=" + myCluster.size());

        boolean dissonantNearby = liveSessions.stream().anyMatch(s ->
                !s.playerId().equals(player.getUUID())
                        && !mySession.songId().equals(s.songId())
                        && mySession.distanceTo(s) <= GlobalConfig.BASE_RADIUS.get());
        DissonanceBehavior dissonanceBehavior = GlobalConfig.DISSONANCE_BEHAVIOR.get();
        if (dissonantNearby && dissonanceBehavior == DissonanceBehavior.CANCEL) {
            trace.add("STOP (effects): dissonance (another track playing nearby), Dissonance.behavior=CANCEL");
            debug(note.senderId, player, trace);
            return;
        }
        boolean applyDissonanceReduction = dissonantNearby && dissonanceBehavior == DissonanceBehavior.REDUCE;

        int durationTicks = (arrangement.duration_seconds != null ? arrangement.duration_seconds : GlobalConfig.DEFAULT_DURATION_SECONDS.get()) * 20;
        int radius = arrangement.radius != null ? arrangement.radius : GlobalConfig.BASE_RADIUS.get();
        List<LivingEntity> targets = findTargets(player, arrangement, radius);
        trace.add("targets=" + targets.size() + " within radius=" + radius);

        double coverage = arrangement.coverageScore(available);
        int applied = 0;
        for (EffectEntry entry : arrangement.effects) {
            // ADDED 2026-09-12 (user request): layered effects — e.g. one
            // entry active solo (and already scaling up with more players
            // below) plus a second entry that only joins in once the
            // ensemble reaches its own min_ensemble_size.
            if (myCluster.size() < entry.min_ensemble_size) {
                continue;
            }
            Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(entry.effect)).orElse(null);
            if (effect == null) {
                MimiEffectsMod.LOGGER.warn("Unknown mob effect '{}' in track '{}'.", entry.effect, track.display_name);
                continue;
            }
            int level = EffectCalculator.computeFinalLevel(entry.base_level, entry.max_level, myCluster.size(), coverage,
                    GlobalConfig.PER_PLAYER_BONUS.get(), GlobalConfig.FULL_ENSEMBLE_BONUS.get(),
                    applyDissonanceReduction, GlobalConfig.REDUCE_BY_LEVELS.get());
            if (level <= 0) {
                continue;
            }
            for (LivingEntity target : targets) target.addEffect(new MobEffectInstance(effect, durationTicks, level - 1, false, true, true));
            applied++;
        }
        trace.add("applied " + applied + " effect(s) to " + targets.size() + " target(s), ensemble=" + myCluster.size()
                + " coverage=" + coverage + (dissonantNearby ? " dissonance=" + dissonanceBehavior : ""));
        debug(note.senderId, player, trace);
    }

    /**
     * Prunes ACTIVE_SESSIONS of anyone who's logged off or gone quiet for
     * longer than General.session_timeout_seconds, returning what's left —
     * see the class-level note on PlayerSession for what this feeds.
     */
    private static List<PlayerSession> liveSessionsSnapshot(MinecraftServer server, long now) {
        long timeoutTicks = Math.max(1, GlobalConfig.SESSION_TIMEOUT_SECONDS.get()) * 20L;
        List<PlayerSession> live = new ArrayList<>();
        Iterator<Map.Entry<UUID, PlayerSession>> it = ACTIVE_SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PlayerSession> entry = it.next();
            PlayerSession session = entry.getValue();
            if (now - session.lastActiveTick() > timeoutTicks || server.getPlayerList().getPlayer(entry.getKey()) == null) {
                it.remove();
                continue;
            }
            live.add(session);
        }
        return live;
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

    private static void purgeMobs(ServerPlayer player, InstrumentSpec instrument, MobPurge purge, int ensembleSize, List<String> trace) {
        if (purge == null) {
            trace.add("purge: no mob_purge configured for this track");
            return;
        }
        if (purge.requires_static_instrument && !Boolean.TRUE.equals(instrument.isBlock)) {
            trace.add("purge: SKIPPED — requires_static_instrument=true but this instrument isBlock=" + instrument.isBlock);
            return;
        }

        // REDESIGNED (2026-09-12, user request): purge's reach can
        // optionally scale with ensemble size, opt-in via max_radius_blocks
        // (see its javadoc for why this isn't unbounded by default — an
        // ever-growing purge radius is a direct performance risk, per the
        // entity-count/TPS investigation earlier this session). Originally
        // this reused the Ensemble section's per_player_bonus multiplier,
        // but the user actually wanted a plain linear step instead: each
        // additional player adds one more radius_blocks' worth of reach,
        // capped at max_radius_blocks — so with radius_blocks=32 and
        // max_radius_blocks=128, 1 player=32, 2=64, 3=96, 4+=128. No extra
        // "players needed to reach max" field needed — that number falls
        // out of max_radius_blocks / radius_blocks on its own.
        int effectiveRadius = purge.radius_blocks;
        if (purge.max_radius_blocks != null && ensembleSize > 1) {
            effectiveRadius = (int) Math.min(purge.max_radius_blocks, (long) purge.radius_blocks * ensembleSize);
        }

        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        // FIX (2026-09-11): getOrDefault(..., Long.MIN_VALUE) then "now -
        // last" overflows a long on a player's very first purge (now -
        // Long.MIN_VALUE wraps around to a huge NEGATIVE number, which is
        // always < purge_interval_ticks) — so the cooldown branch fired
        // unconditionally, the entry below it was never reached, and purge
        // stayed permanently "on cooldown" for every player from note one.
        // This was the actual reason mob_purge never fired, independent of
        // instrument/config/arrangement — all of which were fine.
        Long lastBoxed = LAST_PURGE_TICK.get(player.getUUID());
        if (lastBoxed != null && now - lastBoxed < Math.max(1, purge.purge_interval_ticks)) {
            trace.add("purge: on cooldown (" + (Math.max(1, purge.purge_interval_ticks) - (now - lastBoxed)) + " ticks left)");
            return;
        }
        LAST_PURGE_TICK.put(player.getUUID(), now);

        int scanned = 0, skippedBoss = 0, skippedRule = 0, damaged = 0, died = 0;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(effectiveRadius))) {
            scanned++;
            // Added 2026-09-04: bosses are never purgeable, full stop —
            // checked before anything else, independent of environment/
            // hostile_only settings. EXTENDED 2026-09-12 (user request):
            // an admin can opt purge specifically back into hitting
            // boss-tier entities via GlobalConfig — regular effect
            // targeting (heal/buff) is untouched by this and still always
            // exempts bosses, see findTargets() above.
            if (!GlobalConfig.MOB_PURGE_IGNORES_BOSS_PROTECTION.get() && BossProtection.isProtected(entity)) {
                skippedBoss++;
                continue;
            }
            boolean tamed = entity instanceof TamableAnimal animal && animal.isTame();
            if (!MobPurgeMatcher.shouldPurge(entity instanceof ServerPlayer, EntityHostilityUtil.isHostile(entity), entity.isInWater(), tamed, purge.environment, purge.hostile_only)) {
                skippedRule++;
                continue;
            }
            // REDESIGNED (2026-09-11, user request): this used to be an
            // instant one-shot kill (1.0E6F damage). The user wants
            // qualifying mobs to suffer the music gradually and flee in
            // panic instead of dropping dead the instant it starts playing
            // — damage_per_tick is a normal, finite hit through the usual
            // combat pipeline (still respects isInvulnerableTo()), applied
            // once per purge_interval_ticks, so death takes several
            // intervals rather than one.
            //
            // purgeKillInProgress flags this specific hurt() call to
            // onExperienceDrop() below, so repeatedly purging mobs while
            // testing (or just playing) doesn't turn into a passive XP
            // farm — item/gear drops (LivingDropsEvent) are untouched, only
            // experience orbs are suppressed.
            purgeKillInProgress = true;
            try {
                entity.hurt(player.damageSources().playerAttack(player), (float) purge.damage_per_tick);
            } finally {
                purgeKillInProgress = false;
            }
            damaged++;
            if (entity.isDeadOrDying()) {
                died++;
            } else if (entity instanceof Mob mob) {
                // Panic: drop whatever it was doing and bolt away from the
                // player — "the music is unbearable" per the user's own
                // framing. FIX (2026-09-12): a one-shot push here wasn't
                // enough on its own — the mob's AI goal selector
                // re-targets the player on its own schedule (far more
                // often than once a purge interval), so it just walked
                // back. Registering it in PANICKING makes onServerTick
                // reassert this every tick instead, until the mob stops
                // qualifying for purge or this entry's grace period runs out.
                PANICKING.put(mob, new PanicState(player.getUUID(), now + Math.max(1, purge.purge_interval_ticks) + PANIC_GRACE_TICKS));
                applyPanicFlee(mob, player);
            }
        }
        trace.add("purge: scanned=" + scanned + " skipped(boss)=" + skippedBoss + " skipped(rule)=" + skippedRule
                + " damaged=" + damaged + " died=" + died
                + " [env=" + purge.environment + ", hostileOnly=" + purge.hostile_only + ", radius=" + effectiveRadius
                + (effectiveRadius != purge.radius_blocks ? " (base=" + purge.radius_blocks + ", ensemble=" + ensembleSize + ")" : "") + "]");
    }

    /**
     * Registered on NeoForge.EVENT_BUS in MimiEffectsMod. Reasserts flee
     * behavior every tick for whatever's in PANICKING, dropping entries
     * that died, unloaded, expired, or whose source player logged out.
     * Cheap even for a few dozen mobs — no pathfinding, just two field
     * writes per entry — see PANICKING's javadoc for why this exists
     * instead of only touching mobs from within purgeMobs().
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PANICKING.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Mob, PanicState>> it = PANICKING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Mob, PanicState> entry = it.next();
            Mob mob = entry.getKey();
            if (!mob.isAlive() || mob.isRemoved() || !(mob.level() instanceof ServerLevel level)) {
                it.remove();
                continue;
            }
            if (level.getGameTime() >= entry.getValue().expiresAtTick()) {
                it.remove();
                continue;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getValue().sourcePlayerId());
            if (player == null) {
                it.remove();
                continue;
            }
            applyPanicFlee(mob, player);
        }
    }

    private static void applyPanicFlee(Mob mob, ServerPlayer player) {
        mob.setTarget(null);
        double dx = mob.getX() - player.getX();
        double dz = mob.getZ() - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-3) {
            dx = 1.0;
            dz = 0.0;
            dist = 1.0;
        }
        // Overwrite (not add to) horizontal velocity — this runs every
        // tick now, and adding on top of existing velocity each time would
        // accumulate into an ever-increasing speed. Vertical velocity is
        // left alone so gravity keeps working normally.
        mob.setDeltaMovement((dx / dist) * FLEE_VELOCITY, mob.getDeltaMovement().y, (dz / dist) * FLEE_VELOCITY);
        mob.hasImpulse = true;
    }

    private static Arrangement findMatchingArrangement(TrackConfig track, Map<String, Integer> available) {
        if (track.arrangements == null) return null;
        for (Arrangement arrangement : track.arrangements) {
            if (arrangement != null && arrangement.coverageScore(available) >= 1.0) return arrangement;
        }
        return null;
    }

    /**
     * Registered on NeoForge.EVENT_BUS in MimiEffectsMod. Only cancels the
     * XP orb — item/gear drops go through a separate event
     * (LivingDropsEvent) and are untouched, per the user's request to keep
     * loot but not turn repeated purging into an experience farm.
     */
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (purgeKillInProgress) {
            event.setCanceled(true);
        }
    }

    /**
     * Registered on NeoForge.EVENT_BUS in MimiEffectsMod. Strips plain
     * vanilla-style material drops (bones, rotten flesh, string, ...) from
     * purge kills — user request, 2026-09-12: those items are extra
     * entities the client/server have to sync and simulate for basically
     * no reason once purge is killing dozens of mobs regularly. Armor,
     * tools/weapons and anything with notable rarity (enchanted, or a
     * modded "artifact" that declares its own Rarity above COMMON) are
     * left alone — see isNoteworthyDrop.
     */
    public static void onLivingDrops(LivingDropsEvent event) {
        if (purgeKillInProgress) {
            event.getDrops().removeIf(itemEntity -> !isNoteworthyDrop(itemEntity.getItem()));
        }
    }

    /**
     * Mod-agnostic "is this worth letting drop" check — deliberately not
     * tied to any specific mod's item classes or NBT (same philosophy as
     * MobPurge's targeting, see its class comment): armor/shields
     * (Equipable), swords/tools (TieredItem) and bows/crossbows
     * (ProjectileWeaponItem) always count as gear; anything else counts
     * only if its rarity is above COMMON — which ItemStack#getRarity()
     * already bumps for enchanted items, so a plain enchanted item (from
     * any mod) is covered without a separate isEnchanted() check.
     */
    private static boolean isNoteworthyDrop(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof Equipable || stack.getItem() instanceof TieredItem || stack.getItem() instanceof ProjectileWeaponItem) {
            return true;
        }
        return stack.getRarity() != Rarity.COMMON;
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
        // FIX (2026-09-11): same Long.MIN_VALUE overflow as LAST_PURGE_TICK
        // below — this is the actual reason debug_logging=true never
        // produced a single log line in any session: the throttle looked
        // "on cooldown" on the very first call for any given key and never
        // got the chance to record a real timestamp afterward.
        Long lastBoxed = LAST_DEBUG_TICK.get(senderId);
        if (lastBoxed != null && nowTick - lastBoxed < throttleTicks) {
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
