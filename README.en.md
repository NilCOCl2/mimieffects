[Русский](README.md) | **English**

# MIMI Effects

An addon for [MIMI](https://modrinth.com/mod/mimi) on NeoForge 1.21.1.
The idea is simple: MIMI lets you play real, live music in Minecraft —
this mod gives that music a purpose. Any song can be set up to do
things to the world around it: heal and buff nearby players, drive off
and kill mobs, and demand specific instruments played by a specific
number of musicians. All of it is configured without touching source
code — through JSON files or an in-game editor.

## How it works

Whenever someone plays a MIMI instrument (handheld or block), the mod
checks:

1. **Which song is currently playing** — by `track_id`, which MIMI
   itself computes from the MIDI file (it's a hash, not the file name;
   there's no reason and basically no way to set it by hand).
2. **Who else is nearby and what they're playing** — everyone
   currently playing the *same* song within `base_radius` blocks of
   each other is grouped into one "ensemble" (cluster). Players
   playing different songs near each other don't interfere with one
   another unless dissonance handling is explicitly turned on.
3. **Whether the ensemble's instrument makeup matches** one of that
   track's arrangements (`required_instruments`) — only then do
   effects actually apply.

### Ensemble buffs

Each arrangement's effect level scales along two independent bonuses:
how many players are in the ensemble (`per_player_bonus`) and how
complete the instrument set is (`full_ensemble_bonus`). Playing solo
works and grants the base effect — a full lineup makes it stronger
rather than being required to turn it on at all (unless you explicitly
lock a track to "full ensemble only", see below).

- **Solo play works by default.** Want a track that does nothing at
  all short of a full lineup? Set `min_ensemble_size` on the
  arrangement — a hard headcount floor, independent of which
  instruments actually showed up.
- **One track, several layers of effects**: each effect inside an
  arrangement has its own `min_ensemble_size` — e.g. "Luck" is always
  active solo, while "Luck II" only kicks in once three or more
  players have gathered. The same track can gradually "unlock" more
  as the lineup grows.
- **Dissonance**: if different music is playing nearby at the same
  time, the behavior is configurable (`Ensemble.behavior` in the
  config) — lower the effect level (`reduce`), grant no effect at all
  (`cancel`), or ignore the other music entirely (`ignore`).
- **Slowness while playing**: holding a handheld instrument and
  actually playing notes applies a short Slowness II (refreshed on
  every note). Stationary (block) instruments already immobilize the
  player through MIMI's own seat mechanic, so nothing extra was needed
  for those.

### Mob purge

A separate, opt-in mechanic at the track level (not the arrangement
level): while a specific song plays on a **stationary** instrument (by
default — handheld can be allowed too), the music gradually damages
qualifying mobs within range and sends them fleeing in panic until the
track stops or the mob dies. No instant one-note kills — just a slow
fade.

Finely configurable: hostile-only or everything, surface-only /
underwater-only / anywhere, damage per tick and the interval between
"hits", and a radius that can grow with the number of musicians in the
ensemble (opt-in, so it can't turn into an uncontrolled AoE weapon by
accident). Bosses (by HP threshold, exact type, tag, or a whole mod)
are protected from purge globally, unless a server explicitly opts
back in.

Purge kills suppress XP drops (so it can't double as a passive farm)
and only let "worthwhile" items fall — armor, weapons, tools, and
anything with above-common rarity (enchanted, or flagged rare by any
other mod); bones, rotten flesh and similar junk don't clutter the
world with extra item entities.

### Note Scrolls

Playing a track isn't enough on its own to get its buffs — the
triggering player also needs a **Note Scroll** bound to that specific
track anywhere in their inventory (not in hand, just carried). It's
not consumed — one scroll works forever as long as it's in your
inventory. A scroll does **not** gate `mob_purge` — mob purge works
whether or not anyone's carrying a scroll.

- There is no crafting recipe, and there won't be — deliberately, so
  the mod can be removed from a server at any time without leaving a
  broken crafting chain or progression gate behind.
- Scrolls come from MIMI's own villager musician NPC (the
  "instrumentalist" profession, available from trading level 1) or
  from creative/`/give`.
- The creative inventory's "Ingredients" tab lists one already-bound
  scroll per currently loaded track — like enchanted books listing one
  per enchantment, instead of a single useless unbound stack.
- Holding Shift while hovering a scroll shows exactly which
  instruments are needed and how many players are required for each
  effect — no more guessing at a lineup blind.

## Track editor and generator

`/mimieffects gui` opens an in-game editor: a file list on the left,
and on the right — the track's name, required instruments, effects,
targets (who receives the effect), mob purge settings, and the
ensemble gate. Instruments and effects are picked from the real live
list MIMI and the server's effect registry actually expose — never
typed in blind.

If hand-configuring dozens of tracks sounds tedious, **`/mimieffects
genscrolls`** fills in every still-untouched stub itself: it picks 1-3
honest vanilla positive effects (occasionally a themed pair, like
"water breathing + dolphin's grace"), the instruments needed for them
(favoring mainstream ones — winds, guitars, microphones, piano, drums
— configurable), and, rarely, gives exactly one track in the whole
batch mob purge paired with healing effects. Tracks already configured
by hand are left untouched. `/mimieffects clearscrolls confirm` resets
everything back to blank stubs if you want to regenerate from scratch.

## Commands

All commands require operator level 2+.

| Command | What it does |
|---|---|
| `/mimieffects reload` | Reload `config/mimieffects/tracks/*.json`, scaffold stubs for new MIDI files without a config |
| `/mimieffects gui` | Open the track editor |
| `/mimieffects genscrolls [max ensemble size]` | Auto-fill unconfigured tracks with random effects/instruments |
| `/mimieffects clearscrolls confirm` | Reset every track back to an unconfigured stub |

## Server config

`config/mimieffects-server.toml`, synced to clients on join. Sections:
**General** (ensemble radius, tick interval, session timeout, default
behavior, editor row limits), **Ensemble** (per-player/full-ensemble
bonuses, dissonance behavior), **MobRules** (boss protection, extra
hostility rules), **Generation** (instrument priority for the
generator), **Debug** (a step-by-step trace of every note — turn this
on when something "just doesn't work" instead of guessing).

## Example track config

```jsonc
{
  "track_id": "<UUID, computed automatically by MIMI — don't set by hand>",
  "display_name": "Track name",
  "spell_name": "Hymn of Exorcism: Swiftness",
  "artist": "Artist (optional)",
  "midi_file_name": "song.mid",
  "arrangements": [
    {
      "name": "Solo",
      "required_instruments": { "mimi:acguitar": 1 },
      "effects": [
        { "effect": "minecraft:regeneration", "base_level": 1, "max_level": 3, "min_ensemble_size": 1 },
        { "effect": "minecraft:absorption", "base_level": 1, "max_level": 2, "min_ensemble_size": 2 }
      ],
      "affects": "all_nearby",
      "min_ensemble_size": null
    }
  ],
  "mob_purge": {
    "radius_blocks": 64,
    "max_radius_blocks": null,
    "requires_static_instrument": true,
    "environment": "surface",
    "hostile_only": true,
    "purge_interval_ticks": 20,
    "damage_per_tick": 4.0
  }
}
```

## Known limitations

- Only a track's first arrangement is editable in the GUI — tracks
  with several variants (solo/duet/full band) keep the others on
  disk, but they can't be edited from the editor.
- One `targets` set per arrangement — you can't have one effect list
  simultaneously "heal allies, harm hostiles" with different targets
  per effect.
- There's no dedicated item to open the editor with — only the
  command.
- On a real (non-singleplayer) server, the mod's config screen is only
  available to the local host — that's a NeoForge limitation, not this
  mod's.

## Building

```bash
./gradlew build
```

(Windows: `gradlew.bat build`.) Requires JDK 21. The first build
downloads the NeoForge toolchain and the MIMI dependency from the
Modrinth Maven — a few minutes the first time, that's expected.

The built jar ends up at `build/libs/mimieffects-<version>.jar`. Drop
it into `mods/` next to the matching version of **MIMI**.

## Development

Started as a joint project with Claude (Anthropic) — the mod's
scaffolding, config, ensemble/clustering, GUI editor, scroll generator,
and reverse-engineering MIMI's internals. Live integration with MIMI's
note stream via Mixin, and ongoing balancing — NilCOCl2.
