package com.mimieffects.item;

import com.mimieffects.MimiEffectsMod;
import com.mimieffects.track.Arrangement;
import com.mimieffects.track.EffectEntry;
import com.mimieffects.track.TrackConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ADDED 2026-09-12 (user request): a permanent, non-consumed "permit" —
 * holding one bound to a track anywhere in inventory is what lets that
 * track's arrangement effects apply at all (gate lives in
 * MimiNoteBridge.applyFor(), not here). Never crafted; obtained via
 * villager trade (VillagerTradeHandler) or creative/give.
 *
 * The bound track_id lives in the BOUND_TRACK data component (see
 * ModDataComponents) — display resolves it against the CURRENT
 * TrackRegistry every time (not baked in at trade time), so edits to a
 * track's names in the JSON/editor show up on existing scrolls too.
 *
 * REDESIGNED (2026-09-13, user request): reverted the 2026-09-12 redesign
 * — the item's displayed NAME is back to the generic "Note Scroll" (plain
 * super.getName(), no override needed), with the track's thematic "spell
 * name" (TrackConfig.spell_name) shown as the first tooltip line instead.
 * The user's own reasoning: "сначала должно быть имя предмета, а потом уже
 * заклинание" (the item's name should come first, the spell after) — seeing
 * "Незримость" as the bold title with no visual indication it was a Note
 * Scroll read as broken, whereas vanilla items (enchanted books, potions)
 * always lead with what KIND of item it is.
 */
public final class NoteScrollItem extends Item {

    private static final String[] ROMAN_NUMERALS = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
    // Plain char-count wrapping rather than pixel-accurate font.split() — good
    // enough for tooltip text and avoids depending on Minecraft's Font metrics
    // for something this cosmetic.
    private static final int WRAP_CHARS = 40;

    public NoteScrollItem(Properties properties) {
        super(properties);
    }

    public static String boundTrackId(ItemStack stack) {
        return stack.get(ModDataComponents.BOUND_TRACK.get());
    }

    public static ItemStack forTrack(String trackId) {
        ItemStack stack = new ItemStack(ModItems.NOTE_SCROLL.get());
        stack.set(ModDataComponents.BOUND_TRACK.get(), trackId);
        return stack;
    }

    /**
     * ADDED 2026-09-12 (user request): vanilla's enchantment glint is tied
     * to an item actually carrying enchantment data (EnchantedBookItem),
     * not to its texture — swapping the model to a book texture alone
     * doesn't give it the shimmer. Wired up directly instead, and only for
     * a bound scroll (an unbound one is "useless like this" per its
     * tooltip, so it shouldn't look special).
     */
    @Override
    public boolean isFoil(ItemStack stack) {
        return boundTrackId(stack) != null;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        String trackId = boundTrackId(stack);
        if (trackId == null) {
            addWrapped(tooltipComponents, I18n.get("item.mimieffects.note_scroll.unbound"), ChatFormatting.RED);
            return;
        }
        TrackConfig track = MimiEffectsMod.TRACK_REGISTRY.find(trackId);
        if (track == null) {
            addWrapped(tooltipComponents, I18n.get("item.mimieffects.note_scroll.tooltip_missing"), ChatFormatting.RED);
            return;
        }

        // MOVED HERE 2026-09-13 (user report): the spell_name used to BE the
        // item's name; now the name is the generic "Note Scroll" and this
        // (admin-authored free text, so literal() not translatable() — same
        // reasoning as TrackEditorScreen's own data fields) is the first
        // tooltip line instead, styled to read as a headline under the title.
        if (track.spell_name != null && !track.spell_name.isBlank()) {
            tooltipComponents.add(Component.literal(track.spell_name).withStyle(ChatFormatting.LIGHT_PURPLE));
        }

        String trackName = track.display_name != null && !track.display_name.isBlank() ? track.display_name : trackId;
        String artistLine = track.artist != null && !track.artist.isBlank() ? track.artist + " — " + trackName : trackName;
        addWrapped(tooltipComponents, artistLine, ChatFormatting.ITALIC, ChatFormatting.GRAY);

        if (track.midi_file_name != null && !track.midi_file_name.isBlank()) {
            addWrapped(tooltipComponents, I18n.get("item.mimieffects.note_scroll.file", track.midi_file_name), ChatFormatting.DARK_GRAY);
        }

        Arrangement arrangement = track.arrangements != null && track.arrangements.length > 0 ? track.arrangements[0] : null;
        // FIX (2026-09-13, user report): the tooltip listed WHAT the scroll
        // grants but never WHICH instruments trigger it — playing was pure
        // guesswork. Detailed info (instruments + per-effect ensemble-size
        // thresholds) is tucked behind Shift, vanilla-idiomatic ("Hold
        // SHIFT for more information"), so the default tooltip stays short.
        boolean detailed = Screen.hasShiftDown();

        if (arrangement != null && arrangement.effects != null && arrangement.effects.length > 0) {
            tooltipComponents.add(Component.translatable("item.mimieffects.note_scroll.effects_header").withStyle(ChatFormatting.GRAY));
            for (EffectEntry entry : arrangement.effects) {
                tooltipComponents.add(effectLine(entry, detailed));
            }
        }

        boolean hasInstruments = arrangement != null && arrangement.required_instruments != null && !arrangement.required_instruments.isEmpty();
        if (hasInstruments && detailed) {
            tooltipComponents.add(Component.translatable("item.mimieffects.note_scroll.instruments_header").withStyle(ChatFormatting.GRAY));
            for (Map.Entry<String, Integer> e : arrangement.required_instruments.entrySet()) {
                tooltipComponents.add(instrumentLine(e.getKey(), e.getValue()));
            }
        } else if (hasInstruments) {
            tooltipComponents.add(Component.translatable("item.mimieffects.note_scroll.hold_shift").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }

        addWrapped(tooltipComponents, I18n.get("item.mimieffects.note_scroll.tooltip"), ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
    }

    /** ADDED 2026-09-12 (user request): the fixed tooltip strings ran off the edge of the screen unwrapped. */
    private static void addWrapped(List<Component> out, String text, ChatFormatting... styles) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String line : wrap(text, WRAP_CHARS)) {
            out.add(Component.literal(line).withStyle(styles));
        }
    }

    private static List<String> wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() > 0 && current.length() + 1 + word.length() > maxChars) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static Component effectLine(EffectEntry entry, boolean detailed) {
        Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(entry.effect)).orElse(null);
        Component name = effect != null ? effect.value().getDisplayName() : Component.literal(entry.effect);
        MutableComponent line = Component.literal("- ").append(name);
        if (entry.base_level > 1) {
            line.append(" ").append(romanNumeral(entry.base_level));
        }
        if (detailed) {
            line.append(" ").append(Component.translatable(
                    entry.min_ensemble_size > 1 ? "item.mimieffects.note_scroll.needs_players" : "item.mimieffects.note_scroll.solo_ok",
                    entry.min_ensemble_size
            ).withStyle(ChatFormatting.DARK_GRAY));
        }
        return line.withStyle(ChatFormatting.BLUE);
    }

    /** ADDED 2026-09-13 (user request): shows which instrument(s) actually trigger this scroll's effects, not just what it grants. */
    private static Component instrumentLine(String instrumentId, int count) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(instrumentId));
        Component name = item != null && item != net.minecraft.world.item.Items.AIR
                ? new ItemStack(item).getHoverName()
                : Component.literal(instrumentId);
        MutableComponent line = Component.literal("- ").append(name);
        if (count > 1) {
            line.append(" x" + count);
        }
        return line.withStyle(ChatFormatting.AQUA);
    }

    private static String romanNumeral(int level) {
        return level >= 0 && level < ROMAN_NUMERALS.length ? ROMAN_NUMERALS[level] : String.valueOf(level);
    }
}
