package com.mimieffects.command;

import com.mimieffects.MimiEffectsMod;
import com.mimieffects.track.TrackLoader;
import com.mimieffects.track.TrackScaffolder;
import com.mimieffects.track.TrackSyncUtil;

import com.mojang.brigadier.CommandDispatcher;
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
        );
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

        final int finalLoaded = loadedTracks;
        final int finalScaffolded = scaffolded;
        source.sendSuccess(
                () -> Component.translatable("mimieffects.command.reload.success", finalLoaded, finalScaffolded),
                true
        );

        return finalLoaded;
    }
}
