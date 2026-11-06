# How each feature works

> Working notes, kept while the editor was built and moved here from a file
> outside the repository in October 2026. They are written for whoever
> changes the code next: what the game does with a save, measured or read
> out of the decompiled game, and what went wrong when that was guessed at.
> "Invariant N" anywhere in these documents is rule N of
> [invariants.md](invariants.md).

One entry for each thing the editor does beyond the 0.21a it was built on:
where the data lives in a save, what the game does with it on load (from the
decompiled game), what the editor therefore writes, and how it was checked in
the game.

- **Resurrect dead companions**: dead companions (object deleted by the game) appear
  in the character list via `CompanionRegistry` flags/orphan detection; revival is a
  donor transplant (`save/Resurrector`) — remnant purge, UUID de-collision, free
  party slot, death-flag clearing, Sagani's pet Itumaak replaced via
  `linkedCreaturePrefix`. Created companions (`Companion_Generic`) leave no death
  trace and cannot be auto-detected. Facts verified against a 12-save death matrix.
- **Quest restore on resurrection**: companion death auto-fails their quest by
  patching exactly one entry of `QuestManager.QuestTrackers` — a **.NET
  BinaryFormatter (NRBF) blob** nested as `Byte[]` (a third serialization format;
  `SerializedActiveQuests` does NOT change). The tracker (`QuestManager+QuestTracker`:
  4 BitArrays + `EndState` int + `Failed` bool) gets `EndState=-1→N`, `Failed=true`.
  `save/QuestTrackerBlob` is a bounded NRBF reader that indexes fixed-width field
  offsets and patches in place (every other byte preserved — restore reproduces the
  pre-death blob byte-for-byte, validated 11/11 against real alive/failed pairs in
  `test-env/EK_TEST_ENV/questsave/`). `save/QuestRestorer` un-fails the quest during
  `Resurrector.transplant` (registry now carries `questPath` per companion — note
  Hiravias=`13_twin_elms_elms_reach/13_qst_true_to_form`, Zahua=px2 quest in
  `px1_companions/`). **Durance only**: death also fires quest event 7 ("Durance
  Died", TriggeredEvents bit 7) and sets global `nDuranceQuestState=8`; restore
  clears the bit and maps the highest fired progression event back to its state
  value (events 0-4,6 → states 1-5,7, from `companion_qst_durance.quest`).
- **Console tab** (replaces the Cheats menu; opens a view like Edit Attributes):
  - *Achievements toggle button*: flips ONLY `AchievementTracker.m_disableAchievements`.
    Decompiled ground truth: achievements are gated exclusively on that flag; nothing
    re-derives it from `CheatsEnabled` on load, and `CheatsEnabled` keeps runtime cheat
    effects alive (god-mode death immunity checks it) — so it is deliberately left alone.
  - *Command console* emulating save-representable POE1 commands (semantics matched
    to decompiled `Scripts.cs`/`CommandLine.cs`): AddExperience(+Player/ToLevel; XP
    threshold = 1000·L·(L−1)/2), AttributeScore (sets BaseX; player only — companions
    prefab-reset), Skill (sets the skill's stored POINTS for a rank, NOT the
    `<Skill>Bonus` the in-game command writes — `Restored()` re-derives every
    `<Skill>Bonus` from the applied status effects on load, so copying the game
    there produced an edit the next load undid),
    Give/RemovePlayerMoney, Set/IncrementGlobalValue, PrintGlobal, AdjustPrestige/
    AdjustSecurity/AddTurns (needs `Stronghold` in `usefulGlobals` — added), IRoll20s
    (flips CheatsEnabled only), HealParty (the heal flag, shared with Heal, level up
    and resupply). Commands edit the UI's saveData; Save persists them.
  - *In-game-only command reference*: a searchable, curated table below the console
    listing ~90 commands that only exist in the game's own console (need a live
    GameObject/scene — teleports, God/Invisible, AI, stores, etc.), most-useful-first
    then alphabetical, paraphrased from the official wiki (pillarsofeternity.fandom.com/
    wiki/Console — 322 poe1-tagged rows scraped via the DOM `<img alt="Yes">` markers in
    the poe1 column, since get_page_text strips the checkmark images; filtered out pure
    engine/modding internals and anything already covered by the editor's own commands).
  - `ConsoleTab.js`; menu item `menuConsole`/`menuOpenConsole`; view id `consoleView`.
    Gotcha: every `data-bound` element MUST actually exist with the right id — Editor.js's
    `initialise()` loop has no try/catch around component `init()` calls, so one missing
    binding (forgot `data-bound="ConsoleTab"` on the reference `<table>` once) throws
    inside `ConsoleTab.init()` and silently aborts the entire startup chain after it
    (`getDirectoryPaths()` never runs → saves folder never populates, looks like an
    unrelated bug). Symptom: every view stacked visible on load, "Unable to locate save
    folder" that never resolves. Always smoke-test a fresh cold boot after adding bound
    elements.
- **Edit difficulty level** (Save data menu, modal): replicates the in-game New Game
  Settings dialog — `Difficulty` (GameDifficulty enum), `ExpertMode`, `TrialOfIron`,
  `TacticalMode` (RealTime/TurnBased) — all `[Persistent]` on `Global(Clone)/GameState`,
  all in TypeMap, written via the normal globals scalar path on Save. `DifficultyEditor.js`;
  modal id `difficultyDialog`. (Originally embedded in the Console tab; moved out per
  user request to its own Save-data modal, same pattern as `CurrencyEditor.js`/
  `currencyDialog`.) Trial of Iron was verified in the game in both directions on
  2026-10-05, and the game's Options → Difficulty showed the edited difficulty
  ("Testing in the game itself").
- **Inventory tab** (top-level menu item, a view like Edit Attributes — `inventoryView`,
  `SavedGame.views.INVENTORY`): a near-replica of the game's inventory screen. The
  selected character's portrait sits between their equipment slots, with quick items and
  weapon sets beneath; every party member's own 16-slot pack is a row on the right, and
  the shared stash sits below. Real icons and names throughout (see item catalog below).
  Interaction mirrors the game: click once to pick an item up, click again to drop it
  (Escape puts it back), double-click a *stackable* to open a quantity panel where 0
  removes the item. Each sidebar party member also gets a briefcase shortcut straight
  into their pack. Party money is deliberately not shown (it has its own editor).
  - **Editable**: packs (character↔character), the stash, the 11 equipment slots, the
    quick slots and the four weapon sets (2026-09-24 — the plumbing had been there
    since the first commit, untested; driving it found what follows).
  - **The game's weapon-set rules, decompiled**: `Equippable.CanUseSlot` — the main
    hand wants `PrimaryWeaponSlot`; the off hand wants `SecondaryWeaponSlot` **and not
    `BothPrimaryAndSecondarySlot`**, which is what makes a weapon two-handed (a
    two-hander carries all three flags, so testing the secondary flag alone lets it
    into the off-hand — the editor did). `UIInventoryGridItem.ItemTransferValid`: a
    two-hander shares its set with nothing (gui 1737, "Two-handed weapon requires two
    slots."). `WhyCantEquip`: `SoulboundToOther` when `EquipmentSoulbind.BoundGuid`
    (persisted on the item's own packet, = the owner's ObjectID) is set and is not the
    wearer — most soulbound items are weapons, but belts and hats too. On load
    `Equipment.Restored` → `EquipAllItems` rebuilds the held weapons from
    `WeaponSetsSerialized` + `SelectedWeaponSetSerialized`, so editing the active set
    needs nothing more.
  - **The UI checks a swap both ways**: dropping onto an occupied slot sends the
    occupant back where the held item came from, so `displacedRejection` checks it
    there too (Kana's pistol dropped on Aloth's sceptre would hand the sceptre to Kana).
    `SavedGameOpener` ships `boundTo` per item for this.
  - **`InventoryManager` judges the end state, not each step** (`refusal`): capacity
    (`MaxItems`), stack caps outside the infinite containers, slot flags (with a
    catalog), locked sets, the two-hander rule and soulbinding, then refuses with a
    `problem()` the handler returns verbatim. Step-by-step capacity checks used to
    fail real edits — the UI parks an item in its owner's pack between two slots, and
    two full packs trade one move at a time. `settleSlots` gives each item the tile it
    asked for once its occupant has left; before, trading a pack item for a quick item
    put the newcomer on uiSlot 4 of a four-slot bar.
  - **An oversized stash stack is split, as the game splits it**
    (`UIInventoryGridItem.TryMergeInto`): `MaxStackSize` moves as a *new* object
    (minted on Apply, like a catalog item) and the rest stays in the stash.
  - Verified in the game (2026-09-24): Aloth's soulbound sceptre and Bittercut traded
    sets (tooltips and his damage line agree), and the Watcher's fourth quick slot
    emptied and refilled with 5 of the stash's 155 lockpicks. `ingame_quick.py` builds
    that save; `quick_and_weapons.py` is the suite.
  - **Test fixture**: `src/test/resources/InventoryManagerTest/MobileObjects.save` is
    the prologue save plus Gyrd Háewanes Sténes (a real two-hander, spliced from a
    mid-game save) in Calisca's set II, soulbound to her. Elwyn holds a sword and a
    heater shield, Calisca a battle axe and a torch.
  - **Equipment validation** (three independent gates, all from decompiled ground truth):
    (1) the item's own `Equippable` per-slot flag from the catalog; (2) `RestrictedToClass`
    (e.g. Outworn Buckler is Paladin-only); (3) `Equipment.HasEquipmentSlot`, which is
    what makes slots vanish per character — **Grimoire exists only for `Class == Wizard`,
    Head only for `Race != Godlike`** (so every godlike, moon/fire/death/nature alike, has
    no head slot), **Pet only on the `Player` object**. `SavedGameOpener` derives this
    server-side from `CharacterStats.CharacterClass`/`CharacterRace` and ships an
    `unavailableSlots` list, so UI and save logic can't drift. Blocked slots render hatched
    and inert; an illegal drop explains itself ("X cannot be worn in the feet slot").
  - **Equip/unequip in the save**: worn gear is referenced only by UUID from the wearer's
    fixed `EquipmentSetSerialized`, so equipping = unlink from the pack + write the GUID
    into slot N (+ re-parent the item packet if it changed hands). Unequipping is the
    awkward direction — the pack has no entry, so `buildInventoryEntry` manufactures an
    `InventoryItem`, copying its TypePairs from an entry already in that save rather than
    hardcoding C# type strings. Dropping onto an occupied slot swaps, reusing the freed
    pack entry. **Slot GUIDs must be written as `UUID` objects, not strings** — a String
    through `Property.update` makes the serializer emit the wrong type and the save no
    longer deserializes.
  - `InventoryItem.BaseItem` for a manufactured entry is rebuilt as catalog-directory +
    the exact-case prefab name from the item packet's `ObjectName`. Only the file name
    matters: `GameResources.LoadPrefab` does
    `Path.GetFileNameWithoutExtension(path).ToLowerInvariant()` and ignores the directory
    entirely, which is why the catalog's lowercased bundle path is safe to use.
  - `save/InventoryManager.apply(List<Change>)`: a container is addressed by
    (character ObjectID, component). Per change — `stackSize<=0` removes the `ItemList`
    entry + `SerializedItemList` UUID + the item's own standalone packet; a different
    destination does a capacity-checked move then re-points the item packet's `Parent`
    at the new owner; `destSlot` places the item on a specific tile, falling back to the
    lowest free one like `BaseInventory.FirstFreeSlot`. Stack writes use the
    case-sensitive `findExact` helper from invariant #14, never `findProperty`.
  - `handlers/UpdateInventory.java` mirrors `UpdateParty.java`. `SavedGameOpener` emits a
    top-level `"inventory"` key: `{characters:[{guid,objectName,packComponent,isPlayer,
    pack,quickbar,equipment}], stash, icons}`, where `icons` is a deduped
    catalogKey→base64 PNG map (icons repeat heavily across a party).
  - `InventoryEditor.js` diffs the working copy against an open-time snapshot and sends
    only the minimal change set; the reply is folded in with `SavedGame.adopt` (invariant 12).
  - **Adding items that aren't in the save at all** (the "All items in the game" panel):
    `InventoryManager.addNewItem` mints a fresh UUID and manufactures the item's own
    top-level packet. `ObjectPersistencePacket.CreateObject` instantiates from
    `PrefabResource`, so the packet only needs `ObjectName` = `<Prefab>(Clone)`,
    `PrefabResource`, `Parent`, and *minimal* components — `InstanceID{Guid}` +
    `Persistence`. Everything else comes back as prefab defaults, which is exactly right
    for a new item. `SerializedItemList` is `[Persistent(ConversionType.GUIDLink)]`, which
    is why the standalone packet is mandatory rather than optional.
    `handlers/BrowseItems.java` pages the catalog (search + category + offset) so the
    icons don't have to cross the JCEF bridge all at once.
    **Never insert the template's own `Property` objects into the new packet.** Doing so
    aliased the two: writing the new GUID into the copied `InstanceID` silently rewrote
    the *template item's* GUID too, and the game then dropped both items on load with no
    error. `copyComponent` deep-copies instead. Invariant to check after any minting
    work: every packet's `InstanceID.Guid` must equal its own `ObjectID` (a unit test now
    asserts this across the whole save, and it only surfaced in-game).
  - **Save dialog**: the typed name only becomes the display name in `saveinfo.xml`; on
    disk the game insists on `<sessionID> <gameID> <SceneTitle>.savegame`, which is why
    the file is hard to find. `ChangesSaver.previewSaveFileName` computes it without
    creating anything and `handlers/SaveTarget.java` serves it plus the destination
    folder. To change the folder it opens **CEF's native Save dialog** (this JCEF has no
    folder mode) and keeps the parent of whatever is picked. **Never open a modal Swing
    dialog from a handler**: the `JFileChooser` that used to do this disabled the CEF
    frame and could open behind it, so the editor froze and was closed — the crash
    report that started the 2026-09-14 audit.
  - **Gotcha — the bundled JCEF is Chrome 45** (`navigator.userAgent` says
    `Chrome/45.0.2454.101`), older than the "pre-57" these notes used to claim. No CSS
    Grid, no flexbox `gap` (Chrome 84), no unprefixed `filter` (Chrome 53), no
    `position: sticky`, no CSS custom properties. All of them fail *silently*: `gap`
    is not even a recognised property here, so seven rules across the party dialog and
    console tab were laying out completely flush until someone measured. Use
    `inline-block` + margins, or `> * + * { margin-left: … }` for a flex row; plain
    `display:flex` itself is fine. Ask the browser rather than a version table —
    `el.style.setProperty(prop, value)` then reading it back tells you in one call
    whether a declaration survives, and a sweep of every declaration in `ui.css` takes
    seconds. Also, a click handler that re-renders destroys the tile before its
    `dblclick` can fire, so single clicks are held ~220ms and cancelled when a
    double-click arrives.
  - **Light-mode overrides lose to a later rule of equal specificity.**
    `body.theme-light .abl-icon` (0,2,1) sits after `body.theme-light .abl-icon-empty`
    (0,2,1) and silently won, so the empty tile kept the dark-theme fill. Bootstrap
    plays the same trick from the other side: its `button.close { padding: 0 }` (0,1,1)
    beats a bare `.pm-close`, which is why the dialog's close button was an 11px hit
    target — and it was every dialog, not just that one, so `.modal button.close`
    now sets a real hit target for all of them. When a rule looks like it should apply and doesn't, compare specificity AND
    source order before changing the colour.
  - **Light mode is `body.theme-light`, not `.light-theme`.** An entire block of
    light-mode rules was written against the wrong class and silently did nothing until
    the theme was actually toggled and inspected. Item tiles deliberately keep their DARK
    background in light mode — the white/gold/blue quality washes are only legible
    against dark. Modals keep the dark chrome in both themes, so their text must not get
    a light-mode override.
  - **Weapon sets III and IV are locked unless earned.** `CharacterStats.MaxWeaponSets`
    is `2 + BonusWeaponSets`; the extra sets come from a talent. `SavedGameOpener` ships
    `maxWeaponSets` per character and the UI hatches out the rest.
  - The `.sidebar` is `position: fixed` with its own scroll, so the collapse handle has
    to be `position: fixed` too — an absolutely-positioned child rides the scroll
    instead of staying centred.
  - The UI is loaded from `src/ui/` **on disk**, not from the jar (`EternityKeeper.java`
    resolves `src/ui/index.html`), so HTML/CSS/JS changes only need an app restart, not
    a rebuild — but a stale jar will still ship old UI to anyone else. To reload it
    over CDP, navigate to **`file:///src/ui/index.html`** — the scheme handler's own
    URL. `location.reload()` resolves relative to nothing and lands on a page with no
    `window.get*`/`save*` bridge functions: it renders, and every call into Java
    silently does nothing.
  - **Bulk sell and tidy** (`invSellMode`/`invTidy`): sell mode turns the packs and
    the stash into a selection and credits `saveData.currency` with what a store
    would pay; "Tidy stacks" repacks each container to the item's own cap. Prices
    are the game's: `Item.GetDefaultSellValue()` = `floor(GetValue() * 0.2)`, or
    `floor(GetValue())` when `FullValueSell`, and `Equippable.GetValue()` adds each
    mod's `Cost × EconomyManager.ItemModCostMultiplier` (=1000), doubled for a
    two-hander. The extractor sums that into `catalog.json` (`value`,
    `fullValueSell`); `ItemCatalog.sellValue` applies the rest. **"Select junk" is
    deliberately narrow**: unenchanted worn gear only (filter WEAPONS/ARMOR/CLOTHING,
    `value < 1000`, not unique/soulbound, no Grimoire or Pet slot). Price alone
    sweeps up every potion, and MISC hides grimoires, pets, hides and lockpicks
    among the lore books — and a grimoire is filed under WEAPONS at 100cp, so only
    the *slot* distinguishes it. Nothing sells worn gear or quick items. A sale
    is staged like any other change: the items leave the save and the money
    goes into the purse when Apply succeeds, as one step (invariant 21).
  - **The game's own stash panel always filters to exactly one category**, and
    remembers which. A stash that reads empty in-game after an edit is almost
    certainly showing a category whose items were the ones removed — check the other
    category buttons before believing the save is wrong.
- **Stronghold tab** (top-level menu item, `strongholdView`,
  `SavedGame.views.STRONGHOLD`): Caed Nua laid out like the game's own stronghold
  screen — the upgrade list down the middle with icon, name, description, what it
  is worth and what it needs, and a rail of Prestige/Security gauges plus the
  editable scalars on the right.
  - **Gated on `SerializedIsActivated`.** A save where the player has not taken the
    keep shows an explanation instead of an editor, and granting it is deliberately
    NOT offered (see the structure map above).
  - **Building an upgrade is three edits, not one**: append the enum to
    `m_upgradesBuilt`, apply its Prestige/Security adjustments, and set its
    `UpgradeCompletedGlobalVariableName`. `StrongholdManager` mirrors
    `CompleteBuildingUpgrade`/`DestroyUpgrade` exactly, including the `HasUpgrade()`
    guard — without it the adjustments get paid twice for one upgrade.
  - Demolishing **cascades**: pulling down an upgrade takes everything built on top
    of it, since the game's tree never allows an orphan. The UI stages the whole
    cascade and the gauges project the result before Apply.
  - `save/StrongholdCatalog` (reads `stronghold.json`), `save/StrongholdManager`,
    `handlers/UpdateStronghold`, `ui/js/StrongholdEditor.js`,
    `tools/gamedata/stronghold.py`.
  - **An Apply goes in whole or not at all (2026-10-27).** A change the save
    cannot take throws a `WriteRefusedException` naming it ("No prisoner 'x'
    is in this save's stronghold to release. Nothing was changed."): an edit
    on a keep that is not the player's yet, an upgrade the game never
    configured, a missing list or variable, nobody of that name to dismiss or
    release, and (in the handler) an unknown change kind. These used to be
    logged and skipped while the rest of the batch was written and reported
    as a success. A change that is already so (built, demolished, the same
    number) is still a quiet no-op, and a batch of nothing but those still
    returns `false`. `stronghold_people.py` sends a real refused batch through
    the tab and checks the draft is kept.
  - **Hirelings can be dismissed and prisoners released (2026-09-21)**, as the
    game's own buttons do it: `StrongholdManager.Change.dismissHireling(global)`
    mirrors `DismissHireling` (entry out, `HiredGlobalVariableName` → 0, the
    adjustments off only if `Paid`), `releasePrisoner(global)` mirrors
    `RemovePrisoner` (global → 0, entry out, visitors untouched). Both are keyed
    on the global, which is what the opener hands out as `key` in
    `stronghold.hirelings` / `stronghold.prisoners` (lists now, not counts).
    **Taking someone on is deliberately not offered**: they arrive through
    conversations and visitors that change the world too. Verified in the game:
    Prestige/Security down by exactly the knight's +4/+2, the hirelings badge
    3/8, the knight offered for hire again, and Kestorik gone from Actions.
  - **Names come from `save/GameText`**, which reads the install's English
    `data/localized/en/text/game/<table>.stringtable` (one per
    `DatabaseString.StringTableType`, lower-cased) lazily with StAX, all or
    nothing per table; `TestHarness` pins it with `GameText.useNoText()`. The
    stronghold catalog carries each hireling's prefab name
    (`NPC_Goldpact_Knight`; `HirelingPrefab` resolves in `resources.assets`,
    never in the old bundle) but no display name — the save's
    `SerializedNameId` is the prefab's `DisplayName.StringID`. Without an install
    a name falls back to the global ("b_warden_wilds_hireling" → "Warden
    Wilds"). `TestEnvironment` now copies the English text folder (3.3 MB) into
    the test env's `poe`.
  - **Test fixture**: `src/test/resources/StrongholdManagerTest/MobileObjects.save`
    is the prologue save with the Odlewnia keep spliced in (activated, 24
    upgrades, four hirelings, Kestorik, their globals at 1) and the archer made
    unpaid (Prestige 38, Security 34 to match). The original prologue fixture
    stays unactivated because older tests depend on that.
  - Gotcha — a new component's `transition` comes from `$.extend(X.prototype,
    Renderer.prototype)` at the bottom of the file, NOT from a hand-written
    `X.prototype.transition`. And its **menu item must be bound in `SavedGame`'s
    save-opened path**, not in the component's own `init()`: `Editor.js` calls
    `.off()` on those menu items whenever the save list is showing, so a handler
    attached once at startup does not survive going back to the list.
- **Abilities and talents editor** (`abilitiesView`, character menu): what a
  character knows, plus a browser for everything they could learn. The two
  halves are stored quite differently and the editor hides that:
  - An **ability/spell is its own top-level object** parented to the character
    (`Parent` = the owner's *ObjectName*, not its ObjectID), with exactly three
    components — the ability class, `InstanceID`, `Persistence` — and
    `Owner` = the owner's ObjectID as a GUIDLink. `CharacterStats.Restored()`
    rebuilds `m_abilities` from `GetComponentsInChildren<GenericAbility>()`, so
    an ability exists iff its object does. Minting one is the item-minting
    pattern exactly (deep-copy components, `InstanceID.Guid == ObjectID`).
  - A **talent is only its prefab name** in `CharacterStats.m_serializedTalents`
    (a plain `List<string>`). But `GenericTalent.Purchase` — the level-up path —
    *also* instantiates the abilities the talent grants, and it is never re-run
    on load, so adding a talent must mint those objects too, stamped
    `EffectType = Talent` (`InstantiateAbility` overrides the prefab's own
    value, which reads `Ability`). `ModExistingAbility` talents are the easy
    case: `Restored()` walks `m_talents` and reapplies their `AbilityMod`s
    itself (mods are NOT persisted), so the name alone suffices. Skill bonuses
    are baked into `<Skill>Bonus` at purchase, so `AbilityManager` applies them
    — but note `Restored()` re-derives every `<Skill>Bonus` from the applied
    status effects on load, so a value written straight into that field does not
    survive. The base game ships zero such talents, so nothing depends on it
    today.
  - The **component class is not interchangeable**: ~60 classes appear
    (`GenericAbility`, `GenericSpell`, `Chant`, `Carnage`, `WeaponFocus`…), the
    packet's TypeString must be the real one, and a template of the same class
    is preferred when minting so class-only fields survive. Spells additionally
    need `IsFree=false` and `NeedsGrimoire` (wizards only) — added rather than
    assigned, since the template may be a non-spell; a *new* dictionary entry
    must copy a sibling's `TypePair`, because a hand-made one serializes with an
    empty C# type name and the save no longer reads back.
  - **`AbilityProgressionTable` drives what is offered.** One table per class,
    one shared `talents` table, a `racial` table, and one per story companion.
    Rows are OR-ed `RequirementSets`: a set naming no class opens the row to
    everyone, so a row is class-restricted only when *every* set names one —
    81 of the 140 shared talents are, and ignoring that offers a wizard the
    barbarian's Accurate Carnage. Subrace gates the racial table; a few Watcher
    abilities are player-only. Companion tables are named after the companion,
    not the object (Durance's object is `Companion_GGP`, his table is
    `durance`), and the expansion companions carry their pack prefix
    (`px1_caroc`, `px1_zahua`, `px2_maneha`) — `SavedGameOpener` resolves this
    through `CompanionRegistry` rather than the object name.
  - `save/AbilityManager` (add/remove, both kinds), `save/AbilityCatalog`
    (`abilities.json` + `progression.json`), `handlers/BrowseAbilities` (paged
    catalog + an icons-by-key mode), `handlers/UpdateAbilities`,
    `ui/js/AbilityEditor.js`.
  - **Gotcha — the opener's reply has a size ceiling.** Shipping ability icons
    inside it took a full-party save from 4.6 MB to 6.5 MB and the JCEF query
    silently never came back: no error, no callback, just a spinner forever.
    Icons are now requested by key for whatever is on screen. Anything new that
    wants to ride along in `SavedGameOpener` must be counted in bytes first.
- **Grimoire tab** (top-level menu item, `grimoireView`,
  `SavedGame.views.GRIMOIRE`): a wizard's spellbook laid out the way the game
  lays it out — eight chapters, four slots to a chapter, with the wizard spell
  list underneath to add from.
  - **A grimoire is an item, so this lists books rather than people.**
    `Grimoire.Find()` reads the component off whatever sits in the wearer's
    Grimoire equipment slot, so the spells belong to the book: one in the stash
    is as editable as one being carried, and a book keeps its spells when it
    changes hands. A real mid-game save holds **dozens** — the test save has 32,
    almost all looted enemy books sitting in the stash — so the rail sorts
    equipped first, then carried, then stash, and scrolls.
  - **Only `SerializedSpellNames` matters.** The class declares two
    `[Persistent]` members describing the same thing and only one survives:
    `SerializedSpells` is a `SpellChapter[8]` of `GenericSpell` object
    references and **every chapter comes back all-null** in every real save
    (measured). The payload is `SerializedSpellNames`, a flat
    `List<string>` of spell prefab names — no cross-references, no UUIDs, no
    parallel structure, which makes it as easy to edit as `m_upgradesBuilt`.
    Its setter rebuilds the chapters from scratch, so it must be the one the
    game applies last; that grimoires load correctly at all is the proof.
  - **Four to a level, and the game enforces it silently.** The setter resolves
    each name with `GameResources.LoadPrefab`, files it under its own
    `SpellLevel`, and drops anything past the fourth at that level without a
    word — `Grimoire.MaxSpellLevel = 8`, `MaxSpellsPerLevel = 4`.
    `GrimoireManager.plan()` applies exactly that arithmetic (chapter order,
    four to a chapter, levels 1-8 only, each prefab once since LoadPrefab
    lower-cases what it looks up) so the editor never shows a spell the next
    load would discard.
  - A spell's level is resolved from `AbilityCatalog` in the handler rather
    than trusted from the client, since it decides which chapter the spell
    lands in. The browser pages the 114 wizard spells via a new `spellClass`
    filter on `BrowseAbilities` — only wizards cast out of a grimoire; every
    other caster knows their spells outright.
  - The whole intended contents are sent per book rather than a diff: a flat
    list of strings has nothing to keep in sync, so replacing it wholesale is
    both simpler and safer than working out what moved.
  - `save/GrimoireManager`, `handlers/UpdateGrimoires`,
    `ui/js/GrimoireEditor.js`; the opener emits a top-level `"grimoires"` key
    of `{guid, prefab, holder, spells:[{prefab, key, displayName, spellLevel}]}`.
    Display name and icon are deliberately **not** repeated there — the book is
    already in the inventory payload with both, under the same GUID.
  - **The inventory payload's containers are `{component, maxItems, items}`,
    not bare arrays** — including the stash. Treating one as an array threw
    inside the render chain and left the save view half-drawn with no character
    list, which looks exactly like a save that failed to open.
  - Verified in the game: removing one level-8 spell from Aloth's grimoire in
    the editor and loading the written save shows chapters I-VII full at four
    each and VIII holding one, on the game's own grimoire screen.
  - **Test fixture**: `src/test/resources/GrimoireManagerTest/MobileObjects.save`
    is the ordinary prologue save with a real grimoire spliced onto
    `Player_Elwyn` — the prologue party has no wizard, and a Grimoire component
    cannot be manufactured without a template to copy its TypePairs from.
    Splicing a packet between saves works fine (each packet is serialized with
    its own name and type header), but **`InstanceID.Guid` is a `java.util.UUID`
    while `ObjectID` and `Parent` are Strings** — writing a String into the
    Guid makes the serializer emit the wrong element type and the packet no
    longer deserializes, exactly like the equipment slot GUIDs. Symptom: the
    reader takes ASCII bytes for a name-cache index and throws
    `IndexOutOfBoundsException` with a huge index.
- **Vendors tab** (top-level menu item, `vendorsView`, `SavedGame.views.VENDORS`):
  every store in the save with what it holds, and taking out what the user
  picks. `save/VendorStock` reads, `save/VendorManager` writes,
  `handlers/GetVendors` (read-only, workers) and `handlers/UpdateVendors` (an
  Apply) carry it; `ui/js/VendorEditor.js`.
  - **Stock lives in the area files, and they are safe to rewrite.** A store is
    any object with a `Store` component; its stock is that component's
    `ItemList` + `SerializedItemList`, each GUID also the ObjectID of the
    item's own packet in the same file, parented to the store (`Parent` = the
    store's ObjectName). All 190 packet files of a real save (111 MB)
    round-trip byte for byte through `PacketDeserializer` and
    `DeserializedPackets.reserialize`, so an area file is written like the
    world state: `replace`, only for files that changed. The UI suite unzips
    the written save and checks exactly the edited `.lvl` files differ.
  - **`\x05Store` finds them without reading them.** A string value is a 7-bit
    length and UTF-8 (`BinaryWriter.writeString`), so a Store component's
    TypeString is those six bytes; on a real save the scan picks exactly the
    40 of 190 files that hold a store. Read in parallel: 0.8 s cold, 0.35 s
    warm, where every area file took 9.9 s. The list is only read when the tab
    is shown (`visible` in its state, set by `SavedGame`'s view switch; the
    editor's save-view transition passes `visible: false`, because
    `Renderer.transition` merges state and a stale flag would read the areas
    of a save nobody asked about).
  - **`Original` is the game's own line.** Set only for a store's initial stock
    (`BaseInventory.Restored` → `AddItem(original: true)`); what the player sold
    is not, and neither is restock, which `Store.RegenerateItems` destroys —
    every copy of each prefab in its `RegenerationItemTable` — and re-rolls
    every `RegenerationHours` (12). Nothing is picked by default; "Select what
    you sold, everywhere" stages every non-original item at every store the
    party has opened (`m_firstTime` false), for review before Apply. Original
    stock carries a gold corner and is never picked for you.
  - **A damaged packet file read short without an error**: `PacketDeserializer`
    returned 1 packet of 6 from a truncated area file and said nothing, and
    writing that back would drop the rest. The check began here, in
    `VendorStock.readWhole`; since 2026-09-25 it is the reader's own, for every
    writer (invariant 17). `readWhole` only turns a refused read into
    "unreadable" for the list, and `VendorManager` refuses in the same words as
    every other writer.
  - **An entry is its place, not its GUID.** Nine GUIDs in the test save's
    stronghold merchant are shared by eighteen entries, each a *different*
    item (a Dyrwoodan outfit and a monk's outfit under one), none with a
    packet. Picking or removing by GUID marked namesakes and could take the
    wrong entry, so the page sends `{index, guid}` and the manager removes by
    index, highest first, refusing when the GUID at that index differs. Found
    only by building the in-game save: the UI said 675 picked for 693 tiles.
  - Everything is planned before anything is written: a request naming one
    entry no longer where it was (a stale list) is refused whole, no file touched. The
    file name comes from the page, so only `MobileObjects.save` or a plain
    `*.lvl` name is accepted (`VendorStock.isPacketFile`).
  - Remaining entries are renumbered `uiSlot` 0..n-1 in list order — every
    store in the real saves reads that way (`Sort`, `CompressSlots`). An item's
    packet goes only when no list in the file still names its GUID. 280
    entries in a real save already point at no packet (242 of the stronghold
    merchant's, 38 of Crucible Keep's, one GUID in both); the game keeps such an
    entry as the bare prefab (`SerializedItemList`'s setter), so removing one is
    just the two list entries.
  - Heodan's store (world state, prologue) has **no `BaseItem` path** on any
    entry — never restored — so the prefab comes from the item packet's
    ObjectName. Several NPCs carry a `Store` component their dialogue never
    opens (`NPC_Winfrith` beside `Store_Winfrith`); `m_firstTime` still true
    tells them apart, and the list hides never-opened stores unless asked.
  - Prices are the store's: `Item.GetBuyValue` = `ceil(value × sellMultiplier)`,
    with `Store.Restored`'s `/10` for multipliers stored ten times over. Names
    are prettified from the object and scene names ("Store_Inn_Black_Hound" →
    "Black Hound (inn)", "AR_0003_Dyrford_Store" → "Dyrford Store"): the
    game's `Vendor.StoreName` and map names live in scene data, not the save.
  - Icons come from `browseItems` with `iconKeys` (new mode, like the ability
    browser's) for the store on screen; the list itself is ~600 KB for a
    mid-game save and carries none.
  - A click redraws only its tile (the stronghold merchant can hold 800);
    `refresh()` redraws everything but the grid.
  - **Verified in the game (2026-09-25)**: the General Goods Merchant's 693
    entries (everything the party sold him) taken out, the written save loaded,
    the party walked into the Great Hall — the rewritten `.lvl` — and his store
    screen read "No items." in every category. He trades in the hall's east
    room (the Treasury landmark), not beside the Steward. `ingame_vendors.py`
    builds that save.
  - **What it buys is order, not space**: every sold item out of every store
    the party traded with (2,325 items, 39 stores) rewrites 39 area files in
    2.8 s and makes a 9.9 MB save 3.4% smaller. The roadmap's "smaller,
    faster saves" premise did not survive measuring.
  - **Test fixture**: `src/test/resources/VendorsTest/` — a prologue world state
    (Heodan's store) plus four real area files from a mid-game save: the
    Artificer's Hall (an empty NPC store and the shop, two sold traps), the
    Fishery (two uniques, never opened), Caed Nua's chapel (a priest who is a
    character with an Inventory as well as a store) and a hideout with chests
    and no store.
- **Heal, level up and resupply** (Character menu, `partyCareDialog`,
  `ui/js/PartyCare.js`, 2026-09-29): the roadmap's "bulk party operations",
  each done the way the game does it, all riding Save like the currency.
  - **Healing is the game's own flag, not a number the editor works out.**
    `Health.m_needs_current_values` is `[Persistent]`, `true` on a new object,
    and read in exactly one place: `Health.Update()`, which sets current
    health and stamina to `MaxHealth`/`MaxStamina` and clears it. Setting it
    in the save means the first frame after the load refills the character to
    the maximum the game computes — `(class base + (ScaledLevel − 1) ×
    per-level) × difficulty × class multiplier × Constitution`, then
    `MaxStaminaMultiplier` and `StaminaBonus` for stamina, with every active
    effect in play. The editor could not reproduce that and does not try.
    Only the flag travels (`health` per character in the opener's reply):
    Save writes back whatever it is sent, and the rest of `Health` is the
    game's. It marks the **active party** only (`PartyMemberAI.IsActiveInParty`)
    — whom `Scripts.HealParty` and resting act on.
  - **A 2015-era save has no flag**: the ChangesSaverTest fixture's `Health`
    lacks the field, so the game keeps its default (`true`) and refills on
    load by itself. The opener ships `health` only where the save holds the
    flag, and the dialog says the save has none to set.
  - Loading ignores a stored `CurrentHealth`/`CurrentStamina` of exactly 0
    (the setters return early while `GameState.IsLoading`), so a knocked-out
    character loads with the field default of 100 and the flag fills from
    there.
  - **Not a rest**: resting also clears fatigue, resets `m_spellCastCount`
    (int[8]) and refreshes per-rest abilities (`CharacterStats.
    HandleGameOnResting`). Fatigue is applied as an affliction status effect,
    so writing `m_CurrentFatigueLevel` alone would leave its penalties in
    place; none of the three is offered.
  - **Levelling compares experience, not level.** `Scripts.AddExperience
    ToLevel` sets `Experience = ExperienceNeededForLevel(L)` (`500·L·(L−1)`)
    for anyone whose *Level* is below L, which lowers a character with
    level-ups still to take: Keira, a hired adventurer in the Caed Nua test
    save, is level 1 with 86,485 experience, and the game's command at level
    10 would cut her to 45,000. The editor raises only those whose experience
    is short, and the console's `AddExperienceToLevel` now calls the same
    code. It covers **everyone in the save**: a companion waiting at Caed Nua
    keeps experience on their own packet (`StoredCharacterInfo.Experience` is
    `PersistenceManager.GetSavedValue(GUID, CharacterStats, "Experience")`),
    which is where `Stronghold.AddExperience` adds theirs.
    `PlayerLevelCap` is 12 + 2 per White March part; the `Experience` setter
    clamps to it on load, and the dialog says so past level 12.
  - **Camping supplies are `PlayerInventory.campingSupplies`**, a
    `[Persistent]` int. `CampingSupplies.StackMaximum`: Story Time 99, Easy 6,
    Normal 4, Hard and Path of the Damned 2, anything else 1. The property
    clamps only when the count changes (a camp, a purchase, a difficulty
    change), so the dialog caps the refill itself, from the live difficulty.
  - The console's `HealParty` is an editor command now (it was in the
    in-game-only reference), sharing `PartyCare.heal`.
  - Tests: `ChangesSaverTest.aHealAndRefilledCampingSuppliesAreWritten` (the
    2015 fixture's world state swapped for a current one, since it predates
    the flag), `SavedGameOpenerTest.theOpenerShipsWhatHealingAndResupplyingNeed`,
    two merge cases in `SaveMergeTest.js`, and `party_care.py` (38 checks).
    `ingame_care.py` builds the in-game test save.
  - **Verified in the game (2026-09-29)**: save 4 (Słońce w Cieniu) loaded as
    it was and again after Heal the party and Refill. Before: the Watcher at
    Health 1055/1260, Sagani 1230/1242, Hiravias 969/994, Edér 1634/1638,
    three camping supplies. After: 1260/1260, 1242/1242, 994/994, 1638/1638
    and four — maximums the editor never computed. Both loads completed with
    no exception in Player.log. `ingame_care.py` builds the save.
- **Find anything** (the box at the top of the character list, Ctrl+F;
  `ui/js/Finder.js` for the box, `ui/js/SaveFind.js` for the matching,
  2026-10-01): one search over the open save — characters, every item
  wherever it is (packs, quick slots, worn, weapon sets, the stash),
  abilities and talents, and the global variables — whose results open the
  right tab on the right thing: a character's sheet; the Inventory tab with
  the holder on the doll and the tile pulsing (the stash's category and search
  cleared if they hid it); the Abilities tab with the row picked out; the
  Globals table filtered to the name.
  - **In the sidebar, not the navbar.** Measured: the navbar's menus end at
    x=995 at every width and the theme toggle sits at the right edge, leaving
    136px at 1,280 and nothing at 1,100, where the toggle itself sat 20px over
    "Console" until it learned to show only its icon below 1,140px
    (`responsive.py` checks it at every size). The sidebar is 250px at every
    size. The results are a
    fixed panel outside the sidebar, which clips anything wider than itself.
  - **Matching ignores case and accents.** `SaveFind.fold` is NFD with the
    accents stripped, plus the letters NFD leaves whole (ł, ø, æ, œ, ß, đ, ı),
    so the Polish save's "Niezłomny" is found as "niezlomny"; it keeps a map
    back to the original letters, so the match is bolded in the name as shown.
    Ranking: the whole name, its start, the start of a word in it (after a
    non-letter, or at a capital after a small letter, which is how the
    globals spell their words), then anywhere; several words match in any
    order. Groups keep their first 6/8/6/8 and say "8 of 25".
  - **Items come from the Inventory tab's working copy**
    (`InventoryEditor.everything`), so an item moved but not yet applied is
    found where it now is; `SaveFind.itemsOf` reads the opener's payload when
    there is no working copy.
  - **The index is dropped whenever the list closes** and built again by the
    next search. Building it only on the box's focus event left a stale index
    in the suite: a window in the background does not always get focus
    events, and nothing can be edited without leaving the box anyway.
  - **A highlight has to be part of building the row, not a class added
    afterwards.** The Abilities tab rebuilds every row when its icons arrive
    (`requestIcons` → `renderCharacter`), which wiped the first version's
    mark at once. Both tabs keep `revealed = {key, until}` and their builders
    apply it; each timer clears only its own mark, since the previous reveal's
    2.6 s timer fired during the next one and took its highlight too.
  - Not searched: vendors' stock (read from the area files only when the
    Vendors tab opens; an empty result says so), grimoire spells, raw stats.
    A localized save stores companions under their translated names —
    Durance is "Niezłomny" in the Polish test save — and the English name is
    not in the payload, so "durance" finds the Endurance potions instead.
  - Tests: `src/test/js/SaveFindTest.js` (15, node; CI runs it beside the
    merge tests) and `find.py` (33 checks, the shaudit pass over the results
    in both themes among them).
- **Compare saves** (*Compare* on the save list; `save/SaveDiff`,
  `save/SaveComparison`, `handlers/CompareSaves`, `ui/js/SaveCompare.js` for
  the pairing and formatting, `ui/js/CompareSaves.js` for the dialog,
  2026-10-01): two saves of the list side by side, in the editor's own words.
  - **Two layers.** `SaveDiff` compares two packet files object by object,
    matched by ObjectID, as the property trees the files hold (not the mirror
    objects): each object's own fields, then every variable of every
    component. A dictionary is compared by key — the game's Hashtables list
    entries in the table's own order — and a binary blob (quest progress,
    conversations read) by content. `leaves()` names where inside two values
    they differ ("[0].uiSlot", ".Capacity", "[b_aloth_joined]").
    `SaveComparison` turns that into sections, each of which **claims** the
    differences it explains; what no section claims is listed under
    "Everything else", grouped by object. `counts` says how many differences
    there were, how many are shown and how many left out, and every test
    asserts the first is the sum of the other two.
  - **Measured on two real saves fifty minutes of play apart** (6,953
    objects): 3,789 differences, 3,230 of them `LevelName`/`Location`/
    `Rotation` — the party's 1,081 belongings riding along into another area
    — and 238 timers and counters (`*Timer*`, `Faction.CurrentTeamInstance`,
    `m_availableStatusEffectID`, the autosave number...). Those are counted
    and named in `leftOut`, not listed. The diff itself takes 120 ms; the
    whole comparison 1.8 s, 1.2 of it reading the two world states side by
    side. Comparing ~190 area-file pairs byte for byte took 2.2 s on one
    thread and 0.26 s in parallel.
  - **What the game makes afresh is no change**: it renumbers its clones on
    every load ("Companion_Sagani(Clone)_5" is "_4" next time, and every
    ability and pet names her by the new number — a parent that resolves to
    the same object either way is no move); it re-sorts an item's mods; a
    .NET list's `Capacity` grows by doubling; and it gives items new IDs (the
    stash's stacks, and boots put in the stash came out of it under a new
    one). Items are therefore paired lost-with-gained by the same thing in
    the same place (no change), the same thing in another quantity
    (Quantity), then the same thing anywhere (Moved).
  - Sections: the save (saveinfo's area, chapter, name, time played;
    `GameState`; the game clock as "day N, hh:mm" counted from
    `WorldTime.AdventureStart` with the change beside it; money; camping
    supplies; the achievements flag), characters (everyone with
    `CharacterStats`, plus anyone else keeping `PartyMemberStats` — Sagani's
    fox is "Arctic Fox, animal companion"; skills shown as ranks), items
    (Gained, Lost, Moved, Quantity, Enchantments, Changed, Rearranged; at
    most 200 rows a group, icons only for rows shown), abilities and talents
    (an ability handed over with its item is lost by one and learned by the
    other, its `Parent`/`Owner` explained), grimoires, the stronghold
    (upgrades by catalog name, hirelings and prisoners by the opener's own
    `hirelings()`/`prisoners()`), global variables by name, quests and
    reputation, areas (files compared whole, a store's stock counted), and
    "Everything else" (at most 400 rows).
  - **Quests by their journal titles.** `QuestManager.Timestamps` keys each
    quest the party has met by its file ("data/quests/critical_path/act_4/
    cp_qst_confront_lka.quest") with when each objective was reached; the
    game's text for it is `data/localized/en/text/quests/<same path>.
    stringtable`, entry 0 the title, an objective's ID its entry
    (`GameText.quest`). The QuestTrackers blob keys the same files and says
    finished, failed or under way (`QuestTrackerBlob.quests()`,
    `Tracker.eventsFired()`). "Memories of the Ancients: under way, Enter Sun
    in Shadow. Search for Thaos." `SerializedActiveQuests` is a .NET blob
    nothing reads; when it alone changed the row says so.
  - `ReputationManager.PlayerDisposition.m_dispositions` is indexed by
    `Disposition.Axis`: Benevolent, Cruel, Clever, Stoic, Aggressive,
    Diplomatic, Passionate, Rational, Honest, Deceptive.
  - **Which save it offers.** The save picked and, before it, the one it was
    made from: the editor's Save keeps the timestamp of the save it was made
    from, so that is the latest save of the same playthrough (the folder
    name's session) at or before the picked one's time. Copies of one save
    share that timestamp, so among them the one whose name the picked save's
    extends wins ("Caed Nua (edited)" came from "Caed Nua"; an unnamed save
    starts every name). Failing that, the next save of the playthrough, else
    a neighbour in the list.
  - `CompareSaves` accepts only folders directly inside the save list's own
    unpacked folder: the paths come from the page. It runs on the workers
    (`CatalogQuery`); a different playthrough is compared but flagged.
  - Tests: `SaveDiffTest` (10), `SaveComparisonTest` (22: every section
    through the manager that makes that change in the editor, the clock,
    renumbering, re-made items, `Capacity`, enchantments, a damaged save),
    `CompareSavesTest` (2), `SaveCompareTest.js` (11, node, in CI) and
    `compare.py` (27 checks). Its first end-to-end run is how invariant 19
    was found.
- **A main character from another playthrough** (Import character over the
  Watcher; `save/CharacterImporter`, 2026-10-25): the use a player asked for
  is a "chapter select" — a new Watcher dropped into a save already at Caed
  Nua. The import replaces the main character and everything they own and
  takes their place; the world (quests, reputation, companions, money) is the
  save's.
  - **The playthrough's ID lives on the main character**: the `Player`
    component's `SessionID`. The game names autosaves by it and groups the
    load list by it. The import copied the whole object, so the save joined
    the other playthrough: in the game its autosave was written under that
    ID, in that playthrough's autosave slots (which rotate through three), and
    the load list put it in that group. The import now keeps the save's own
    `SessionID`, and Save writes the main character's name into
    `saveinfo.xml`'s `PlayerName` (it kept the old Watcher's until the game
    next saved).
  - **The party's belongings hang off the main character too**: the
    `StashInventory`, `QuestInventory` and `CraftingInventory` components
    and the items they list (parented to `Player_*`), and, on
    `PlayerInventory`, the money (`currencyTotalValue`) and the camping
    supplies (`campingSupplies`; the component's other variables,
    `ItemList`, `SerializedItemList` and `MaxItems`, are the Watcher's own
    pack). The overwrite took out
    everything the old Watcher owned, so it emptied the save's stash, quest
    items and crafting materials and brought the other playthrough's, with
    its money (found by review, 2026-10-27). Now the save's three
    components move to the newcomer whole, their items stay (re-parented to
    the newcomer's `ObjectName`), the save's money and camping supplies are
    kept, and what the file's Watcher had in those containers is not
    brought. The camping supplies were found in the game: the first fixed
    save showed the prologue's 0 where the save had 4. The move runs
    after the ID remap, which is for the file's objects only. A packet is
    written with the reference IDs it was read with, so a container
    carrying a shared reference cannot move to another packet: none of 13
    saves measured has one, and a save that does is refused
    (`CharacterImporter.movable`). Tests:
    `aWatcherFromAnotherPlaythroughLeavesTheSavesStashQuestItemsAndMoney`;
    `ingame_swap.py` checks the stash, the money and the camping supplies
    (226 items, 210,753 copper, 4 supplies kept).
  - Verified in the game (2026-10-27), the prologue Watcher over the Caed
    Nua save again: the newcomer's sheet with 210,753 copper and 4 camping
    supplies; the stash holds the Caed Nua books and weapons
    ("Ninagauth's Black Pages" first), about 36 quest items (keys, letters,
    maps) and the late-game crafting stacks (168, 201). `Player.log` holds
    no `already exists`, and only the `StatusEffect.ClearEffect` exceptions
    of the scene unloading on quit, which the untouched save logs too (more
    of them; loaded the same way as a baseline).
  - Verified in the game (`tools/ui-tests/ingame_swap.py`): the prologue
    game's level 1 Fire Godlike Monk over the Caed Nua game's level 15
    paladin. The sheet, portrait, pack and party bar are the newcomer's, the
    companions the save's; the save sits in the Caed Nua group and its
    autosave is written under the Caed Nua ID. `Player.log` holds no
    `already exists` and only the exceptions the untouched save logs on the
    same route (loaded the same way as a baseline).
- **Loadouts** (*Save to a file…* / *Put one on…* under the Inventory tab's
  paper doll, `#loadoutDialog`; `save/Loadout`, `handlers/ExportLoadout`,
  `handlers/ReadLoadout`, `handlers/ApplyLoadout`, 2026-10-02): what the
  character on the doll wears, holds in their weapon sets and keeps in quick
  slots, to a `.loadout` file and back onto anyone, in this save or another
  playthrough. Not their pack, not what they know.
  - **The file is a packet file like a `.chr`**: the character's own object
    first (its slot lists say what sat where), then each item's own object,
    enchantments, soulbinding and a grimoire's spells included. Written to a
    sibling temp file and moved into place. Nothing worn means nothing is
    written ("X wears and holds nothing…"); a file whose first object is not
    a character with `Equipment` and `CharacterStats` is "not a loadout".
  - **Everything goes on as a copy under an ID of its own** (tree and mirror
    `ObjectID`, `InstanceID.Guid`), so one file can go on twice or back into
    the save it came from, and what a copy replaces goes to the stash as it
    was. **A copy names none of the objects its original did**:
    `Equippable.AbilityModGuids` (the abilities an enchantment grants) and
    `Summon.m_summons` (a pet's creature) are cleared on every component, and
    the game makes its own on load — `ItemModComponent.Initialize` for an
    enchantment, `Summon.PerformSummoning` for a worn pet, whose creature takes
    the prefab's fixed ID and is reused if one exists. Left as they were, two
    items would share one ability object.
  - **Each item is judged on its own**, so one that cannot go on does not stop
    the rest: no head slot for a godlike, a grimoire slot for wizards only, a
    pet slot for the main character only, the deprecated cape never, weapon
    sets past `2 + BonusWeaponSets` and quick slots past `4 + BonusQuickSlots`
    locked, `RestrictedToClass` from the catalog's `classes`, and soulbinding.
    A weapon set the loadout fills goes on **whole**: a two-hander shares its
    set with nothing, so the other hand comes off to the stash (`cleared`).
  - **The main character and every companion have the same ObjectID in every
    playthrough** (the Watcher is `09517a0d-4fec-407c-a749-a531f3be64e0` in
    every test save of both playthroughs), so to the game the Watcher of another
    playthrough *is* the owner of the Watcher's soulbound sword, and it goes
    on — the game itself showed "Bound To: phanto", the other save's Watcher,
    on a sword bound to Phantom. A refusal names the owner as the target save
    knows them ("soulbound to phanto" for Calisca there), falling back to the
    loadout's own name.
  - **One transaction through `InventoryManager.apply`**: carry each copy into
    the stash (`Change.carried`, a whole packet with its ID already set), take
    off what is in the way (quick occupants, the cleared hand), then equip from
    the stash — equipping into an occupied slot swaps the occupant into the
    stash entry the copy left. `refusal()` and `checkWritable()` judge the end
    state, so nothing the inventory editor would refuse slips through.
  - **The UI**: the plan dialog lists every item with its icon, its place
    ("weapon set II, main hand", "quick slot 3") and its outcome ("Replaces
    Bittercut", "Into an empty slot", "Left out: only the main character has a
    pet slot"). Both buttons wait while the tab has staged changes of its own
    ("Apply or revert the changes first"): a loadout reads and writes the save
    as it is, and the reply would wipe the staged edits. The dialog cannot be
    closed while it goes on (`backdrop: 'static'`, Cancel and × disabled) —
    the reply is adopted whatever becomes of the dialog. The file dialogs are
    `ChrDialog.choose` with the `loadout` extension; a cancelled one answers
    `CANCELLED`, which the page ignores. Only a `.loadout` is planned: a
    `.chr` reads like one (a character, then their things), so `ReadLoadout`
    refuses any other name before the plan, as `ApplyLoadout` would after it.
  - **Verified in the game (2026-10-02)**: the Watcher's gear on Aloth in the
    Caed Nua save (seven enchanted pieces, the arquebus, four quick stacks) and
    on the prologue playthrough's Watcher (the soulbound sword and the pet as
    well). Every copy's tooltip carried its enchantments, the abilities they
    grant were on the action bar (Holy Power 3 per rest, Visage of
    Concelhaut, Watchful Presence, Dimensional Shift, Lord's Authority), a
    skull pet floated beside the prologue Watcher, the replaced gear was in
    the stash with its own enchantments, and Player.log had no exception.
    `ingame_loadout.py` builds both saves.
  - Tests: `LoadoutTest` (15), `LoadoutHandlersTest` (7) and `loadout.py`
    (44 checks: the save, the plan in both themes, the put-on, the written
    file read back, the other playthrough).
- **Undo and Redo** (beside the Save button in `#fabRow`, Ctrl+Z / Ctrl+Y /
  Ctrl+Shift+Z; `ui/js/SaveHistory.js` for the history, `ui/js/EditHistory.js`
  for the controls, `environment/EditHistory` and `handlers/ChangeHistory` on
  the server, 2026-10-04): one history of everything changed since the save
  was opened or last saved.
  - **Two kinds of step, undone by whoever holds the change.** A *values* step
    is something typed into the page -- exactly the scopes Save writes
    (`SaveMerge.writable`: stats, portrait paths and picture, the health flag,
    currency, camping supplies, globals) -- and the page undoes it, writing the
    old values into the same `{type, value}` slots (invariant 12). An *applied*
    step is an Apply: the page keeps its `historyStep` and asks the server,
    which swaps the files back (invariant 20) and replies with the reopened
    save, adopted like any Apply.
  - **Nothing reports its edits.** `Modifications.render` -- which every edit
    already reaches, to arm Save -- calls `EditHistory.notice()`, which compares
    the values Save writes (3,948 of them on the 13-character test save, ~4 ms)
    with the last reading and makes a step of the difference. Keystrokes into
    one element within 1.5 s are one step ("Phantom’s Might, 18 to 25", not
    "to 2" and then "to 25"). `EditHistory.labelled(label, fn)` names a step
    where a derived label would not do: console commands as typed, "Heal the
    party", "Revert Aloth’s changes".
  - **Labels speak the sheet's language**: attributes by name, skills as ranks
    (points are stored, ranks shown), identity fields by name; an Apply says
    what it was ("Inventory: Potion of Barring Death’s Door to Aloth",
    "Stronghold: 1 change", "Party: Edér in, Aloth out", "Put Phantom’s loadout
    on Aloth"). The Undo button's title and the toast after each undo use them.
  - **A tab's draft is not a step, and blocks undo.** Inventory, Abilities,
    Stronghold, Grimoire and Vendors stage changes until Apply, each with its
    own Revert, and `unapplied()` says so. Undoing an Apply reopens the save, and
    a draft built on the old one would be applied to the wrong thing, so while
    any tab has one (or is applying, saving, importing, moving the party),
    Undo says "Apply or revert the changes in the Inventory tab first." and
    does nothing. Each tab is `reset()` after an applied undo, which is lossless
    for that reason.
  - **A reply's changes are not the user's.** `SavedGame.adopt` records the
    Apply, and the history rebaselines on the merged save -- but some tabs arm
    Save before rendering what they adopted, so `notice()` waits until the
    merged object is the one on screen (`expected`); before that, the old copy
    against the new baseline would have looked like a pile of edits. Undoing a
    stronghold dismissal brings Prestige back because `adopt` keeps the merge
    base in step with what the file holds: the server's change is never
    mistaken for something typed.
  - **Save is a checkpoint.** The page and the server both start again after a
    successful Save (`SavedGame.written`, `ChangesSaver.run`): past it, what Save
    wrote is in the same `MobileObjects.save` an undo would put back, so
    undoing an older Apply would silently take the saved values with it. The
    save that was opened is still in the list, and earlier writes of this one
    are in File → Backups.
  - **In flight**: an undo sent while something new is typed cannot be redone
    after it (`History.ask`/`settle` compare a stamp), and a refused undo or
    redo -- the page and the server no longer agree -- starts the history
    again with the server's own message.
  - **Found while building it**: the inventory's Revert brought sold items back
    but left the money they sold for in the purse (free money); a sale is
    paid by its Apply now, so there is nothing to take back (invariant 21).
    The sheet's Revert (`PanelChanges`) put a
    portrait's paths back but not the picture the sheet draws
    (`character.portrait`); the baseline keeps the picture now. And the
    achievements toggle copied two values in place instead of adopting its
    reply, leaving the merge base behind, so an undo would have kept the new
    flag as if typed; it adopts now.
  - **Not checked in the game**, and nothing new for it to read: an undone
    Apply leaves every file byte for byte as it was (`EditHistoryTest`), and a
    redone one as the Apply wrote it.
  - Tests: `EditHistoryTest` (12), `ChangeHistoryTest` (5), one each in
    `ChangesSaverTest` and `ImportCharacterTest`, `SaveHistoryTest.js` (19,
    node, in CI) and `undo.py` (44 checks).
- **Multi-store install detection** (`environment/GameLocator`): finds the game
  wherever the player actually put it, across every drive and every store.
  This was the top correctness gap — the saves were never the problem, since
  every desktop store uses `%USERPROFILE%\Saved Games\Pillars of Eternity`, but
  portraits, the item catalog and the stronghold and identity data all come out
  of the *install*, and the old search looked at `%SYSTEMDRIVE%` plus four
  hardcoded paths under Program Files.
  - Strategies in order, first accepted answer wins: **Steam**, **GOG**
    (registry), **Epic** (JSON manifests in ProgramData), **known layouts on
    every drive**, then **Microsoft Store** as a note.
  - **Steam is resolved properly, not guessed.** A Steam root (registry, or the
    usual folders on every drive, or `~/.steam/steam` on Linux) →
    `steamapps/libraryfolders.vdf` for every library on every drive →
    `steamapps/appmanifest_291650.acf`, which names `installdir` outright, so a
    renamed folder is still found. 291650 is Pillars of Eternity's appid.
  - **The registry is silent more often than you would think.** On this
    development machine Steam is installed and running from `D:\Steam` and
    there is **no `HKCU\Software\Valve\Steam` key at all** — nor any
    `HKLM\...\Valve\Steam`. The drive scan is not a last-resort nicety; it is
    the path that actually fires here, and it finds the install in ~110ms.
  - **A candidate is only accepted if it contains `PillarsOfEternity_Data`.**
    That is what the editor actually reads, so a stale registry entry or a
    library the game was moved out of is rejected rather than leaving the
    editor pointed at a folder with no portraits and no explanation. The
    accepted path is canonicalised, since a case-insensitive drive scan
    otherwise hands back `d:\steam\...` for display and for settings.
  - **The Microsoft Store copy is detected and explained, never offered.** It
    registers like any appx package under
    `HKCU\...\AppModel\Repository\Packages`, so it can be found — but its files
    live under WindowsApps behind the `gameflt` driver, which refuses reads
    even to an administrator. The handler passes the explanation through as
    `notes`, and the Settings dialog shows it under the folder field. An empty
    box with no reason is the worst of both.
  - The registry is read by shelling out to `reg query` (`CommandLineRegistry`),
    which is the only way from Java 8 without a native library. Every failure —
    missing key, no `reg` at all, not Windows — is simply "no answer".
  - **`TestHarness` pins the locator with `GameLocator.useNoGame()`**, the same
    way it pins the catalogs. Without it a machine with the game installed
    answers differently from one without: five existing tests started passing
    the real `D:\Steam\...` path around the moment detection began working.
  - `Configuration.possibleInstallationLocations` is gone — it was exactly the
    mechanism being replaced, and leaving it would invite someone to fix the
    wrong thing.
  - Save-path detection picked up the **macOS** layout at the same time
    (`~/Library/Application Support/Pillars of Eternity`, with and without a
    `SavedGames` suffix, whichever exists). Written from the roadmap's note
    rather than from a Mac — there is none here and the app cannot start there
    yet, since `pom.xml` has no macOS JCEF profile — so treat it as unverified
    on hardware.
- **Save format conversion** (`save/SaveConverter`, a *Format* action beside
  Load/Rename/Delete on the save list): rewrites the type names in a save
  between the two ways Unity has named its core assembly, as a byte rewrite
  rather than a full deserialize.
  - **The whole difference between the formats is one string.** A type is
    serialized as an assembly-qualified name, and Unity 2017.2 split
    `UnityEngine` into modules, so `UnityEngine.Color, UnityEngine` became
    `UnityEngine.Color, UnityEngine.CoreModule`. Nothing else about the format
    changed — the fixture pair that shipped with the original Windows Store
    work differs by exactly 43 and 45 occurrences, 11 bytes each.
  - **Measuring first killed the premise the old code was written on.** The
    comment in `TypeMap` says the Windows Store and Xbox builds use a newer
    Unity "whose saves don't work with Steam or GoG". That was true in 2020.
    It is not true now: **every save this machine's Steam copy writes contains
    `UnityEngine.CoreModule`** — 5,496 occurrences across 194 files of one
    late-game save, and 32 in a prologue one. A Windows Store save and a
    current Steam save are the same format. So the conversion is a
    *downgrade*, for a Steam or GOG build old enough to predate the change,
    and the dialog says exactly that rather than implying everyone needs it.
  - **`PacketDeserializer.isWindowsStoreSave()` is gone**, and so is the
    `isWindowStoreSave` key the opener hardcoded to `false` and the two dead
    branches in `SaveSearch.js` that read it. It tested `contents.contains(
    "UnityEngine.CoreModule")`, which on any current install is true of every
    save the player owns — had the half-built flow it belonged to ever been
    finished, the editor would have told the user each of their saves was a
    Windows Store save. Nothing needed it: `Deserializer.convertToType` splits
    the type string on the comma, so the module name never mattered for
    reading.
  - **The rewrite only touches what it can prove is a type string.** A header
    string is a present-guard byte, a 7-bit-encoded length, then UTF-8
    (`BinaryWriter.writeStringGuarded`), so an occurrence is rewritten only
    when a guard and a length are found that frame it *exactly* and the module
    name ends the string. A `System.Type` written as a value carries no guard
    byte and is left alone, which is also what the deserializing converter
    does. Anything that cannot be framed hands the whole file to that slower
    converter instead of being guessed at — measured across a real 119 MB
    save, **5,496 of 5,496 occurrences framed unambiguously**, so the fallback
    never fires in practice.
  - **The 2015-era saves qualify the assembly in full** —
    `UnityEngine.Color, UnityEngine, Version=0.0.0.0, Culture=neutral,
    PublicKeyToken=null` — which puts the module name in the *middle* of the
    type string with its version trailing it. Splicing `.CoreModule` in there
    would leave the version qualifiers describing an assembly that is not the
    one named, so converting a save of that shape *up* is refused rather than
    mangled. Detection is deliberately more forgiving than the rewrite and
    still calls it legacy.
  - **What it bought.** Against the deserializing path on a real 119 MB save:
    1.4s of 20-thread work becomes 0.57s single-threaded, and the heap ceiling
    drops from **more than 256 MB** (it OOMs there, building 395 object graphs
    at once) to **under 96 MB** (one file in, one file out). End to end, an
    archive conversion is ~2.4s, of which the zip round trip is now the bulk.
  - **Verified against the converter it replaces, not just against itself**:
    byte-identical output on the golden fixture pair, byte-identical on all
    194 serialized files of a real save, a round trip back to the original
    bytes exactly, and the same packet count out of `PacketDeserializer`.
  - `handlers/ConvertSave` answers `{savePath, convert}` — with `convert`
    false it writes nothing and only says what the save is and where a copy
    would go, which is what lets the dialog explain itself before the user
    commits. The copy lands in `<saves folder>/converted/`, which the save
    list never shows because `SaveGameExtractor` filters on `File::isFile`.
    The original is never written to and an existing converted copy is never
    overwritten.
  - **Not verified in the game, and cannot be here**: the build this conversion
    is *for* is one older than the Unity change, and the only copy on this
    machine is current. Treat "an old Steam or GOG build reads the converted
    save" as reasoned from the format rather than measured.
- **Save validation** (`save/SaveValidator`, a strip above the views that is
  invisible until something is actually wrong): the invariants this project
  learned the hard way, asserted against the save rather than discovered
  in-game.
  - **Where it runs is the whole point.** Every manager re-opens the save
    through `SavedGameOpener` after applying, so the opener is the choke point
    where a structural mistake the *editor* made surfaces at once. The item-
    minting aliasing bug — inserting a template's own `Property` objects into a
    new packet, which rewrote the template's GUID too — showed up only in-game,
    as both items silently missing. It is now three separate checks firing at
    once.
  - What it looks for: two objects sharing an ObjectID; an object whose
    `InstanceID.Guid` is not its own ObjectID; `ItemList` and
    `SerializedItemList` of different lengths (invariant 6); a carried or worn
    item GUID with no standalone packet; a leading object count that
    disagrees with the contents (invariant 5); and a file that gave back fewer
    objects than its count promised (`SHORT_READ`, invariant 17), which puts
    "Part of this save could not be read" at the head of the strip.
  - **Every check was measured against four real saves before being written** —
    a 4,894-packet mid-game save, an early-prologue one, and both unit fixtures
    — and reports nothing on any of them (the short read cannot fire on a
    file that reads whole). Another candidate did not survive that: *"Parent
    names an object that exists"* fires **29 times on a perfectly healthy
    save**, because a dead companion's belongings outlive the companion the
    game deleted. A check that cries wolf on an untouched save is worse
    than no check, so it is deliberately not made.
  - The object-count check is skipped in the opener: a whole read stops at the
    count, so the two agree by construction, and a short read is reported as
    `SHORT_READ` instead. (This entry used to say the reader always reads
    exactly what the count claims. It did not — a failed read was dropped and
    the loop went on — and that belief is how a partial read got written back.)
  - It reports rather than refuses — a short read excepted, which nothing will
    write (invariant 17). By the time the opener sees a problem the
    damage is already in the working copy, and blocking the user from writing
    their file would not undo it — the strip says what is wrong, what the game
    will do about it (drop what it cannot resolve, usually without an error),
    and that reopening the save is the way back.
  - The strip carries class **`save-only`**, not `view`: it belongs to the save
    screen as a whole rather than to one tab, and `Editor.render` hides
    `.view, .save-only` together so it cannot outlive the save.
  - Verified end to end against a deliberately broken save (two item packets
    given the same ObjectID — the aliasing bug's exact signature): the strip
    names all three consequences, and clears again when a healthy save is
    opened.
- **Portrait picker** (a modal off the character view's own portrait,
  `portraitDialog`): the portraits on the player's own install, in a grid of
  the party-bar thumbnails, filtered by the folders the game keeps them in and
  searchable by file name.
  - **A portrait is two plain strings and nothing else.** `Portrait` has
    exactly two `[Persistent]` fields, `m_textureLargePath` and
    `m_textureSmallPath`, both relative to `PillarsOfEternity_Data`.
    Decompiled `Portrait.Start()` loads whatever they name and only derives a
    path from `CompanionInstanceID` when one is **empty**, so a non-empty path
    is used verbatim and nothing recomputes it on load.
    `GUIUtils.LoadTexture2DFromPathCallback` then does
    `Path.Combine(Application.dataPath, path)` and reads the file straight off
    disk — which is why a portrait the player dropped in themselves works
    exactly like a shipped one, and why `save/PortraitCatalog` lists the
    directory rather than a table typed out here.
  - **Both halves are always written together.** The large is the character
    sheet (210×330), the small is the party bar (76×96), and they pair by
    convention as `X_lg.png` beside `X_sm.png`. A portrait whose small
    counterpart is missing is deliberately **not offered**: the game would load
    the large one happily and hand the party bar the blank white fallback
    texture. The shipped game has 118 complete pairs — 99 player, 13 companion,
    6 NPC — plus an unpaired `genericportrait.png`.
  - **No handler or manager: it rides Save.** The two strings are scalars, so
    `ChangesSaver.updateComponent` (the old `updateCharacter`, generalised over
    the component name) writes them through the same tested `updateVariables`
    path the character's stats use. `SavedGameOpener` ships them as
    `portraitPaths` in the same `{type, value}` shape as `stats`, so the round
    trip is symmetric and there is no second write tier to keep in step.
  - `handlers/BrowsePortraits` pages the grid (40 at a time) for the reason
    `BrowseItems` does: the small images alone are ~1.9 MB and one JCEF reply
    will not carry that. Only the small travels; the large is fetched once, for
    the one portrait actually picked, since it is seven times the bytes.
  - `PortraitCatalog.imageData` resolves the path and then checks it still sits
    inside the portraits folder before reading. The request crosses the bridge
    from a page, and a page should not be able to name any file on the disk.
  - The button only appears where there is a `Portrait` component to write, so
    a companion the game deleted (a resurrect stub) does not offer one.
  - Verified in the game: giving the Watcher the Grieving Mother's portrait in
    the editor shows her face in both the party bar and the character sheet,
    with the rest of the sheet untouched.
- **Identity panel** (in the character view, under the attributes and skills):
  race, subrace, class, culture, background and gender, plus a deity for priests
  and an order for paladins. Every one is a `[Persistent]` enum on
  `CharacterStats`, already shipped in `stats` and already listed in
  `Eternity.structures`, so the panel edits `saveData` and Save writes it on the
  ordinary scalar path — no handler, no manager.
  - **`RacialBodyType` is read-only and must never be written.** `Awake()` sets
    it to `CharacterRace` for anyone who is not godlike, and for a godlike it
    holds the body *underneath* the godlike features (a Moon Godlike player with
    `RacialBodyType = Aumaua` is normal). Writing it is either pointless or
    breaks the model.
  - **Identity sticks for companions**, unlike base attributes: `Restored()`
    re-copies only BaseMight…BasePerception from the prefab (invariant #11).
  - **Gear can be stranded.** `Equipment.HasEquipmentSlot` gives a grimoire slot
    only to a wizard and a head slot only to a non-godlike, and nothing repairs
    an item left in a vanished slot — `RepairSaveLoadEquipmentErrors()` handles
    the deprecated Cape and locked slots only. The panel names the item and says
    to unequip it in the Inventory tab; it deliberately does not move it, since
    the identity edit rides Save while inventory edits ride Apply and the two do
    not share a transaction.
  - **Changing class does not remove the old class's abilities.** `Restored()`
    rebuilds `m_abilities` from whatever ability objects exist and never checks
    them against the class, so the panel says so and points at the Abilities tab.
  - Subrace is scoped to race; the godlike set is exactly what
    `CharacterStats.SubraceIsGodlike()` tests for. Race and class are narrowed to
    the playable six and eleven — the enums also carry creature types (Beast,
    Spirit, Troll, Ogre) with no portraits, subraces or progression tables. A
    value already in the save that the list does not offer is still shown,
    marked "(unusual)", rather than being silently reassigned.
  - **What each choice is worth is shown under its dropdown**, and none of it is
    baked into the save at creation — which is exactly why the panel can show it
    and why editing these really does move the character sheet:
    - `GetAttributeScore()` = `BaseX` + `XBonus` + `RaceAbilityAdjustment` +
      `CultureAbilityAdjustment`, floored at 1, recomputed on every read.
    - `CalculateSkillInternal()` = rank + `<Skill>Bonus` + `ClassSkillAdjustment`
      (guarded by `IsPlayableClass()`) + `BackgroundSkillAdjustment`.
    All four tables are `static int[,]` fields on `CharacterStats`, copied
    verbatim into `save/IdentityCatalog`. **The two column orders differ and both
    are counter-intuitive**: attributes run `AttributeScoreType` = Resolve,
    Might, Dexterity, Intellect, Constitution, Perception, while skills run
    `SkillType` = Stealth, Athletics, Lore, Mechanics, Survival, Crafting.
    Reading a row under the wrong order relabels every bonus and never looks
    wrong — Dwarf as `{0,2,-1,0,1,0}` is +2 Might/−1 Dex/+1 Con and nothing else.
  - A **subrace's racial ability** comes from the game's own `racial`
    AbilityProgressionTable (one ability per subrace, all `auto`), so
    `IdentityCatalog` reads it through `AbilityCatalog` rather than listing it.
    Note the progression tables are stored lower-cased, so a subrace has to be
    put back on its enum constant. Changing subrace does **not** mint the
    ability object — `Restored()` rebuilds `m_abilities` from what exists — so
    the panel says when the character doesn't have it and points at the
    Abilities tab.
  - **Deity and paladin-order dispositions are prefab data, not code.**
    `Religion.DeityInfo` / `PaladinOrderInfo` are inspector arrays on the same
    InGameGlobal prefab the stronghold hangs off; `tools/gamedata/identity.py` pulls
    them into `itemdata/identity.json` (5 deities, 6 orders, plus the
    ±20/40/60% `PositiveTraitBonus`/`NegativeTraitBonus` ladder).
    `Religion.GetCurrentBonusMultiplier()` reads them **for the player character
    only**, so a companion's order is flavour, and Pallegina's own
    `FrermasMesCancSuolias` is configured with no dispositions at all. Absent
    catalog degrades to silence; the compiled tables always work.
  - **An order outlives a class change, and so does its dialogue
    (read 2026-11-01).** `Conditionals.IsPaladinOrder` compares the order
    field and nothing else, and not one of the 53 conversation nodes that test
    an order also requires the Paladin class (`px2_05_cv_bleakwalkers` node 57
    tests "not a Bleak Walker AND not a Paladin": the line for everyone
    else). Nothing resets the order on load; only character creation and the
    console's `SetPaladinOrder` write it. The panel hides the Order dropdown
    for a non-paladin but leaves the value alone, so a Watcher made a Paladin
    of an order and then given another class keeps the order's dialogue. What
    goes is the order's disposition bonus: `Religion` applies it only while
    `CharacterClass == Paladin`. Not yet checked in the game itself.
  - **Gender has no mechanical effect** — `StringTableManager.GetCharacterName(
    DisplayName, Gender)` is the only thing that reads it.
  - The panel closes with **"On the character sheet"**: the six attributes and
    six skills with the working shown (`18 base +3 bonus`, `rank 10 +2 class +1
    background`). Verified against the game itself — a 15th-level Moon Godlike
    Paladin of the Goldpact Knights reads 21/10/13/14/12/19 and Stealth 2,
    Athletics 13, Lore 2, Mechanics 0, Survival 7 in both. (The game's own sheet
    lists five skills; Crafting doesn't appear on it.)
  - **`XBonus` and `<Skill>Bonus` are re-derived on load.** `Restored()` walks
    `m_statusEffects` into fresh accumulators and assigns `MightBonus`,
    `StealthBonus`, `LoreBonus` and the rest from them, so the persisted number
    is a snapshot of what the active effects added, not an independent value.
    It is still right to show it — the effects are restored too — but writing
    one of those fields directly will be undone by the next load.
  - The tables come over a dedicated `getIdentityEffects` handler rather than in
    the opener's reply (same for every save, and the opener already has a size
    ceiling); the arithmetic stays in `SavedGame.js` because the numbers have to
    move as the dropdowns move, without a round trip.
- **Skills editor** (in the character view, beside the attributes): the six skills
  plus unspent points. The save stores cumulative POINTS but the game's sheet shows a
  RANK — decompiled `CharacterStats.GetPointsForSkillLevel` / `CalculateSkillLevelViaPoints`
  confirm rank N costs N(N+1)/2, so the editor edits ranks and writes the exact point
  total, echoing "N pts" (and "(n spare)" when the stored total isn't triangular, which
  real saves do contain). **Skills stick for companions**, unlike base attributes:
  `CharacterStats.Restored()` re-copies only BaseMight…BasePerception from the prefab,
  never the skills. `SavedGame.js` handles this; no new handler needed since skills ride
  the existing scalar path.
- **Item catalog** (`save/ItemCatalog`, ~2184 items / 969 icons): real display names and
  the 42×42 inventory icons, keyed by lowercased prefab file name
  (`…/Ring_PREORDER_Gauns_Pledge.prefab` → `ring_preorder_gauns_pledge`). Each item has
  its own bundle under `assetbundles/prefabs/objectbundle/` named exactly that, holding
  an Item component whose `DisplayName` is a `DatabaseString{StringTable,StringID}` into
  `data/localized/en/text/game/*.stringtable` and whose `IconTexture` is the icon.
  (`DatabaseString.StringTableType`: 1=Gui 4=Characters 5=Items 6=Abilities 16=ItemMods…
  — measured on 2026-10-05: 2,009 of the 2,156 item *names* are in **items**, 142
  in **characters**, 3 in recipes and 2 in abilities. This note used to say
  names live in characters.) Parsing Unity
  bundles from Java 8 isn't realistic, so `tools/gamedata/items.py` (Python + UnityPy,
  run through `extract_gamedata.py`, which the editor itself runs) writes
  `catalog.json` + `icons/` — **never into the repo**, since it's derived from the
  user's own game install, same policy as portraits. `ItemCatalog` looks at the
  `itemDataLocation` setting, then `AppPaths.gameDataCandidates()` (`<data>/gamedata`,
  then the old `itemdata` folders), and degrades to prettified file names when absent.
  Catalog entries also carry `maxStack` (drives the quantity panel), `slots` (the
  Equippable per-slot flags), `path` (prefab path), `classes` (`RestrictedToClass`),
  `filter` (`UIInventoryFilter.ItemFilterType`: 1=WEAPONS 2=ARMOR 8=CLOTHING
  16=CONSUMABLES 32=INGREDIENTS 64=QUEST 128=MISC — drives the stash's category buttons)
  and `quality` — soulbound (has an `EquipmentSoulbind` component) beats unique (the
  `Unique` flag) beats fine (carries a `Fine_`/`Exceptional_`/`Superb_`/`Legendary_` mod).
  Quality tints the tile: white / gold / blue respectively. Counts: 17 soulbound,
  200 unique, 243 fine. Extraction gotcha: a bundle ships its item's *dependencies*
  too, including other real items (a belt that summons a weapon ships the weapon), so the
  right component is the one whose GameObject is named like the bundle — picking the
  first match labelled a belt "Firebrand". `ItemCatalog.useNoCatalog()` pins tests off it
  so extraction output doesn't depend on whether the machine has a game installed.
- **Names in the game's language** (`environment/GameLanguage`, `save/GameText`,
  `handlers/GetLanguages`, a choice in Settings; 2026-10-05): every item,
  ability, talent, spell, stronghold upgrade (with its description), deity,
  order, hireling and quest is shown as the game shows it to this player. The
  editor's own words stay English.
  - **The game ships its text once per language**: `data/localized/<code>`
    under the data folder (de, en, es, fr, it, ko, pl, ru on the Steam build),
    each with a `language.xml` (`<Name>polish</Name>`, what the game's own
    setting says, and `<GUIString>Polski</GUIString>`, what the language calls
    itself) and the same string tables under `text/game` and `text/quests`.
    The expansions have folders of their own (`data_expansion1/localized/...`)
    but the base tables already hold their entries: every catalog name resolves
    from `data/localized`.
  - **Which language**: the one Settings names (`language` in settings.json),
    where the install has it; else the one the game is set to; else English.
    The game's setting is a Unity PlayerPrefs value, on Windows in the
    registry: `HKCU\Software\Obsidian Entertainment\Pillars of Eternity`,
    value `LanguageName_h2027703280`, `REG_BINARY`, the name as text with a
    zero after it (`656E676C69736800` is "english"). `GameLanguage` reads it
    with `reg query <key> /f LanguageName` and matches it against the
    `<Name>` of each language the install has. No registry, no such value, or
    a name no folder claims: English.
  - **The catalogs keep their English names and say where each came from.**
    The extractors write `"nameId": [table, id]` beside `"name"` (and
    `descId`, `descriptionId`): the number is `DatabaseString.StringTableType`
    (4 characters, 5 items, 6 abilities, 938 stronghold...). Item names come
    from *items* for 2,009 of 2,156 entries and from *characters* for 142,
    whatever an earlier note here said. Re-reading the game data with the IDs
    added changed nothing else: every other field of all five files compared
    equal. A catalog asks `GameText.translated(table, id)` for the other
    language's word when it loads (`GameText.said`), and keeps its own name
    where there is none: no ID (data read by an earlier version), no entry or
    an empty one in that language, no install. So English behaves exactly as
    before, and needs no install at all.
  - **What already read the install follows too**: hireling names
    (`SerializedNameId`) and quest titles and objectives go through
    `GameText.lookup`/`quest`, which answer in the language in use and fall
    back to English entry by entry.
  - **Story companion names follow the same language choice**: their verified
    `Characters` string-table IDs are in `CompanionRegistry`. A non-empty
    `CharacterStats.OverrideName` remains the save's own name; otherwise the
    opened character model carries the localized official name used by the
    sidebar, inventory, party, and comparison views.
  - **Names are read once and kept**, so `SaveSettings` starts `GameText` and
    the four catalogs again when `language` or `gameLocation` changes, and only
    then. (Before this a change of game folder went unnoticed by `GameText`
    until the editor was restarted.) A save that is already open keeps the
    names it was opened with; the dialog says the choice applies to saves
    opened from now on.
  - **A language code is never put into a path unless a folder of exactly
    that name is one of the install's languages** (`GameLanguage.folder`): it
    comes out of settings.json, which the page can write.
  - **Data read before the IDs were kept** can only be English.
    `ItemCatalog.localizable()` says which kind is loaded, and Settings asks
    for the data to be read again when another language is chosen over it.
  - **Nothing of this reaches a save.** A display name is never among what
    Save writes (invariant 19); `language.py` saves a save with Polish chosen
    and compares it with the one it was made from: the money that was edited
    and the name typed, nothing else.
  - **The UI suites are pinned to English** (`config.SETTINGS`): left to
    follow the game, every suite that reads a name would depend on what the
    game on that machine is set to. `TestHarness` pins the registry lookup off
    (`GameLanguage.useNoSetting()`) for the same reason.
  - Searching (the item and ability browsers, Find) matches the name shown,
    and the catalog key, which is the English prefab name. The English name
    itself is not searched once another language is shown.
  - `TestEnvironment` copies every language's `language.xml`, `text/game` and
    `text/quests` now (34 MB), not the conversations.
  - Tests: `GameLanguageTest` (9), six more in `GameTextTest`,
    `CatalogLanguageTest` (9), `GetLanguagesTest` (9: the reply, and that
    saving the choice takes effect at once) and `language.py` (23 checks,
    against the game's own Polish tables read independently). Not checked in
    the game: nothing the game reads is changed.
- **Polish save names**: the game strips non-ASCII when building `.savegame` filenames;
  the list/rename/suggestion UI now prefers `sceneTitle` from `saveinfo.xml` (proper
  UTF-8, BOM handled in `SaveGameInfo`).
- **Serializer buffering**: save pipeline ~30s → ~2s (unbuffered per-byte I/O was
  the bottleneck; zip compression is not — keep NORMAL level).
- **Serializer caches** (2026-10-22): a world state is read once into memory
  rather than reopened and sought for each of its thousands of packets, and
  type names, fields, enum tables and the header tables are looked up once
  per type instead of by reflection or a list search each time. Reading a
  save takes half as long (Caed Nua's 165 files, 20 s to 10 s). Verified by
  round-tripping both test saves' 168 world-state and area files through the
  build before and the build after: every file came out byte for byte the
  same in both, and the same as the game wrote it.
- **The save list** (2026-10-22): a filter box, sorting by date or name, and
  a grid or list layout kept between runs (`SaveListModel.js` holds the
  ordering, tested in plain node). Selecting or opening a save no longer
  redraws the tiles; `render()` redraws them only when the list itself is a
  new one, so anything that edits an entry in place hands over a copy of the
  list (Rename does; the tile kept its old name until a new search when it
  did not). The tiles are unpacked in parallel; `UnpackedSaves` and
  `leaveOut` are synchronized.
- "Saving…" spinner during saves; off-screen-window guard; run.bat resolves the
  bundled JDK.
