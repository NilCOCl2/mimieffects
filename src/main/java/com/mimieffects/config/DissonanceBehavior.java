package com.mimieffects.config;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

import java.util.Locale;

/**
 * ADDED 2026-09-12 (user request): was a plain ConfigValue<String>, which
 * NeoForge's config screen can only show as a free-text edit box — no
 * cycling choice, and no way to reject a typo like "reduec". A proper enum
 * (defineEnum) gets the same three fixed choices but as a real cycle
 * button, with a translated label via TranslatableEnum instead of the raw
 * "REDUCE"/"CANCEL"/"IGNORE" constant name.
 */
public enum DissonanceBehavior implements TranslatableEnum {
    REDUCE,
    CANCEL,
    IGNORE;

    @Override
    public Component getTranslatedName() {
        return Component.translatable("mimieffects.configuration.dissonance.behavior." + name().toLowerCase(Locale.ROOT));
    }
}
