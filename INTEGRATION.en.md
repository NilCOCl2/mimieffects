[Русский](INTEGRATION.md) | **English**

# MIMI integration — technical notes

Reference material for future changes to `MimiNoteBridge`/
`TrackScaffolder`/`TrackSyncUtil` — only what the code actually relies
on today, without the investigation history. (This used to be a
separate document, `REVERSE_ENGINEERING.md`, written at the very start
of the project while reading through MIMI's source; some of its
conclusions went stale — what's still current lives here.)

## MIMI has no event API

Not a single `EVENT_BUS.post(...)` anywhere in the mod's tree — just
ordinary public `static` methods. That's not actually a problem: almost
everything needed is available through them directly.

## How the mod finds out a note was played

The one thing the public API doesn't provide is a callback for "a note
was just played, right now." So a Mixin is still used for that:
`ServerNoteConsumerManagerMixin` injects at `HEAD` of
`ServerNoteConsumerManager.handleEvent(NoteEvent)` — a public package,
`io.github.tofodroid.mods.mimi.common.api.event.note`, just without a
Forge event wrapper around it. Every call reaches
`MimiNoteBridge.onMimiNote(NoteEvent)`.

## Which song a source is currently playing

```java
ATransmitterBroadcastProducer transmitter = ServerTransmitterManager.getTransmitter(player.getUUID());
ServerMusicPlayerStatusPacket status = transmitter.getStatus(); // null if the source isn't registered
// status.isPlaying + status.fileId — this IS the track_id (a UUID, not a file name)
```

If `fileId == null` while `isPlaying == true`, it's live improvisation
with no loaded file.

## How `track_id` is actually computed

```java
// io.github.tofodroid.mods.mimi.common.midi.LocalMidiInfo (public static)
UUID id = LocalMidiInfo.createFileId(midiFile);
// UUID.nameUUIDFromBytes over "file:<name>;tempo:<BPM>;length:<sec>;channels:<byte mapping>;"
```

This is **not** a `ResourceLocation` shaped like `mimi:midi/name.mid` —
it's a deterministic hash of the file's content. `TrackScaffolder`
calls this exact method directly so the `track_id` in our JSON files is
guaranteed to match what the server actually broadcasts. File path:
`config/mimi/server_midi_files/*.mid` and `*.midi`.

## Which instrument a player is holding

`InstrumentConfig.getBydId(note.instrumentId)` → an `InstrumentSpec`
with `registryName` (used as `mimi:<registryName>` — the same id the
item/block itself has) and `isBlock` (handheld vs. stationary).

The canonical list of **real** instruments is
`InstrumentConfig.getAllInstruments()`, not "every item under the
`mimi` namespace": MIMI also registers non-instrument infrastructure
under that same namespace (`ledcube_a`..`h`, `broadcaster`, `relay`,
`transmitter`, `tuningtable`, etc.), none of which are instruments —
`TrackSyncUtil` and `ScrollGenerator` both use `getAllInstruments()`
specifically so a decorative block never gets picked as a required
instrument.

## Block instruments and player immobility

A player on a block instrument physically "sits" on it through MIMI's
own mechanic (`TileInstrument` → `EntitySeat`, an ordinary mount like a
boat or a horse) — this already fully immobilizes the player using
vanilla's own systems. Nothing extra is needed for movement
restriction there; for handheld instruments the mod adds a short
slowness effect on every note instead (see `MimiNoteBridge.applyFor`).
