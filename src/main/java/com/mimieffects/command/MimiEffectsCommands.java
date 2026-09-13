package com.mimieffects.command;

import com.mimieffects.MimiEffectsMod;
import com.mimieffects.config.GlobalConfig;
import com.mimieffects.track.ScrollGenerator;
import com.mimieffects.track.TrackLoader;
import com.mimieffects.track.TrackScaffolder;
import com.mimieffects.track.TrackSyncUtil;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The other missing piece: neither this repo nor the friend's build had
 * any command registered anywhere, so "/mimieffects reload" (from ТЗ §8's
 * readiness criteria) never existed as a real command — there was nothing
 * wrong with typing it, it simply wasn't registered with Brigadier.
 *
 * RegisterCommandsEvent fires on NeoForge.EVENT_BUS (the game bus), see
 * MimiEffectsMod for where this gets subscribed.
 *
 * FIX (2026-09-01): all chat feedback uses Component.translatable(key, ...)
 * with lang-file entries (assets/mimieffects/lang/*.json) instead of
 * Component.literal("...кириллица...") baked into this .java file. Two
 * reasons: (1) it sidesteps a real bug we hit — javac on Windows without
 * an explicit UTF-8 encoding (see build.gradle) mangled Cyrillic string
 * literals into mojibake at compile time; lang JSON files are read by
 * Minecraft's own resource loader, which is UTF-8 by contract, so this
 * class of bug can't recur here. (2) it's also just the idiomatic
 * Minecraft-modding way to support multiple languages.
 *
 * ADDED (2026-09-02): "/mimieffects gui" — opens the Track Editor screen
 * for the calling player by pushing them a TracksSyncPayload directly.
 */
public final class MimiEffectsCommands {

    private MimiEffectsCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("mimieffects")
                        .requires(source -> source.hasPermission(2)) // OP only
                        .then(Commands.literal("reload").executes(MimiEffectsCommands::runReload))
                        .then(Commands.literal("gui").executes(MimiEffectsCommands::runGui))
                        .then(Commands.literal("genscrolls")
                                .executes(MimiEffectsCommands::runGenScrolls)
                                // ADDED 2026-09-13 (user request): "если не
                                // писать то дефолт, а если число стоит то
                                // максимум по числу" — caps how many
                                // effects/instruments/ensemble tiers a
                                // generated scroll can demand; omitted =
                                // ScrollGenerator.DEFAULT_MAX_ENSEMBLE_SIZE.
                                .then(Commands.argument("maxEnsembleSize", IntegerArgumentType.integer(1))
                                        .executes(MimiEffectsCommands::runGenScrollsWithMax)))
                        .then(Commands.literal("clearscrolls")
                                .executes(MimiEffectsCommands::runClearScrollsPrompt)
                                .then(Commands.literal("confirm").executes(MimiEffectsCommands::runClearScrolls)))
        );
    }

    /**
     * ADDED 2026-09-13 (user request): resets every track's generated
     * instruments/effects/mob_purge back to a blank stub so the set can be
     * regenerated from scratch — destructive, so it requires the explicit
     * "confirm" literal rather than firing on the bare command.
     */
    private static int runClearScrollsPrompt(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.translatable("mimieffects.command.clearscrolls.confirm_needed"));
        return 0;
    }

    private static int runClearScrolls(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Path tracksDir = MimiEffectsMod.tracksDir();

        int cleared;
        try {
            cleared = ScrollGenerator.clearAll(tracksDir);
        } catch (IOException e) {
            source.sendFailure(Component.translatable("mimieffects.command.clearscrolls.failed", e.getMessage()));
            MimiEffectsMod.LOGGER.error("clearscrolls: failed to reset tracks", e);
            return 0;
        }

        int loaded = TrackLoader.loadAll(tracksDir, MimiEffectsMod.TRACK_REGISTRY);
        broadcastTrackCache();

        final int finalCleared = cleared;
        source.sendSuccess(() -> Component.translatable("mimieffects.command.clearscrolls.success", finalCleared), true);

        return loaded;
    }

    /**
     * ADDED 2026-09-12 (user request): auto-fills every still-unconfigured
     * track stub with a random combination of vanilla buffs + matching
     * instruments (see ScrollGenerator) instead of requiring the admin to
     * hand-build each one through the Track Editor.
     */
    private static int runGenScrolls(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return runGenScrolls(ctx, ScrollGenerator.DEFAULT_MAX_ENSEMBLE_SIZE);
    }

    private static int runGenScrollsWithMax(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return runGenScrolls(ctx, IntegerArgumentType.getInteger(ctx, "maxEnsembleSize"));
    }

    private static int runGenScrolls(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, int maxEnsembleSize) {
        CommandSourceStack source = ctx.getSource();
        Path tracksDir = MimiEffectsMod.tracksDir();

        ScrollGenerator.Result result;
        try {
            result = ScrollGenerator.generate(
                    tracksDir,
                    TrackSyncUtil.availableInstrumentIds(),
                    GlobalConfig.PREFER_COMMON_INSTRUMENTS.get(),
                    maxEnsembleSize
            );
        } catch (IOException e) {
            source.sendFailure(Component.translatable("mimieffects.command.genscrolls.failed", e.getMessage()));
            MimiEffectsMod.LOGGER.error("genscrolls: failed to scan tracks dir", e);
            return 0;
        }

        int loaded = TrackLoader.loadAll(tracksDir, MimiEffectsMod.TRACK_REGISTRY);
        broadcastTrackCache();

        final int finalGenerated = result.generated;
        final int finalScanned = result.scanned;
        source.sendSuccess(
                () -> Component.translatable("mimieffects.command.genscrolls.success", finalGenerated, finalScanned),
                true
        );

        return loaded;
    }

    private static int runGui(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException(); // console has no screen to open

        try {
            PacketDistributor.sendToPlayer(player, TrackSyncUtil.buildSyncPayload(MimiEffectsMod.tracksDir()));
        } catch (IOException e) {
            source.sendFailure(Component.translatable("mimieffects.command.gui.failed", e.getMessage()));
            MimiEffectsMod.LOGGER.error("gui: failed to build track sync payload", e);
            return 0;
        }

        return 1;
    }

    private static int runReload(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        Path tracksDir = MimiEffectsMod.tracksDir();
        Path midiDir = MimiEffectsMod.midiDir();

        int loadedTracks;
        try {
            java.nio.file.Files.createDirectories(tracksDir);
            loadedTracks = TrackLoader.loadAll(tracksDir, MimiEffectsMod.TRACK_REGISTRY);
        } catch (IOException e) {
            source.sendFailure(Component.translatable("mimieffects.command.reload.load_failed", tracksDir, e.getMessage()));
            MimiEffectsMod.LOGGER.error("reload: failed to load tracks", e);
            return 0;
        }

        int scaffolded = 0;
        try {
            TrackScaffolder.Result result = TrackScaffolder.scaffoldMissingTracks(midiDir, tracksDir);
            scaffolded = result.created;
            if (result.created > 0) {
                // Reload again so the just-created stubs are picked up
                // immediately instead of requiring a second /reload.
                loadedTracks = TrackLoader.loadAll(tracksDir, MimiEffectsMod.TRACK_REGISTRY);
            }
        } catch (IOException e) {
            // Non-fatal: scaffolding failing shouldn't hide a successful
            // track reload from the admin.
            source.sendFailure(Component.translatable("mimieffects.command.reload.scaffold_failed", e.getMessage()));
            MimiEffectsMod.LOGGER.warn("reload: scaffolding failed", e);
        }

        broadcastTrackCache();

        final int finalLoaded = loadedTracks;
        final int finalScaffolded = scaffolded;
        source.sendSuccess(
                () -> Component.translatable("mimieffects.command.reload.success", finalLoaded, finalScaffolded),
                true
        );

        return finalLoaded;
    }

    /**
     * ADDED 2026-09-13 (user report, dedicated server): every command that
     * changes what's in TRACK_REGISTRY needs to push that out to every
     * connected client's own copy too — see TrackCacheSyncPayload's javadoc
     * for why a client's registry doesn't just stay in sync on its own.
     */
    private static void broadcastTrackCache() {
        PacketDistributor.sendToAllPlayers(TrackSyncUtil.buildCacheSyncPayload());
    }
}
