package com.mimieffects.integration;

import com.mimieffects.config.GlobalConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/**
 * Added 2026-09-04 per user request: bosses must be exempt from BOTH
 * mob_purge (so it can't be abused to trivially kill/damage boss fights)
 * and from ordinary track effects (so a "melody of life" playing near a
 * boss fight can't accidentally heal it via a hostile/friendly/neutral
 * targeting flag). Deliberately server-wide (GlobalConfig), not
 * per-track — a per-track exclude list is one an admin can easily forget
 * to add to any given file; this way it's enforced everywhere at once,
 * with vanilla's own Ender Dragon and Wither excluded by default.
 *
 * Two independent match mechanisms, both optional:
 * - Exact entity type ID (GlobalConfig.PROTECTED_ENTITY_TYPES)
 * - Entity type tag (GlobalConfig.PROTECTED_ENTITY_TAGS) — lets an admin
 *   protect an entire category (e.g. a modpack-wide "c:bosses" tag) if
 *   one exists on their server, without us having to guess at or
 *   hardcode any particular mod's boss list.
 */
public final class BossProtection {

    private BossProtection() {
    }

    public static boolean isProtected(LivingEntity entity) {
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());

        for (String exact : GlobalConfig.PROTECTED_ENTITY_TYPES.get()) {
            if (exact != null && exact.equals(typeId.toString())) {
                return true;
            }
        }

        for (String tagStr : GlobalConfig.PROTECTED_ENTITY_TAGS.get()) {
            if (tagStr == null || tagStr.isBlank()) {
                continue;
            }
            String clean = tagStr.startsWith("#") ? tagStr.substring(1) : tagStr;
            ResourceLocation tagId = ResourceLocation.tryParse(clean);
            if (tagId == null) {
                continue;
            }
            TagKey<EntityType<?>> tagKey = TagKey.create(Registries.ENTITY_TYPE, tagId);
            if (entity.getType().is(tagKey)) {
                return true;
            }
        }

        return false;
    }
}
