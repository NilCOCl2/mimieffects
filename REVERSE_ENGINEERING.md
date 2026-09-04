# Реверс-инжиниринг MIMI — итоги (по исходникам, не по догадкам)

Сделано чтением реального кода `tofodroid/mimi-mod` (main, MIT), а не через
dev-окружение и логирование "вслепую". Оказалось, что почти всё нужное —
**публичные static-методы**, никакого Mixin/рефлексии не понадобится.

## Главный вывод, меняющий план

MIMI не хранит "IsPlaying" как флаг NBT — вся система событийная
(`MidiNotePacket` на каждую ноту). А `track_id` — это **не**
`ResourceLocation` вида `mimi:midi/имя.mid` (наша догадка из ТЗ была
неверной), а **детерминированный UUID**, вычисляемый из имени файла +
темпа + длины + channel mapping. Ниже — точный путь, как получить всё,
что нужно `InstrumentDetector`, через официальные публичные методы.

## 1. Какой инструмент держит игрок в руке

```java
import io.github.tofodroid.mods.mimi.common.item.ItemInstrumentHandheld;

boolean holding = ItemInstrumentHandheld.isEntityHoldingInstrument(player);
Byte mainHandId = ItemInstrumentHandheld.getEntityHeldInstrumentId(player, InteractionHand.MAIN_HAND);
Byte offHandId  = ItemInstrumentHandheld.getEntityHeldInstrumentId(player, InteractionHand.OFF_HAND);
```

Оба метода `public static`. NBT читать не нужно вообще.

## 2. Блочный инструмент — сидит ли игрок на нём

Подтвердилась гипотеза ТЗ про `Vehicle`, но детали другие:

- `TileInstrument` (block entity инструмента) хранит `currentSeat` типа
  `EntitySeat` и отдаёт `getCurrentPlayer()` (`public`).
- `EntitySeat` — обычная сущность (`ModEntities.SEAT`), у неё `getRider()`.
- То есть `player.getVehicle() instanceof EntitySeat` и дальше можно либо
  взять `((EntitySeat) vehicle).getRider()`, либо, если есть ссылка на
  `TileInstrument`, звать `tile.getCurrentPlayer()` напрямую.

## 3. Какую песню сейчас реально играет источник игрока

Это самое ценное открытие. У каждого инструмента (ItemStack) есть NBT-тег
`source_uuid` (реальное имя тега, константа `MidiNbtDataUtils.SOURCE_TAG`)
— он указывает, ОТКУДА инструмент получает ноты: это может быть UUID
живого игрока (импровизация с MIDI-клавиатуры) или UUID Transmitter'а
(блочного или предмета), который проигрывает файл.

```java
import io.github.tofodroid.mods.mimi.util.MidiNbtDataUtils;
import io.github.tofodroid.mods.mimi.server.events.broadcast.producer.transmitter.ServerTransmitterManager;
import io.github.tofodroid.mods.mimi.common.network.ServerMusicPlayerStatusPacket;

UUID sourceId = MidiNbtDataUtils.getMidiSource(instrumentStack); // null если не подключён
ServerMusicPlayerStatusPacket status = ServerTransmitterManager.createStatusPacket(sourceId);

if (status != null && status.isPlaying) {
    UUID songId = status.fileId; // <-- это и есть наш track_id!
} else {
    // источник не проигрывает файл -> импровизация (track_id == null)
}
```

`createStatusPacket` — `public static`, `ServerMusicPlayerStatusPacket`
у же содержит готовый `.isPlaying` и `.fileId`. Всё, что нужно, отдаётся
одним вызовом — ни Mixin, ни подписки на пакеты не требуется.

`PlayerTransmitterBroadcastProducer` регистрируется по `player.getUUID()`
(значит, "источник = сам игрок, играющий вживую" — тоже валидный вызов
`createStatusPacket(player.getUUID())`, просто `fileId` будет null, если
файл не загружен — естественно матчится с "импровизацией" из ТЗ §9).

## 4. Как считается track_id (fileId) на самом деле

```java
// io.github.tofodroid.mods.mimi.common.midi.LocalMidiInfo (public static)
public static UUID createFileId(File file) {
    // UUID.nameUUIDFromBytes от строки:
    // "file:<имя файла>;tempo:<BPM>;length:<сек>;channels:<byte-маппинг>;"
}
```

Значит **track_id — это UUID**, а не читаемая строка. Наш `TrackScaffolder`
уже поправлен: он теперь зовёт этот самый метод MIMI (а не строит
`mimi:midi/имя.mid` по догадке), чтобы `track_id` в наших JSON-конфигах
гарантированно совпадал с тем, что реально разошлёт сервер.

Путь к файлам подтверждён из кода:
`config/mimi/server_midi_files/*.mid` (и `*.midi` — оба расширения
принимаются, ТЗ упоминало только `.mid`).

## 5. Публичного API/событий действительно нет

Проверил явно: ни одного `EVENT_BUS.post(...)`, ни одного класса `extends
Event` во всём мод-дереве. Утверждение ТЗ §1 подтверждено — но, как видно
выше, отсутствие форджевых ивентов не помешало: почти все нужные точки
входа оказались обычными публичными static-методами внутри классов,
которые не помечены `final`/`private`.

## 6. Реальный реестр инструментов — тоже данные, не гадание

Полный список инструментов лежит прямым текстом в
`src/main/resources/data/mimi/instruments/default.json` (плюс
`custom.json` для модпак-дополнений) — публичные данные, задают
`instrumentId` (Byte), `registryName` (используется как ID предмета/блока,
`mimi:<registryName>`) и `isBlock` (ручной vs блочный). Например:

| registryName | isBlock | (реальное, не "electric_guitar") |
|---|---|---|
| `elecguitar` | false | электрогитара, ручной |
| `bassguitar` | false | бас-гитара, ручной |
| `acguitar` | false | акустическая гитара, ручной |
| `drums` | **true** | барабаны — блочный инструмент! |
| `violin` | false | скрипка, ручной |
| `marimba`, `xylophone`, `organ`, `piano`, `keyboard` | true | блочные |

Примеры треков (`zemlya.json`) обновлены на реальные `registryName` вместо
выдуманных `electric_guitar`/`bass_guitar`/`drum_kit`. `track_id` в
примерах пока placeholder — реальный UUID появится только когда конкретный
.mid файл ляжет в `config/mimi/server_midi_files/` (см. п.4 выше).

## Итог: открытых вопросов для дев-окружения почти не осталось

Всё, что казалось риском проекта (пункт "MIMI не публикует API" из ТЗ §1),
оказалось решаемо чтением исходников за один присест:
`isEntityHoldingInstrument`/`getEntityHeldInstrumentId` для рук,
`EntitySeat`/`TileInstrument.getCurrentPlayer()` для блочных, `source_uuid`
→ `ServerTransmitterManager.createStatusPacket()` для `songId`,
`LocalMidiInfo.createFileId()` для стабильного track_id, и данные
инструментов прямо в JSON. Реальное dev-окружение с MIMI всё равно
понадобится — проверить это вживую, а не только по коду (сигнатуры могли
измениться между версией на GitHub и релизным джаром 1.21.1, которым вы
пользуетесь) — но это уже не "неизвестная территория", а верификация
конкретных, выписанных здесь предположений.


- **Резервный план ТЗ §6 (Mixin/рефлексия) не нужен вообще.** Прямая
  зависимость на MIMI jar (уже заложена в `build.gradle` как
  `implementation files('libs/mimi-mod-...jar')`) даёт всё необходимое
  через публичный API.
- **Формат `track_id` меняется с "строка/ResourceLocation" на UUID** —
  затронуло `TrackConfig`/`Arrangement`/`TrackScaffolder` (см. изменения
  в коде) и сами файлы примеров `zemlya.json`/`_improvisation.json` —
  их track_id теперь должен быть настоящим UUID конкретного .mid файла,
  который будет лежать в `config/mimi/server_midi_files/`. До первого
  реального прогона на сервере наши примеры остаются "как есть" —
  плейсхолдеры для документации, реальные значения появятся только когда
  туда лягут настоящие MIDI-файлы.
- Единственное, что ещё желательно проверить в реальном dev-окружении —
  сверить сигнатуры и константы из этого документа с тем, что реально
  скомпилируется против релизного джара MIMI для 1.21.1 (код на GitHub
  мог чуть разойтись с опубликованным релизом). Строковые ID инструментов
  для `required_instruments` уже не гадание — см. п.6 ниже.
