# The save format

> Working notes, kept while the editor was built and moved here from a file
> outside the repository in October 2026. They are written for whoever
> changes the code next: what the game does with a save, measured or read
> out of the decompiled game, and what went wrong when that was guessed at.
> "Invariant N" anywhere in these documents is rule N of
> [invariants.md](invariants.md).

How a save is put together, and where each thing the editor changes is kept.
What each *feature* reads and writes, in detail, is in [features.md](features.md).

## Architecture (verified against real saves)

- A save (`*.savegame`) is a heavily-compressed zip: `MobileObjects.save` (world state) + `*.lvl` area files + `saveinfo.xml` + screenshot.
- `MobileObjects.save` = `[int count][N ObjectPersistencePacket]`, read by a custom Java port of C#'s SharpSerializer (`serializer/`). Each packet: `ObjectName`/`ObjectID`/`GUID`/`LevelName`/`Location`/`Parent` + `ComponentPackets[]` (each a `TypeString` + `Variables` map). Owned objects point at their owner via `Parent`.
- **`serializer/TypeMap.java` is the de-facto schema** (~90 C#→Java type mappings). Any non-primitive C# type not in it FAILS deserialization. Supporting new data = add a TypeMap entry + a mirror class in `game/`.
- Data flow: `SavedGameOpener` → **scalar-only** JSON to the UI → UI edits its copy → `ChangesSaver` re-deserializes the original, applies updates to the `Property` tree, reserializes, zips a NEW save. The UI never sees complex objects.
- **Two write tiers**:
  - *Scalar* (generic, tested): `ChangesSaver.updateVariables` with `save/model/TypedValue` — int/float/bool/String/enum/UnsignedInteger/EternityDateTime; a value that is not the type the save holds stops the Save.
  - *Structural* (manual): the `PartyManager`/`CharacterImporter` pattern — find objects, components and variables through the save model (`save/model`, invariant 26), mint objects with `SaveModel.mint` and build components with `ComponentBuilder`, with exact C# type strings.
- Windows Store→Steam conversion is a serialize-time module rename `UnityEngine.CoreModule`→`UnityEngine` (`SerializerFormat.UNITY_2017`). Normal edits must reserialize with `PRESERVE`.

## Names the save model goes by

`save/model/SaveModel` finds what a writer looks for by these rules, one for
each kind of object. They were measured on 2026-11-02 over 24 saves (the
test environment's and the real saves folder's, read only), where every
save had exactly one of each:

| What | Rule | Every save measured had |
|---|---|---|
| The main character | name starts `Player_` | `Player_New_Game(Clone)_0` |
| A companion | name starts `Companion_` | |
| The in-game global | name starts `InGameGlobal(` | `InGameGlobal(Clone)` |
| The world global | name starts `Global(Clone)`, or is exactly `Global` | `Global(Clone)` |
| A component | `TypeString`, compared ignoring case | no two differing only in case |
| An object by ID | `ObjectID`, compared ignoring case | |

The names in the tree were the names in the mirror in every save. Before the
model each writer had its own rule: Save took any name starting `player_`
in any case and any starting `Global`, the party `Player_`, the stronghold
`InGameGlobal(`, and components were matched exactly.

A list's `Capacity`, written beside its items, was measured on 2026-11-03 over
2,283 packet files (the test environment's saves and 15 real ones, read in
place): never below the list's length, and often above it, in .NET's growth
steps (4, 8, 16, 32, 64, 512). `GuidList` and `ItemContainer` raise it to the
length when an add passes it, and never lower it. Loaded in the game on
2026-11-03 (`tools/ui-tests/ingame_capacity.py`: the prologue playthrough's
stash, 16 items with a `Capacity` of 16, given a seventeenth): the stash
showed all 17, the Minor Ring of Deflection among them, and `Player.log` held
no `Exception` and no `already exists`. The game does not need the extra room
a .NET list keeps; a `Capacity` equal to the length loads.

## Save structure map (from live dump of a real save)

- **Character** (`Player_*`/`Companion_*`): `CharacterStats` = 168 scalar vars — skills (`AthleticsSkill`…`CraftingSkill`, `RemainingSkillPoints`), `Experience`, `Level`, defenses (`BaseWill`/`BaseReflexes`/`BaseFortitude`/`BaseDeflection`), attributes (`BaseMight`…). All already on the tested scalar path. Class/Race/Subrace/Culture/Background enums are already shipped to the UI by `GetGameStructures`.
- **Health** component: `CurrentHealth`/`CurrentStamina` (floats); dead ⇔ `CurrentHealth == 0` (`SavedGameOpener.detectDead`); `m_maxHealthOverride/-StaminaOverride` use `-3.4e38` as an "unset" sentinel; `m_needs_current_values` (bool, false once the game has run a frame) refills both to their maximum on the next load when set — the editor's heal. Camping supplies are `PlayerInventory.campingSupplies` (int).
- **Inventories — every party member carries their own pack.** The player's is a `PlayerInventory`, a companion's a plain `Inventory`; both get `MaxItems` = 16 because `BaseInventory.Update()` assigns `AttachedCharacter.InventoryMaxSize` (`=> 16`) every frame. Everyone also has a `QuickbarInventory` (`MaxItems` = `MaxQuickSlots` = 4 + `BonusQuickSlots`, reassigned every frame; the persisted value agrees) and an `Equipment`. **A quick slot takes any item** — no type filter in `CanPutItem`, and the HUD even offers non-consumables from it. `StashInventory` and `CraftingInventory` stack without limit (`InfiniteStacking`); everything else holds `MaxStackSize` at most, and real saves bear that out (every over-cap stack in four saves is in one of those two). Only `StashInventory` (max `int.MaxValue`), `QuestInventory` and `CraftingInventory` are party-wide, and they hang off `Player_*` alone. Stored/roster duplicates (`Companion_X_stored`) carry no inventory components at all.
  Each container holds `ItemList` (List<`InventoryItem`>: `BaseItem` = prefab path string, `stackSize`, `uiSlot`) + parallel `SerializedItemList` (List<UUID>, same index) + `MaxItems`; `uiSlot` is the tile index *within that character's own grid* (0..15). Currency = `PlayerInventory.currencyTotalValue.v`.
  `Equipment` = `EquipmentSetSerialized` (11 UUID slots) + `WeaponSetsSerialized` (8 = 4 sets × primary/secondary) + `SelectedWeaponSetSerialized`. **The 11 slots are ordered by `EquipmentSet.SerializedEquipment`, NOT by the `Equippable.EquipmentSlot` enum — the two disagree** (enum: …RightRing, LeftRing, Hands…; serialized: …Hands, RightRing, LeftRing…). Real order: `Head, Neck, Chest, Hands, RightRing, LeftRing, Cape(deprecated, always empty), Feet, Waist, Grimoire, Pet`. Following the enum silently mislabels every character's gear (caught only by noticing bracers sitting in a "ring" slot).
  Equipped items are referenced by UUID only; resolve one by finding the packet with that `ObjectID` — its `ObjectName` is the prefab name plus a `(Clone)` suffix.
  Every item's `SerializedItemList` UUID is ALSO the `ObjectID` of its own standalone top-level `ObjectPersistencePacket` elsewhere in the save, **parented to the character carrying it** — one packet per stack, regardless of `stackSize`. Removing an item means deleting all three in sync (`ItemList` entry + `SerializedItemList` UUID + the standalone packet, which goes only once no list in the file names it: `SaveModel.removeUnlisted`), the same "purge, don't orphan" pattern as `Resurrector`; handing one to another party member means moving it between two characters' lists *and* re-pointing that packet's `Parent`.
- **Stronghold** (on `InGameGlobal(Clone)`): scalars `Prestige`/`Security`/`m_Debt`/`AvailableTurns`/`m_currentTurn`/`BonusTurnMoney`/`IsErlTaxActive`… + structural lists (`m_upgradesBuilt`, `m_hirelingsHired`, `m_prisoners`, `SerializedStoredGuids` — the last already mutated by `PartyManager`).
  - **`SerializedIsActivated` is whether the player owns Caed Nua at all.** Setting it true calls `ActivateStronghold(restoring: true)`, which does nothing but set the flag — so the editor gates on it and deliberately does NOT offer to grant the stronghold, since the quest that hands it over also moves the party, advances quest state and spawns the steward.
  - **`Prestige` and `Security` are independent persisted scalars, never recomputed.** `CompleteBuildingUpgrade()` adds an upgrade's `PrestigeAdjustment`/`SecurityAdjustment` once, at the moment of building; `DestroyUpgrade()` subtracts them. Nothing derives either number from `m_upgradesBuilt` on load, so adding to that list without doing the same arithmetic leaves a save the game could never have produced. Same for `UpgradeCompletedGlobalVariableName` (only `EasternBarbican` names one: `b_Eastern_Barbican`).
  - **`m_hirelingsHired`** entries are `StrongholdHireling` (or
    `StrongholdGuestHireling`) with `Paid`, `IsLeaving`, `SerializedNameId`,
    `CostPerDay`, `LeavesAfterFullPayCycle`, `PrestigeAdjustment`,
    `SecurityAdjustment`, `HiredGlobalVariableName`, `CanHireGlobalVariableName`.
    `Restored()` swaps each for the configured hireling it matches by
    `HiredGlobalVariableName` or `SerializedNameId`. **`Paid` means "their
    Prestige/Security are currently counted"**: the pay cycle takes them off and
    clears `Paid` when the keep cannot pay, so a dismissal subtracts only for a
    paid one. **`m_prisoners`** entries are `StrongholdPrisonerData`:
    `PrisonerName` (CharacterDatabaseString), `PrisonerDescription`
    (StrongholdDatabaseString), `GlobalVariableName`. None of the 12 test-env
    saves holds a prisoner; the real saves folder's `2.0 Save Games Backup` has
    two (Kestorik, `b_kestorik_prisoner`, in `…23813238…` and `…26429508
    Odlewnia`). The game lists prisoners on the stronghold's **Actions** page
    (`UIStrongholdActionsPage`), not a page of their own.
  - `m_upgradesBuilt` is a `List<StrongholdUpgrade.Type>` — a flat list of enum values with no cross-references, no UUIDs and no parallel structure, which makes it the easiest structural list in the save to edit. **Enums serialize by ordinal** (`BinaryWriter` writes `n` or the constant's index), so our mirror enum's `ArtificersHall` and the game's own `AritficersHall` typo interoperate fine — but always match on ordinal, never on name.
  - The upgrade table itself is NOT in the save: cost, build time, adjustments, prerequisite, name, description and icon all live on the `Stronghold` behaviour attached to the `InGameGlobal` prefab (`assetbundles/prefabs/objectbundle/ingameglobal.unity3d`). `tools/gamedata/stronghold.py` pulls them into `stronghold.json`. 25 of the enum's 28 values are configured; `BeastVault`, `RoadRepairs` and `AdditionalStorage` are cut content.
- **Portrait** (on every character packet): exactly two `[Persistent]` strings, `m_textureLargePath` and `m_textureSmallPath`, relative to `PillarsOfEternity_Data` and used verbatim — `Start()` only derives a path from `CompanionInstanceID` when one is empty. The pair is `X_lg.png` (210×330, the character sheet) beside `X_sm.png` (76×96, the party bar); write one without the other and the bar shows the blank white fallback. Any readable file under the data directory works, which is how custom portraits have always worked in this game.
- **Grimoire** (on the grimoire *item*'s own packet, alongside Equippable/InstanceID/Persistence): `SerializedSpellNames` is a `List<string>` of spell prefab names and is the whole of it; `SerializedSpells` (`SpellChapter[8]`) deserializes to eight chapters of four nulls in every real save, because its elements are `GenericSpell` object references. The names setter files each spell under its own `SpellLevel` and drops the fifth at any level silently. `m_PrimaryOwnerName` is persisted and empty in practice.
- **Vendor stock is NOT in `MobileObjects.save`.** A mid-game save has 68 stores holding 3,772 items and **every one of them lives in a `.lvl` area file** (measured: 0 stores in the world state, 68 across 164 area files, 77 MB, all readable in 9.9s with the existing deserializer). `ChangesSaver` zips them as they stand in the working copy; the Vendors tab (below) is the one thing that rewrites them. Also: `Store.RegenerateItems()` only destroys and re-adds items named in that store's `RegenerationItemTable` — everything else in a store's `ItemList` is permanent, including uniques the player has not bought, so a blanket purge would delete real content. (An early-prologue save is the exception that misleads: Cilant Lis's Heodan carries a `Store` component on his own character object in the world state.)
