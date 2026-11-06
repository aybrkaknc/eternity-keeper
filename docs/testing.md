# Testing

> Working notes, kept while the editor was built and moved here from a file
> outside the repository in October 2026. They are written for whoever
> changes the code next: what the game does with a save, measured or read
> out of the decompiled game, and what went wrong when that was guessed at.
> "Invariant N" anywhere in these documents is rule N of
> [invariants.md](invariants.md).

Three layers: JUnit tests for everything that reads or writes a save, scripted
UI suites that drive the running editor ([tools/ui-tests](../tools/ui-tests/README.md)),
and loading the result in the game.

The save-list grid/list controls and the no-rerender selection/open behavior
are covered by `python tools/ui-tests/run_suites.py save_views.py`. The pure
timestamp ordering helper is covered by `node src/test/js/SaveListModelTest.js`.
Archive staging collision and replacement cases are in `SaveGameExtractorTest`;
the headless typed listing/open staging service is in `SaveArchiveServiceTest`.

## Test-first

Work test-first: write a failing JUnit test, make it pass, refactor.
- Tests live in `src/test/java`, extend `TestHarness` (gives `Environment.initialise()`, `EK-` temp-dir cleanup, `mockEnvironment()`/`mockSettings()`, `expose()` for private access, `interceptLogging()`).
- Real save fixtures: `src/test/resources/MobileObjects.save` (23 packets: `Player_Elwyn` + companion, Stronghold present) and `ChangesSaverTest/id 0 Encampment.savegame/`.
- The serializer runs headlessly without JCEF: `Environment.initialise()` + `new PacketDeserializer(file).deserialize()`.
- Recent mutation code (`PartyManagerTest`, `CharacterImporterTest`, `SaveGameRenamerTest`) is the style reference for new tests.
- Final verification: rebuild the jar and exercise the change in the running app against the test environment.

**When the editor will not start ("the editor did not come up").** On
2026-11-01 every suite failed that way, and so did a build of an earlier
commit: the editor died in `CefApp` with `UnsatisfiedLinkError: Native
Library ...\jawt.dll already loaded in another classloader`. The cause was
`%USERPROFILE%\.accessibility.properties` turning on the Java Access Bridge
(`assistive_technologies=com.sun.java.accessibility.AccessBridge`, written
2026-10-31 by something on the machine). AWT then loads the bridge from the
extension class loader, the bridge loads `jawt.dll` there, and JCEF's own
`System.loadLibrary("jawt")` fails. That file is the user's accessibility
setting; leave it. The released editor died the same way for anyone with
the bridge on, so since 2026-11-01 the jar starts in `Launcher`, which sets
`javax.accessibility.assistive_technologies` to an empty list before any AWT
class loads (it cannot be `EternityKeeper.main`: that class is a Frame, and
Toolkit reads the setting while it initialises). Start the jar by hand with
stderr captured to see an error like this one: the suites send the editor's
output to nowhere.

## Testing in the game itself (2026-09-14)

Two saves covering every editor feature were built through the editor's own
controls (`ingame_save.py`, `ingame_save_b.py` in `tools/ui-tests`), loaded in
Pillars of Eternity v3.9.5, and every edit read back off the game's screens:
portrait (party bar and sheet), background, Might, Athletics, an added talent,
a minted catalog item (with its tooltip), money, stronghold Prestige and a
demolished upgrade, party roster (one out, a resurrected companion in),
grimoire contents, difficulty, a character exported to `.chr` and imported into
another save, and a renamed save in the load list. Not observable in the game:
the achievements flag. How to do it again without surprises:

- **The game reads only `C:\Users\<user>\Saved Games\Pillars of Eternity`**, so
  a test save has to be copied there. Back the folder up first and checksum it
  (`find -print0 | xargs -0 md5sum` — the names have spaces); Steam Cloud is on
  for it (`steam_autocloud.vdf`). Afterwards delete what you added and diff the
  checksums.
- **The game scans that folder once, at launch.** A save copied in while it is
  running is not in the load list until the game restarts — it is not broken.
- **Trial of Iron saves are rewritten on quit**: the game deletes the save it
  loaded and writes a new one under a new game ID. Clean-up has to look for
  that file too, not just the name that was copied in.
- **The window is `PillarsOfEternity.exe`** for computer-use access; it has no
  Start-menu entry, so launch it with `Start-Process steam://rungameid/291650`
  first. The access request takes only the full path,
  `D:\Steam\steamapps\common\Pillars of Eternity\PillarsOfEternity.exe`: the
  bare name, "Pillars of Eternity" and "PillarsOfEternity" are all reported
  as not installed (2026-10-27). Close the editor before driving the game — its window comes to the
  front and takes the clicks. Scroll wheels barely move the game's lists; drag
  the scrollbar or click headers instead.
- Native file dialogs the editor opens (export/import `.chr`) are answered with
  `filedialog.py`: find the `#32770` window by title, `WM_SETTEXT` the last
  visible Edit, `WM_COMMAND IDOK`. No focus or keystrokes needed.
- **An area transition writes an autosave** at the top level of the saves
  folder (`<session-guid-with-dashes> autosave_0.savegame`), and **Continue on
  the main menu loads the newest save** — that autosave included, which is a
  quick way back into the area after a restart. The clean-up must delete it;
  the same names inside `2.0 Save Games Backup` are the user's own.
- **Reading health and supplies in the game**: hovering a party portrait
  shows `Endurance x/y` and `Health x/y` (the number under a portrait is
  endurance only); the badge on the camp button is the camping-supplies
  count. The load list groups saves by playthrough, so a save is found
  under its playthrough's group, by the user save name in `saveinfo.xml`.
- **The game's log is `%USERPROFILE%\AppData\LocalLow\Obsidian
  Entertainment\Pillars of Eternity\Player.log`** (`Player-prev.log` the run
  before): one `BEGIN LEVEL LOAD` / `LEVEL LOAD COMPLETE` pair per area and any
  exception in between — the evidence that an edited area file loaded cleanly.
  The `companion_*_5_stored` bundle errors appear on every load of the Caed Nua
  test saves and are not the editor's.
- **`AvailableTurns` is played on load**, not banked: taxes, events and
  adventures for those turns resolve immediately, so the money read in the game
  is the editor's figure plus that turn income (check the stronghold log).
- **The load list's party portraits are `0.png`…`5.png` inside the save**:
  32×41 scaled copies of each party member's `_sm.png`, in
  `PartyMemberAI.AssignedSlot` order (pets, at slot 6+, get none). Since
  2026-09-21 Save redraws them (`save/PartyPortraits`), reading the property
  tree Save just wrote — **not the packets' mirror objects, which
  `Property.update` does not touch**, so a portrait picked in this Save would
  otherwise be missed. Verified in the game: the edited save's load-list entry
  showed the new face and the smaller party beside the untouched original.
  `ingame_portraits.py` builds that save; delete it from the test-env saves
  folder afterwards (the suites expect 12).

- **A character is clicked at the feet.** The game takes a click on the
  circle a character stands in; on the body it is an order to walk there.
  Hovering the feet of someone who can be talked to puts their name up
  ("General Goods Merchant"), which is how to tell an NPC from the party
  member standing in front of him. (2026-10-05: twenty clicks on a
  merchant's chest, each sending Aloth to stand on him, and a detour into the
  area file to find out whether the edit had deleted him. It had not.)
- **A dialogue line is chosen with its number key** (`1`); a click on the
  text only highlights it. Escape does not open the menu, the gear on the
  action bar does. Double-clicking a portrait centres the view on that
  character, and clicking a spot on the area map (the map icon) moves the
  view there, which gets the party across an area without the keyboard.
- **The load list** groups saves by playthrough: by the `SessionID` the
  game keeps on the main character, not by the name the group is labelled
  with (2026-10-25: a save whose world state carried another playthrough's
  ID was listed in that one's group, and its autosave written under that ID).
  The group holding the newest save comes first, so the groups change places
  between launches. A skull beside a save marks a Trial of Iron game. Scene names
  are shown in the game's current language, not the one the save was made in
  (the game here was set to English by October: the registry's
  `LanguageName_h2027703280` under `HKCU\Software\Obsidian
  Entertainment\Pillars of Eternity` reads `english`, though the test saves
  were made in Polish).
- **Trial of Iron, checked in both directions (2026-10-05,
  `ingame_iron.py`).** Switched *on* for a mid-game save: the load list shows
  the skull and the game treats the file as its one save. It deleted the file
  it had loaded and wrote "Great Hall" under a new game ID at the first area
  transition, skull and all. Switched *off* for the prologue playthrough
  (which is a Trial of Iron game): no skull, and the file is still there
  after something else is loaded. The game's own menu reads the same either
  way (Save Game, Load Game, Quit).
- **The game's own exception when it leaves Caed Nua.** Unloading the
  courtyard or the Great Hall with the test saves' party logs
  `NullReferenceException` at `CharacterStats.CheckToAddFlankedAll` (from
  `StatusEffect.ClearEffect`, from `CharacterStats.OnDestroy`), twice, after
  `TRANSITION TO EMPTY COMPLETE`. The untouched save does it too (loaded as a
  baseline on 2026-10-05 for exactly this), so it is not the editor's. When
  reading the log, tell the *unloading of the scene before* (it comes first,
  right after `BEGIN LEVEL LOAD INITIATED`) from the load itself. Quitting
  to the main menu from the Caed Nua courtyard adds a second kind from the
  same `OnDestroy` path, `StatusEffect.GetCurrentAppliedValue` (via
  `get_CurrentAppliedValue`, from `ClearEffect`): the untouched Caed Nua
  save logged it four times, the edited one twice (baseline of
  2026-10-27).
- **Where the General Goods Merchant stands**: the Great Hall
  (`AR_0604_Stronghold_Great_Hall`), in the east room the area map labels
  Treasury, a man in blue with a headwrap. `NPC_General_Goods_Merchant` at
  (-5.2, 19.3), five units from the rewards chest.
- **`jjs` scripts the editor's own reader.** JDK 8 ships Nashorn:
  `jjs -cp target/eternity-keeper.jar script.js -- file.lvl`, with
  `Java.type('uk.me.mantas.eternity.serializer.PacketDeserializer')`, reads a
  packet file headlessly in a dozen lines. It answered "is the merchant still
  in the edited area file, and what exactly did the edit take out" in a
  minute: the 533 objects his 693 entries had, and nothing else.
- **Clean-up moves, it does not delete.** What a test leaves in the real
  saves folder (the copies, the autosave an area transition writes, the file
  a Trial of Iron game rewrites itself as) is moved to
  `test-env\ingame-leftovers-<date>`, and the folder's checksums are then
  compared with the ones taken before.
