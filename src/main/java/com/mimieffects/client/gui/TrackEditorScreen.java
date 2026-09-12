package com.mimieffects.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import com.mimieffects.network.SaveTrackPayload;
import com.mimieffects.network.TrackFileDto;
import com.mimieffects.track.Arrangement;
import com.mimieffects.track.EffectEntry;
import com.mimieffects.track.EffectTargets;
import com.mimieffects.track.MobPurge;
import com.mimieffects.track.TrackConfig;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-game "configurator" — lets an admin pick a track (loaded from
 * config/mimieffects/tracks/*.json, including TrackScaffolder's
 * auto-generated stubs) and assign instruments/effects/mob_purge to it
 * without hand-editing JSON.
 *
 * KNOWN SCOPE LIMITS (being upfront rather than silent about them):
 * - Only arrangements[0] is editable. A track with multiple arrangements
 *   (e.g. zemlya.json's "Full Band"/"Acoustic Duo"/"Solo") will have its
 *   OTHER arrangements preserved on save (not deleted), but you can't
 *   edit or add them from this screen yet — hand-edit the JSON for that,
 *   or ask for arrangement-switching to be added as a follow-up.
 * - Instrument/effect row counts are capped by GlobalConfig.
 *   MAX_INSTRUMENTS_PER_ARRANGEMENT/MAX_EFFECTS_PER_ARRANGEMENT (default 5
 *   each) — more than that per arrangement needs hand-editing, or raising
 *   those config values.
 * - track_id is never editable here (it's derived from the real MIDI
 *   file by MIMI itself — see INTEGRATION.md — editing it would
 *   just disconnect the track from the song that's supposed to trigger it).
 *
 * FIX (2026-09-06): every UI string is now Component.translatable(key)
 * with entries in both assets/mimieffects/lang/en_us.json and ru_ru.json,
 * instead of a mix of hardcoded English (my original literals) and
 * hardcoded Russian (added later) Component.literal(...) calls. literal()
 * completely ignores the client's language setting, so the previous mix
 * showed up as a jumble of both languages regardless of which one the
 * player's client was set to — this wasn't a translation FILE bug, it
 * was that most of the screen was never wired to the translation system
 * at all. Labels drawn directly via GuiGraphics#drawString (not real
 * Component widgets) are resolved once via Component.translatable(key)
 * .getString() at the point they're built, which still respects the
 * client's locale.
 */
public final class TrackEditorScreen extends Screen {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] ENVIRONMENTS = {"surface", "underwater", "any"};

    private List<TrackFileDto> tracks;
    private int page = 0;
    private static final int TRACKS_PER_PAGE = 8;

    private List<String> instrumentIds;
    private List<String> effectIds;

    private String selectedFileName;
    private TrackConfig selectedConfig;
    private Arrangement editingArrangement;
    private final List<InstrumentRow> instrumentRows = new ArrayList<>();
    private final List<EffectRow> effectRows = new ArrayList<>();
    private final List<EditorLabel> editorLabels = new ArrayList<>();
    private String statusMessage = "";

    // Picker overlay state — see openPicker()/buildPickerWidgets().
    private static final int PICKER_PAGE_SIZE = 12;
    private List<String> pickerOptions;
    private String pickerFilter = "";
    private int pickerPage = 0;
    private java.util.function.Consumer<String> pickerCallback;
    // ADDED 2026-09-13 (user report): the picker (and the summary buttons
    // behind it) used to show raw ids like "mimi:accordion2" / "minecraft:
    // water_breathing" — tells this rebuild whether to resolve rows through
    // instrumentDisplayName() or effectDisplayName() for a human label.
    private boolean pickerIsInstruments;

    public TrackEditorScreen(List<TrackFileDto> tracks, List<String> instrumentIds, List<String> effectIds) {
        super(Component.translatable("mimieffects.editor.title"));
        this.tracks = tracks;
        this.instrumentIds = instrumentIds != null ? instrumentIds : new ArrayList<>();
        this.effectIds = effectIds != null ? effectIds : new ArrayList<>();
    }

    /** Called by ClientPayloadHandlers when a fresh sync arrives while this screen is already open. */
    public void updateTracks(List<TrackFileDto> newTracks, List<String> newInstrumentIds, List<String> newEffectIds) {
        this.tracks = newTracks;
        this.instrumentIds = newInstrumentIds != null ? newInstrumentIds : this.instrumentIds;
        this.effectIds = newEffectIds != null ? newEffectIds : this.effectIds;
        this.statusMessage = Component.translatable("mimieffects.editor.status.saved").getString();
        rebuild();
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        this.clearWidgets();
        this.editorLabels.clear();

        if (pickerCallback != null) {
            buildPickerWidgets();
            return;
        }

        int listX = 10;
        int listY = 30;
        int rowHeight = 20;

        int start = page * TRACKS_PER_PAGE;
        int end = Math.min(start + TRACKS_PER_PAGE, tracks.size());
        for (int i = start; i < end; i++) {
            TrackFileDto file = tracks.get(i);
            String label = displayLabelFor(file);
            int rowIndex = i - start;
            this.addRenderableWidget(Button.builder(Component.literal(truncate(label, 24)), btn -> selectTrack(file))
                    .bounds(listX, listY + rowIndex * rowHeight, 160, rowHeight - 2)
                    .build());
        }

        int pageY = listY + TRACKS_PER_PAGE * rowHeight + 4;
        if (page > 0) {
            this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.prev"), btn -> {
                page--;
                rebuild();
            }).bounds(listX, pageY, 75, 18).build());
        }
        if (end < tracks.size()) {
            this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.next"), btn -> {
                page++;
                rebuild();
            }).bounds(listX + 85, pageY, 75, 18).build());
        }

        this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.close"), btn -> this.onClose())
                .bounds(listX, this.height - 28, 160, 20)
                .build());

        if (selectedConfig != null) {
            buildEditorWidgets(200, 30);
        }
    }

    private String displayLabelFor(TrackFileDto file) {
        try {
            TrackConfig parsed = GSON.fromJson(file.json, TrackConfig.class);
            if (parsed != null && parsed.display_name != null && !parsed.display_name.isBlank()) {
                return parsed.display_name;
            }
        } catch (JsonSyntaxException ignored) {
            // Fall through to filename — a broken file is still selectable
            // so the admin can fix it here rather than only in a text editor.
        }
        return file.fileName;
    }

    private void selectTrack(TrackFileDto file) {
        this.selectedFileName = file.fileName;
        try {
            this.selectedConfig = GSON.fromJson(file.json, TrackConfig.class);
        } catch (JsonSyntaxException e) {
            this.statusMessage = Component.translatable("mimieffects.editor.status.invalid_json", e.getMessage()).getString();
            this.selectedConfig = null;
            rebuild();
            return;
        }
        if (this.selectedConfig == null) {
            this.selectedConfig = new TrackConfig();
        }
        if (this.selectedConfig.arrangements == null || this.selectedConfig.arrangements.length == 0) {
            this.selectedConfig.arrangements = new Arrangement[] { new Arrangement() };
        }
        this.editingArrangement = this.selectedConfig.arrangements[0];
        if (this.editingArrangement.required_instruments == null) {
            this.editingArrangement.required_instruments = new LinkedHashMap<>();
        }
        if (this.editingArrangement.effects == null) {
            this.editingArrangement.effects = new EffectEntry[0];
        }
        if (this.editingArrangement.targets == null) {
            this.editingArrangement.targets = new EffectTargets();
            this.editingArrangement.targets.players = !"ensemble".equals(this.editingArrangement.affects);
            this.editingArrangement.targets.orchestra = "ensemble".equals(this.editingArrangement.affects);
        }

        instrumentRows.clear();
        for (Map.Entry<String, Integer> e : editingArrangement.required_instruments.entrySet()) {
            if (instrumentRows.size() >= maxInstruments()) {
                break;
            }
            instrumentRows.add(new InstrumentRow(e.getKey(), String.valueOf(e.getValue())));
        }

        effectRows.clear();
        for (EffectEntry e : editingArrangement.effects) {
            if (effectRows.size() >= maxEffects()) {
                break;
            }
            effectRows.add(new EffectRow(e.effect, String.valueOf(e.base_level), String.valueOf(e.max_level), String.valueOf(e.min_ensemble_size)));
        }

        this.statusMessage = "";
        rebuild();
    }

    /**
     * Opens the picker overlay (2026-09-03 request: "give me a choice
     * instead of free text, the same way command tab-completion knows
     * every effect/item") — a filterable, paged list built from the
     * server's own live registry contents (see TrackSyncUtil), the same
     * source Minecraft's own command autocomplete would use.
     */
    private void openPicker(List<String> options, boolean isInstruments, java.util.function.Consumer<String> callback) {
        this.pickerOptions = options;
        this.pickerFilter = "";
        this.pickerPage = 0;
        this.pickerCallback = callback;
        this.pickerIsInstruments = isInstruments;
        rebuild();
    }

    // FIX (2026-09-13, user request): was a hardcoded constant; now a
    // server config an admin can raise/lower without a rebuild — see
    // GlobalConfig.MAX_INSTRUMENTS_PER_ARRANGEMENT/MAX_EFFECTS_PER_ARRANGEMENT.
    // Methods, not fields, since the config value can only be read once the
    // server config has synced (not necessarily at class-load time).
    private static int maxInstruments() {
        return com.mimieffects.config.GlobalConfig.MAX_INSTRUMENTS_PER_ARRANGEMENT.get();
    }

    private static int maxEffects() {
        return com.mimieffects.config.GlobalConfig.MAX_EFFECTS_PER_ARRANGEMENT.get();
    }

    /** Human-readable label for an instrument id ("mimi:accordion2" -> "Accordion" / localized name), falling back to the raw id if unresolvable. */
    private static String instrumentDisplayName(String id) {
        try {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            if (item != null && item != Items.AIR) {
                return new ItemStack(item).getHoverName().getString();
            }
        } catch (Exception ignored) {
            // malformed id (e.g. still being typed) — show it as-is below
        }
        return id;
    }

    /** Human-readable label for an effect id ("minecraft:water_breathing" -> "Water Breathing"), falling back to the raw id if unresolvable. */
    private static String effectDisplayName(String id) {
        try {
            Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(id)).orElse(null);
            if (holder != null) {
                return holder.value().getDisplayName().getString();
            }
        } catch (Exception ignored) {
            // malformed id (e.g. still being typed) — show it as-is below
        }
        return id;
    }

    private void buildPickerWidgets() {
        int x = 10;
        int y = 30;

        EditBox search = new EditBox(this.font, x, y, 300, 18, Component.translatable("mimieffects.editor.search"));
        search.setValue(pickerFilter);
        search.setResponder(v -> {
            pickerFilter = v;
            pickerPage = 0;
            rebuild();
        });
        this.addRenderableWidget(search);
        this.setInitialFocus(search);
        y += 22;

        // Safety net: if the exact id isn't in the list (e.g. an instrument
        // added by a MIMI custom.json this client hasn't seen reflected
        // yet), typing it fully and pressing this still works rather than
        // hard-blocking on the picker.
        this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.use_typed"), btn -> {
            String typed = pickerFilter;
            java.util.function.Consumer<String> cb = pickerCallback;
            pickerCallback = null;
            if (cb != null && !typed.isBlank()) {
                cb.accept(typed.trim());
            }
            rebuild();
        }).bounds(x, y, 300, 18).build());
        y += 24;

        List<String> filtered = new ArrayList<>();
        String needle = pickerFilter.toLowerCase();
        for (String id : pickerOptions) {
            if (needle.isBlank() || id.toLowerCase().contains(needle)) {
                filtered.add(id);
            }
        }

        int start = pickerPage * PICKER_PAGE_SIZE;
        int end = Math.min(start + PICKER_PAGE_SIZE, filtered.size());
        for (int i = start; i < end; i++) {
            String id = filtered.get(i);
            int rowIndex = i - start;
            // ADDED 2026-09-13 (user report): filtering still matches the raw
            // id (so typing "mimi:" or "minecraft:" still works), but the
            // button itself shows a human name — same fix as the summary
            // buttons behind this picker.
            String label = pickerIsInstruments ? instrumentDisplayName(id) : effectDisplayName(id);
            this.addRenderableWidget(Button.builder(Component.literal(label), btn -> {
                java.util.function.Consumer<String> cb = pickerCallback;
                pickerCallback = null;
                cb.accept(id);
                rebuild();
            }).bounds(x, y + rowIndex * 20, 300, 18).build());
        }

        int pageY = y + PICKER_PAGE_SIZE * 20 + 4;
        if (pickerPage > 0) {
            this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.prev"), btn -> {
                pickerPage--;
                rebuild();
            }).bounds(x, pageY, 90, 18).build());
        }
        if (end < filtered.size()) {
            this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.next"), btn -> {
                pickerPage++;
                rebuild();
            }).bounds(x + 100, pageY, 90, 18).build());
        }

        this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.cancel"), btn -> {
            pickerCallback = null;
            rebuild();
        }).bounds(x, this.height - 28, 100, 20).build());
    }

    private void buildEditorWidgets(int x, int startY) {
        int y = startY;
        int fieldW = 220;
        // FIX (2026-09-13, user report): the Mob Purge section + Save button
        // used to sit at the bottom of this SAME growing column, so a track
        // with several instrument/effect rows pushed them low enough to
        // visually collide with the Save button (pinned to a fixed
        // this.height - 28). Moved onto their own column, far enough right
        // to sit in the screen's wide unused margin (same fix already
        // applied to the track-id copy button and max_radius_blocks field)
        // — its vertical position no longer depends on how long the left
        // column gets.
        int x2 = x + 320;
        int y2 = startY;

        addEditorLabel(x, y, tr("mimieffects.editor.label.display_name"));
        y += 10;
        EditBox displayName = new EditBox(this.font, x, y, fieldW, 18, Component.translatable("mimieffects.editor.display_name_placeholder"));
        displayName.setValue(nullToEmpty(selectedConfig.display_name));
        displayName.setResponder(v -> selectedConfig.display_name = v);
        this.addRenderableWidget(displayName);
        y += 22;

        // ADDED 2026-09-12 (user request): track_id is never editable here
        // (see class javadoc) but admins need it for /give, debugging, etc.
        // — show it read-only with a one-click copy-to-clipboard button.
        // FIX (2026-09-13, user report): tried pinning this to the far
        // right screen edge twice now (first it clipped inside the left
        // column, then it drifted so far right it looked disconnected from
        // the label it belongs to — "куда улетела"). Simplest fix that
        // can't misjudge text width in any locale: put it on its own line
        // directly under the label, anchored to the same x as everything
        // else in this column.
        addEditorLabel(x, y, tr("mimieffects.editor.label.track_id") + ": " + nullToEmpty(selectedConfig.track_id));
        y += 11;
        this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.copy_track_id"), btn -> {
            net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(nullToEmpty(selectedConfig.track_id));
            this.statusMessage = Component.translatable("mimieffects.editor.status.copied").getString();
        }).bounds(x, y, 80, 14).build());
        y += 18;

        // ADDED 2026-09-12 (user request, Note Scroll item): spell_name is
        // the scroll's own displayed name (a "spell", not the song title),
        // artist is optional flavor text shown alongside display_name in
        // the scroll's tooltip — see NoteScrollItem.
        addEditorLabel(x, y, tr("mimieffects.editor.label.spell_name"));
        y += 10;
        EditBox spellName = new EditBox(this.font, x, y, fieldW, 18, Component.translatable("mimieffects.editor.label.spell_name"));
        spellName.setValue(nullToEmpty(selectedConfig.spell_name));
        spellName.setResponder(v -> selectedConfig.spell_name = v);
        this.addRenderableWidget(spellName);
        y += 22;

        addEditorLabel(x, y, tr("mimieffects.editor.label.artist"));
        y += 10;
        EditBox artist = new EditBox(this.font, x, y, fieldW, 18, Component.translatable("mimieffects.editor.label.artist"));
        artist.setValue(nullToEmpty(selectedConfig.artist));
        artist.setResponder(v -> selectedConfig.artist = v);
        this.addRenderableWidget(artist);
        y += 22;

        addEditorLabel(x, y, tr("mimieffects.editor.label.arrangement_name"));
        y += 10;
        EditBox arrangementName = new EditBox(this.font, x, y, fieldW, 18, Component.translatable("mimieffects.editor.label.arrangement_name"));
        arrangementName.setValue(nullToEmpty(editingArrangement.name));
        arrangementName.setResponder(v -> editingArrangement.name = v);
        this.addRenderableWidget(arrangementName);
        y += 22;

        y = renderSectionLabel(x, y, tr("mimieffects.editor.section.targets"));
        EffectTargets targets = editingArrangement.targets;
        y = addTargetToggle(x, y, tr("mimieffects.editor.target.players"), targets.players, value -> targets.players = value, "mimieffects.editor.tooltip.target.players");
        y = addTargetToggle(x, y, tr("mimieffects.editor.target.friendly"), targets.friendly, value -> targets.friendly = value, "mimieffects.editor.tooltip.target.friendly");
        y = addTargetToggle(x, y, tr("mimieffects.editor.target.hostile"), targets.hostile, value -> targets.hostile = value, "mimieffects.editor.tooltip.target.hostile");
        y = addTargetToggle(x, y, tr("mimieffects.editor.target.neutral"), targets.neutral, value -> targets.neutral = value, "mimieffects.editor.tooltip.target.neutral");
        y = addTargetToggle(x, y, tr("mimieffects.editor.target.orchestra"), targets.orchestra, value -> targets.orchestra = value, "mimieffects.editor.tooltip.target.orchestra");
        y += 4;

        y = renderSectionLabel(x, y, tr("mimieffects.editor.section.instruments"));
        for (int i = 0; i < instrumentRows.size(); i++) {
            InstrumentRow row = instrumentRows.get(i);
            int rowY = y;

            this.addRenderableWidget(Button.builder(
                    row.id.isBlank() ? Component.translatable("mimieffects.editor.choose_instrument") : Component.literal(instrumentDisplayName(row.id)),
                    btn -> openPicker(instrumentIds, true, picked -> row.id = picked)
            ).bounds(x, rowY, 165, 16).build());

            EditBox countBox = new EditBox(this.font, x + 170, rowY, 40, 16, Component.translatable("mimieffects.editor.count"));
            countBox.setValue(row.count);
            countBox.setResponder(v -> row.count = v);
            this.addRenderableWidget(countBox);

            int rowIndex = i;
            this.addRenderableWidget(Button.builder(Component.literal("X"), btn -> {
                instrumentRows.remove(rowIndex);
                rebuild();
            }).bounds(x + 215, rowY, 20, 16).build());

            y += 19;
        }
        if (instrumentRows.size() < maxInstruments()) {
            this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.add_instrument"), btn -> {
                instrumentRows.add(new InstrumentRow("", "1"));
                rebuild();
            }).bounds(x, y, 150, 18).build());
            y += 22;
        }

        y = renderSectionLabel(x, y, tr("mimieffects.editor.section.effects"));
        for (int i = 0; i < effectRows.size(); i++) {
            EffectRow row = effectRows.get(i);
            int rowY = y;

            this.addRenderableWidget(Button.builder(
                    row.effect.isBlank() ? Component.translatable("mimieffects.editor.choose_effect") : Component.literal(effectDisplayName(row.effect)),
                    btn -> openPicker(effectIds, false, picked -> row.effect = picked)
            ).bounds(x, rowY, 110, 16).build());

            EditBox baseBox = new EditBox(this.font, x + 114, rowY, 25, 16, Component.translatable("mimieffects.editor.base"));
            baseBox.setValue(row.baseLevel);
            baseBox.setResponder(v -> row.baseLevel = v);
            baseBox.setTooltip(tooltip("mimieffects.editor.tooltip.base_level"));
            this.addRenderableWidget(baseBox);

            EditBox maxBox = new EditBox(this.font, x + 142, rowY, 25, 16, Component.translatable("mimieffects.editor.max"));
            maxBox.setValue(row.maxLevel);
            maxBox.setResponder(v -> row.maxLevel = v);
            maxBox.setTooltip(tooltip("mimieffects.editor.tooltip.max_level"));
            this.addRenderableWidget(maxBox);

            // ADDED 2026-09-13 (user request): EffectEntry.min_ensemble_size
            // ("layered effects" — see its own javadoc) had no GUI field at
            // all despite already being fully wired up server-side.
            EditBox minPlayersBox = new EditBox(this.font, x + 170, rowY, 30, 16, Component.translatable("mimieffects.editor.min_players"));
            minPlayersBox.setValue(row.minEnsembleSize);
            minPlayersBox.setResponder(v -> row.minEnsembleSize = v);
            minPlayersBox.setTooltip(tooltip("mimieffects.editor.tooltip.effect_min_players"));
            this.addRenderableWidget(minPlayersBox);

            int rowIndex = i;
            this.addRenderableWidget(Button.builder(Component.literal("X"), btn -> {
                effectRows.remove(rowIndex);
                rebuild();
            }).bounds(x + 204, rowY, 18, 16).build());

            y += 19;
        }
        if (effectRows.size() < maxEffects()) {
            this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.add_effect"), btn -> {
                effectRows.add(new EffectRow("", "0", "0", "1"));
                rebuild();
            }).bounds(x, y, 150, 18).build());
            y += 22;
        }

        // --- Right column: ensemble gate + mob_purge + Save — see the
        // javadoc on x2/y2's declaration for why these moved off the left
        // column entirely instead of just being reordered within it.
        // FIX (2026-09-13, design pass): the section header and the field
        // label used to both say "ансамбль" back to back — reworded the
        // field label so it reads as a continuation, not a repeat.
        y2 = renderSectionLabel(x2, y2, tr("mimieffects.editor.section.ensemble"));
        addEditorLabel(x2, y2, tr("mimieffects.editor.min_ensemble_size"));
        y2 += 10;
        EditBox minEnsembleBox = new EditBox(this.font, x2, y2, 60, 16, Component.translatable("mimieffects.editor.min_ensemble_size"));
        minEnsembleBox.setValue(editingArrangement.min_ensemble_size == null ? "" : String.valueOf(editingArrangement.min_ensemble_size));
        minEnsembleBox.setResponder(v -> editingArrangement.min_ensemble_size = v.isBlank() ? null : parseIntOr(v, 1));
        minEnsembleBox.setTooltip(tooltip("mimieffects.editor.tooltip.min_ensemble_size"));
        this.addRenderableWidget(minEnsembleBox);
        y2 += 24;

        y2 = renderSectionLabel(x2, y2, tr("mimieffects.editor.section.mob_purge"));
        boolean purgeEnabled = editingArrangement != null && selectedConfig.mob_purge != null;
        this.addRenderableWidget(Button.builder(
                Component.translatable("mimieffects.editor.mob_purge_toggle",
                        tr(purgeEnabled ? "mimieffects.editor.enabled" : "mimieffects.editor.disabled")),
                btn -> {
                    selectedConfig.mob_purge = purgeEnabled ? null : new MobPurge();
                    rebuild();
                }
        ).bounds(x2, y2, fieldW, 18).tooltip(tooltip("mimieffects.editor.tooltip.mob_purge")).build());
        y2 += 22;

        if (purgeEnabled) {
            MobPurge purge = selectedConfig.mob_purge;

            EditBox radiusBox = new EditBox(this.font, x2, y2, 80, 16, Component.translatable("mimieffects.editor.radius_blocks"));
            radiusBox.setValue(String.valueOf(purge.radius_blocks));
            radiusBox.setResponder(v -> purge.radius_blocks = parseIntOr(v, purge.radius_blocks));
            radiusBox.setTooltip(tooltip("mimieffects.editor.tooltip.radius_blocks"));
            this.addRenderableWidget(radiusBox);

            // ADDED 2026-09-12 (user report): max_radius_blocks (opt-in
            // ensemble scaling for purge reach, see MobPurge's javadoc) had
            // no GUI field at all — blank means null, i.e. flat radius,
            // the pre-existing default for every track.
            EditBox maxRadiusBox = new EditBox(this.font, x2 + 90, y2, 80, 16, Component.translatable("mimieffects.editor.max_radius_blocks"));
            maxRadiusBox.setValue(purge.max_radius_blocks == null ? "" : String.valueOf(purge.max_radius_blocks));
            maxRadiusBox.setResponder(v -> purge.max_radius_blocks = v.isBlank() ? null : parseIntOr(v, purge.radius_blocks));
            maxRadiusBox.setTooltip(tooltip("mimieffects.editor.tooltip.max_radius_blocks"));
            this.addRenderableWidget(maxRadiusBox);
            y2 += 22;

            int envIndex = indexOf(ENVIRONMENTS, purge.environment);
            this.addRenderableWidget(Button.builder(
                    // ADDED 2026-09-12 (user request): the raw environment
                    // value ("surface"/"underwater"/"any") used to be shown
                    // literally regardless of client locale — it's still
                    // stored as that raw English string in the track JSON
                    // (mod-agnostic, matches MobPurgeMatcher), only the
                    // DISPLAYED text is now looked up per-locale.
                    Component.translatable("mimieffects.editor.environment", tr("mimieffects.editor.environment." + purge.environment)),
                    btn -> {
                        int next = (envIndex + 1) % ENVIRONMENTS.length;
                        purge.environment = ENVIRONMENTS[next];
                        rebuild();
                    }
            ).bounds(x2, y2, fieldW, 18).tooltip(tooltip("mimieffects.editor.tooltip.environment")).build());
            y2 += 22;

            this.addRenderableWidget(Button.builder(
                    Component.translatable("mimieffects.editor.static_only", trBool(purge.requires_static_instrument)),
                    btn -> {
                        purge.requires_static_instrument = !purge.requires_static_instrument;
                        rebuild();
                    }
            ).bounds(x2, y2, fieldW, 18).tooltip(tooltip("mimieffects.editor.tooltip.static_only")).build());
            y2 += 22;

            this.addRenderableWidget(Button.builder(
                    Component.translatable("mimieffects.editor.hostile_only", trBool(purge.hostile_only)),
                    btn -> {
                        purge.hostile_only = !purge.hostile_only;
                        rebuild();
                    }
            ).bounds(x2, y2, fieldW, 18).tooltip(tooltip("mimieffects.editor.tooltip.hostile_only")).build());
            y2 += 22;

            this.addRenderableWidget(Button.builder(
                    Component.translatable("mimieffects.editor.silent", trBool(purge.silent)),
                    btn -> {
                        purge.silent = !purge.silent;
                        rebuild();
                    }
            ).bounds(x2, y2, fieldW, 18).tooltip(tooltip("mimieffects.editor.tooltip.silent")).build());
            y2 += 26;
        }

        this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.save"), btn -> save())
                .bounds(x2, this.height - 28, 100, 20)
                .build());
    }

    private int renderSectionLabel(int x, int y, String label) {
        addEditorLabel(x, y, label);
        return y + 12;
    }

    private void addEditorLabel(int x, int y, String text) {
        editorLabels.add(new EditorLabel(x, y, text));
    }

    private int addTargetToggle(int x, int y, String label, boolean enabled, java.util.function.Consumer<Boolean> setter) {
        return addTargetToggle(x, y, label, enabled, setter, null);
    }

    private int addTargetToggle(int x, int y, String label, boolean enabled, java.util.function.Consumer<Boolean> setter, String tooltipKey) {
        Button.Builder builder = Button.builder(Component.literal((enabled ? "[x] " : "[ ] ") + label), btn -> {
            setter.accept(!enabled);
            rebuild();
        }).bounds(x, y, 220, 18);
        if (tooltipKey != null) {
            builder.tooltip(tooltip(tooltipKey));
        }
        this.addRenderableWidget(builder.build());
        return y + 20;
    }

    /** ADDED 2026-09-12 (user request): hover tooltips on editor buttons, mirroring the server config screen's. */
    private static Tooltip tooltip(String key) {
        return Tooltip.create(Component.translatable(key));
    }

    /** ADDED 2026-09-12 (user request): booleans used to render as literal "true"/"false" regardless of locale. */
    private static String trBool(boolean value) {
        return tr("mimieffects.editor.bool." + value);
    }

    /** Resolves a translation key to plain text in the client's current locale, for raw-drawn labels. */
    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private void save() {
        if (selectedConfig == null || editingArrangement == null) {
            return;
        }

        Map<String, Integer> instruments = new LinkedHashMap<>();
        for (InstrumentRow row : instrumentRows) {
            if (row.id == null || row.id.isBlank()) {
                continue;
            }
            instruments.put(row.id.trim(), Math.max(1, parseIntOr(row.count, 1)));
        }
        editingArrangement.required_instruments = instruments;

        List<EffectEntry> effects = new ArrayList<>();
        for (EffectRow row : effectRows) {
            if (row.effect == null || row.effect.isBlank()) {
                continue;
            }
            EffectEntry entry = new EffectEntry(
                    row.effect.trim(),
                    parseIntOr(row.baseLevel, 0),
                    parseIntOr(row.maxLevel, 0)
            );
            entry.min_ensemble_size = Math.max(1, parseIntOr(row.minEnsembleSize, 1));
            effects.add(entry);
        }
        editingArrangement.effects = effects.toArray(new EffectEntry[0]);

        selectedConfig.arrangements[0] = editingArrangement;

        String json = GSON.toJson(selectedConfig);
        PacketDistributor.sendToServer(new SaveTrackPayload(selectedFileName, json));
        this.statusMessage = Component.translatable("mimieffects.editor.status.saving").getString();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // ADDED 2026-09-13 (design pass, user request "что можно улучшить"):
        // this screen never painted anything behind its own widgets, so
        // every label/button sat directly over the live (unblurred) game
        // world — busy backgrounds like a bookshelf made text hard to read
        // and the whole screen read as "broken" rather than a real UI.
        guiGraphics.fill(5, 20, this.width - 5, this.height - 8, 0x90101010);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        for (EditorLabel label : editorLabels) {
            guiGraphics.drawString(this.font, label.text, label.x, label.y, 0xD0D0D0);
        }
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        if (!statusMessage.isEmpty()) {
            guiGraphics.drawString(this.font, statusMessage, 10, this.height - 42, 0xFFFF55);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private static final class InstrumentRow {
        String id;
        String count;

        InstrumentRow(String id, String count) {
            this.id = id;
            this.count = count;
        }
    }

    private record EditorLabel(int x, int y, String text) {
    }

    private static final class EffectRow {
        String effect;
        String baseLevel;
        String maxLevel;
        String minEnsembleSize;

        EffectRow(String effect, String baseLevel, String maxLevel, String minEnsembleSize) {
            this.effect = effect;
            this.baseLevel = baseLevel;
            this.maxLevel = maxLevel;
            this.minEnsembleSize = minEnsembleSize;
        }
    }
}
