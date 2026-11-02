# Eternity Keeper — state of the project and where it goes next

A working map of what exists today, what should be cleaned up, and what to build
next. Kept next to the code so it stays honest.

---

## 1. Where the project actually is

~30,000 lines of Java, 628 passing JUnit tests plus node-run suites for the
UI's save merge, search, comparison and undo history, a jQuery/Bootstrap UI running on an
embedded Chromium (JCEF),
and a save format that has been reverse-engineered far enough to mint objects
the game accepts and to rewrite the area files as well as the world state.
Twenty-five scripted UI suites drive the running editor against a real
13-character save; see §2 for what the last full pass found.

### Shipped and verified in-game

| Area | State |
|---|---|
| Character attributes, skills, raw variables | Works. Companion **base attributes** can't stick — the game re-copies them from the prefab on load, so the UI locks them |
| Character import/export (`.chr`) | Works, including overwrite-in-place |
| Party management | Move companions between active party and stronghold roster |
| Resurrect dead companions | Donor transplant, validated against a 12-save death matrix |
| Quest restore on resurrection | Patches the NRBF quest-tracker blob; byte-exact against 11 real pairs |
| Console tab | Achievements toggle, save-representable console commands, in-game command reference |
| Difficulty editor | Difficulty, Expert, Trial of Iron, Tactical mode |
| Currency editor | Works |
| **Inventory tab** | Per-character packs, equipment, quick items, weapon sets, stash, item browser |
| **Item catalog** | 2,156 items with real names, icons, stack sizes, slot rules, class restrictions, quality tiers |
| **Abilities tab** | Per-character abilities, spells and talents, with a class-aware browser |
| **Ability catalog** | 1,440 abilities, spells and talents plus all 26 progression tables |
| Polish/UTF-8 save names | Reads `sceneTitle` from `saveinfo.xml` |
| Light/dark themes | Both audited |
| Save format conversion | Rewritten as a byte pass and finally reachable from the UI (see §4.3) |
| **Vendors tab** | Every store's stock, read from the area files; take out what you pick (§2.1) |
| Loadouts (`.loadout`) | One character's gear to a file and onto anyone, as copies (§5) |
| Undo and Redo | Everything since the save was opened or last written, typed or applied (§3.4) |

### Known limitations, stated plainly

- **Game data is read from the player's install, not shipped.** The editor
  runs the reader itself (a few minutes, once) and degrades to prettified
  file names until it has.
- **Windows only for 1.0.** Linux still compiles but is untested; Mac has no
  JCEF build at all.
- **The game's own UI sprites are not extractable** — item icons are fine, but the
  inventory chrome lives in an `InventoryAtlas` whose sprite rects aren't readable.

---

## 2. Cleanup

### Done

- Removed `bin/` — 240 stale `.class` files committed to git; Maven builds to `target/`.
- Removed `EternityBootstrapper/.vs/` — committed IDE state, including a binary `.suo`.
- Added `bin/` and `.vs/` to `.gitignore`.
- Deleted the auto-updater (three handlers, `Updates.js`, its dialog, the
  HttpClient dependencies) and `EternityBootstrapper/`, which existed only for it.

### Whole-repository audit (2026-09-14)

Started from a crash report — choosing where to save froze the editor and it
shut down — and widened to every write path and every duplicated routine.
Each bug below was reproduced on the running editor or in a failing test
before it was fixed.

**Bugs**

| What the user saw (or would have) | Cause | Fix |
|---|---|---|
| Choosing a save destination froze the editor, then it closed | A modal Swing `JFileChooser` inside the CEF window disabled the frame and could open behind it | CEF's own native Save dialog; the folder is the parent of whatever is picked |
| An edit written to a locked or half-written file silently looked like nothing happened | `SharpSerializer` appends; nine in-place writers each had their own delete-and-recreate, or none | `DeserializedPackets.replace`: write a sibling temp file, then an atomic move |
| An Apply in one tab threw away unsaved edits in another (an attribute after a party change, a global after a grimoire Apply, a portrait after an inventory Apply) | Seven editors merged the server's reply seven ways, three of them wrong | `ui/js/SaveMerge.js`: one three-way merge against a snapshot of the file |
| An Apply the user discarded was back when the save was reopened from the list — a resurrected companion alive again — and a later Save would have written it | Every manager edited the list's unpacked copy in place | `environment/WorkingSave`: the first edit makes a private copy; opening a save from the list deletes it |
| Save sent megabytes it never reads, and turned every stat in the live copy into a string | `prepareData` deep-copied the whole save and mutated the characters it claimed to copy | `SaveMerge.writable`: only stats, portraits, globals and money |
| Temp folder grew 10–14 MB per resurrection | Every candidate donor save was unpacked and none was deleted | Rejected candidates and the donor are deleted |
| A catalog browser that hit an error spun forever | An exception after parsing escaped the worker; the callback never fired | `CatalogQuery` answers with the error |
| Two icon caches could corrupt under concurrent browsing | Unsynchronised `HashMap`s | `save/IconFolder`, one `ConcurrentHashMap` |
| `TypePair.equals` threw on a null member | Unguarded field compare | `Objects.equals` and a matching `hashCode` |
| Running the tests with the editor open broke the editor | Test cleanup deleted every `EK-` folder in temp | It deletes only what the test made |

**Consolidation** — the same routine written several times, now written once:
`handlers/SaveMutationHandler` (seven Apply handlers), `handlers/CatalogQuery`
(three browsers), `handlers/ChrDialog` (import and export), `save/PacketMint`
(item and ability minting), `EKUtils.findPacketById`/`copyComponent`,
`AbilityCatalog.prefabNameOf`, and `JSHandlers` as a table of names rather
than 25 hand-written registrations. The dead Windows Store converter in
`EKUtils` went with the updater.

**Left alone on purpose**: the one-line `saveData()`/`markDirty()` accessors
at the top of each UI component (local closures read better than a shared
global), `PartyManager`'s two AI component builders (they mirror two different
game components that happen to share three fields), and the two catalogs'
`search` loops (different filters over different entry types).

**Verified end to end**: one real edit through every write path — stats,
portrait, identity, difficulty, a global, console money, the achievements
toggle, an inventory sale, a talent, a stronghold demolition, a grimoire
change, a minted catalog item and a minted ability — saved, the written file
reopened from the list, every edit present and the validator clean.

### What the browsers offer, and where things sit (2026-09-14, second pass)

**The catalogs offer only what the game ships.** The debug items were the
visible part: 12 items and 9 abilities marked `DEBUG`, `Test`, `_Temp`,
`_UNUSED_` or `DO_NOT_USE`, none of them in any file of twelve real saves. The
larger part was invisible: **549 of the 2,156 item-catalog entries were not
items** — companions, NPCs, creatures, placed traps, crafting recipes and data
tables, each named after an item its bundle happened to carry
(`Companion_Aloth` was "Aloth's Grimoire"). Adding one would have minted a
character as an item. `save/ShippedContent` filters both browsers; lookups are
untouched, so a save that holds such an object still shows its name. The
`Prototype/` folder is *not* filtered: Currier's Key and Crypt's Master Key are
in every mid-game save.

**Layout, measured rather than eyeballed** (`layout_rules.py`, 15 checks):

| Reported or found | Fix |
|---|---|
| Party packs one to a row in a 560px column | A card per member, three to a row, filling the panel; wraps below the paper doll rather than dropping to one card |
| Grimoire sort buttons ~1,600px from the spell search | On the search row |
| Abilities sort buttons ~500px from the search toolbar | On the search row |
| An ability's + button ~700px from its name | Browser list capped at 560px |
| Raw and globals values 830–1,100px from their names | Tables sized to content, name column 360px |
| Attribute inputs 315px from their labels | 150px label column |
| Stronghold upgrade rows 1,930px wide | Upgrade list capped at 1,180px |
| Panel bar (title, Revert, Apply) in five places across eight tabs | One frame for every view, and a reserved scrollbar gutter so short tabs don't shift |

### Review of the whole repository (2026-10-04 and 05)

Measured first, then fixed; each has a test that failed before.

| Found | How | Fixed by |
|---|---|---|
| Every search unpacked every save whole: 5.84 s and 767 MB for twelve saves | Timing the list | `SaveGameExtractor` unpacks the eight files a tile is drawn from; `environment/UnpackedSaves.complete` fetches the rest on open and compare, and `WorkingSave` asks it before anything is read or copied. 0.3 s, and opening costs 0.7 s more for the one save opened |
| 49 folders, 2.5 GB, left in `%TEMP%` by editors that were killed | Looking | `environment/TempSweep` at start, by the names the editor makes and a day old |
| One save with a field missing from `saveinfo.xml` ended the whole search with a NullPointerException | `DamagedSavesTest` | Each save's tile is made on its own; what is left out is named with the reason, in the reply (`unreadable`) and on the page |
| A search that failed left the page on "Searching..." for ever | Reading the handler | `SaveInfoLister.run` answers whatever happens |
| A search that found nothing left the last search's tiles on screen, opening nothing | Writing the suite | `saves: []` on `NO_RESULTS` |
| A length read out of damaged bytes asked for 2 GB: an `OutOfMemoryError`, the thread gone, the page waiting | `DamagedPacketFileTest`, a thousand damaged files from a fixed seed | Lengths are bounded by what the file has left; a stack that runs out counts as a failed read |
| One changed byte left a tree the write check tripped over, uncaught | The same test | `PacketDeserializer` treats a tree that cannot be made sense of as unreadable; `DictionaryProperty.findEntry` no longer assumes a key |
| The opener and every Apply had no catch-all: any unexpected exception left the spinner turning | Following the two above upwards | `handlers/Answered`: every worker hop goes through `Answered.to`, every handler is registered through `Answered.guarded` (one answer per query), and `AnsweredTest` reads the handlers' sources to keep it so. An edit that fails that way is aborted and put back; one written and then unreadable is undone |
| Save could take the name of a file already in the saves folder | Reading `getAvailableGameID` while checking what lazy unpacking changed | The number is looked for in the saves folder as well as in the list |
| `updateSaveInfo` failed on an empty array when the summary could not be parsed | Reading | It throws an `IOException` that says so |
| `ChrDialogTest` raced the pool it waited on | It began failing | It waits for the choice itself |
| A game-data read that was stopped left its half-built `gamedata.new` folder behind | Stopping one in the release and looking | `GameDataExtraction` removes it after a run that did not succeed |
| A resurrected companion's quick-slot items existed twice under one ID: the game leaves them in the area the companion died in, and the donor brings the same objects. The game logged three exceptions on loading that area | Reading Player.log after loading an edited save in the game | `GuidRemap.heldByAreas`: resurrection and import also give fresh IDs to what an area file of the save still holds (35 objects on the test save). Loaded again in the game: no exception |
| The sheet's totals did not follow a typed attribute or skill; the sidebar did not follow the name box; Tidy merged nothing in the stash when a stack was over the item's cap (it divided by the cap; the stash has none) and the stash count ignored the filter | `checklist.py`, written to walk the manual checklist | `SavedGame.refreshSheet`/`refreshName`; Tidy treats the stash as unlimited; "12 of 226 items" |
| A scripted install stopped at a message box: the installer said "cannot start from this folder" with `MsgBox`, which `/SUPPRESSMSGBOXES` does not suppress. On a build machine nobody is there to close it | Running the installer's test for the first time, where the box came up on the screen of the person at the machine | `SuppressibleMsgBox`; the test gives every run of setup a time limit and says what a setup that does not finish is probably doing; the workflow's job has a limit of its own |
| An upgrade given its folder on the command line was not checked at all: the page the check sat on is not shown when an earlier install is found | Adding an upgrade to the installer's test | The folder is checked again in `PrepareToInstall`, which every install passes |
| The uninstaller left the launcher's options file behind, and the folder with it | The same test | `[UninstallDelete]` |

Checked and found sound: no HTML sink in the page takes text from a save (the
three string-built fragments hold a class name, a boolean and the server's own
base64); the page loads nothing from the network; `saveinfo.xml` cannot make
the parser read a file or call an address (jOOX parses a string inside a
wrapper element, where a document type is not allowed — pinned by test, since
it is an accident of how it is called); zip4j refuses an entry that climbs out
of its folder.

### Review of the fork (2026-10-27)

A `/thermos` review of everything the fork adds to upstream
(`upstream/main...HEAD`, 103 commits): a bug pass and a code-quality pass,
each finding checked against the code before it was kept. The bugs were
fixed test-first on `fix/import-keeps-partys-belongings`; each test failed
before its fix.

| Found | Severity | Fixed by |
|---|---|---|
| Importing a character over the Watcher replaced the save's stash, quest items, crafting materials, money and camping supplies with the imported character's own game's: they hang off the main character | High | `CharacterImporter.keepPartysBelongings` moves the save's three containers to the newcomer whole and keeps the money and supplies (`1a221e6`). The in-game check found the camping supplies, which the first fix had missed |
| An import written and then not handed back stayed in the working save | Medium | `ImportCharacter` undoes the step, as `SaveMutationHandler` does (`1a221e6`) |
| Every Save after the first backs up the file the one before wrote; with one limit of ten for every reason, ten Saves pushed out the only copy of a deleted save | Medium | `SaveBackups.prune` keeps ten of each reason (`338195c`) |
| An edit whose reopen could not read the save was answered as a success carrying `{"error": ...}`: the edit stayed, and most tabs adopted the error as the save. Open sent an empty save after the error | Medium | `SavedGameOpener.reopen()` throws `Unreadable`; the writers take the step back and answer a failure (`4f238ea`) |
| A Stronghold Apply skipped what the save could not take, wrote the rest and reported success | Low | Refusals are `WriteRefusedException`s naming the change; the batch goes in whole or not at all (`5166ad0`) |
| Rename took `saveinfo.xml` out of the player's file and then added the new one, and named the working copy first | Low | A `.renaming` copy is built beside the save and moved over it whole; the working copy follows (`eded601`). Checked in the game's load list |

**Structural findings, not done (need a decision).** None is a bug today;
each is where the next one is likeliest. By leverage:

| Item | Size | Recommendation |
|---|---|---|
| ~~`ImportCharacter` repeats `SaveMutationHandler`'s begin, commit, reopen and undo by hand~~ | Small | **Done 2026-11-01**: the steps are `handlers/SaveMutation`, which `SaveMutationHandler` and `ImportCharacter` both run their edit through |
| About fifteen hand-written walks of the packet tree (follow references, find a component, iterate a collection) | Medium | **Slice 1 done 2026-11-02**: the save model (`save/model`, invariant 26, [docs/glossary.md](docs/glossary.md)). The import, the stronghold, the quest restore, the resurrection, the party and Save find objects, components and variables through it and write typed values that refuse what they cannot be, with byte-identical output (`UnchangedWritesTest`). Slice 2, to be designed in its own round: GUID lists kept in step with their objects, minting, the leading count, and moving `InventoryManager`, `Loadout`, the vendors and `EKUtils.findSubComponent`/`findPacketById` (58 uses) onto it |
| Eight places update `DeserializedPackets`' object count by hand | Small | Let `DeserializedPackets` set it from what it holds (invariant 5) |
| Managers answer a refusal with `return false` and a log line, so the page is told "failed, see the log" | Medium | Throw a refusal that says why, as `StrongholdManager` now does; one manager at a time |
| Five staged tabs each send their Apply and adopt the reply their own way | Small | One `Eternity.Apply.send` (invariants 12, 20, 21 in one place) |
| `SaveComparison` (2,453 lines), `SavedGameOpener` (1,588), `InventoryManager` (1,453), `InventoryEditor.js` (2,411), `SavedGame.js` (1,290) | Large | Split along the section seams they already have, one file per change |
| `modern.css` redefines 257 of `ui.css`'s selectors; its `backdrop-filter` and `prefers-reduced-motion` do nothing in Chrome 45 | Medium | Fold it into `ui.css`, with a screenshot sweep in both themes before and after |

### Proposed, not done (needs a decision)

| Item | Size | Recommendation |
|---|---|---|
| The item extractor's naming | `tools/gamedata/items.py` | Skip non-item bundles at extraction too, so a regenerated catalog is clean at the source (the editor already filters them) |

### Verified in the game (2026-09-14)

Two saves built through the editor's own controls and loaded in Pillars of
Eternity v3.9.5; every edit read off the game's own screens:

| Feature | Seen in the game |
|---|---|
| Portrait | New face on the party bar and the character sheet |
| Identity | Background "Ixamitl Plains - Aristocrat" on the sheet |
| Attributes, skills | Might 23 (base 20), Athletics 14 (rank 12) |
| Abilities | "Weapon Focus: Adventurer" in the sheet's Talents |
| Inventory (minting) | Minor Ring of Deflection in the pack, tooltip "Of Deflection +5" |
| Money, junk sale, console | 224,845 cp = editor's 223,108 + 1,737 turn income shown in the stronghold log |
| Stronghold | Prestige 47, Curio Shop back to "Purchase", three turns played |
| Party, resurrection | Sagani in the roster, Edér alive in the party |
| Grimoire | Eldritch Aim in Aloth's book, Chill Fog out |
| Difficulty | Options → Difficulty shows Easy |
| Export / import `.chr` | Level-15 Aloth, with his records, in the prologue party |
| Rename | "EK import test, renamed" in the load list |

Not observable in the game UI: the achievements flag. Found by it and fixed:
the stronghold's "Turns available" hint (turns are played on load), and
`TrialOfIron` written as `true` rather than `True` in `saveinfo.xml`.


---

## 3. Feature roadmap

Features come first; store/platform compatibility follows in §4.

### Phase 1 — character progression

**1.1 Skills editor** — ✅ **done**
Athletics, Stealth, Lore, Mechanics, Survival and Crafting are already on the
proven scalar write path — they just have no friendly UI. Worth knowing: the
save stores cumulative **points**, while the character sheet shows the derived
**rank** (rank N costs N(N+1)/2 points), so the editor must show both or edits
will look like they did nothing.
Shipped: rank-based editing with the point total shown alongside, unspent points,
and confirmation from the decompiled source that skills are *not* prefab-reset for
companions (only the six base attributes are). Verified against a real save —
Mechanics 0 → rank 5 wrote 15 points.

**1.2 Talents and abilities** — ✅ **done**
Add and remove abilities, spells and talents, with the browser restricted to
what each character could actually take (straight from the game's own
`AbilityProgressionTable`, including per-companion tables). The extractor did
generalise: the same script now emits `abilities.json` (1,440 entries) and
`progression.json` alongside the item catalog. Verified end-to-end in the
running editor against a level-16 party — 620 abilities and talents read back
with full catalog coverage, and a staged talent applied and reloaded correctly.

**1.3 Culture, race and class** — *done*

An Identity panel under the attributes and skills: race, subrace, class,
culture, background and gender, plus a deity for priests and an order for
paladins. All six are `[Persistent]` enums on `CharacterStats`, so they ride
the ordinary scalar path and are written on Save with everything else — no new
handler.

Three things the decompile settled:

- **`RacialBodyType` is not editable and must not be written.** `Awake()` sets
  it to `CharacterRace` for anyone who is not godlike, and for a godlike it
  holds the body underneath — the test save's Moon Godlike player really does
  carry an Aumaua body.
- **Only base attributes are re-copied from a companion's prefab** by
  `Restored()`, so identity sticks for companions exactly as skills do.
- The equipment caveat is real and the game does not clean up after it.
  `RepairSaveLoadEquipmentErrors()` handles the deprecated Cape and locked
  slots only, so an item left in a slot that stops existing is simply
  unreachable. The panel names the item and says to unequip it in the
  Inventory tab rather than moving it silently.

Subrace is scoped to race (the godlike set is exactly what
`SubraceIsGodlike()` tests), and a race change carries the subrace with it.
Verified in the game: a companion changed to a Mountain Dwarf Cipher of Rauatai
reads back exactly that on the character sheet.

**1.3b What each choice does** — *done*

Every dropdown now carries a line saying what it is worth, and the panel ends
with the totals the game's own sheet will show.

The reason this works at all: none of it is baked in at character creation.
`GetAttributeScore()` adds `RaceAbilityAdjustment` and
`CultureAbilityAdjustment` to the stored `BaseX` on every read, and
`CalculateSkillInternal()` adds `ClassSkillAdjustment` (behind an
`IsPlayableClass()` guard) and `BackgroundSkillAdjustment` to the rank. All
four are `static int[,]` fields on `CharacterStats`, copied verbatim into
`save/IdentityCatalog` — the two column orders differ and neither is the order
the sheet shows, so reading a row under the wrong one relabels every bonus
without ever looking wrong.

Two halves are not in the assembly. A subrace's racial ability is one row of
the game's own `racial` progression table, which `AbilityCatalog` already
carries, so the Moon Godlike line reads *Silver Tide* with the game's own
description. Deity and paladin-order dispositions are inspector arrays on the
`Religion` behaviour attached to InGameGlobal, so `tools/gamedata/identity.py`
pulls them into `itemdata/identity.json` alongside the ±20/40/60% ladder
`GetCurrentBonusMultiplier()` applies — for the player character alone.

Verified against the game: a 15th-level Moon Godlike Paladin of the Goldpact
Knights reads 21/10/13/14/12/19 and Stealth 2, Athletics 13, Lore 2,
Mechanics 0, Survival 7 in both the editor's panel and the character sheet,
and the sheet's own "Favored Dispositions: Stoic, Rational" matches what the
Order line predicts.

Turned up on the way: `Restored()` re-derives every `XBonus` and
`<Skill>Bonus` from the applied status effects on load, so a value written
straight into one of those fields does not survive a reload. Nothing in the
panel depends on it, but the Console tab's `Skill` command did — it wrote
`<Skill>Bonus` exactly as the in-game command does, which is right for a live
console and useless in a save. It now sets the stored points for a rank.

### Phase 2 — world and inventory

**2.1 Vendor cleanup** — *done: a Vendors tab lists every store's stock from
the area files and takes out what the player picks, nothing more*

Built in the shape the measurements called for: a reviewable list per store,
defaulting to nothing. What it took, and what it found on the way:

*Area files are safe to rewrite.* Every one of the 190 packet files of a real
mid-game save (111 MB) comes back **byte for byte** through the editor's
reader and writer, so an area file is written exactly as the world state
always has been: a sibling file, then an atomic move, and only the files that
changed. A Save afterwards differs from the save it came from in exactly the
area files that were edited — the UI suite unzips both and checks.

*Only 40 of those 190 files hold a store*, and they can be told apart without
reading them: a Store component's type string is written as a length byte and
the bytes of "Store", and that sequence picks out exactly those 40. Read side
by side, a mid-game save's 74 stores and 2,917 items take 0.8 s the first time
the tab opens and 0.35 s after that, where reading every area file took 9.9 s.

*"Original" is the line the game draws.* `InventoryItem.Original` is set only
for a store's initial stock. Anything the player sold there is not original,
and neither is restock — which `Store.RegenerateItems` destroys again, every
copy of each prefab in its table, every twelve game hours. So "select what you
sold" picks everything not original; original stock, uniques included, is
marked on its tile and never picked for you, because nothing puts it back.

*A damaged file reads short without an error* — or did. `PacketDeserializer`
returned whatever it managed to read — one packet of six from a truncated area
file in the tests, 6,043 of 6,953 from a real world state cut at 90% — and
writing that back would silently drop the rest. The check began here, in this
tab's own reader; since 2026-09-25 it is the reader's for the whole editor.
`deserialize()` refuses a short read with a `ShortReadException` whose message
is the user's ("Only 4893 of the 4894 objects in MobileObjects.save could be
read, so nothing was written: the rest would have been lost."), and
`DeserializedPackets` will not write one whatever a caller has done with it.
Every writer refuses in those words — each Apply, Save, import and export,
resurrection and conversion — and a damaged save still opens, under a strip
that leads with "Part of this save could not be read". Verified on the running
editor with a real save cut half-way through its last object: the strip, an
Apply and a Save all said so, and nothing reached the saves folder.

*A store's list can point at nothing.* 280 entries in a real save — 242 of the
stronghold merchant's, 38 of Crucible Keep's — name an item whose packet does
not exist anywhere; the game keeps such an entry as a bare copy of the prefab.
Removing one is just its two list entries. An item's packet only goes when no
list in the file still names it.

*And a GUID does not name an entry.* Nine of those packetless GUIDs are shared
by eighteen of the merchant's entries, each a different item — a Dyrwoodan
outfit and a monk's outfit under one. So an entry is addressed by its place in
the list, with the GUID it should hold there as the check that nothing moved.
Addressing by GUID picked namesakes and could have removed the wrong entry; it
surfaced only when the in-game test save said 675 picked for 693 tiles.

The rest follows the game: the remaining entries are numbered 0..n-1 again
(every store in the real saves reads that way; `Sort` and `CompressSlots`
leave it so), prices are what the store asks (`ceil(value × sellMultiplier)`),
and a request built from a list that has since changed is refused whole with
no file touched. Store names are prettified from the object and scene names —
the game's own (`Vendor.StoreName`) lives in the level's scene data, which is
not in the save.

*Verified in the game* (v3.9.5): all 693 entries the party had sold to Caed
Nua's General Goods Merchant taken out in the editor, the written save loaded,
the party walked into the Great Hall — whose area file the editor had rewritten
— and the merchant's own store screen read "No items." in every category. The
game's log shows the hall loading with no error from its stores.

*What it buys is order more than space.* Taking every sold item out of every
store the party has traded with — 2,325 items at 39 stores in a 9.9 MB
mid-game save — rewrites 39 area files in 2.8 s and leaves the save **3.4%
smaller** (80.4 MB of area files unpacked become 77.2). The old premise, that
vendor stock is what makes saves big, does not survive the measurement; what
the tab does give is a stronghold merchant whose list is not 693 items of the
party's cast-offs.

**2.1b Bulk tidy-up and sell — requested on Reddit** — *done*

Shipped in the Inventory tab: a sell mode that turns every pack and the stash
into a selection and credits the party's money with what a store would pay, and
a "Tidy stacks" button that repacks each container up to the game's own cap.
Prices come from the catalog, which the extractor now fills in properly (1,658
of 2,156 items priced, 177 of them `FullValueSell`).

"Select junk" ended up much narrower than first planned. Price alone sweeps up
the party's whole stock of potions, and MISC is worse — the game files
grimoires, pets, hides and lockpicks there next to the lore books. Junk is
therefore unenchanted worn gear only: WEAPONS, ARMOR or CLOTHING, under the
1000cp an enchantment costs, not unique or soulbound, and never something that
goes in a grimoire or pet slot.

Verified end to end: 13 items sold for 270cp produced a file with exactly 13
fewer packets and 13 fewer inventory entries, the purse up by exactly 270, and
the game itself then loaded it showing 1,000,313cp and the two unsold items
still in the stash.

The original note, kept because the reasoning still applies:

A natural companion to vendor cleanup, and every piece is already here:
`InventoryManager` removes an item in the three places it lives, and currency is
a scalar the currency editor already writes.

What the game says, from the decompiled `Item`:

    GetDefaultSellValue() = floor(GetValue() * 0.2), or floor(GetValue())
                            when the item's FullValueSell flag is set

so a dump-everything button should pay a fifth of value, the way a store does.
Two things need doing first:

- The catalog carries no prices. `Value` is a `CurrencyValue` struct
  (`{"v": 50.0}`) and the extractor's number check silently dropped every one —
  fixed in the script, but the catalogs need regenerating to pick it up. It now
  records `fullValueSell` too.
- `Equippable.GetValue()` adds each item mod's `Cost * ItemModCostMultiplier`
  (doubled for two-handers), so an enchanted weapon is worth well above its base
  value. Pricing from the base alone would quietly short-change exactly the
  items a player most wants to sell. Extracting mod costs is the honest version.

Worth deciding before building: "stack identical items" only means something up
to `MaxStackSize` — 501 of 2,156 items stack at all, and weapons and armour
never do. The sensible reading of the request is *select a set, delete it,
credit the purse*, with stacking as tidy-up where the cap allows.
*Effort: medium, most of it in the catalog. Risk: low — deletion and a scalar.*

**2.2 Stronghold editor** — *done: upgrades, scalars, dismissing hirelings
and releasing prisoners; taking people on is deliberately not offered*

Shipped as a Stronghold tab laid out like the game's own screen: the upgrade
list down the middle, prestige and security gauges in a rail on the right. It
refuses to touch a save where the player has not taken Caed Nua yet
(`SerializedIsActivated`), and deliberately does not offer to grant it — the
quest that hands over the keep also moves the party, advances its own state and
spawns the steward, none of which a flag flip would do.

The catch that shaped the whole thing: **Prestige and Security are plain
persisted scalars.** `CompleteBuildingUpgrade()` adds an upgrade's own
adjustments once, when it is built, and `DestroyUpgrade()` takes them back off;
neither is ever recomputed from `m_upgradesBuilt`. Appending to that list alone
would produce a stronghold with upgrades it gets no credit for. So the editor
does the game's arithmetic itself, which means it needs the game's numbers — 25
upgrades' worth of cost, build time, prestige, security, prerequisite, icon and
`UpgradeCompletedGlobalVariableName`, none of which are in the save. They live
on the `Stronghold` behaviour attached to the `InGameGlobal` prefab, and
`tools/gamedata/stronghold.py` pulls them out. Demolishing also cascades: taking
down an upgrade takes everything built on top of it, the way the game's own
tree requires.

Verified in the game: demolishing the Curio Shop and banking 7 turns produced a
save the game loads with Prestige 47 (down the +1 it was worth), Security 44,
every other upgrade still Completed, and the Curio Shop offered for purchase
again at 1,800cp / 2 days — exactly the price the catalog carries.

**Hirelings and prisoners (2026-09-21).** Each is listed by the game's own
name, with Dismiss and Release doing exactly what the game's buttons do.
`Stronghold.DismissHireling` takes the entry out of `m_hirelingsHired`, sets
its `HiredGlobalVariableName` to 0 and, only if the hireling is `Paid`, takes
their Prestige and Security back off — an unpaid one's share already came off
when the pay cycle could not pay them. `RemovePrisoner`, which is what the
game's own Release button calls, clears the prisoner's global and drops the
entry; a visitor asking after them is left alone, as the game leaves it. The
names are not in the save: a hireling carries `SerializedNameId`, a string id
into the characters table, and a prisoner a pair of `DatabaseString`s, so
`save/GameText` reads the install's `data/localized/en/text/game/*.stringtable`
(names fall back to the global, "Crucible", without an install).

Verified in the game on a real Act III save: the Crucible Knight dismissed and
Kestorik released gave Prestige 35 and Security 33 (the knight's +4/+2 gone),
the hirelings badge 3/8 with the knight offered for hire again, and nobody in
the Actions page, which is where the game lists prisoners.

**Not offered: taking someone on.** Hirelings and prisoners arrive through
conversations and visitors that also change the world (a prisoner's own object
is destroyed as they are locked up), so minting the list entry alone would be a
keep the game could not have produced.

**2.3 Grimoire editor** — *done*

A Grimoire tab: eight chapters of four slots, laid out like the game's own
grimoire, with the 114 wizard spells underneath to add from.

The shape turned out to be the easiest structural list in the save.
`SerializedSpellNames` is a flat `List<string>` of spell prefab names with no
cross-references, no UUIDs and no parallel structure — the same shape as
`m_upgradesBuilt`. The class's other `[Persistent]` member, `SerializedSpells`,
is a `SpellChapter[8]` of object references and comes back all-null in every
real save, so it is dead weight.

What needed care was the arithmetic the game does silently: the names setter
resolves each prefab, files it under its own `SpellLevel`, and drops anything
past the fourth at that level without a word. `GrimoireManager.plan()` mirrors
that exactly, so the editor cannot show a spell the next load would discard.

Two things measuring changed about the design. A grimoire is an **item**, so
the view lists books rather than characters — and a real mid-game save holds
**32** of them, almost all looted enemy books in the stash, which is why the
rail sorts equipped first and scrolls. And the inventory payload's containers
are `{component, maxItems, items}` rather than bare arrays, including the
stash; treating one as an array left the save view half-drawn.

Verified in the game: a level-8 spell removed from Aloth's grimoire in the
editor shows as chapters I-VII full and VIII holding one on the game's own
grimoire screen.

**2.4 Companion portrait picker** — *done*

A modal off the character view's own portrait: the portraits on the player's
own install, in a grid of the party-bar thumbnails, filtered by the folders
the game keeps them in and searchable.

It really is a small win, because a portrait is two plain strings and nothing
else. `Portrait` has exactly two `[Persistent]` fields — `m_textureLargePath`
and `m_textureSmallPath`, relative to `PillarsOfEternity_Data` — and
`Portrait.Start()` loads whatever they name, only deriving a path from
`CompanionInstanceID` when one is empty. `LoadTexture2DFromPathCallback` reads
the file straight off disk, which is why custom portraits have always worked
in this game and why the editor lists the directory rather than a fixed table.

Being scalars, they need no manager and no Apply step: generalising
`ChangesSaver.updateCharacter` over the component name was enough, and the
picker's edit is written by Save like any attribute change.

Two things the design turns on. Both halves are always written together — the
large is the character sheet, the small is the party bar — and a portrait
whose `_sm` counterpart is missing is not offered at all, because the game
would load the large one happily and hand the bar a blank white texture. And
the grid is paged at 40: the shipped game has 118 pairs and the small images
alone are about 1.9 MB, well past what one JCEF reply carries.

Verified in the game: giving the Watcher the Grieving Mother's portrait shows
her face in both the party bar and the character sheet, sheet otherwise
untouched.

Turned up on the way: Bootstrap's own `button.close { padding: 0 }` left an
11×21 hit target in **every** dialog, not just the party one that had already
been patched. `.modal button.close` now fixes them all.

**2.5 Confirming changes, and keeping the panels honest** — *done*

Every view now carries **Revert** and **Apply changes** in its top right, and
the four that already had the pair had them in four different places at three
different sizes. The character sheet, the raw and globals tables and the
console had neither: they write into `saveData` as you type, so the only way
back from a number you had changed was closing the save and losing everything
else with it. `ui/js/PanelChanges.js` keeps a confirmed baseline of what those
panels edit and offers the same two words over it.

Apply there is a commit point rather than a write — nothing reaches the disk
until Save — and the bar says so. What is deliberately *not* done is disabling
the Save button when a panel is reverted: inventory, party and item edits live
outside those scopes, so "clean" would not mean "nothing to save", and guessing
wrong in that direction hides a real pending change.

The other half was making the editor follow a character change instead of the
snapshot the opener sent. Slot rules and the ability browser's class now come
off the live `CharacterStats`, so turning someone into a wizard opens their
grimoire slot and stops offering them the paladin's talents straight away. The
hazard that created is stated rather than hidden: inventory changes are written
by Apply and identity changes by Save, so a slot that exists only because of an
unsaved class edit now says so in the Inventory tab.

Also from the same pass, and each measured rather than guessed:

- **The portrait picker could only reach 80 of the game's 118 portraits.** Both
  browsers that append a page were adding the request offset to the number of
  rows already on screen, which double-counts everything past the first page;
  the button hid itself halfway and quoted "-2 left".
- **Paged lists are sorted on the server.** `BrowseAbilities` takes a `sort`,
  because reordering the window the client happens to hold would put a level 8
  spell above a level 1 one the moment the next page arrived. A spell's level
  is its chapter, an ability's is the character level its progression row
  names, and the two live in different places.
- The grimoire's chapters are two columns of four full-width rows instead of
  eight rows of four tiles, which is what put long spell names behind an
  `overflow: hidden`; its rail runs the full height of the panel; the equipment
  slots are three to a row; and the ability browser groups by level and
  explains the highlighted row on the right, the way the game does at level-up.

### Phase 3 — deeper save surgery

**3.1 Quest editing beyond restore**
`QuestTrackerBlob` already reads and patches tracker state. Full editing needs
an NRBF *writer* for `SerializedActiveQuests`.
*Effort: high. Risk: high.*

**3.2 Save validation pass** — *done*

A strip above the views, invisible until something is actually wrong, saying
what the save contradicts about itself.

The checks are the invariants this project learned the hard way: two objects
sharing an ObjectID, an object whose `InstanceID.Guid` is not its own
ObjectID, `ItemList` and `SerializedItemList` of different lengths, a carried
or worn item GUID with no packet of its own, and a leading object count that
disagrees with the contents.

**Where it runs is the whole point.** Every manager re-opens the save through
`SavedGameOpener` after applying, so the opener is where a structural mistake
the editor made surfaces at once. The item-minting aliasing bug showed up only
in-game, as both items silently missing; it is now three separate checks
firing together.

**Every check was measured against four real saves before being written** and
reports nothing on any of them. A sixth candidate did not survive that:
"Parent names an object that exists" fires 29 times on a perfectly healthy
save, because a dead companion's belongings outlive the companion the game
deleted. A check that cries wolf on an untouched save is worse than no check.

It reports rather than refuses: by the time the opener sees a problem the
damage is already in the working copy, and blocking the write would not undo
it. The strip says what is wrong, what the game will do about it, and that
reopening the save is the way back. Since 2026-09-27 the same invariants are
also checked before anything is written (§5), and there a breach the edit
made is refused before it reaches the working copy.

Verified end to end against a deliberately broken save — two item packets
given the same ObjectID, the aliasing bug's exact signature.

Related, and already fixed: `Restored()` re-derives every `XBonus` and
`<Skill>Bonus` from the applied status effects, so the Console tab's `Skill`
command — which wrote `<Skill>Bonus`, faithfully copying the in-game console
command — produced a change no load would keep. It now sets the skill's stored
points for a rank, the way the Skills panel does. Worth a systematic look for
other fields with the same shape.

**3.3 Save comparison** — *done* (2026-10-01)

Compare on the save list puts two saves side by side in the editor's own
words. `save/SaveDiff` compares the two world states object by object, by
ObjectID, as the property trees the files hold (dictionaries by key, binary
blobs by content); `save/SaveComparison` puts that into sections — the save,
each character, items by where they went, abilities, grimoires, the keep,
global variables, quests by their journal titles, areas and their stores —
each of which *claims* the differences it explains. What none claims is
listed under "Everything else", and what changes between any two saves of a
playthrough is counted rather than listed: where objects stand and the timers
and counters the game keeps updating.

*Measuring first set the shape.* Two real saves fifty minutes of play apart
differ in 3,789 values, and 3,468 of them are the party's belongings riding
along into another area and clocks ticking. What the player did is a few
hundred: experience, kills, health, money, items, globals, a quest's
objectives. Two copies the editor wrote differ in exactly the edits. The game
also makes things afresh that a byte comparison would report: it renumbers its
clones on every load ("Companion_Sagani(Clone)_5" is "_4" next time, and
everything she owns names her by it), re-sorts an item's enchantments, grows
.NET lists' buffers, and gives stash stacks new IDs. Each is recognised as no
change.

*It found a bug on its first end-to-end run.* Saving a save wrote the English
name of every companion the game had not named into the save, where the game
shows a name in the player's own language (Edér came back "Eder"): the opener
filled in the name it lists her by, and Save writes every stat back. Fixed;
the UI suite now checks that a saved save differs from its original in the
edit and nothing else.
**3.4 Undo within a session** — *done* (2026-10-04)

Undo and Redo sit beside Save (and on Ctrl+Z, Ctrl+Y), over one history of
everything changed since the save was opened or last written. The two halves
of the editor needed different machinery. A typed change — a stat, a
portrait, a global, money — is the page's to undo: the history compares the
values Save writes against the last ones it saw whenever anything arms the
Save button, which every editor already had to do, so nothing new has to
report its edits; keystrokes into one field are one step. An Apply wrote files,
so its undo is the server's: every writer goes through
`DeserializedPackets.replace`, which now keeps the file it replaces (a hard
link, so it costs nothing) while the Apply's step is open, and undoing the
step swaps the files back — byte for byte, which the tests check. A refused
or failed Apply puts back whatever it had already written.

*Drafts are not steps.* A tab's staged changes stay its own, with its own
Revert; while one has any, nothing is undone, because undoing an Apply
reopens the save and the draft would be built on one no longer there. The
toast says which tab. *Save is a checkpoint*: past it, what Save wrote is in
the same files an undo would put back, and the save that was opened is still
in the list. Building it found three older faults: the inventory's Revert left
a staged sale's money in the purse (see below), the sheet's Revert put a portrait's paths
back but left the new picture on screen, and the achievements toggle bypassed
the merge every other Apply goes through.

*A sale is paid when it is applied.* Its items leave the save on the
Inventory tab's Apply, and its money used to go into the purse at Confirm —
and the purse is what Save writes, so a Save in between wrote the money beside
every item it was paid for. Making Save refuse and every draft-dropping path
refund was tried first, and review found six paths it missed. The money now
goes in when the Apply succeeds, as part of that one step: undoing it brings
the items back and takes exactly that much out again. A resurrection, an
import and a party change, which reopen the save under a tab's draft, wait
for the draft as Undo does; and a tab takes no clicks while its Apply is on
its way, since the reply replaces the draft.

---

## 4. Compatibility (after the features)

### 4.1 Detect the game across every store — *done*

`environment/GameLocator` replaces the old search, which checked a hardcoded
list **only on the system drive**, so a Steam library on `D:` — the most common
setup — was never found.

Order, first accepted answer wins: explicit setting, then **Steam**, **GOG**
(registry), **Epic** (JSON manifests in ProgramData), **known layouts on every
drive**, then **Microsoft Store** as an explanation rather than an answer.

Steam is resolved rather than guessed: a Steam root → `libraryfolders.vdf` for
every library on every drive → `appmanifest_291650.acf`, which names
`installdir` outright, so even a renamed folder is found.

Two things the measurements changed:

- **The registry is silent more often than you would think.** On this
  development machine Steam is installed and running from `D:\Steam` and there
  is no `HKCU\Software\Valve\Steam` key at all. The drive scan is not a
  last-resort nicety, it is the path that actually fires — and it finds the
  install in about 110ms.
- **A candidate is only accepted if `PillarsOfEternity_Data` is in it.** That is
  what the editor reads, so a stale registry entry or a library the game was
  moved out of is rejected instead of leaving the editor pointed at a folder
  with no portraits and no explanation.

Verified end to end: with `gameLocation` blanked out of settings, a cold boot
finds `D:\Steam\steamapps\common\Pillars of Eternity` on its own and never
opens the settings dialog.

Save paths picked up the macOS layout at the same time (`~/Library/Application
Support/Pillars of Eternity`), which this section owed 4.2. Written from the
note below rather than from a Mac — there is none here and the app cannot start
there yet — so both spellings are tried.

### 4.2 Mac support — asked for on Reddit
Build and bundle JCEF for macOS. Concretely, what is missing today:

- `pom.xml` has profiles for `win64`, `win32` and `linux64` only, and
  `lib/native/` holds the matching three. There is no macOS JCEF/JOGL native
  bundle, so the app cannot start there at all.
- Everything above the browser layer is plain Java 8 and portable. Save paths
  are the other platform-specific piece: macOS keeps saves under
  `~/Library/Application Support/Pillars of Eternity/`, which the detection work
  in 4.1 has to cover.
- The item and ability catalogs come from the game's own asset bundles, so they
  work anywhere the game is installed.

This is the single most requested thing from outside, and it is packaging work
rather than save-format work — worth pulling forward if the goal is other people
using the editor rather than just this fork.

### 4.3 Save format conversion — *done, and the premise had to be corrected*

Two things were true of this item when it was written and are not true now.

**It was not reachable.** `EKUtils.convertWindowsStoreToSteamSaveFiles` existed
and was exercised by a test, but nothing in the running editor called it:
`SavedGameOpener` hardcoded `isWindowStoreSave` to `false`, and the two
branches in `SaveSearch.js` that read it could never fire. "Works, slow" was
true of a unit test, not of the editor.

**The formats are no longer different.** The premise was that the Windows Store
and Xbox builds ship a newer Unity whose saves Steam and GOG cannot read. That
was true in 2020. Today **every save this machine's Steam copy writes contains
`UnityEngine.CoreModule`** — 5,496 occurrences across the 194 serialized files
of one late-game save. A Windows Store save and a current Steam save are the
same format. So the conversion is a *downgrade*, useful only for a Steam or GOG
build old enough to predate Unity 2017.2, and the dialog says so rather than
implying anyone with a modern install needs it.

`PacketDeserializer.isWindowsStoreSave()` went with the flag. It asked whether
the file contained `UnityEngine.CoreModule`, which on a current install is true
of every save the player owns — had the half-built flow been finished, the
editor would have told the user each of their saves was a Windows Store save.

What replaced it, `save/SaveConverter`, does the job as a byte rewrite. The
entire difference between the formats is one string inside each packet's type
header, and a header string is a guard byte, a 7-bit length and UTF-8 — so an
occurrence is rewritten only where a guard and a length frame it exactly and
the module name ends the string, and anything that cannot be framed is handed
to the old deserializing converter rather than guessed at. On a real 119 MB
save, 5,496 of 5,496 occurrences framed unambiguously, so that fallback never
fires in practice.

Against the path it replaces: 1.4s of 20-thread work becomes 0.57s on one
thread, and the heap needed drops from more than 256 MB — it runs out there,
holding 395 object graphs at once — to under 96 MB, because it holds one file.
End to end an archive is ~2.4s, now dominated by the zip round trip.

Checked against the converter it replaces rather than against itself:
byte-identical on the golden fixture pair, byte-identical on all 194 serialized
files of a real save, a round trip back to the original bytes exactly, and the
same packet count out of the deserializer. The one thing *not* verified is the
thing that cannot be: the build this targets is older than the Unity change,
and the only copy here is current.

In the editor it is a **Format** action beside Load, Rename and Delete. It says
what the save is, what conversion is for, and where the copy will go before
writing anything; the copy lands in `<saves folder>/converted/`, which the list
never shows because `SaveGameExtractor` filters on `File::isFile`.


### 4.4 Bundle the item catalog extractor — *done*

`tools/gamedata/extract_gamedata.py` runs the three extractors (items and
abilities, stronghold, identity) with the game path as an argument, speaks a
`PROGRESS`/`DONE`/`ERROR` line protocol, and swaps its output into place only
when every stage succeeded. A release ships it frozen by PyInstaller; the
editor offers to run it on first launch (`environment/GameDataExtraction`,
`handlers/GameData`, `ui/js/GameData.js`). Its output was checked against the
data the editor had been using: all five catalogs and all 1,412 icons
byte-identical.

### 4.5 A release people can install — *done, 1.0.0-beta*

| Blocker | What was done |
|---|---|
| Needed Java 8, Maven and a checkout | `tools/release/build-release.ps1` zips `Eternity Keeper.exe` (Launch4j), the jar, the UI, the JCEF natives, a Java 8 JRE and the frozen reader. 119 MB |
| Ran only from its own folder | `environment/AppPaths`: the UI is found beside the jar, settings and the log live in `%APPDATA%\Eternity Keeper`, old settings are adopted once |
| Game data needed Python and hand-edited paths | 4.4 above |
| DevTools port 13002 open to any local program | Off unless `-Dek.debugPort` asks for it |

Tested end to end from the zip, extracted to a path with spaces and started
with an empty data folder (`tools/ui-tests/gamedata_ui.py` with `EK_RELEASE`):
27 checks, from the bundled Java and the data folder through a real read of the
game data by the frozen reader to real names and icons in the item browser.
That test is what found the launcher's unquoted library path (the editor never
started) and the frozen reader writing no icons at all.

org.json went from 20141113 (the JSON License, whose "shall be used for Good,
not Evil" clause is widely read as GPL-incompatible) to 20250517, which is
public domain. It was a drop-in: the 398 Java tests and every UI suite pass on
it unchanged.

Cleanup at the same time: 32-bit Windows natives (74 MB), the Linux launchers,
the updater's jar lists and dead platform helpers deleted; README rewritten;
`CHANGELOG.md`, `CONTRIBUTING.md`, `THIRD-PARTY-NOTICES.md`; the UI suites
moved into `tools/ui-tests`; GitHub Actions for tests and tagged releases; a
bug-report form that asks for `eternity.log`.

### 4.6 An installer, and a clean machine to test it on — *done 2026-10-05*

Compiled with Inno Setup 6.7.3 and run on the development machine and, by the
`Package` workflow, on GitHub's Windows runner. The script compiled as it was
written; running its test is what found the faults (the last three rows of the
review table in §2). The rest of the table was measured on the zip.

| What | How |
|---|---|
| Installer | `tools/release/installer.iss` (Inno Setup 6), built by `build-release.ps1` out of the same staged folder as the zip: per-user under `%LOCALAPPDATA%\Programs`, no administrator, Start menu entry, Installed apps entry, uninstaller; an upgrade clears the last version's program folders first (only where `eternity-keeper.jar` shows an earlier install); the uninstaller leaves `%APPDATA%\Eternity Keeper` alone and says so |
| Tested where nothing is installed | `tools/release/test-installer.ps1` installs silently into a folder with a space, checks every part, the Start menu entry and the Installed apps entry, runs the shipped Java and the reader's self-test, starts the installed editor and waits for its page over the DevTools port, then uninstalls and checks nothing is left but the data folder. On the way it asks setup for a folder the system's code page cannot spell (refused, nothing installed), runs setup while the installed editor is open (refused, the editor left running: the launcher's mutex is the one setup looks for), installs over the install as the next version would (stale files in the program folders gone, the launcher's options file kept) and, with `-DefaultFolder`, installs once with no folder given and checks setup chose the user's own Programs folder. The `Package` workflow runs it on GitHub's Windows runner — the clean machine 4.6 used to list as missing — when the packaging changes, by hand, and for every `v*` tag |
| What a clean machine needs | Nothing: the browser natives link their runtime statically and the JRE ships its own (checked by reading every shipped binary's import table) |
| Where it can live | Anywhere the system's code page can spell. Measured on a cp1250 Windows: Polish letters in the install path work, Cyrillic and Japanese do not — Launch4j 3.12 and Java 8 read their own paths as ANSI, and the launcher then reports the `jre` folder missing. The launcher's message now says so, the installer refuses such a folder and picks `C:\Eternity Keeper` when the user's own Programs folder is one, and both READMEs say it. **A user profile in another script is fine**: settings, the log, the unpacked saves, a working copy and a Save were all driven with `%APPDATA%` and `%TEMP%` under `Профиль 日本語` |

### 4.7 Still open before a public release

| Item | Why |
|---|---|
| Unsigned executable | SmartScreen warns on first run; the README says how to proceed. Code signing costs money |
| Install folder outside the code page | See 4.6. Going further means replacing Launch4j with a launcher that hands Java the folder's 8.3 short name, and that only where the volume keeps short names |
| ~~Dependencies as old as the code~~ | Done 2026-10-05: Logback 1.3.16 (the newest line that still runs on Java 8) with SLF4J 2, Commons IO 2.22.0, Guava 33.7.2 and zip4j 2.11.6 replace 1.0.13, 2.4, 30.0 and 2.6.4. Their published flaws needed what the editor never does (a network receiver, a writable logging configuration), so this buys a download nothing warns about rather than safety. Every class in the new jars is a Java 8 class; the Java 9 module descriptors they carry are kept out of the shaded jar. zip4j writes every save, so saves written with it were loaded in the game again |

---

## 5. Ideas worth considering

Not requested, offered for the record.

- ~~**Names in the game's language.**~~ Done (2026-10-05), as described
  below; `docs/features.md` has what was learned. The game ships eight languages
  (`data/localized/<code>`, each with a `language.xml` naming it) and the
  editor shows every item, ability, upgrade and hireling in English, whatever
  the player's game says: the three extractors and `save/GameText` read
  `localized/en` only. The catalogs would keep each name's table and ID beside
  the English text, and resolve it through `GameText` in the language the
  game is set to (the registry's `LanguageName` value) or the one chosen in
  Settings, falling back to English string by string. Extracted data made
  before this has no IDs and stays English until the game data is read again.
- ~~**Load-list party portraits.**~~ Done (2026-09-21): `save/PartyPortraits`
  redraws `0.png`…`5.png` on Save from each member's small portrait, in party
  slot order; verified in the game's load list.
- ~~**Quick slots and weapon sets.**~~ Done (2026-09-24): editable before,
  untested; now held to the game's rules (`Equippable.CanUseSlot`, the
  two-hander check, soulbinding, `MaxWeaponSets`, stack caps), judged on the end
  state so swaps work with full packs, with stash stacks split the way the game
  splits them. Verified in the game on Aloth's sets and the Watcher's quick
  slots.
- ~~**Backup-before-save.**~~ Done (2026-09-21): `save/SaveBackups` copies a
  save into `<data>\backups` before Delete, Rename, or a Save that replaces a
  file of the same name; File → Backups restores, never over a save that is
  there. Delete also stopped accepting any path the page sent.
- ~~**Save comparison.**~~ Done (2026-10-01): Compare on the save list shows
  what differs between two saves — the save picked and the one it was made
  from, by default — in the editor's own words: a stat under its character, an
  item by where it went, a quest by its title and the objectives reached, a
  store by its stock. Where objects stand and the game's timers are counted,
  not listed, and whatever the sections cannot explain is still shown, under
  "Everything else". Two late-game saves take about two seconds. Its first
  end-to-end run caught Save writing English names over companions' own.
- ~~**Validation pass before writing.**~~ Done (2026-09-27):
  `serializer/PacketInvariants` checks what is about to be written — the
  leading count, an ID two objects share, an `InstanceID.Guid` that is not its
  object's own, item lists that no longer pair up, a listed, worn or held GUID
  with nothing behind it, and every value held as the kind the file declares —
  and `DeserializedPackets` refuses an edit that newly breaks one, in words,
  with nothing written. Only new breaches count, because the game leaves its
  own: across 38 real saves, thousands of inventory entries in the area files
  name items that are not there. It costs about 7% of a read. It caught a real bug the day it went
  in: import kept the IDs a character's belongings had in the save the file
  came from, so an item handed to someone else since the export — or every
  item, keeping both copies of a character — existed twice, which the game
  drops. Between saves of the test environment's mid-game playthrough, 236 of
  the 524 possible overwrite imports would have lost something that way.
  Imported objects get IDs of their own now (`save/GuidRemap`, shared with
  resurrection), and `import_chr.py` drives one such import through the
  editor.
- ~~**Undo within a session.**~~ Done (2026-10-04): Undo and Redo beside
  Save, over everything typed or applied since the save was opened or last
  written (§3.4). An Apply's undo puts back, byte for byte, the files it
  replaced; a typed change goes back into the slot the page holds.
- ~~**Bulk party operations**~~ Done (2026-09-29): Character → Heal, level
  up and resupply. The heal sets the game's own full-refill flag
  (`Health.m_needs_current_values`), so the game works out every maximum
  itself when the save loads, for the active party as its HealParty does.
  The level-up raises everyone below a level to its experience and, unlike
  the game's AddExperienceToLevel, lowers nobody: a hired adventurer in the
  test saves is level 1 with 86,485 experience, and the game's command would
  cut her to 45,000. Camping supplies refill to the difficulty's cap. Not a
  full rest: fatigue is a status effect and spells per rest are left alone.
  Verified in the game: the Watcher loaded at Health 1260/1260 instead of
  1055/1260, three companions likewise, and four camping supplies for three.
- ~~**Search across everything**~~ Done (2026-10-01): a Find anything box at
  the top of the character list (Ctrl+F) searches the characters, every item
  wherever it is, abilities and talents, and the global variables, ignoring
  case and accents, and each result opens its tab on the right thing with the
  item or row picked out. It sits in the sidebar because the navbar has no
  room for it below about 1,300px. Vendors' stock is not searched.
- ~~**Preset/loadout export**~~ Done (2026-10-02): the Inventory tab saves
  what a character wears, holds and keeps in quick slots to a `.loadout`
  file and puts one on anyone, in this save or another playthrough. Every
  item goes on as a copy under an ID of its own, its enchantment and pet
  links cleared so the game makes its own; whatever a copy replaces goes to
  the stash, and the plan says item by item what goes where and what is left
  out and why: a slot the character lacks, gear for another class, a
  soulbound item bound to someone else. The game knows the main character
  and every companion by the same ID in every playthrough, so the Watcher's
  soulbound sword goes on the Watcher of another one. Verified in the game:
  the copies' enchantments and the abilities they grant, a pet summoned for
  a copied pet item, and the replaced gear in the stash.
- **Open a save by dropping it on the window.** Taken out of 1.1.0-beta
  before release: the page's `drop` event gets only a file's name (Chrome
  45's `File` has no path; `file.path` is Electron's), and an AWT
  `DropTarget` on the frame never sees a drop over the browser, whose own
  window takes it. The way in is JCEF's `CefDragHandler.onDragEnter`, whose
  `CefDragData.getFileNames()` has full paths: keep the last one seen and let
  the page's `drop` ask Java for it. The back end is there already
  (`OpenSavedGame` accepts a `.savegame` path; `SaveArchiveService.stageArchive`
  unpacks it into a folder of its own). Before shipping it: refuse a drop
  while a save is open with changes not saved or applied (invariant 21), and
  give a dropped save's tile its real path, because the list's Delete and
  Rename build the path from the saves folder and the file name.

---

## 6. Order of work

Features first, per the project owner's direction; compatibility afterwards.

1. ~~Commit the current work.~~ done
2. ~~Skills editor (1.1)~~ done
3. ~~Talents and abilities (1.2)~~ done
4. ~~Bulk tidy-up and sell (2.1b)~~ done
5. ~~Stronghold editor (2.2)~~ done; hirelings can be dismissed and
   prisoners released
5b. ~~Vendor cleanup (2.1)~~ done: a reviewable list per store, read from
    and written to the area files
6. ~~Culture / race / class (1.3)~~ done, with what each choice is
   worth shown under it (1.3b)
7. ~~Grimoire editor (2.3)~~ done
7b. ~~Companion portraits (2.4)~~ done
8. ~~Save validation pass (3.2)~~ done
8b. ~~Multi-store detection (4.1)~~ done
9. ~~Faster conversion (4.3)~~ done, with the premise corrected
10. ~~A release people can install (4.4, 4.5)~~ done, 1.0.0-beta
10b. ~~The dependencies in 4.7~~ done; the installer of 4.6 compiled, run and
     fixed. What 4.7 still lists needs a decision (signing) or a report
     from someone it affects (a folder outside the code page)
10c. ~~Names in the game's language (§5)~~ done
10d. Mac support (4.2) ← next, if someone with a Mac can test it
11. ~~Delete the auto-updater and bootstrapper~~ done, in the audit (§2)
