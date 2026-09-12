package com.mimieffects;

import com.mimieffects.command.MimiEffectsCommands;
import com.mimieffects.config.GlobalConfig;
import com.mimieffects.integration.MimiNoteBridge;
import com.mimieffects.item.ModDataComponents;
import com.mimieffects.item.ModItems;
import com.mimieffects.item.NoteScrollItem;
import com.mimieffects.item.VillagerTradeHandler;
import com.mimieffects.network.NetworkRegistration;
import com.mimieffects.track.TrackConfig;
import com.mimieffects.track.TrackLoader;
import com.mimieffects.track.TrackRegistry;
import com.mimieffects.track.TrackScaffolder;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.io.IOException;

/**
 * The mod's actual entry point.
 *
 * THIS CLASS DID NOT EXIST until now — that is the entire reason the built
 * jar "did nothing": neoforge.mods.toml declares the mod's metadata (name,
 * id, dependencies), but NeoForge only runs code from a class annotated
 * with @Mod. Without one, the jar is a syntactically valid, completely
 * inert mod — it loads, shows up in the mods list, and executes exactly
 * zero lines of logic. Every class built so far (EffectCalculator,
 * ClusterAnalyzer, TrackConfig, MobPurgeMatcher, GlobalConfig...) was pure
 * library code sitting on disk with nothing ever calling it.
 *
 * Constructor signature confirmed against NeoForge 1.21.1 docs
 * (docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles,
 * .../misc/config): as of NeoForge 21.0, mod constructors take
 * (IEventBus modEventBus, ModContainer modContainer) directly —
 * FMLJavaModLoadingContext.get() was removed, and
 * ModLoadingContext#registerConfig was replaced by
 * ModContainer#registerConfig.
 */
@Mod(MimiEffectsMod.MOD_ID)
public final class MimiEffectsMod {

    public static final String MOD_ID = "mimieffects";
    public static final Logger LOGGER = LoggerFactory.getLogger("MimiEffects");

    /** Shared in-memory registry, populated by /mimieffects reload. */
    public static final TrackRegistry TRACK_REGISTRY = new TrackRegistry();

    public MimiEffectsMod(IEventBus modEventBus, ModContainer modContainer) {
        // Registers config/mimieffects-server.toml, synced to clients on join.
        modContainer.registerConfig(ModConfig.Type.SERVER, GlobalConfig.SPEC);

        // RegisterCommandsEvent fires on the game bus (NeoForge.EVENT_BUS),
        // not the mod bus — this is what actually gives you "/mimieffects
        // reload" in-game, which was the other missing piece.
        NeoForge.EVENT_BUS.addListener(MimiEffectsCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        // Suppresses XP orb drops (only) from purge kills — see
        // MimiNoteBridge.onExperienceDrop for why (2026-09-11, user
        // request: purge shouldn't double as a passive XP farm).
        NeoForge.EVENT_BUS.addListener(MimiNoteBridge::onExperienceDrop);
        // Strips plain material drops (bones, rotten flesh, ...) from
        // purge kills, keeping gear/weapons/notable-rarity items — see
        // MimiNoteBridge.onLivingDrops (2026-09-12, user request: fewer
        // leftover item entities for the client/server to simulate).
        NeoForge.EVENT_BUS.addListener(MimiNoteBridge::onLivingDrops);
        // Keeps purge-panicked mobs actually fleeing every tick instead of
        // their own AI re-targeting the player between purge intervals —
        // see MimiNoteBridge.onServerTick (2026-09-12).
        NeoForge.EVENT_BUS.addListener(MimiNoteBridge::onServerTick);
        // Adds the Note Scroll trade to MIMI's own instrumentalist
        // villager — see VillagerTradeHandler (2026-09-12, user request).
        NeoForge.EVENT_BUS.addListener(VillagerTradeHandler::onVillagerTrades);

        // Server-bound (playToServer) network registration only — the
        // client-bound half lives in MimiEffectsModClient, which is the
        // only class allowed to reference client-side handler code.
        modEventBus.addListener(NetworkRegistration::registerCommon);

        // Note Scroll item + the data component that binds one to a
        // specific track — see NoteScrollItem's javadoc (2026-09-12).
        ModItems.ITEMS.register(modEventBus);
        ModDataComponents.COMPONENTS.register(modEventBus);
        // Lists one already-bound scroll per track in creative — see
        // onBuildCreativeTab. "Or take it from creative" was one of the two
        // acquisition paths the user asked for, the other being the
        // villager trade.
        modEventBus.addListener(this::onBuildCreativeTab);

        LOGGER.info("MimiEffects constructed — commands, config and networking registered.");
    }

    /** Load the track files before the first MIMI note can arrive. */
    private void onServerStarting(ServerStartingEvent event) {
        try {
            java.nio.file.Files.createDirectories(tracksDir());
            TrackScaffolder.Result scaffold = TrackScaffolder.scaffoldMissingTracks(midiDir(), tracksDir());
            if (scaffold.created > 0) {
                LOGGER.info("Created {} MimiEffects track configuration stub(s).", scaffold.created);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not create MimiEffects track configuration stubs.", e);
        }
        TrackLoader.loadAll(tracksDir(), TRACK_REGISTRY);
        LOGGER.info("Loaded {} MimiEffects track configuration(s).", TRACK_REGISTRY.all().size());
    }

    private void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.INGREDIENTS) {
            return;
        }
        // ADDED 2026-09-12 (user request): show one ALREADY-BOUND scroll per
        // currently loaded track, like enchanted books listing one entry
        // per enchantment, instead of one useless unbound stack — an admin
        // can just take the right one instead of hand-typing a track_id.
        // Falls back to a single unbound entry if TRACK_REGISTRY is empty,
        // which it will be if this fires before any world has ever been
        // loaded this client session (tracks only load via
        // ServerStartingEvent) — otherwise the item would be invisible in
        // creative entirely instead of just less convenient.
        boolean any = false;
        for (TrackConfig track : MimiEffectsMod.TRACK_REGISTRY.all()) {
            if (track.track_id == null) {
                continue;
            }
            event.accept(NoteScrollItem.forTrack(track.track_id));
            any = true;
        }
        if (!any) {
            event.accept(new ItemStack(ModItems.NOTE_SCROLL.get()));
        }
    }

    /** config/mimieffects/tracks — shared by the reload command and the network save handler. */
    public static Path tracksDir() {
        return FMLPaths.CONFIGDIR.get().resolve("mimieffects").resolve("tracks");
    }

    /**
     * config/mimi/server_midi_files — MIMI's own folder ("mimi" confirmed
     * as MIMI's config dir constant by reading its source, see
     * INTEGRATION.md).
     */
    public static Path midiDir() {
        return FMLPaths.CONFIGDIR.get().resolve("mimi").resolve("server_midi_files");
    }
}
