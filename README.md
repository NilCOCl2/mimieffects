# MimiEffects — статус на старте

## Что уже есть и проверено

Собрано и прогнано (см. ниже, как воспроизвести):

- `orchestrator/EffectCalculator.java` — формула из ТЗ §4.5, чистая математика.
- `orchestrator/ClusterAnalyzer.java` + `ClusterableSession.java` — кластеризация
  по songId + связные компоненты по дистанции (ТЗ §4.3). Специально сделана
  generic и без зависимостей от Minecraft, чтобы её можно было тестировать
  без dev-окружения.
- `track/TrackConfig.java`, `Arrangement.java`, `EffectEntry.java`,
  `TrackRegistry.java` — модель данных трека и выбор arrangement (ТЗ §4.4).
- `track/TrackLoader.java` — сканер `config/mimieffects/tracks/*.json`
  (использует Gson — он идёт транзитивно с Minecraft/NeoForge, поэтому этот
  файл не компилируется отдельно, но логика небольшая и прямолинейная).
- `track/TrackScaffolder.java` — автоматическое обнаружение MIDI-файлов без
  конфига (сканирует `server_midi_files`) и создание для них
  JSON-заготовки с `track_id` и пустыми `required_instruments`/`effects`.
  Заготовка ничего не делает в игре, пока админ не впишет инструменты и
  эффекты — безопасный дефолт. Логика диффа (что уже сконфигурировано,
  что нет) проверена в `selftest/ScaffolderDemo.java`: из 3 midi-файлов
  1 уже покрыт конфигом (пропущен), 2 новых получили корректные заготовки.
  **Формат `track_id` (`mimi:midi/<файл>.mid`) — предположение из ТЗ §6,
  требует подтверждения после реверс-инжиниринга MIMI.**
- `config/GlobalConfig.java` — `ModConfigSpec` 1:1 со схемой `common.toml`
  из ТЗ §3.1.
- Примеры треков: `config/mimieffects/tracks/zemlya.json`,
  `_improvisation.json`.

### Найденный и исправленный баг на этапе примеров

В ТЗ §9 импровизация должна давать эффект "для любых 2+ инструментов", но
`required_instruments` для неё пустой. Прямая реализация "coverage = 1.0 при
пустом required_instruments" сработала бы и для одного инструмента —
тихая ошибка. Добавил `Arrangement.min_instrument_count` (используется
только когда `required_instruments` пуст) и юнит-тест на оба случая (1 и 2
инструмента).

### Как перепроверить логику самостоятельно

Файлы в `orchestrator/` и `track/` (кроме `TrackLoader`) собираются чистым
`javac` без Minecraft на classpath:

```
javac -d out src/main/java/com/mimieffects/orchestrator/*.java \
             src/main/java/com/mimieffects/track/EffectEntry.java \
             src/main/java/com/mimieffects/track/Arrangement.java \
             src/main/java/com/mimieffects/track/TrackConfig.java \
             src/main/java/com/mimieffects/track/TrackRegistry.java
```

Отдельно есть `../mimieffects-selftest/SelfTest.java` — standalone-копия
формулы и кластеризатора с 13 проверками (граничные случаи: maxLevel-кап,
диссонанс до нуля, дальний игрок не попадает в кластер, разные треки не
сливаются, транзитивная цепочка через промежуточного игрока всё-таки
сливается в один кластер). Все 13 проходят.

## Решение по конфигурированию сервера (важно!)

Проверил актуальную документацию NeoForge для 1.21.1: конфиг-экраны есть
из коробки (`IConfigScreenFactory` + `ModConfigSpec`), отдельный мод типа
Configured не нужен. **НО**: серверный (`SERVER`) конфиг редактируется в
этом экране только если ты сам локальный хост (синглплеер/LAN) — на
реальном выделенном сервере поле недоступно для обычного подключения.

Из этого решение для MVP:

1. `GlobalConfig` — `SERVER`-тип `ModConfigSpec`, синхронизируется на
   клиентов автоматически. Правится через TOML-файл + команда
   `/mimieffects reload` (уже есть в критериях готовности ТЗ §8) —
   работает всегда, не требует доп. зависимостей, не выглядит подозрительно.
2. Встроенный NeoForge-экран — бесплатный бонус для локального тестирования,
   но не решение для продакшена.
3. Фаза 2 (опционально): свой GUI для OP-админов поверх той же пакетной
   инфраструктуры, что и `ConductorWandScreen` (сервер → клиент пакет с
   текущими значениями, клиент шлёт изменения обратно, сервер валидирует и
   сохраняет TOML). Даёт живое редактирование на реальном сервере без
   доступа к файлам. Не начинал, чтобы не распылять MVP.

Веб-морду решили не делать вообще — согласились, что она будет странно
смотреться рядом с игровым модом, а `/reload`-паттерн полностью закрывает
потребность админа.

## Новая фича: живая музыка отгоняет монстров (mob_purge)

Запрошено пользователем 2026-08-31: уникальный трек, сыгранный на статичном
(блочном) инструменте, убивает монстров в радиусе (по умолчанию 128 блоков
= 8 чанков — уточнил единицы явно в коде, чтобы не перепутать с диаметром).

Ключевое архитектурное решение: **Born in Chaos закрытый мод** (All Rights
Reserved, собран в MCreator) — исходников нет, поэтому строковые ID вида
`borninchaos:corpse_fish` были бы чистой догадкой с риском ошибиться в
написании. Вместо этого таргетинг идёт **по факту нахождения существа в
воде прямо сейчас** (`entity.isInWater()`) + стандартной ванильной проверке
враждебности (`Enemy` marker interface) — это работает одинаково для
ванильных зомби и для любого модового существа, независимо от того, как
оно называется в реестре. См. `MobPurge.java` и
`orchestrator/MobPurgeMatcher.java` (протестировано — 8 новых проверок,
включая гарантию "игрока и питомцев не убьёт даже при кривом конфиге").

Примеры: `zemlya.json` → `environment: "surface"` (наземные/летающие у
дома), `deep_blue_ultrakill.json` → `environment: "underwater"`
(подводная база). `track_id` в обоих — плейсхолдер, реальный UUID появится
после реверс-инжиниринга конкретного .mid файла (см. выше).

## Дальше по плану

- ~~Поднять dev-окружение и реверс-инжинирить MIMI~~ — сделано чтением
  исходников, см. `REVERSE_ENGINEERING.md`. Остался только один пункт:
  прогнать это вживую против релизного джара 1.21.1, чтобы поймать
  возможные расхождения между GitHub main и опубликованным релизом.
- `InstrumentDetector` — реализовать по найденным в `REVERSE_ENGINEERING.md`
  публичным методам MIMI (`isEntityHoldingInstrument`,
  `ServerTransmitterManager.createStatusPacket` и т.д.).
- `MobPurgeExecutor` — тикающий сервис, читающий `TrackConfig.mob_purge`
  и вызывающий `MobPurgeMatcher` на каждом кандидате в радиусе (логика
  решения уже готова и протестирована, нужна только обвязка
  `Level.getEntitiesOfClass(...)` + вызов `kill()`/`discard()`).
- `SessionManager` / `Orchestrator` — обвязать уже готовые
  `ClusterAnalyzer`/`EffectCalculator`/`TrackRegistry` реальными тиками
  сервера и `MobEffectInstance`.
- `ConductorWandItem` + `ConductorWandScreen` + `SyncWandPacket`.
