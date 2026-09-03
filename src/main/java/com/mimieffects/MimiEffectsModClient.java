package com.mimieffects;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * This is the "config button" the user reported missing. It's a SEPARATE
 * @Mod entry point (same modId, dist = CLIENT) — NeoForge requires the
 * config screen extension point to be registered from a client-only
 * constructor, since ConfigurationScreen/IConfigScreenFactory are client
 * classes that don't exist on a dedicated server. See
 * docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles ("note: an entry
 * in neoforge.mods.toml can have multiple @Mod annotations").
 *
 * IMPORTANT (see BUILD.md / README): NeoForge's own config screen only
 * lets you edit a SERVER-type config (which is what GlobalConfig is) when
 * you're the local host — singleplayer or LAN. On a real dedicated server
 * this button will still show but the fields will be greyed out for
 * anyone connecting remotely. That's expected NeoForge behavior, not a
 * bug in this mod — see README "Решение по конфигурированию сервера" for
 * why /mimieffects reload + editing the TOML directly is the real admin
 * workflow for production servers.
 */
@Mod(value = MimiEffectsMod.MOD_ID, dist = Dist.CLIENT)
public final class MimiEffectsModClient {

    public MimiEffectsModClient(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
