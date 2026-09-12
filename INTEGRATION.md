**Русский** | [English](INTEGRATION.en.md)

# Интеграция с MIMI — технические заметки

Справочник для будущих правок в `MimiNoteBridge`/`TrackScaffolder`/
`TrackSyncUtil` — здесь только то, что реально используется кодом
сейчас, без истории расследования. (Раньше это был отдельный документ
`REVERSE_ENGINEERING.md`, написанный на самом старте проекта в процессе
чтения исходников MIMI; часть его выводов устарела, актуальное —
здесь.)

## У MIMI нет событийного API

Ни одного `EVENT_BUS.post(...)` во всём дереве мода — только обычные
публичные `static`-методы. Это не проблема: почти всё, что нужно,
доступно через них напрямую.

## Как мод узнаёт, что сыграна нота

Единственное, чего публичный API не даёт — колбэка "вот прямо сейчас
сыграна нота". Поэтому здесь всё же используется Mixin:
`ServerNoteConsumerManagerMixin` инжектится в `HEAD`
`ServerNoteConsumerManager.handleEvent(NoteEvent)` — публичный пакет
`io.github.tofodroid.mods.mimi.common.api.event.note`, просто без
Forge-обёртки в виде отдельного ивента. Каждый вызов долетает до
`MimiNoteBridge.onMimiNote(NoteEvent)`.

## Какую песню сейчас играет источник

```java
ATransmitterBroadcastProducer transmitter = ServerTransmitterManager.getTransmitter(player.getUUID());
ServerMusicPlayerStatusPacket status = transmitter.getStatus(); // null, если источник не зарегистрирован
// status.isPlaying + status.fileId — это и есть track_id (UUID, не имя файла)
```

Если `fileId == null` при `isPlaying == true` — живая импровизация без
загруженного файла.

## Как считается `track_id`

```java
// io.github.tofodroid.mods.mimi.common.midi.LocalMidiInfo (public static)
UUID id = LocalMidiInfo.createFileId(midiFile);
// UUID.nameUUIDFromBytes от "file:<имя>;tempo:<BPM>;length:<сек>;channels:<byte-маппинг>;"
```

Это **не** `ResourceLocation` вида `mimi:midi/имя.mid` — детерминированный
хэш содержимого файла. `TrackScaffolder` вызывает этот метод напрямую,
чтобы `track_id` в наших JSON гарантированно совпадал с тем, что реально
разошлёт сервер. Путь к файлам — `config/mimi/server_midi_files/*.mid`
и `*.midi`.

## Какой инструмент держит игрок

`InstrumentConfig.getBydId(note.instrumentId)` → `InstrumentSpec` с
полями `registryName` (используется как `mimi:<registryName>` — тот же
ID, что и у предмета/блока) и `isBlock` (портативный vs стационарный).

Канонический список **настоящих** инструментов — `InstrumentConfig
.getAllInstruments()`, а не "все предметы под namespace `mimi`": MIMI
регистрирует под тем же namespace ещё и служебную инфраструктуру
(`ledcube_a`..`h`, `broadcaster`, `relay`, `transmitter`, `tuningtable`
и т.д.), которая инструментами не является — `TrackSyncUtil` и
`ScrollGenerator` используют именно `getAllInstruments()`, чтобы не
подсунуть decorативный блок как требуемый инструмент.

## Блочный инструмент и неподвижность игрока

Игрок на блочном инструменте физически "сидит" на нём через собственную
механику MIMI (`TileInstrument` → `EntitySeat`, обычная посадка как в
лодку/на лошадь) — это уже полностью обездвиживает игрока штатными
средствами ванили. Отдельно ограничивать движение для блочных
инструментов не нужно; для переносных мод сам добавляет короткое
замедление на каждой ноте (см. `MimiNoteBridge.applyFor`).
