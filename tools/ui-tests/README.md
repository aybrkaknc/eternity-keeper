# UI suites

Scripts that drive a running editor through the embedded browser's DevTools
protocol and check what it does: every view and dialog, one real edit through
every write path (saved and read back from the written file), layout rules
measured in pixels, both themes. The Java tests cover the save format; these
cover the page.

Windows only, like the editor.

## Setup

1. Build the editor: `mvn install -Pwin64` (the suites run `target/eternity-keeper.jar`).
2. Make a test environment, a folder of copied saves the suites may edit:

   ```bash
   java -Djava.io.tmpdir=../test-env -Dek.data=target/ui-tests/data -cp target/eternity-keeper.jar uk.me.mantas.eternity.TestEnvironment "<game install>" "<your saves folder>" -n 12
   ```

   This makes `..\test-env\EK_TEST_ENV\{poe,save}` beside the checkout. It
   copies saves out of your saves folder and never writes into it.
3. Read the game data once (several suites need item names and icons):

   ```bash
   python tools/gamedata/extract_gamedata.py --game "<game install>" --out "<folder>"
   ```

4. `pip install websocket-client`

`config.py` says where everything is. Each value can be set with an environment
variable instead:

| Variable | Default |
|---|---|
| `EK_REPO` | this checkout |
| `EK_JAVA` | `..\tools\jdk8u492-b09\bin\java.exe` beside the checkout, else `java` (must be Java 8) |
| `EK_TEST_SAVES` | `..\test-env\EK_TEST_ENV\save` beside the checkout |
| `EK_GAMEDATA` | `..\itemdata` beside the checkout |
| `EK_GAME` | the Steam install, for `gamedata_ui.py full` |
| `EK_UI_TEST_OUT` | `target\ui-tests`: logs, screenshots, the editor's settings |
| `EK_DEBUG_PORT` | 13002 |

**Never point `EK_TEST_SAVES` at your real saves.** The suites open, edit and
write saves there (they delete what they write).

## Running

```bash
python tools/ui-tests/run_suites.py                     # every suite below but gamedata_ui
python tools/ui-tests/run_suites.py writes.py mint.py
```

`run_suites.py` starts a fresh editor for each suite, with its own settings in
`target\ui-tests\data`, and writes each suite's output to
`target\ui-tests\suite-<name>.log`. It closes every running editor first
(`java.exe`, `javaw.exe` and the browser's `jcef_helper.exe`, which outlives
a killed java and keeps the DevTools port bound). Close your own editor before
running it.

| Suite | Checks |
|---|---|
| `smoke.py` | A cold boot: every view opens and nothing throws |
| `functional.py` | One real interaction per feature, checked against the model |
| `panels.py` | Revert and Apply on every panel |
| `audit.py`, `audit2.py` | Every view and dialog, in both themes: nothing clipped, off screen, too small to hit or faded into its background (`shaudit.js` measures) |
| `paging.py` | Paged browsers reach the end of their lists |
| `format_ui.py` | The save-format conversion dialog |
| `consistency.py` | Views agree with each other after edits |
| `merge_bugs.py` | Unsaved edits survive every Apply |
| `writes.py` | One edit through every write path, saved and read back from the file |
| `mint.py` | A minted item and ability, saved and read back |
| `import_chr.py` | Aloth exported to a `.chr` and imported over himself in another save of the same playthrough — the editor asks first, and the 14 things he brings that the save keeps elsewhere get IDs of their own — then into a different playthrough as a newcomer; both saves validate clean and nothing reaches the saves folder |
| `layout_rules.py` | Measured layout rules (packs three to a row, controls beside what they act on…) |
| `look_variants.py` | The overflow checks at 1440px and in light mode |
| `catalog_offer.py` | The browsers offer only shipped content |
| `backups_ui.py` | A save deleted from the list is copied first; File → Backups restores it, refuses to restore over it, and a rename is copied too (works on a throwaway copy of one test save) |
| `stronghold_people.py` | Hirelings and prisoners by the game's own names; a staged dismissal projects the gauges, Keep and Revert undo it, Apply dismisses; a prisoner released and read back from the written file (borrows a save with a prisoner from the real saves folder's `2.0 Save Games Backup`, and deletes it afterwards) |
| `quick_and_weapons.py` | The game's weapon-set rules refused in the Inventory tab (two-handers, soulbound items, a swap that would hand one over) and by the server itself; a set's halves swapped, a stash stack split into a quick slot, a quick item traded for a pack item; saved and read back |
| `responsive.py` | The Abilities, Stronghold and Vendors tabs at six window sizes, from 1100x800 to 2560x1369, and the theme toggle clear of the navbar's menus at each: nothing off the right edge, the two ability lists one width and every panel on a line one height, the browser beside them or wrapped under them at full width, nothing under the floating Save button, the upgrades and the keep's rail spanning the tab or stacked, the vendor rail and stock side by side; screenshots in `target/ui-tests/shots` |
| `vendors.py` | The Vendors tab on a save with 68 stores: the list, one vendor's 255 items with icons, prices and original stock marked, picking and clearing, the category filter, "what you sold, everywhere", Revert; then five items taken out of two vendors, saved, and the written file checked area file by area file — exactly the two edited `.lvl` files differ from the save it came from |
| `party_care.py` | Heal, level up and resupply on two real saves: the heal marks the active party and nobody else, a level-up raises exactly those short of it and lowers nobody (Keira, level 1 with 86,485 experience, stays put at level 10), supplies refill to Normal's 4; the console's `HealParty` shares it; each save written and read back |
| `find.py` | Find anything, the box over the character list: a character, a worn item, a stash item behind a filter, an ability and a global each open their tab on the right thing; Ctrl+F, the arrow keys and Escape; accents ignored (“niezlomny” finds Niezłomny); an item moved but not applied found where it now is; the results audited in both themes; gone with the save |
| `compare.py` | Compare on the save list: the dialog pairs the save picked with one of its own playthrough, earlier first; two editor-made copies of one late-game save compared and every difference read back in words (money in copper with how far it moved, the difficulty by name, a stat under its character, an item by where it is and how many, Prestige, a global by name, nothing left for "Everything else"); Swap, one save against itself, two playthroughs with "Everything else" folded until asked; the dialog audited in both themes and at 1100x800; then a save edited, saved and compared with the save it came from, which must differ in the edit and the name typed and nothing else (deletes the save it writes) |
| `loadout.py` | Loadouts from the Inventory tab: both buttons wait while the tab has a change staged; the Watcher's gear saved to a `.loadout` through the real file dialog (and a cancelled dialog writes nothing); a text file and a `.chr` refused in words; the plan for Aloth item by item (the pet and the soulbound sword left out and why, what each copy replaces), audited in both themes; the dialog cannot be closed while it goes on; copies with IDs of their own, quick stacks on their own tiles, what was replaced in the stash as it was, the Watcher untouched; saved and read back. Then another playthrough, whose Watcher has the same ID and so takes the soulbound sword, while Calisca there is refused it in that save's name for its owner (deletes the save it writes) |
| `undo.py` | Undo and Redo beside Save: a typed Might is one step however many keys it took, Ctrl+Z in a text box is the box's own and elsewhere the save's, Ctrl+Y and Ctrl+Shift+Z redo; a console command and the sheet's Revert are steps too; nothing is undone while a tab holds unapplied changes (and the toast says which); an inventory Apply undone and redone with every item checked where it ends up; a staged sale is no step and no money yet, holds Undo back like any draft, and reverts to nothing; a stronghold dismissal undone brings back the Prestige the server had changed; the achievements toggle; the controls audited in both themes; after a Save nothing is left to undo on either side, and the written save holds what was on screen (deletes the save it writes) |
| `sale.py` | A sale's money and the items it sold reach the save together or not at all: staged, a sale has moved nothing (no money, no history step, Save not armed) and the status line keeps saying what Apply will do; Revert has nothing to refund; a Save in between goes ahead and the written file holds every item and no money for them; the tab takes no clicks while its Apply is on its way; applied, the sale is one step named as the sale it was, undone and redone whole, with money given afterwards stacked on top; saved and read back; a sale applied with a move is still one step; an item added from the catalog and sold again has no price and leaves nothing staged; a resurrection, a party change and an import wait for the draft and say why, and a draft rebuilt under the user all the same is said to be dropped (deletes the saves it writes) |
| `damaged.py` | The save list beside files that are not saves: noise, a save cut short, one whose summary names nothing and one renamed to a single word cost their own tiles and nothing else, and the list names each with the reason, in both themes; a real save beside them opens and saves, under a name no file in the folder already has (a file put there since the list was read keeps its name and its bytes); a folder with nothing readable leaves no tile of the last search behind |
| `checklist.py` | The manual test-run checklist, item by item, for what no other suite asserts in its words: each check is named after the item it answers (`sheet-skills: rank 4 reads 10 pts`). The save list's tile and actions, a rename, opening; the sheet's totals following a typed attribute and skill, Revert and Apply, a companion's locked attributes, subraces by race, the sidebar following the name box, the portrait search; console commands, the currency and difficulty dialogs, the achievements toggle; the unsaved-changes prompt and a discard that discards; moving, Escape, the stash's categories, search and count, the quantity panel, a ring refused on the feet and worn on the hand, Tidy; ability sort and description, removing one; a grimoire's order, a full level, remove and add; stronghold filters, the turns hint, a staged number; the party's limit of six; a resurrection; the sidebar handle |
| `language.py` | The game's own names in the language chosen in Settings: the list offers what the install has, items, upgrades, hirelings and abilities are named as the game's own table for that language names them (looked up here independently), the item browser and Find find them by that name, and a Save made in another language still differs from its original in the edit alone |
| `gamedata_ui.py` | The game-data banner and Settings row on a first launch; `full` also runs a real extraction (a few minutes) |

`gamedata_ui.py` starts its own editor with an empty data folder, so run it
directly rather than through `run_suites.py`.

`ingame_save.py` and `ingame_save_b.py` build two saves that exercise every
editor feature, for loading in the game itself; `ingame_portraits.py` builds
one whose load-list thumbnails have changed (the Watcher's face, a smaller
party) and checks them in the file; `ingame_keep.py` builds one with the
Crucible Knight dismissed and Kestorik released; `ingame_quick.py` one with
Aloth's weapon sets traded and lockpicks split into the Watcher's quick slot;
`ingame_vendors.py` one with everything the party sold to Caed Nua's General
Goods Merchant taken out of his stock; `ingame_import.py` one with a companion
exported and imported over himself in a later save of the same playthrough;
`ingame_swap.py` one with the prologue game's Watcher imported over the Caed
Nua game's, and checks in the file that it stays in the Caed Nua
playthrough; `ingame_iron.py` one with the Trial of Iron switch flipped (off by default,
on for the save `EK_IRON_SAVE` names); `ingame_capacity.py` one with a ring
added to a stash that had room written for exactly its sixteen items, so the
list's `Capacity` is raised to seventeen; `filedialog.py` answers the native
file dialogs the editor opens. They are not pass/fail suites: each leaves its
save in the test-env saves folder and writes what to look for in the game
beside the suites' other output. [docs/testing.md](../../docs/testing.md)
has how to load one in the game without touching your own saves, and why the
game's log has to be read afterwards.

`screenshots.py` draws the pictures the README shows into `docs/screenshots`
(it needs Pillow). Look at them before committing them.

A caution: many page reloads in one session can leave queries unanswered, which
shows up as a dialog stuck on its loading text. That is why each suite gets a
freshly started editor.
