# Game-data reader

Reads what the editor shows about the game — real item names and icons,
abilities, progression rules, stronghold upgrades, deities — out of a Pillars
of Eternity install:

    pip install -r requirements.txt
    python extract_gamedata.py --game "<install folder>" --out "<folder>"

The editor normally runs this itself: it offers to on first launch, and
Settings can run it again after the game updates. A release ships it frozen by
PyInstaller as `gamedata\extract_gamedata.exe` (`tools/release/build-release.ps1`),
so players need no Python. From a checkout, the editor runs this script under
`python`; `-Dek.extractor=<program>` points it at another copy.

Why offline: all of this lives inside Unity asset bundles and the game's own
`resources.assets`, which Java 8 can't realistically parse. And why from the player's own install: the data is the
game's, so it can't ship with the editor — the same policy the editor has
always followed for portraits.

## What it writes

| File | What it holds | Stage |
|---|---|---|
| `catalog.json` | ~2,150 items: display name, icon, prefab path, max stack size, the `Equippable` per-slot flags, class restrictions, the game's item filter category, price, quality tier (soulbound / unique / fine) | `items.py` |
| `abilities.json` | ~1,440 abilities, spells and talents: display name, description, icon, the exact component class the save must record, `EffectType`, spell level, and for talents what they grant or modify | `items.py` |
| `progression.json` | All 26 `AbilityProgressionTable`s — which class, subrace or companion may take what, and from which level | `items.py` |
| `icons/` | The PNGs both catalogs reference | `items.py` |
| `stronghold.json`, `stronghold-icons/` | The 25 buildable upgrades with cost, time, Prestige and Security, prerequisites, and the 30 hirelings — see [STRONGHOLD-NOTES.md](STRONGHOLD-NOTES.md) | `stronghold.py` |
| `identity.json` | The 5 deities and 6 paladin orders with the dispositions they favour, and the bonus ladder | `identity.py` |

The item stage reads the object bundles; the other two read the `InGameGlobal`
prefab out of `resources.assets`, where the game loads it from
(`ingameglobal.py`).
| `nameId`, `descId`, `descriptionId` in the above | Beside each name and description, the string table and entry it was read from, as `[table, id]` (`DatabaseString.StringTableType`: 5 is items, 6 abilities...). The editor shows the name in the language the game is played in by reading the same entry from that language's table on the player's install | all three |
| `gamedata.json` | What was read, from where and when — the editor's Settings dialog shows it | `extract_gamedata.py` |

The editor looks for this in `%APPDATA%\Eternity Keeper\gamedata` (where it
writes), then in an `itemdata` folder beside the jar, in the working directory,
or beside it — where earlier versions kept it. The `itemDataLocation` setting
overrides all of them. Without any of it the editor still works; items just
show prettified file names.

## Protocol

`extract_gamedata.py` talks to the editor on stdout:

    PROGRESS <percent> <what it is doing>
    DONE <output folder>
    ERROR <reason, in words a player can act on>

Anything else is the stages' own chatter and only reaches the log. Exit code 2
means the folder is not a game install; 1 means a stage failed. The output is
assembled in `<out>.new` and moved into place only when every stage has
succeeded, so a failed or stopped run leaves the previous data untouched.

A full run opens all 4,604 object bundles and takes a few minutes (195 seconds
on the development machine). Its expected summary:

    DONE: 2156 items, 976 abilitys, 273 spells, 191 talents, 1387 icons, 0 bundles unreadable
    25 upgrades, 30 hirelings, 25 icons
    5 deities, 6 orders

## Two things worth knowing before editing `items.py`

**A bundle ships its subject's dependencies too**, including other real items —
a belt that summons a weapon ships the weapon's prefab. The component that
describes the thing the bundle is *named* after is the one to read; picking the
first match once labelled a belt "Firebrand".

**Items and abilities look alike.** A potion carries a `GenericAbility`
component describing its effect, so the ability scan would happily claim it;
anything with an inventory icon (`IconTexture`) is an item and is skipped.

## The `*.unity3d` files are not the game's

The bundles the game loads have no extension (`objectbundle/acceptance`, with
`acceptance.mainasset` beside it naming the prefab inside) and were built with
Unity 2019.4, which the game has run on since its 2025 rebuild. The
`*.unity3d` files in the same folders — 2,998 of them on a Steam install that
has been updated since 2015, `ingameglobal.unity3d` and `global.unity3d`
included — are the original game's Unity 4.6 bundles, which Steam's updates
never removed and which the current game cannot load. A clean install has
none of them, which is how a GOG player found that the reader opened
`ingameglobal.unity3d` for the stronghold data: it had the Hearth and the
Courtyard Pool at 1,600 cp (the game: 600) and lacked two hirelings. Read what
the game loads; `UnityPy.load(path).assets[0].unity_version` says which build
made a file.
