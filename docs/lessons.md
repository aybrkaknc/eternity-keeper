# What testing and review found

> Working notes, kept while the editor was built and moved here from a file
> outside the repository in October 2026. They are written for whoever
> changes the code next: what the game does with a save, measured or read
> out of the decompiled game, and what went wrong when that was guessed at.
> "Invariant N" anywhere in these documents is rule N of
> [invariants.md](invariants.md).

The passes over the whole editor, in the order they were made. Each records the
faults it found and, more usefully, the shape of each fault: the same shapes
come back.

## Bugs found by testing the whole editor (2026-08-21)

Fixed; recorded because each one was invisible until the feature was driven
end-to-end, and the same shapes will recur.

- **An editor that changes `saveData` must call
  `Eternity.Modifications.transition({modifications: true})`.** The Save button
  is `disabled` on that flag (`Modifications.js`), so the currency editor —
  which set the amount and nothing else — produced an edit that literally could
  not be saved.
- **A UI component must not be the only source of data a save mutation needs.**
  `AbilityEditor` read a talent's `grants` out of whatever its browser had
  fetched, so removing a talent nobody had searched for left the ability it
  granted behind. `UpdateAbilities` now resolves grants from `AbilityCatalog`
  server-side and merges whatever the client sent.
- **A cached working copy has to notice the party changing.** `InventoryEditor`
  rebuilt only when its copy was empty, so a companion resurrected mid-session
  never got a pack row — the save had 13 characters, the screen drew 12. It now
  compares a fingerprint of the character GUIDs.
- **`TacticalMode` is `{Disabled, RoundBased}`, not `{RealTime, TurnBased}`.**
  The mirror enum had invented names. Ordinals matched so saves round-tripped,
  but the names leak into `saveinfo.xml` as text, where the game had no name to
  match.
- **`saveinfo.xml` keeps its own copy of `Difficulty`/`TrialOfIron`/
  `TacticalMode`** for the load screen. Editing `GameState` alone left the load
  list advertising the old difficulty. `ChangesSaver.summaryFields` mirrors them.
- **A dead companion has no name in the save.** The registry's English name is
  the fallback, which reads oddly in a translated save;
  `SavedGameOpener.localizedName` now prefers a leftover copy's `OverrideName`
  where one exists (roster duplicates), and there is nothing to read when the
  object was deleted outright.
- **A hand-maintained list of views goes stale the moment one is added.**
  `Editor.render` hid each view by id — seven `X.html.someView.hide()` calls —
  so the grimoire view, which nobody added to it, simply stayed on screen
  behind the save-loading panel: on every cold boot, and every time the user
  went back to the list. It now does `$('.view').hide()`, which is what
  `SavedGame.render` already did and what cannot go stale. **The regression
  test checks the property, not the list**: no element with class `view` may be
  visible while the save list is showing, so whatever gets added next is
  covered. A modal is not a `.view` and nothing else hides it, so
  `PortraitPicker.render` closes its dialog when the save closes.
- Layout: an unqualified `.abl-search input` width also hit the checkbox next to
  it; ability panels stretched to the full window, leaving ~850px between a row's
  name and its own button; and empty icon tiles were drawn *lighter* than filled
  ones, so a missing icon read as a broken image.

## Sweeping every element of the editor (2026-09-11)

Three scripted passes, run against a real 13-character save, both themes:
every view and every dialog measured for anything clipped, off screen, too
small to hit or faded into its background; every dialog again but opened the
way a user opens it; and one real interaction per feature checked against what
the model says afterwards. 165 checks. Two things it caught, and one thing it
taught about how to write the checks.

- **A light-mode override on a dialog that never goes light.** The Save
  dialog's "what file will be written, and where" panel had a full
  `body.theme-light .save-target-*` block painting it dark brown. Modals keep
  the dark chrome in both themes, so in light mode the file name rendered
  `#3a2a18` on `#2e261c` — **contrast 1.1**, invisible, in the one place that
  dialog exists to tell you something. The dark colours are right in both
  themes; the overrides are deleted. This is the same shape as the
  `.abl-icon-empty` bug and the `.light-theme` one before it, and the reason
  it survived so long is worth keeping: **a dialog opened with
  `modal('show')` and no data has no text to measure.** The first pass said
  the dialog was clean. Only driving it through `Modifications.save()`, with a
  real save open and the target filled in, put a string in the element that
  was broken.
- **`CloneFactory` left `data-bound` on every clone.** It cleared the jQuery
  data cache but not the attribute, so each of the save tiles answered
  `$('[data-bound]')` with no id to bind to. Nothing re-runs `bindDOM`, so it
  never misfired — but the markup claimed a binding that did not exist, which
  is how the next person gets misled. `removeAttr` alongside the cache clear.

Things the sweep confirmed rather than fixed, worth not re-investigating:
every `data-bound` element resolves to its component; no two elements share an
id; no view or dialog scrolls the page sideways at 2560px; every visible
button and link has a handler, a `data-dismiss` or a real href; and the
stronghold's "Built" toggle really does project the cascade (48→18 Prestige,
44→14 Security on a fully built keep).

**A caution for the scripts, not the app**: driving the UI through many
`Page.navigate` reloads in one session can leave queries queued on
`Environment.workers()` that never answer, and the symptom is a dialog stuck
on its loading text. A user never reloads the page, and a fresh boot answers
in about a second. Restart the app between audit scripts rather than chasing
it.

## Revert and Apply on every panel, and keeping them honest (2026-09-12)

The Inventory, Abilities, Stronghold and Grimoire tabs each stage a change set
and send it to a manager on Apply, so they have always had the pair. The
character sheet, the raw and globals tables and the console did not: they write
into `saveData` as you type, and the only way back from a number you had
changed was closing the save and losing everything else with it.

- **`ui/js/PanelChanges.js`** keeps a *confirmed baseline* of the two things
  those panels edit — each character's own `stats` and `portraitPaths`, and the
  save's `globals` — and offers the same two words everywhere: **Revert** puts
  that panel's scope back to the baseline, **Apply changes** makes what is on
  screen the new baseline. Apply is a commit point, not a write; nothing
  reaches the disk until Save and the bar says so.
- The baseline stores **values, not the `{type, value}` wrappers**, and a
  revert writes back into the live wrappers. Replacing the objects would break
  invariant 12 — unsaved edits elsewhere live in exactly those objects.
- **Taken once per opened save, then only topped up.** A manager's Apply hands
  back a freshly built `saveData`, and re-capturing on every render would
  quietly confirm edits the user had not confirmed. `captureNewCharacters`
  picks up the ones a resurrection or an import mints, so an unknown character
  reads as clean rather than as a pile of unconfirmed edits.
- **Deliberately not done: disabling the Save button when a panel is reverted.**
  Inventory, party and item edits live outside these two scopes, so "clean"
  here does not mean "nothing to save". Guessing wrong in that direction would
  hide a real pending change, which is the one direction that costs the user
  their work.
- Every panel bar is the same markup and the same position — `.panel-bar` as
  the view's first child, actions floated right. The four existing pairs were
  in four different places at three different sizes (the inventory's lived
  inside the *pack panel's* head, the grimoire's inside the book body), and
  their per-tab CSS is gone. `.panel-bar` carries `flex: 0 0 100%` because the
  inventory and abilities views are flex-wrap containers where a bare
  `width: 100%` still lets a child shrink.
- One hook, not seventeen: everything that changes `saveData` already calls
  `Eternity.Modifications.transition({modifications: true})` to arm the Save
  button, so `Modifications.render` recounts the bars. Seventeen call sites
  across ten files would otherwise each have had to remember.

### The editor now follows a character change instead of a snapshot

`SavedGameOpener` derives a few things from `CharacterStats` server-side and
ships them as a snapshot. The Identity panel edits those same stats live, so
the snapshot went stale the moment someone changed a class: the Inventory tab
kept offering a grimoire slot to a character who was no longer a wizard, and
the Abilities browser kept offering a paladin's talents to a cipher.

- `utils.js` gained `liveStat` and `unavailableSlotsNow`, which redo the two
  `Equipment.HasEquipmentSlot` rules identity can actually change — Head for a
  non-Godlike, Grimoire for a wizard — over the server's list. **Pet is taken
  from that list as it stands**, because it depends on the object being
  `Player_*` and no edit can move that.
- `AbilityEditor.liveIdentity` reads class and subrace from the live stats, and
  the browse cache is keyed on **(character, class, subrace)** rather than on
  the guid: changing the class has to refetch, or the results stay the old
  class's.
- **The two halves reach the disk by different routes**, and that needed saying
  rather than hiding. Inventory changes are written by Apply in that tab;
  identity changes by Save. Applying an equipped grimoire and never saving the
  class would leave a book in a slot the wearer cannot reach, and nothing in
  the game puts it back — `RepairSaveLoadEquipmentErrors()` handles the
  deprecated Cape and locked slots only. The Inventory tab now says so in its
  status line whenever the live slots disagree with the opener's.
- The identity dropdowns carry `data-stat` now. The panel closes over the stat
  it writes, so nothing needs the attribute to work — but a field nothing can
  name is a field nothing else can check, and these are the values the rest of
  the editor derives from.

### "Show more" could not reach the end of the list

Both browsers that *append* a page rather than replacing one were adding the
request offset to the number of rows already on screen. `portraits` already
counts what the offset skipped, so the sum double-counted everything past the
first page: with 118 portraits and a page of 40, the second page put it at
40 + 80 = 120, **the picker hid its own button at 80 of 118**, and the label
read "-2 left" on the way out. The wizard spell list had the same slip and
survived it only because 114 spells fit in two pages of 60.

`handlers/BrowsePortraitsTest` pins the server half of that contract — pages
must tile the whole catalog exactly once, with a `total` that does not move —
even though the bug was entirely client-side. A reader who finds the fix should
be able to see what the other half promises.

### Sorting a paged list happens on the server

`BrowseAbilities` takes `sort`: the default is by name (what the catalog
already returns), `"level"` sorts by the level a player would look for. **The
two lists mean different levels** and both are the right one: a spell's is its
`SpellLevel`, which is the chapter of the grimoire it lands in, while an
ability or talent's is the character level its progression row unlocks it at —
and that lives on the `Unlock` rather than on the ability, so it only exists
once a character has been named. An ability nothing unlocks sorts *last*,
because "no level" is not level zero.

Sorting in the client was not an option: the browsers page, so reordering the
window the client happens to hold would put a level 8 spell above a level 1 one
the moment the second page arrived. `sortingSurvivesPaging` is the test that
says so.

### Layout, from the same pass

- **The grimoire's chapters are two columns of four rows**, not eight rows of
  four tiles across. The game lays a grimoire out as two facing pages, and a
  row of four tiles put every long spell name behind an `overflow: hidden` —
  "Concelhaut's Draining Missiles" simply stopped mid-word. Each row is now the
  full column width, with the name ellipsised only if it really runs out, and
  the remove button **before** the name in the DOM: the name is its own block
  formatting context, so a float that follows it drops to the next line and
  doubles the row height.
- **The grimoire rail is the full height of the panel.** `.grm-main` is a block
  with a `.grm-columns` flex row under the panel bar, and `align-items:
  stretch` is what makes the rail as tall as the book beside it — with
  `flex-start` it stopped at its own content and left the panel looking half
  empty.
- **The paper doll is the game's own** (superseded the "three to a row" grid
  on 2026-09-21, read off the game's inventory screen by hovering every slot):
  Head, Chest, **right-hand** ring, Feet, Pet down the left of the portrait;
  Neck, Hands, **left-hand** ring, Waist, Grimoire down the right. The doll
  faces you, so the save's `RightRing` (index 4) is drawn on *your* left —
  verified with Ring of Thorns (`Ring_Hag`) in slot 4 showing left in the
  game. Both are labelled "Ring", as the game does; the tooltip and refusals
  say which hand. Quick items three to a row, weapon sets I/II over III/IV.
  The two small squares in the game's top corners are the character's primary
  and secondary colours, not equipment. `layout_rules.py` measures all of it.
- **The ability browser explains what it is offering.** Level markers whenever
  the requirement changes, and the highlighted row's description on the right —
  what it is, what it needs, what it says, and what adding it will drag along
  with it (`grants`, `modifies`, skill bonuses). Clicking a row opens its
  description; the plus button is still the only thing that changes the
  character, so it stops the click from propagating.
- Both long lists are **width-capped for the reason the ability panels already
  were**: a row is a name, a level and a button, and on a 2560px screen the
  full width left about a thousand pixels of nothing between the spell and the
  button that adds it.
- One real save holds a global whose **name is the empty string**. An
  unlabelled editable row reads as a rendering fault, so it says "(unnamed)"
  rather than hiding a variable that exists.

### Three ability panels, a full-width keep, and every size measured (2026-09-25)

- **The Abilities tab is three columns of one height**: Abilities and spells,
  Talents, then the browser (moved into `.abl-columns` as its third child).
  The two lists share a basis (`flex: 1 1 340px`) so they are always equally
  wide; the browser takes about twice that (`flex: 2 1 760px`) and wraps under
  them at full width when the tab is narrower than about 1,500px (a window
  under about 1,800px: measured, three across at 1920, wrapped at 1440). The
  height is `calc(100vh - 218px)` (min 480px) — the navigation and panel bars
  above, the status line and the floating Save button's corner below; each
  panel is a flex column whose list scrolls inside it. The row is
  `calc(100% + 18px)` wide with `margin-right: -18px`, so the last panel on a
  line ends flush with the tab — the Chrome 45 stand-in for `gap`.
- **The lists hug their content** (`.abl-list { flex: 0 1 auto }`): stretching
  the Talents list left its granted-abilities heading floating half-way down an
  empty panel.
- **The Stronghold's upgrades span the tab in two columns** beside the rail.
  Rows take `flex: 1 1 34%` with `min-width: 560px` (never three to a line; one
  when the window cannot fit two beside the 260px rail — two at 1920, one at
  1440), and a zero-height `.sh-upgrade-filler` goes after the last row —
  without it an odd row out stretched across both columns, to 1930px. Below
  1180px the rail stacks under the list.
- Both browsers page with the same compact pair ("‹ Previous  1–40 of 98
  Next ›"); the dialog-sized buttons crowded the label between them.
- `responsive.py` measures both tabs at six sizes by emulating the viewport
  over CDP (`Emulation.setDeviceMetricsOverride`, `fitWindow=False`); the window
  itself never moves. It checks the property rather than a layout: whatever
  shares a line shares a height, and nothing reaches past the tab's edge or
  under the Save button.

## Whole-repository audit (2026-09-14)

The full list of bugs and consolidations is in `ROADMAP.md` §2. What is worth
knowing before touching the code again:

- **Shared bases exist now — use them instead of copying a neighbour.** An Apply
  handler extends `SaveMutationHandler` (worker hop, working-save lookup, reopen);
  a catalog browser extends `CatalogQuery` (worker hop, JSON parse, `offset`/`limit`/
  `page`, and it answers even when the search throws — before, the browser spun
  forever); a new object is minted with `SaveModel.mint` (header override, copied
  components, `InstanceID.Guid`); icons come from `save/IconFolder`; handlers are
  registered by adding one row to the `JSHandlers.handlers` table (a test scans the
  package for handlers missing from it).
- **`CatalogQuery.array(values)`** builds a JSONArray from any collection; it
  dates from org.json 20141113, whose constructor took only `Collection<Object>`.
- **Tests only delete the `EK-` temp folders they created.** The harness used to
  delete every one, including a running editor's unpacked saves and working copy,
  so running `mvn test` with the editor open broke its next Apply.
- **The scripted UI suites** live in `tools/ui-tests` (`run_suites.py` boots the
  editor fresh for each, with its own settings in `target/ui-tests/data`): smoke,
  functional, panels, audit, audit2, paging, format_ui, consistency, merge_bugs,
  `writes.py` (one edit through every write path, saved and read back from the
  written file), `mint.py` (a minted item and ability, same), layout_rules,
  look_variants, catalog_offer, backups_ui, stronghold_people,
  quick_and_weapons, responsive, vendors, import_chr, party_care, find,
  compare, loadout, undo, sale, damaged and checklist — 28 suites, the default
  set of `run_suites.py` (978 checks on 2026-10-05, 83 of them checklist's). They delete the saves they write; check the test-env
  save folder still holds 12 saves afterwards. `gamedata_ui.py` runs on its
  own (fresh data folder). **`checklist.py` names each check after the item
  of the manual test-run checklist it answers** (`sheet-skills: ...`), for
  the items no other suite asserts in the checklist's own words.
- Resurrection unpacks candidate donor saves into `EK-donor*`; they are deleted as
  soon as they are rejected or used.

## Catalog hygiene and layout, second pass (2026-09-14)

- **The item catalog is a quarter non-items.** 549 of 2,156 entries are
  companions, NPCs, creatures, traps, recipes and tables whose bundles carried
  an item and got its name (`Companion_Aloth` = "Aloth's Grimoire"). Plus 12
  items and 9 abilities of developer leftovers (`DEBUG`, `Test`, `_Temp`,
  `_UNUSED_`, `DO_NOT_USE`). `save/ShippedContent` gates both browsers' `search`;
  `lookup` is deliberately untouched. **`Prototype/` is real content** — its
  keys are in every mid-game save and Korgrak's Head is in area files — so a
  name that *looks* like a marker must be checked against real saves (grep the
  unpacked saves in `%TEMP%\EK-unpacked-saves`) before it is filtered.
- **Layout faults are measured, not eyeballed**: `layout_rules.py` (`tools/ui-tests`)
  asserts party packs three to a row, sort controls beside their search box,
  a value within 480px of its name, an attribute input within 200px of its
  label, upgrade rows ≤ 1,200px, and every tab's panel bar at one left, top and
  right edge. `look_variants.py` repeats the overflow checks at 1440px and in
  light mode (`Emulation.setDeviceMetricsOverride` on this Chrome 45 CDP needs
  `fitWindow=False`).
- **Every `.view` shares one frame**: `.main-panel > .view` (specificity 0,2,0)
  at the end of `ui.css` sets margin and padding for all tabs. A view-specific
  box rule written by id (`#consoleView` had one) outranks it and puts that
  tab's Revert/Apply somewhere else — don't add one. `html { overflow-y: scroll }`
  keeps a short tab from shifting its buttons by a scrollbar's width.
- **Controls sit beside what they act on**: sort buttons go in the search row
  (`.grm-toolbar`, `.abl-search`), not floated to the far end of a heading;
  long lists get a `max-width` so a row's button stays near its name.

## Review, damaged input and the installer (2026-10-04 and 05)

The full table is in `ROADMAP.md` §2 ("Review of the whole repository") and
§4.6. What is worth knowing before touching the code again:

- **Measure the list, not the save.** Timing a search found it unpacking
  every save whole for every search; looking in `%TEMP%` found 49 folders and
  2.5 GB left by editors that had been killed (the UI suites kill theirs).
  `environment/TempSweep` clears them at start: only the names the editor
  makes (`EK-editing-`, `EK-history-`, `EK-donor`, `EK-convert` and a number,
  or `EK-` and a number), a day old, never `EK-unpacked-saves`.
- **The saves folder is the one place the editor reads what it did not
  write.** `DamagedSavesTest` puts noise, an empty file, a save cut short,
  summaries with fields missing or that are not XML, a zip that climbs out of
  its folder and a summary that asks the parser to fetch something beside one
  good save; each costs its own tile. `SaveGameExtractor.unreadable()` names
  what was left out and why, the list's reply is `{saves, unreadable}` (or
  `{error, unreadable}`), and the page shows it under the search row
  (`#unreadableSaves`; text nodes only: a file name is whatever somebody
  called the file). `damaged.py` drives it.
- **`saveinfo.xml` cannot reach outside the save only by accident.** jOOX's
  `$(String)` parses the text inside a wrapper element, where a document type
  is not allowed, so one with a DOCTYPE fails to parse. `$(File)` or a newer
  jOOX would not have that property; two tests pin it (a local DTD, and a
  listening socket nobody may call).
- **Fuzz the reader with a fixed seed** (`DamagedPacketFileTest`, about a
  thousand damaged files in 2.5 s). It found a length read out of damaged
  bytes asking for 2 GB — an `OutOfMemoryError`, not an exception, so nothing
  caught it — and a tree that read to its end and tripped the write check.
  Lengths are bounded by what the file has left (`Deserializer.sane`), and a
  tree nothing can make sense of is unreadable (`Optional.empty()`), which
  every caller already handles.
- **A test that writes into a kept file in place destroys the copy.**
  `EditHistory.keep` hard-links the file it keeps, so
  `FileUtils.writeStringToFile(world, ...)` afterwards wrote through the link
  and "put back" the edit. Real writers go through `replace` (a new file moved
  over the old one); a test standing in for one has to do the same.
- **`settle()`-style waits race a pool.** `ChrDialogTest` waited by pushing an
  empty task through each executor; the workers are a pool, so the empty task
  could finish on another thread before the real one had queued its follow-up.
  Wait for the thing itself (a latch).
- **The release needs nothing on a clean machine** — read off every shipped
  binary's import table (`pefile`): the JCEF natives link their runtime
  statically and import only `jawt.dll`, which the JRE ships, and the JRE
  carries its own runtime DLLs.
- **Launch4j 3.12 and Java 8 read their own paths as ANSI.** From a folder
  whose name is outside the system code page (Cyrillic or Japanese on this
  cp1250 machine) the launcher reports the `jre` folder missing; Polish
  letters work. Only the *install* folder matters: with `%APPDATA%` and
  `%TEMP%` under `Профиль 日本語` the release listed, opened, applied and
  saved without an error. The launcher's `bundledJreErr` now names this cause,
  `installer.iss` refuses such a folder (`InCodePage`) and falls back to
  `C:\Eternity Keeper` when the user's own Programs folder is one.
- **An `.l4j.ini` is read as ANSI too**, so a test that writes one with a
  non-ASCII `-Dek.data` in UTF-8 gets a mojibake data folder; set `APPDATA`
  in the child's environment instead.
- **A failed launch leaves its error dialog open** (`Eternity Keeper.exe`, a
  `#32770` window titled "Eternity Keeper"), which `config.stop_editors()`
  does not close. Read and close it by window class and title after a launch
  that did not come up.
- **`checklist.py` found three faults by doing what the list says**: the
  sheet's totals did not follow a typed attribute or skill (only the
  dropdowns redrew them), the sidebar did not follow the name box, and Tidy
  merged nothing in the stash whenever a stack was over the item's cap — it
  divided by the cap, and the stash has none. The first fix divided by
  `Infinity`, got zero stacks wanted, and deleted every lockpick; the check
  that "none of them is lost" is why that never left the machine.
- **First launch does not open Settings by itself when it finds the game and
  the saves**; it opens it only when the game cannot be found.
- **A stopped game-data read is a killed reader**, so it cannot clear its own
  `<out>.new`; `GameDataExtraction` removes it after any run that did not
  succeed. Found by stopping one in the release and listing the data folder.
- **Probes kept in the scratchpad, not the repo** (release_firstrun,
  release_profile, release_paths, release_stop): they start the *extracted
  release* with `APPDATA`/`TEMP` set in the child's environment and an
  `.l4j.ini` for the DevTools port, which is the way to test anything about
  where the release keeps its files without touching the real profile.

### The installer, compiled and run (2026-10-05)

Inno Setup 6.7.3 went onto the development machine
(`winget install --id JRSoftware.InnoSetup -e --source winget --scope user`:
no administrator prompt, `%LOCALAPPDATA%\Programs\Inno Setup 6`), and the same
day the `Package` workflow built and tested the installer on GitHub's runner.

- **The script compiled as written; running its test is what found the
  faults.** The first run passed. Extending the test to what the first one
  left out (an upgrade, setup beside an open editor, a folder the editor
  cannot start from) found one in each direction it looked.
- **`MsgBox` ignores `/SUPPRESSMSGBOXES`.** The folder check said "cannot
  start from this folder" with `MsgBox`, so a silent install into such a
  folder put a message box on the screen and waited at it. On the development
  machine that was the screen of the person working at it (their screenshot
  is how it was found); on a build machine it is a job that hangs for six
  hours. Everything the installer says goes through
  `SuppressibleMsgBox` now, the test gives every run of setup a time limit
  (60 s for one that must refuse, and says a setup that does not finish "is
  probably showing a message box"), and the workflow's job has
  `timeout-minutes`.
- **A silent install still walks the wizard's pages**: `NextButtonClick` runs
  for every page that is not skipped, so the check on the folder page does
  stop a fresh `/VERYSILENT /DIR=...`. **An upgrade skips that page**
  (`DisableDirPage` is `auto`), so `/DIR=` on an upgrade went unchecked; the
  same check sits in `PrepareToInstall` as well, which every install passes
  (setup then exits non-zero and copies nothing).
- **The launcher holds its mutex for as long as the editor runs** (Launch4j
  keeps the launcher process alive for a single-instance program), so
  `AppMutex=EternityKeeper` works: setup run beside an open editor exits
  non-zero, removes nothing, and the editor still answers on its DevTools
  port.
- **An upgrade keeps `Eternity Keeper.l4j.ini`** (a user's own JVM options)
  and clears `ui`, `lib`, `jre` and `gamedata`; the uninstaller removes the
  ini (`[UninstallDelete]`), or it was the one file that kept the folder.
- **With no folder given setup chooses `%LOCALAPPDATA%\Programs\Eternity
  Keeper`** (`{code:DefaultDir}`); `test-installer.ps1 -DefaultFolder` checks
  that by really installing there and removing it, which is why it is a
  switch: the workflow passes it, a development machine need not.
- **The links under Installed apps come from the build**: `build-release.ps1`
  passes `/DAppUrl=` from `GITHUB_REPOSITORY` in a workflow, else the
  checkout's `origin`, so a release names the repository that built it.
- **`InCodePage` was checked with a throwaway setup** that writes what it
  says about a few paths and stops (`InitializeSetup` returning False): on
  cp1250, Polish and German letters pass, `ñ`, `Ω`, Cyrillic and Japanese do
  not. **A Pascal Script character constant below 256 is an ANSI character**:
  `#$00F1` is byte F1 in the system code page (`ń` here), not `ñ`, and a
  probe that built its test paths that way reported a pass that was not one.
  Real Unicode went in through `{param:...}`.
- **An editor left open is an install refused.** `gamedata_ui.py`, run
  against a release, started the release's editor and left it running; the
  launcher's mutex outlives the launcher (the Java process inherits it), so
  the next run of setup stopped with "Eternity Keeper is currently running".
  The suite closes its editor now. `config.stop_editors()` before any installer
  test.
- **One failure nobody could explain.** Once in eight launches the installed
  editor started (its log shows the page asking for the game folder) and the
  test never saw its page on the DevTools port in two minutes. The next five
  runs passed. An editor that cannot bind its port starts without DevTools,
  and somebody at the machine closing the window that popped up would look the
  same, so the test now asks Windows for a free port instead of using a fixed
  one, and reports what was still running, what the port answered and the end
  of both logs if it happens again.
- PowerShell: `Start-Process -PassThru` with `WaitForExit(ms)` gives an empty
  `ExitCode` unless the handle was read first (`$null = $run.Handle`), and
  `$home` cannot be assigned (it is `$HOME`).

### Current libraries (2026-10-05)

Logback 1.0.13 to 1.3.16 (the newest line that runs on Java 8; it wants SLF4J
2), Commons IO 2.4 to 2.22.0, Guava 30.0 to 33.7.2, zip4j 2.6.4 to 2.11.6,
JUnit 4.12 to 4.13.2. Every Java test and UI suite passed unchanged.

- **SLF4J 2 finds Logback through `META-INF/services/org.slf4j.spi.
  SLF4JServiceProvider`**, which therefore has to be in the shaded jar (it
  is: nothing else ships a file of that name, and the shade plugin has no
  services transformer). Without it logging turns into a silent no-op and
  `eternity.log` is never written; `test-installer.ps1` and `import_chr.py`
  would both notice.
- **Every class in the new jars is a Java 8 class** (read off the class
  files' version bytes), but most carry a Java 9 `module-info.class`, and one
  library's descriptor at the root of a jar holding all of them would
  describe the wrong thing: the shade filters drop `module-info.class` and
  `META-INF/versions/**`.
- Guava no longer brings JSR-305 or the Checker Framework qualifiers; it
  brings JSpecify. `THIRD-PARTY-NOTICES.md` follows what `mvn
  dependency:tree` prints.

### Loaded in the game again, with the new libraries (2026-10-05)

Five saves built through the editor's own controls by the `ingame_*.py`
builders with the upgraded build (zip4j 2.11.6 wrote every one), loaded in
Pillars of Eternity v3.9.5, each followed by a read of Player.log:

- **Everything in one save** (`ingame_save.py`): the new portrait on the party
  bar and the sheet, Background Aristocrat, Might 23, Athletics 14, Lore 3,
  the talent Weapon Focus: Adventurer at the foot of the talents list, the
  minted Minor Ring of Deflection with its tooltip, 224,639 cp (223,108 plus
  the three turns the game played on load), Edér in the party and Sagani out
  (in the load list's little portraits too), Eldritch Aim in Aloth's first
  chapter where Chill Fog was, Options → Difficulty on Easy, Prestige 47 and
  the Curio Shop offered for purchase again.
- **An import into the playthrough the character came from**
  (`ingame_import.py`, new): Aloth exported at Caed Nua and imported over
  himself in a save eighteen hours of play later. In the game he is level 15
  with 115,496 experience, as exported (16 and 120,000 before), carries
  exactly the ten stacks and four quick items of the export, and the stash's
  consumables page is as full as it was. 14 of his objects got IDs of their
  own on the way in. This is the import the 2026-09-27 fix and invariant 25
  were for, and the first time it was loaded in the game.
- **A vendor** (`ingame_vendors.py`): the General Goods Merchant reads "No
  items." in three categories, where the same save unedited lists Blade of
  the Endless Paths, Tidefall and the rest of what the party sold him.
- **Trial of Iron**, switched on and switched off (see "Testing in the game
  itself").
- **Player.log**: no `already exists`, no `already been added`, and no
  exception during any load. The only exceptions in two sessions, seventeen
  of them, are the game's own on leaving Caed Nua, which the untouched save
  logs as well.

## The game-data reader on a clean install (2026-10-09)

The first bug report after the release, under the Reddit post: "it fails to
read my game data. My installation does not have ingameglobal unity bundle
asset." The reader stopped at the stronghold stage, and since its output is
all or nothing, the items and abilities it had already read went too.

- **`objectbundle/ingameglobal.unity3d` is not the game's.** It is a Unity
  4.6.1 bundle from the 2015 build. The current game is Unity 2019.4 (since
  the 2025 rebuild) and loads bundles with no extension, each with a
  `.mainasset` sidecar naming the prefab inside; Steam's updates left 2,998
  `*.unity3d` files of the old build beside them on this machine (and an
  empty `assetbundles/px1.unity3d`), which is the only reason the reader ever
  found the file. A clean install has none of them. The game loads
  `InGameGlobal` with `Resources.Load("Prefabs/InGameGlobal")`, out of
  `resources.assets` (3 occurrences of the name; the GameObject has 39
  components), and `tools/gamedata/ingameglobal.py` now takes it from there.
- **The leftover was stale as well.** Against `resources.assets`, the 2015
  bundle had the Brighthollow Hearth and Courtyard Pool at 1,600 cp (the
  game, and the community guides: 600) and lacked two hirelings
  (`b_bleakwalker_hireling`, from The White March Part II, and
  `b_henchman_fampyr`). The deities and orders were identical. The editor
  only shows an upgrade's cost (`StrongholdEditor.js`), so the beta showed
  two wrong labels and wrote nothing wrong.
- **A file's Unity version says whether the game loads it.**
  `UnityPy.load(path).assets[0].unity_version` for anything the reader opens
  should match the game's own `resources.assets`.
- **`m_Script` read out of `resources.assets` does not resolve** (its
  `m_FileID` came back as 16777216), while the fields behind it, the icon
  pointers and the hirelings' `m_GameObject` all read correctly. So the two
  behaviours are told by their fields (`Upgrades` with `StandardHirelings`;
  `DeityInfo` with `PaladinOrderInfo`) among the components of the
  `InGameGlobal` GameObject, where nothing else can be confused with them.
- **`HirelingPrefab` resolves there** (it never did in the bundle), so the
  catalog's hireling entries now carry the prefab's name
  (`NPC_Goldpact_Knight`). The editor reads only `maxHirelings` from that
  part of the catalog, so the keys stayed the hiring globals.
- **The reader's output being all or nothing** turned a missing stronghold
  table into no item names at all. Left as it is for now: the three stages
  share one install, and a stage that cannot find its input is a reader
  that is wrong about the install, which is better heard about than hidden.
