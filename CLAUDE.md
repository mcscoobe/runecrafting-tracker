# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build the plugin
./gradlew build

# Launch a real RuneLite client with this plugin loaded (developer mode)
./gradlew runTestClient

# Run unit tests
./gradlew test
```

Java 1.8 source compatibility. RuneLite is resolved as `latest.release`, so a build can
break from an upstream API change without any local edit.

There are no unit tests — `src/test/.../RunecraftingTrackerPluginTest` is only a `main()`
that boots RuneLite with the plugin as a builtin, so `./gradlew test` runs nothing.
Verification is manual: `runTestClient`, then craft at an altar.

## Architecture

**Package:** `com.runecraftingtracker` — five classes, no sub-packages.

| Class | Role |
|---|---|
| `RunecraftingTrackerPlugin` | Entry point. Owns all mutable state, subscribes to events, drives detection and persistence. |
| `RunecraftingTrackerPanel` | Swing `PluginPanel`. Rebuilds its whole layout in `pack()`. |
| `PanelItemData` | Mutable per-rune model: name, item ID, visible flag, crafted count, cached GP price. |
| `Runes` | Enum of the 22 craftable runes → `ItemID` values. Constant names double as config keys. |
| `RunecraftingRegions` | `ImmutableSet` whitelist of map region IDs where crafting counts. |

## Core Detection Flow

Detection is a two-phase, tick-aligned diff. XP does **not** trigger counting directly:

1. `onStatChanged` (`Skill.RUNECRAFT`) inside a whitelisted region just sets the
   `runecraftXpGainedThisTick` flag. XP outside a known region is ignored entirely.
2. `onGameTick` builds a fresh `Multiset<Integer>` of inventory + rune pouch contents. If the
   flag is set, it takes the **positive-only** delta against last tick's snapshots and feeds
   that to `updateRuneTracker()`. Snapshots are then rolled forward and the flag cleared.

Two invariants that are easy to break:

- The snapshot must advance **every** tick, not just crafting ticks — otherwise a bank
  withdrawal between crafts gets attributed as crafted runes.
- After `LOGGING_IN`, both snapshots are set to `null` so the first tick re-primes the
  baseline and returns early; pre-existing runes must never count as crafted.

Only increases are counted (`addPositiveDiff`), so consuming runes never decrements. The
plugin deliberately does **not** subscribe to `ItemContainerChanged`.

## Rune Pouch Tracking

Six slots, read from `VarbitID.RUNE_POUCH_TYPE_1..6` / `RUNE_POUCH_QUANTITY_1..6`. The type
varbit is an enum index, not an item ID — it is resolved through client enum `982`
(`EnumID.RUNEPOUCH_RUNE`, hardcoded as `ENUM_RUNEPOUCH_RUNE` because the API constant isn't
exposed yet). Pouch counts merge into the same diff as the inventory.

## Persistence

Counts survive restarts via `ConfigManager` in group `runecraftingtracker`, one int key per
rune: `crafted.<ENUM_NAME>` (e.g. `crafted.BLOOD`). Written on every increment, unset by
"Reset All". `init()` loads them on the first `LOGGING_IN` and a non-zero count marks the row
visible.

## Threading

- `ItemManager.getItemPrice()` asserts client-thread access, so `refreshPanel()` caches prices
  into `PanelItemData` on the client thread *before* dispatching to the EDT.
- "Reset All" fires on the EDT but routes through `clientThread.invokeLater()`, so all
  mutation of `runeTracker` happens on one thread and a concurrent craft tick can't repopulate
  rows mid-reset.
- The `SwingUtilities.invokeLater` body null-checks `uiPanel` — it can run after `shutDown()`.

## Region Whitelist

`RunecraftingRegions.REGIONS` is the gate for all counting; a missing region means silently
zero tracking at that altar. Some entries are verified in-game and annotated as such; the Law
Altar interior is still unconfirmed (see the comment in the file). Verify any new region
in-game with dev tools rather than trusting wiki coordinates. Altars overlap heavily, so
adding a region for one altar often already covers another.

Region reads use `client.getTopLevelWorldView().getMapRegions()`; the bare
`Client.getMapRegions()` is deprecated.
