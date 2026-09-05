package com.mimieffects.integration;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/**
 * Added 2026-09-04. Three admin-configurable, mod-agnostic ways to match
 * an entity against a list, shared by BossProtection (exclude) and
 * EntityHostilityUtil (extra-include for mob_purge/effect targeting):
 *
 * - exact entity type ID ("minecraft:wither")
 * - entity type tag ("#c:bosses")
 * - whole mod namespace ("born_in_chaos_v1") — matches EVERY entity type
 *   registered by that mod. Deliberately opt-in per mod (never guessed
 *   or defaulted by us beyond the two examples the user explicitly named,
 *   see GlobalConfig) since "every entity in this mod" is only a safe
 *   assumption for mods that are entirely hostile-creature packs — the
 *   admin is the one who knows that about their modpack, not us.
 */
final class EntityMatchUtil {

    private EntityMatchUtil() {
    }

    static boolean matchesAny(LivingEntity entity, List<? extends String> exactTypes,
                               List<? extends String> tags, List<? extends String> namespaces) {
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());

        if (namespaces != null) {
            for (String ns : namespaces) {
                if (ns != null && ns.equals(typeId.getNamespace())) {
                    return true;
                }
            }
        }

        if (exactTypes != null) {
            String typeIdStr = typeId.toString();
            for (String exact : exactTypes) {
                if (exact != null && exact.equals(typeIdStr)) {
                    return true;
                }
            }
        }

        if (tags != null) {
            for (String tagStr : tags) {
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
        }

        return false;
    }
}
