package com.mimieffects.item;

import com.mimieffects.MimiEffectsMod;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * ADDED 2026-09-12 (user request): "note scroll" gating mechanic — a single
 * registered Item (NOTE_SCROLL, see ModItems) bound to one specific track's
 * track_id via this component, the same way an enchanted book is one item
 * type distinguished by its stored enchantments. One Item + data instead of
 * one Item per track because tracks are arbitrary JSON files an admin can
 * add/remove at runtime (/mimieffects reload) — Items can only be
 * registered once, at mod init, so N dynamically-discovered tracks can
 * never each get their own real Item type.
 */
public final class ModDataComponents {
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MimiEffectsMod.MOD_ID);

    /** The track_id (as a String, matching TrackConfig.track_id) this scroll unlocks effects for. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> BOUND_TRACK =
            COMPONENTS.registerComponentType("bound_track", builder -> builder
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    private ModDataComponents() {
    }
}
