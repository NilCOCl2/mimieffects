package com.mimieffects.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import com.mimieffects.network.SaveTrackPayload;
import com.mimieffects.network.TrackFileDto;
import com.mimieffects.track.Arrangement;
import com.mimieffects.track.EffectEntry;
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
 * ADDED (2026-09-03): instrument/effect IDs are chosen from a picker
 * overlay (search + paged list) populated from the server's own live
 * registries (BuiltInRegistries.ITEM filtered to "mimi", and
 * BuiltInRegistries.MOB_EFFECT) — the same source Minecraft's own command
 * tab-completion would draw from — instead of free-text entry, so a typo
 * can't silently produce a track that never triggers. A "use typed text
 * as-is" escape hatch remains in the picker for the rare case an id isn't
 * in the synced list yet (e.g. a MIMI custom.json addition the running
 * client hasn't seen reflected in a fresh sync).
 *
 * DISCLOSURE: this file could not be compiled or run in the sandbox that
 * built it — no Minecraft/NeoForge classpath was available there (see
 * BUILD.md). Every API used here (Button.builder, EditBox, Screen
 * lifecycle) is long-stable across 1.20–1.21, but if something doesn't
 * compile, the exact javac error is far more useful for fixing it than
 * another guess would be.
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
    private String statusMessage = "";

    // Picker overlay state — see openPicker()/buildPickerWidgets().
    private static final int PICKER_PAGE_SIZE = 12;
    private List<String> pickerOptions;
    private String pickerFilter = "";
    private int pickerPage = 0;
    private java.util.function.Consumer<String> pickerCallback;

    public TrackEditorScreen(List<TrackFileDto> tracks, List<String> instrumentIds, List<String> effectIds) {
        super(Component.literal("MimiEffects — Track Editor"));
        this.tracks = tracks;
        this.instrumentIds = instrumentIds != null ? instrumentIds : new ArrayList<>();
        this.effectIds = effectIds != null ? effectIds : new ArrayList<>();
    }

    /** Called by ClientPayloadHandlers when a fresh sync arrives while this screen is already open. */
    public void updateTracks(List<TrackFileDto> newTracks, List<String> newInstrumentIds, List<String> newEffectIds) {
        this.tracks = newTracks;
        this.instrumentIds = newInstrumentIds != null ? newInstrumentIds : this.instrumentIds;
        this.effectIds = newEffectIds != null ? newEffectIds : this.effectIds;
        this.statusMessage = "Saved.";
        rebuild();
    }


    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        this.clearWidgets();

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
            this.addRenderableWidget(Button.builder(Component.literal("< Prev"), btn -> {
                page--;
                rebuild();
            }).bounds(listX, pageY, 75, 18).build());
        }
        if (end < tracks.size()) {
            this.addRenderableWidget(Button.builder(Component.literal("Next >"), btn -> {
                page++;
                rebuild();
            }).bounds(listX + 85, pageY, 75, 18).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Close"), btn -> this.onClose())
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
            this.statusMessage = "This file has invalid JSON and can't be edited here: " + e.getMessage();
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

        EditBox search = new EditBox(this.font, x, y, 300, 18, Component.literal("Search"));
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
        this.addRenderableWidget(Button.builder(Component.literal("Use typed text as-is"), btn -> {
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
            this.addRenderableWidget(Button.builder(Component.literal("< Prev"), btn -> {
                pickerPage--;
                rebuild();
            }).bounds(x, pageY, 90, 18).build());
        }
        if (end < filtered.size()) {
            this.addRenderableWidget(Button.builder(Component.literal("Next >"), btn -> {
                pickerPage++;
                rebuild();
            }).bounds(x + 100, pageY, 90, 18).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> {
            pickerCallback = null;
            rebuild();
        }).bounds(x, this.height - 28, 100, 20).build());
    }

    private void buildEditorWidgets(int x, int startY) {
        int y = startY;
        int fieldW = 220;

        EditBox displayName = new EditBox(this.font, x, y, fieldW, 18, Component.literal("Display name"));
        displayName.setValue(nullToEmpty(selectedConfig.display_name));
        displayName.setResponder(v -> selectedConfig.display_name = v);
        this.addRenderableWidget(displayName);
        y += 22;

        EditBox arrangementName = new EditBox(this.font, x, y, fieldW, 18, Component.literal("Arrangement name"));
        arrangementName.setValue(nullToEmpty(editingArrangement.name));
        arrangementName.setResponder(v -> editingArrangement.name = v);
        this.addRenderableWidget(arrangementName);
        y += 22;

        boolean allNearby = !"ensemble".equals(editingArrangement.affects);
        this.addRenderableWidget(Button.builder(
                Component.literal("Affects: " + (allNearby ? "all_nearby" : "ensemble")),
                btn -> {
                    editingArrangement.affects = allNearby ? "ensemble" : "all_nearby";
                    rebuild();
                }
        ).bounds(x, y, fieldW, 18).build());
        y += 24;

        y = renderSectionLabel(y, "Instruments (id -> count):");
        for (int i = 0; i < instrumentRows.size(); i++) {
            InstrumentRow row = instrumentRows.get(i);
            int rowY = y;

            this.addRenderableWidget(Button.builder(
                    Component.literal(row.id.isBlank() ? "(choose instrument)" : row.id),
                    btn -> openPicker(instrumentIds, picked -> row.id = picked)
            ).bounds(x, rowY, 165, 16).build());

            EditBox countBox = new EditBox(this.font, x + 170, rowY, 40, 16, Component.literal("count"));
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
            this.addRenderableWidget(Button.builder(Component.literal("+ Add instrument"), btn -> {
                instrumentRows.add(new InstrumentRow("", "1"));
                rebuild();
            }).bounds(x, y, 150, 18).build());
            y += 22;
        }

        y = renderSectionLabel(y, "Effects (id, base, max):");
        for (int i = 0; i < effectRows.size(); i++) {
            EffectRow row = effectRows.get(i);
            int rowY = y;

            this.addRenderableWidget(Button.builder(
                    Component.literal(row.effect.isBlank() ? "(choose effect)" : row.effect),
                    btn -> openPicker(effectIds, picked -> row.effect = picked)
            ).bounds(x, rowY, 130, 16).build());

            EditBox baseBox = new EditBox(this.font, x + 135, rowY, 30, 16, Component.literal("base"));
            baseBox.setValue(row.baseLevel);
            baseBox.setResponder(v -> row.baseLevel = v);
            this.addRenderableWidget(baseBox);

            EditBox maxBox = new EditBox(this.font, x + 170, rowY, 30, 16, Component.literal("max"));
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
            this.addRenderableWidget(Button.builder(Component.literal("+ Add effect"), btn -> {
                effectRows.add(new EffectRow("", "0", "0"));
                rebuild();
            }).bounds(x, y, 150, 18).build());
            y += 22;
        }

        y = renderSectionLabel(y, "Mob purge (repel/kill on this track):");
        boolean purgeEnabled = editingArrangement != null && selectedConfig.mob_purge != null;
        this.addRenderableWidget(Button.builder(
                Component.literal("Mob purge: " + (purgeEnabled ? "ENABLED" : "disabled")),
                btn -> {
                    selectedConfig.mob_purge = purgeEnabled ? null : new MobPurge();
                    rebuild();
                }
        ).bounds(x, y, fieldW, 18).build());
        y += 22;

        if (purgeEnabled) {
            MobPurge purge = selectedConfig.mob_purge;

            EditBox radiusBox = new EditBox(this.font, x, y, 80, 16, Component.literal("radius (blocks)"));
            radiusBox.setValue(String.valueOf(purge.radius_blocks));
            radiusBox.setResponder(v -> purge.radius_blocks = parseIntOr(v, purge.radius_blocks));
            this.addRenderableWidget(radiusBox);

            int envIndex = indexOf(ENVIRONMENTS, purge.environment);
            this.addRenderableWidget(Button.builder(
                    Component.literal("Environment: " + purge.environment),
                    btn -> {
                        int next = (envIndex + 1) % ENVIRONMENTS.length;
                        purge.environment = ENVIRONMENTS[next];
                        rebuild();
                    }
            ).bounds(x + 85, y, 135, 18).build());
            y += 22;

            this.addRenderableWidget(Button.builder(
                    Component.literal("Static instrument only: " + purge.requires_static_instrument),
                    btn -> {
                        purge.requires_static_instrument = !purge.requires_static_instrument;
                        rebuild();
                    }
            ).bounds(x, y, fieldW, 18).build());
            y += 22;

            this.addRenderableWidget(Button.builder(
                    Component.literal("Hostile only: " + purge.hostile_only),
                    btn -> {
                        purge.hostile_only = !purge.hostile_only;
                        rebuild();
                    }
            ).bounds(x, y, fieldW, 18).build());
            y += 22;

            this.addRenderableWidget(Button.builder(
                    Component.literal("Silent: " + purge.silent),
                    btn -> {
                        purge.silent = !purge.silent;
                        rebuild();
                    }
            ).bounds(x, y, fieldW, 18).build());
            y += 26;
        }

        this.addRenderableWidget(Button.builder(Component.literal("Save"), btn -> save())
                .bounds(x, this.height - 28, 100, 20)
                .build());
    }

    private int renderSectionLabel(int y, String ignoredLabelDrawnInRender) {
        // Labels are drawn in render() (see sectionLabelsY), not as widgets —
        // this just reserves vertical space consistently at each call site.
        return y + 12;
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
        this.statusMessage = "Saving...";
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
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
