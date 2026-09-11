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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
 * - Up to 5 instrument rows and 5 effect rows. More than that per
 *   arrangement needs hand-editing for now.
 * - track_id is never editable here (it's derived from the real MIDI
 *   file by MIMI itself — see REVERSE_ENGINEERING.md — editing it would
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
    private static final int MAX_INSTRUMENTS = 5;
    private static final int MAX_EFFECTS = 5;
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
            if (instrumentRows.size() >= MAX_INSTRUMENTS) {
                break;
            }
            instrumentRows.add(new InstrumentRow(e.getKey(), String.valueOf(e.getValue())));
        }

        effectRows.clear();
        for (EffectEntry e : editingArrangement.effects) {
            if (effectRows.size() >= MAX_EFFECTS) {
                break;
            }
            effectRows.add(new EffectRow(e.effect, String.valueOf(e.base_level), String.valueOf(e.max_level)));
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
    private void openPicker(List<String> options, java.util.function.Consumer<String> callback) {
        this.pickerOptions = options;
        this.pickerFilter = "";
        this.pickerPage = 0;
        this.pickerCallback = callback;
        rebuild();
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
            this.addRenderableWidget(Button.builder(Component.literal(id), btn -> {
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

        addEditorLabel(x, y, tr("mimieffects.editor.label.display_name"));
        y += 10;
        EditBox displayName = new EditBox(this.font, x, y, fieldW, 18, Component.translatable("mimieffects.editor.display_name_placeholder"));
        displayName.setValue(nullToEmpty(selectedConfig.display_name));
        displayName.setResponder(v -> selectedConfig.display_name = v);
        this.addRenderableWidget(displayName);
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
        y = addTargetToggle(x, y, tr("mimieffects.editor.target.players"), targets.players, value -> targets.players = value);
        y = addTargetToggle(x, y, tr("mimieffects.editor.target.friendly"), targets.friendly, value -> targets.friendly = value);
        y = addTargetToggle(x, y, tr("mimieffects.editor.target.hostile"), targets.hostile, value -> targets.hostile = value);
        y = addTargetToggle(x, y, tr("mimieffects.editor.target.neutral"), targets.neutral, value -> targets.neutral = value);
        y = addTargetToggle(x, y, tr("mimieffects.editor.target.orchestra"), targets.orchestra, value -> targets.orchestra = value);
        y += 4;

        y = renderSectionLabel(x, y, tr("mimieffects.editor.section.instruments"));
        for (int i = 0; i < instrumentRows.size(); i++) {
            InstrumentRow row = instrumentRows.get(i);
            int rowY = y;

            this.addRenderableWidget(Button.builder(
                    row.id.isBlank() ? Component.translatable("mimieffects.editor.choose_instrument") : Component.literal(row.id),
                    btn -> openPicker(instrumentIds, picked -> row.id = picked)
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
        if (instrumentRows.size() < MAX_INSTRUMENTS) {
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
                    row.effect.isBlank() ? Component.translatable("mimieffects.editor.choose_effect") : Component.literal(row.effect),
                    btn -> openPicker(effectIds, picked -> row.effect = picked)
            ).bounds(x, rowY, 130, 16).build());

            EditBox baseBox = new EditBox(this.font, x + 135, rowY, 30, 16, Component.translatable("mimieffects.editor.base"));
            baseBox.setValue(row.baseLevel);
            baseBox.setResponder(v -> row.baseLevel = v);
            this.addRenderableWidget(baseBox);

            EditBox maxBox = new EditBox(this.font, x + 170, rowY, 30, 16, Component.translatable("mimieffects.editor.max"));
            maxBox.setValue(row.maxLevel);
            maxBox.setResponder(v -> row.maxLevel = v);
            this.addRenderableWidget(maxBox);

            int rowIndex = i;
            this.addRenderableWidget(Button.builder(Component.literal("X"), btn -> {
                effectRows.remove(rowIndex);
                rebuild();
            }).bounds(x + 205, rowY, 20, 16).build());

            y += 19;
        }
        if (effectRows.size() < MAX_EFFECTS) {
            this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.add_effect"), btn -> {
                effectRows.add(new EffectRow("", "0", "0"));
                rebuild();
            }).bounds(x, y, 150, 18).build());
            y += 22;
        }

        y = renderSectionLabel(x, y, tr("mimieffects.editor.section.mob_purge"));
        boolean purgeEnabled = editingArrangement != null && selectedConfig.mob_purge != null;
        this.addRenderableWidget(Button.builder(
                Component.translatable("mimieffects.editor.mob_purge_toggle",
                        tr(purgeEnabled ? "mimieffects.editor.enabled" : "mimieffects.editor.disabled")),
                btn -> {
                    selectedConfig.mob_purge = purgeEnabled ? null : new MobPurge();
                    rebuild();
                }
        ).bounds(x, y, fieldW, 18).build());
        y += 22;

        if (purgeEnabled) {
            MobPurge purge = selectedConfig.mob_purge;

            EditBox radiusBox = new EditBox(this.font, x, y, 80, 16, Component.translatable("mimieffects.editor.radius_blocks"));
            radiusBox.setValue(String.valueOf(purge.radius_blocks));
            radiusBox.setResponder(v -> purge.radius_blocks = parseIntOr(v, purge.radius_blocks));
            this.addRenderableWidget(radiusBox);

            int envIndex = indexOf(ENVIRONMENTS, purge.environment);
            this.addRenderableWidget(Button.builder(
                    Component.translatable("mimieffects.editor.environment", purge.environment),
                    btn -> {
                        int next = (envIndex + 1) % ENVIRONMENTS.length;
                        purge.environment = ENVIRONMENTS[next];
                        rebuild();
                    }
            ).bounds(x + 85, y, 135, 18).build());
            y += 22;

            this.addRenderableWidget(Button.builder(
                    Component.translatable("mimieffects.editor.static_only", purge.requires_static_instrument),
                    btn -> {
                        purge.requires_static_instrument = !purge.requires_static_instrument;
                        rebuild();
                    }
            ).bounds(x, y, fieldW, 18).build());
            y += 22;

            this.addRenderableWidget(Button.builder(
                    Component.translatable("mimieffects.editor.hostile_only", purge.hostile_only),
                    btn -> {
                        purge.hostile_only = !purge.hostile_only;
                        rebuild();
                    }
            ).bounds(x, y, fieldW, 18).build());
            y += 22;

            this.addRenderableWidget(Button.builder(
                    Component.translatable("mimieffects.editor.silent", purge.silent),
                    btn -> {
                        purge.silent = !purge.silent;
                        rebuild();
                    }
            ).bounds(x, y, fieldW, 18).build());
            y += 26;
        }

        this.addRenderableWidget(Button.builder(Component.translatable("mimieffects.editor.save"), btn -> save())
                .bounds(x, this.height - 28, 100, 20)
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
        this.addRenderableWidget(Button.builder(Component.literal((enabled ? "[x] " : "[ ] ") + label), btn -> {
            setter.accept(!enabled);
            rebuild();
        }).bounds(x, y, 220, 18).build());
        return y + 20;
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
            effects.add(new EffectEntry(
                    row.effect.trim(),
                    parseIntOr(row.baseLevel, 0),
                    parseIntOr(row.maxLevel, 0)
            ));
        }
        editingArrangement.effects = effects.toArray(new EffectEntry[0]);

        selectedConfig.arrangements[0] = editingArrangement;

        String json = GSON.toJson(selectedConfig);
        PacketDistributor.sendToServer(new SaveTrackPayload(selectedFileName, json));
        this.statusMessage = Component.translatable("mimieffects.editor.status.saving").getString();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
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

        EffectRow(String effect, String baseLevel, String maxLevel) {
            this.effect = effect;
            this.baseLevel = baseLevel;
            this.maxLevel = maxLevel;
        }
    }
}
