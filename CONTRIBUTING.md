# Contributing

Bug reports with a log and, ideally, the save are the most useful thing you can
send (see the issue template). For code, read on.

## Setup

- A **Java 8 JDK** and **Maven 3**. Newer JDKs compile the code but cannot run
  the editor: the embedded browser's natives are Java 8 era.
- `mvn install -Pwin64`, then `run.bat`. `-Pwin64` is required.
- Python 3 with `tools/gamedata/requirements.txt`, to read game data from a
  checkout.
- Windows. Linux still compiles (`-Plinux64`) but nobody has run it recently.

If Maven fails with `PKIX path building failed`, your network inspects TLS and
Java does not trust its certificate. Set
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` to use the Windows
certificate store.

## Running from a checkout

`run.bat` starts `target/eternity-keeper.jar` with a JDK 8 (it prefers
`..\tools\jdk8u492-b09` beside the checkout, else `java` on `PATH`) and keeps
settings and the log in the checkout rather than in `%APPDATA%`. Useful
system properties:

| Property | Effect |
|---|---|
| `-Dek.data=<folder>` | Where settings, the log and game data live |
| `-Dek.ui=<folder>` | Load the UI from this folder |
| `-Dek.debugPort=13002` | Open the embedded browser's DevTools port (the UI tests use it) |
| `-Dek.extractor=<program>` | The game-data reader to run (`.py` runs under Python) |

The UI is loaded from `src/ui` on disk, so a change to the page needs a
restart of the editor, not a rebuild.

## Game data

`tools/gamedata/extract_gamedata.py` reads the item and ability catalogs,
progression tables, stronghold upgrades and deities out of a game install with
[UnityPy](https://github.com/K0lb3/UnityPy). The editor runs it itself (from a
checkout, under `python`), but it also works by hand:

```bash
pip install -r tools/gamedata/requirements.txt
python tools/gamedata/extract_gamedata.py --game "<install folder>" --out "<folder>"
```

See [tools/gamedata/README.md](tools/gamedata/README.md) for what it reads and why.

## Tests

```bash
mvn test -Pwin64
node src/test/js/SaveMergeTest.js
node src/test/js/SaveFindTest.js
node src/test/js/SaveCompareTest.js
node src/test/js/SaveHistoryTest.js
```

The scripted UI suites, which drive a running editor over the DevTools
protocol, are in [tools/ui-tests](tools/ui-tests/README.md).

## Making a release

```powershell
pwsh tools/release/build-release.ps1
```

builds the jar and `Eternity Keeper.exe`, freezes the game-data reader with
PyInstaller, and zips them with a Java 8 runtime into
`target/release/EternityKeeper-<version>-win64.zip`. Where
[Inno Setup 6](https://jrsoftware.org/isinfo.php) is installed
(`winget install --id JRSoftware.InnoSetup -e`) it also builds the installer,
`EternityKeeper-<version>-win64-setup.exe`, out of the same folder
([tools/release/installer.iss](tools/release/installer.iss)).

```powershell
pwsh tools/release/test-installer.ps1 -Setup target/release/EternityKeeper-<version>-win64-setup.exe
```

installs it for the current user into a folder of its own, checks every part is
in place, starts the installed editor and waits for it to draw its page,
upgrades it, then uninstalls it and checks nothing is left but the data folder.

The `Package` workflow does both on GitHub's Windows runner, a machine with
none of the development setup on it: whenever the packaging or the game-data
reader changes, when run by hand, and for a tag named `v*`. To release:

1. Set the version in `pom.xml`, and give its section of `CHANGELOG.md` the
   release date (`## 1.0.0-beta (2026-10-06)`); push, and wait for `CI` and
   `Package` to pass.
2. Tag that commit with `v` and the version, and push the tag:
   `git tag -a v1.0.0-beta -m "Eternity Keeper 1.0.0-beta"`, then
   `git push origin refs/tags/v1.0.0-beta`.
3. A few minutes later the tag's `Package` run has built and tested the
   release again and left a draft on the Releases page, holding the zip, the
   installer and their checksums, with a page written by
   [tools/release/release-notes.ps1](tools/release/release-notes.ps1) out of
   `tools/release/release-notes.md` and the version's section of the
   changelog. Read it over and choose **Publish release**. A version with a
   hyphen in it is published as a pre-release.

## How the saves work

A `.savegame` is a heavily compressed zip: `MobileObjects.save` (the world
state), one `.lvl` file per visited area, `saveinfo.xml` and a screenshot. The
world state is serialized with the .NET library
[SharpSerializer](https://github.com/polenter/SharpSerializer), which
`uk.me.mantas.eternity.serializer` reimplements in Java; `TypeMap.java` maps
every C# type a save can hold. The game's saves are compressed harder than
default zip settings, so the files the editor writes are somewhat larger than
the game's own.

Every item ever sold to a vendor stays in the save, inside the `.lvl` files,
which is one reason saves grow as a game goes on.

[docs/](docs/README.md) has the notes kept while this was built: where each
thing lives in a save, what the game does with it when the save loads, the
rules an edit has to keep, and how each feature was checked in the game.

## How the code is laid out

| Where | What |
|---|---|
| `src/main/java/.../serializer` | A Java reimplementation of SharpSerializer, the .NET library the game saves with. `TypeMap.java` is the schema: a C# type missing from it fails to read. |
| `src/main/java/.../game` | Java mirrors of the game's C# classes |
| `src/main/java/.../save` | Opening, editing and writing saves; one manager per feature (`InventoryManager`, `AbilityManager`, `StrongholdManager`…) |
| `src/main/java/.../handlers` | The bridge the page calls, one class per call, registered in `JSHandlers` |
| `src/ui` | The page: jQuery and Bootstrap in a Chrome 45 browser (no CSS grid, no flexbox `gap`, no custom properties) |
| `tools/gamedata` | The game-data reader (Python, UnityPy) |
| `tools/ui-tests` | Scripted UI suites |
| `tools/release` | The release build |

## Working test-first

Write a failing JUnit test, make it pass, then tidy. Tests extend `TestHarness`;
`src/test/resources` holds real save fixtures, and the serializer runs without
the browser (`Environment.initialise()` then
`new PacketDeserializer(file).deserialize()`). UI changes get a check in
`tools/ui-tests` as well. Then run the editor and make the change for real.

## Rules the save format enforces

Breaking one of these produces a save the game silently drops things from, or
cannot load at all. Most were learned the hard way;
[docs/invariants.md](docs/invariants.md) has each in full, with the fault that
taught it, and [docs/features.md](docs/features.md) says what the game does
with each feature's data when a save loads.

1. **Never write to the user's save.** Edits go to a private working copy
   (`environment/WorkingSave`); Save writes a new file.
2. **Write the exact C# type back.** A `String` where the save had a `UUID`, or
   the wrong numeric type, corrupts the file. `save/model/TypedValue` converts
   what the page sends, and refuses what it cannot convert.
3. **Follow references before mutating.** The format de-duplicates object
   graphs, so a property may be a reference to one elsewhere.
4. **Counts match contents**: the leading object count and every list length.
5. **Parallel structures stay in step**: `ItemList`, `SerializedItemList`
   (UUIDs) and the equipment slots.
6. **A new object is complete**: exact type strings, every component, a
   `Parent` link, and an `InstanceID.Guid` equal to its own `ObjectID`. Copy
   components from a template with `save/PacketMint`; never reuse the
   template's own `Property` objects.
7. **Mutations run on `Environment.mutationWorker`.** A new edit handler
   extends `handlers/SaveMutationHandler`, which does that.
8. **The page never replaces its save data wholesale** after an edit; replies
   go through `SavedGame.adopt`, which keeps the user's unsaved changes.
9. **Anything that changes or removes a file already in the saves folder
   takes a copy first** with `save/SaveBackups`, and stops if the copy fails.
   Delete, Rename and a Save that replaces a same-named file all do.
10. **Check what the game does on load before editing a field.** Several stored
   values are recomputed from something else every time a save loads (a
   companion's base attributes, every `<Skill>Bonus`), so editing them does
   nothing.
11. **A read that comes up short is never written.**
   `PacketDeserializer.deserialize()` throws `ShortReadException` for a file
   cut short or damaged part of the way through, and anything that writes what
   it read reads it that way. `deserializeEvenIfShort()` is for showing a
   damaged save, nothing else.
12. **Write a packet file with `DeserializedPackets.replace`.** The serializer
   appends, so writing straight into an existing file leaves the old contents
   in front of the new. `replace` writes a new file and moves it over the old
   one, refuses an edit that newly breaks one of these rules
   (`serializer/PacketInvariants`, with a message for the user), and keeps the
   file it replaced so that Undo can put it back.
13. **What the opener sends in a field that Save writes back is exactly what
   the save holds.** Save writes every such value it is sent, so anything
   worked out for display goes in a field of its own. An English fallback name
   once reached every saved file this way.
14. **A tab's staged changes touch nothing Save writes until its Apply**, and
   whatever reopens the save from outside the tabs (a resurrection, an import,
   a party change, Undo) waits for a staged draft: `EditHistory.refused()`.
15. **Every query the page makes gets an answer.** Work handed to a worker in
   `handlers/` is `execute(Answered.to(callback, ...))`, which answers
   `failure` for work that dies; `AnsweredTest` reads the package for a hop
   that is not.
16. **Objects brought in from another save get IDs of their own wherever the
   target already has those IDs, in an area file as much as in the world
   state** (`save/GuidRemap`). The `.lvl` files hold objects too: what a dead
   companion had in their quick slots, what the party sold to a store.
17. **The folder the save list unpacked holds only a tile's pictures** until
   `UnpackedSaves.complete(folder)`. Resolve the open save's folder with
   `WorkingSave.forEditing` or `forReading`, never from the list's entry.
18. **Writers read and write the property tree, never the mirror objects the
   deserializer also builds**, which an edit does not change. Find objects,
   components and variables through the save model (`save/model/SaveModel`);
   [docs/glossary.md](docs/glossary.md) says what each word means.

## Testing in the game

A change to what the game loads is finished when the game has loaded it.

- The game reads only `%USERPROFILE%\Saved Games\Pillars of Eternity` and
  looks in it once, at launch. Back that folder up, copy the test save in
  under a name that clashes with nothing, and take it out again afterwards. A
  Trial of Iron save comes back under a new name when the game quits.
- Read the screens, **then read `Player.log`**
  (`%USERPROFILE%\AppData\LocalLow\Obsidian Entertainment\Pillars of Eternity`)
  for `Exception` and `already exists`. Two objects under one ID look fine on
  every screen; the log is the only place the game says so.
- `tools/ui-tests/ingame_*.py` build saves for this through the editor's own
  controls and write down what each edit should look like in the game.

## Pull requests

Keep one change per pull request, with its tests. Say what you verified in the
running editor, and in the game if the change affects what the game loads.
