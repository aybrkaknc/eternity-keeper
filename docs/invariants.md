# Save-editing invariants

> Working notes, kept while the editor was built and moved here from a file
> outside the repository in October 2026. They are written for whoever
> changes the code next: what the game does with a save, measured or read
> out of the decompiled game, and what went wrong when that was guessed at.
> "Invariant N" anywhere in these documents is rule N of
> [invariants.md](invariants.md).

Break one of these and the result is a save the game silently drops things
from, or cannot load at all. Each was learned from a real fault; the date and
the fault are given where they are known. [CONTRIBUTING.md](../CONTRIBUTING.md)
has the short version.

1. **Never overwrite an original save.** Clone to a new working dir, write a new `(edited)` `.savegame` (`ChangesSaver.cloneExtractedSave`).
2. **Exact type fidelity** on write-back (`save/model/TypedValue`); writing the wrong C# type corrupts the save. A value that cannot be the type the save holds is refused, never replaced by a guess: until 2026-11-02 Save wrote an enum name the game does not have as the enum's first constant.
3. Only `SimpleProperty` is directly writable (`Property.update`); `EternityDateTime`/`EternityTimeInterval` get field-level updates.
4. **Follow references before mutating** — the format de-duplicates object graphs; chase `ComplexProperty.reference` via `followReference`.
5. **Counts must match contents**: the leading object count and every list length. The leading count is derived on every write: `DeserializedPackets.checkWritable` sets it to the objects the file holds (2026-11-03). Eight writers used to set it by hand, `CharacterImporter` by arithmetic, and a slip in any one would have been a file the game reads only part of. A list's `Capacity` is never below its length: across 2,283 packet files (the test saves and 15 real ones, 2026-11-03) the game's own never are, and often more (.NET's growth: 4, 8, 16…), so an add through the save model raises it to the count when the count passes it (`GuidList`, `ItemContainer`).
6. **Parallel structures stay in sync**: `ItemList` ↔ `SerializedItemList` (UUIDs) ↔ `Equipment` slot UUIDs.
7. **Manufactured objects must be game-accurate**: exact C# type strings, full component sets, `Parent` links, cross-referenced GUID lists. A new object is minted from one the game wrote (`SaveModel.mint`: fresh copies of the kept components, never the template's own properties, and `InstanceID.Guid` the new object's ID) or, for a component the game adds itself, built with `save/model/ComponentBuilder`, whose types were harvested from saves. `PartyManager` is the reference implementation.
8. One `Player_*` per save; companions have fixed GUIDs across saves; imported characters must be anchored (`LevelName` + `Location`) or overwrite in place.
9. All save mutations run on `Environment.mutationWorker` (single-threaded, prevents concurrent-write corruption) — never bypass it. A new Apply handler extends `handlers/SaveMutationHandler`, whose final `onQuery` does the hop; a new character-file action goes through `handlers/ChrDialog`.
10. Raw-tab-style variables are largely untested in-game; new UI features should target verified variables.
11. **Companion base attributes (BaseMight…BaseResolve) are NOT editable** — decompiled
    `CharacterStats.Restored()` re-copies them from the companion's prefab on every load
    (`CompanionInstanceID` check). The player (plain `InstanceID`) is unaffected. The UI
    locks them in both the Attributes and Raw views. Skills store cumulative POINTS;
    the in-game sheet shows the derived RANK (rank N = N(N+1)/2 points), so small point
    edits may not visibly change the sheet.
12. **UI: never replace `saveData` wholesale after a server-side mutation** — unsaved UI
    edits live only in that object. Every Apply reply goes through
    `Eternity.SavedGame.adopt(reply)`, a three-way merge (`ui/js/SaveMerge.js`) against a
    snapshot of what the file holds, taken on open (`SavedGame.open`) and on Save
    (`SavedGame.written`): a value that differs from the snapshot is the user's and is
    kept, everything else comes from the reply (a stronghold's new Prestige, a
    resurrection's cleared death flags). Seven editors used to merge seven ways and three
    lost work. Only the scalar scopes take part — stats, portrait paths + image, the
    health flag, currency, camping supplies, globals — and those are exactly what Save
    sends (`SaveMerge.writable`).
13. **`SharpSerializer.serializeAll` APPENDS.** It opens the target with
    `new FileOutputStream(file, true)`, because the save pipeline normally writes a file
    that does not exist yet. Anything editing a save **in place** calls
    `DeserializedPackets.replace(file)` — serialize to a sibling temp file, check it is
    non-empty, atomic move over the target. Writing straight into the existing file lands
    the new stream after the old one and every read returns the stale copy; a locked
    target used to fail half-way through.
14. **`ComplexProperty.findProperty(name)` matches case-insensitively.** Fine for
    bridging C#/Java naming almost everywhere, but `InventoryItem` has both a
    field `stackSize` and a same-named property `StackSize` independently
    serialized (Polenter.Serialization doesn't know the property just wraps the
    field) — calling `findProperty` with either casing returns whichever one
    appears first in `properties`, silently leaving the other stale on write.
    `InventoryManager.findExact` does a case-sensitive scan instead. If a future
    field turns up with this same shape, same fix applies.
15. **Never edit the directory the save list unpacked.** `environment/WorkingSave` owns
    which directory holds the open save's state: until the first Save, the first edit
    copies the opened save to a private `EK-editing-*` folder (same directory name — the
    new file name is derived from it) and every edit, Save and export uses that; after a
    Save, the written directory; opening a save from the list deletes the copy. Before
    this, an Apply the user discarded was back on reopening (a resurrected companion
    alive again) and a Save from there would have written it. Resolve paths with
    `forEditing`/`forReading`, never from `oldSave` directly.
16. **Back up before touching a file already in the saves folder.** The editor
    never writes to the save it opened, but Delete removes one, Rename rewrites
    one in place, and Save deletes a same-named file to make room. Each calls
    `SaveBackups.forThisProcess().backup(file, Reason)` first and stops if the
    copy fails. Backups live in `<data>\backups\<timestamp>\` with a
    `backup.json`; the newest `SaveBackups.KEEP` (10) **of each reason** are
    kept; `restore` never replaces an existing file. Delete also accepts only a
    `.savegame` file now — it used to delete any path the page sent (a folder,
    `settings.json`…). The limit was once one count for all reasons: every Save
    after the first replaces the file the first one wrote and backs it up, so
    ten Saves pushed out the only copy of a save the player had deleted (found
    by review, 2026-10-27; `SaveBackupsTest.repeatedSavesNeverPushOutTheCopyADeleteTook`).
    Rename no longer edits the file in place: it took `saveinfo.xml` out and
    then added the new one, so a failure between the two left a save without
    it, and the working copy took the new name before the file did. It now
    builds the new `saveinfo.xml` apart, puts it into a `.renaming` copy
    beside the save, moves that over the save whole, and only then updates
    the working copy (same review; `SaveGameRenamerTest`; a renamed Caed
    Nua save showed its new name in the game's load list and loaded with
    only the baseline exceptions, 2026-10-27).
17. **A read that comes up short is never written.** On a file cut short or
    damaged part-way, `PacketDeserializer` used to hand back whatever came
    before the damage with nothing to say so, and every writer then wrote that
    over the file (measured: 1 packet of 6 from a truncated area file; 6,043 of
    6,953 from a real world state cut at 90%). Now `deserialize()` throws
    `ShortReadException`, whose message is written for the user ("Only 22 of
    the 23 objects in MobileObjects.save could be read, so nothing was
    written: the rest would have been lost.") and which `SaveMutationHandler`,
    Save, import and export pass on as it stands. Anything that writes what it
    read reads it with `deserialize()`; `deserializeEvenIfShort()` is for
    showing a damaged save (the opener's `SHORT_READ` strip), and
    `DeserializedPackets.replace`/`reserialize` refuse a short read whatever
    the caller has done with it — the verdict is fixed when the file is read,
    because the write sets the count to the packets the file holds
    (invariant 5). A failed read leaves `SharpSerializer.position()` where it was, so
    reading stops at the first one that cannot move on (asking again for each
    missing packet took three times as long and logged every one); a packet
    that throws is a short read too, not an exception escaping onto a worker
    and leaving the page waiting. To test a writer against it,
    `ShortReadTest.cutShort` moves an object the edit never touches to the end
    of a fixture and cuts the file half-way through it. A refused export leaves
    no `.chr` behind.
18. **An edit that leaves a file contradicting itself is not written.**
    `serializer/PacketInvariants` reads the property tree about to be written
    (not the mirror objects, which `Property.update` never touches): the
    leading count, an ID two objects share, an `InstanceID.Guid` that is not
    its object's own ID, `ItemList`/`SerializedItemList` of different lengths,
    a listed, worn or held GUID with no object behind it, and every simple
    value held as the class its declared type is written as — invariant 2
    made mechanical, since the serializer writes by the value's class and a
    GUID held as text or a `Long` where an int belongs corrupts everything
    after it (an enum may hold its plain number: the deserializer keeps a
    constant the mirror enum lacks that way). `DeserializedPackets` notes what
    a file already contradicts when it is read, and a write refuses only what
    is *new*, with `InconsistentWriteException` ("…2 objects would share the
    ID c4b7…, and the game drops all of them. That is a fault in the
    editor…"); it and `ShortReadException` are both `WriteRefusedException`,
    which every writer passes on as it stands. Only new ones count because
    the game writes its own: across 38 real saves the area files hold 6,619
    inventory entries (stores, creatures' loot) naming an item with no object,
    358 InstanceIDs that are not their object's, 253 worn GUIDs and 233
    unpaired lists. The world states hold one between them, the item-minting
    aliasing bug's signature in a save an early build of the editor wrote
    (two Gaun's Pledge rings, one holding the other's InstanceID, in the real
    saves folder's `cf88c16d… 7764068 CilantLs.savegame` of 2026-08-03), so
    what resurrection and import carry in from another save arrives clean. The check costs about 7%
    of a read (100 ms on a 6,953-object world state). A writer changing
    several files calls `checkWritable()` on every one before writing any
    (`VendorManager`). A test that damages a fixture on purpose builds the
    file through the public constructor, which takes what it is handed as
    what the file already was.
19. **What the opener ships in a scope Save writes is exactly what the save
    holds.** Save writes back every value it is sent (`SaveMerge.writable`:
    stats, portrait paths, the health flag, currency, camping supplies,
    globals), so anything the opener works out for display goes in a field
    of its own, never into those. It used to put the English name it lists a
    companion by into her empty `OverrideName`, and every Save wrote it into
    the save — where an empty one is what lets the game show her name in the
    player's language (Edér came back "Eder"). Nothing caught it until Save
    comparison put a saved save beside its original; `compare.py` now checks
    that a Save changes the edit and nothing else.
20. **Every write of the working save is a step Undo can take back.**
    `handlers/SaveMutation` (run by every Apply and by the import) opens a step of
    `environment/EditHistory` around the edit, `DeserializedPackets.replace`
    keeps the file it is about to replace (a hard link, or a copy where the
    disk cannot link) while a step is open, and the step is committed on
    success or aborted -- which puts back whatever it had written -- on a
    refusal or a failure. So a new writer must write through `replace`, never
    straight into a file, or its change cannot be undone and a failure half
    way cannot be taken back. An Apply's reply carries its step as
    `historyStep`, and the page records it through `SavedGame.adopt(reply,
    label)`: **every Apply passes a label**, which is what Undo says. A
    successful Save and opening a save clear the history on both sides.
21. **A draft touches nothing Save writes until its Apply, and nothing reopens
    the save under one.** Save writes the scalar scopes (`SaveMerge.writable`)
    and never a tab's staged changes, so a staged change that also moves one
    of those values puts half of itself on disk. A sale was that change: its
    items left the save on the Inventory tab's Apply, but its money went into
    `saveData.currency` at Confirm, and a Save in between wrote the money
    beside every item it was paid for (measured: 210,763 cp and the sold
    ring, both in the written save). A first fix made Save refuse in between
    and every draft-dropping path refund; review found six paths it missed
    (a sale confirmed while an Apply or a Save was on its way, a stale list of
    sales refusing a Save with an untrue reason, an import over an existing
    character leaving the draft and its money, a refund that restored the
    pre-sale figure and wiped later money edits...). Now **a sale is paid when
    its Apply succeeds**: `sell()` only notes what is owed (`sold`), and
    `apply`'s `onSuccess` adds it to the page's `currency` *before* adopting
    the reply -- the purse is the page's to hold, the merge keeps what
    differs from the file as the user's, and an unsaved money edit made
    earlier simply adds up (`writes.py`: console money, then a sale). The
    applied step carries the amount (`adopt(reply, label, {credit})`), and
    undoing or redoing it moves the purse by that amount
    (`SaveHistory.moveCredit`), not back to a figure, so whatever else
    changed the purse since stays changed. Revert and a rebuild have nothing
    to refund; a Save in between writes the items and no money. Only what has
    a price is owed for, and something added from the catalog in the same
    draft has none (the save never held it), so money owed always comes with
    something for Apply to take out.
    The same review produced two rules for every tab that stages changes.
    **Whatever reopens the save from outside the tabs waits for a draft**:
    a resurrection, an import and a party change ask
    `EditHistory.refused()`, which says "Apply or revert the changes in the
    Inventory tab first." exactly as Undo does (they used to drop the
    Inventory tab's draft without a word whenever the party changed, or leave
    it stale when it did not). If something gets past that, the tab says the
    draft was dropped. And **a tab takes no clicks while its Apply is on its
    way** (`.view-working`, `pointer-events: none`): the reply replaces the
    draft, so anything staged in those two seconds was lost silently.
    `sale.py` pins all of it.
22. **Every query the page makes gets an answer.** A handler's work runs on a
    worker while the page waits with a spinner up, and work that ended in an
    exception nobody caught ended the thread and nothing else (one changed
    byte in a real save: the opener threw, and the spinner turned until the
    editor was closed). So every worker hop in `handlers/` is
    `execute(Answered.to(callback, ...))`, which answers `failure` for work
    that dies (a `StackOverflowError` included), and `JSHandlers.register`
    wraps every handler in `Answered.guarded`, whose query can be answered
    only once. `AnsweredTest.everyHandlerHandsItsWorkOverGuarded` reads the
    handlers' sources; the one exception it knows by name is `CloseWindow`,
    which answers before it hands the closing over. An Apply that dies this
    way is aborted and put back (`SaveMutation`'s `finally`); one that
    was written and then could not be read back is undone, so the save
    matches what the page still shows. `ImportCharacter` kept its own copy
    of that lifecycle and had no undo: an import that could not be handed
    back stayed, and the next Save wrote a character the page never showed
    (found by review, 2026-10-27; `anImportThatCannotBeHandedBackIsTakenBack`).
    Since 2026-11-01 there is one copy: `handlers/SaveMutation` holds the
    steps, `SaveMutationHandler` runs `mutate` through it, and a handler that
    cannot be one (the import asks for a file first) runs its edit through
    it too. A new writer does the same rather than repeating the steps.
    A writer reopens with `SavedGameOpener.reopen()`, which throws
    `Unreadable` instead of answering; the writer takes its step back and
    answers `failure`. `run()` (Open) answers the error codes itself. The
    opener used to answer an unreadable reopen as a *success* carrying
    `{"error": "DESERIALIZATION_ERR"}`, so the edit stayed and most tabs
    adopted the error object as the save (same review;
    `anEditThatCannotBeReadBackIsAFailureAndIsTakenBack`).
23. **The folder the save list unpacked holds only a tile's eight files**
    (`saveinfo.xml`, `screenshot.png`, `0.png`...`5.png`) until the save is
    wanted. `environment/UnpackedSaves.complete(folder)` unpacks the rest --
    `OpenSavedGame` and `CompareSaves` call it, and `WorkingSave.forEditing`/
    `forReading` call it again before anything is copied or read, so a save
    of eight pictures can never be edited or saved. That a folder is whole is
    remembered, never read off the folder (one with `MobileObjects.save` in
    it may be one whose unpacking stopped half way); a folder no search made
    (a Save's own) has no archive behind it and is whole as it stands. Before
    this every search unpacked every save whole: 5.84 s and 767 MB for twelve.
24. **A new save's name is free in the saves folder too.**
    `ChangesSaver.getAvailableGameID` used to look only at the saves the list
    had unpacked, and the list is as old as the last search and leaves out
    what it cannot read: a backup restored since, a save cut short. The first
    Save then replaced that file (with a backup, but replaced).

25. **What comes in from another save is checked against the area files
    too.** The world state is not the only file with objects in it: what a
    companion held in their quick slots when they died is left in the area
    they died in, as objects of that area under the IDs it always had, and
    what the party sold is in the store's area. A resurrection (and an import
    of a character exported earlier in the same game) brought the same
    objects back under those IDs, and the game said so when that area loaded:
    `PersistenceManager.SaveObject` threw "An item with the same key has
    already been added", then "ERROR: Trying to add 'Potion_of_...(Clone)' to
    MobileObjects when packet already exists! Packet is in both Mobile and
    Persistence object lists!" -- three potions Edér came back holding, in
    `AR_0601_Stronghold_Exterior.lvl` (found 2026-10-05 by reading Player.log
    after the load; the screens showed nothing wrong, and the resurrection had
    been "verified in the game" since July without anyone reading the log).
    `GuidRemap.heldByAreas(saveDirectory, ids)` searches the `.lvl` files for
    the incoming IDs as text (an object's own ID is written as text) and
    `Resurrector` and `CharacterImporter` add what it finds to the IDs that
    get fresh ones; on the Caed Nua test save 35 of Edér's objects did. The
    area files are left exactly as the game wrote them. **After any in-game
    load of an edited save, grep Player.log for `Exception` and `already
    exists`**: that, not the screens, is what catches an ID in two places.

26. **Writers read and write the tree, never the mirror; the save model is
    how.** The deserializer builds two things from a packet file: the
    property tree, which the serializer writes back, and plain Java objects
    (`ObjectPersistencePacket`, `ComponentPersistencePacket`) that an edit
    never reaches. Code that read the mirror after an edit read stale values:
    `PartyManager` had to note that "after in-memory surgery the obj-side
    component list is stale", `CharacterImporter` kept the two in step by
    hand, and the item minting built mirrors with no components at all. Each
    writer also found the main character and the globals its own way
    (`startsWith("Player_")`, `toLowerCase().startsWith("player_")`,
    `startsWith("Global")`), with its own walk down to a component.
    `save/model/SaveModel` is that walk once: `SaveModel.of(packets)` gives
    the objects, `player()`, `inGameGlobal()`, `worldGlobal()`, `object(id)`,
    `globalVariable(name)`; a `SaveObject` has its fields, `location()`,
    `moveTo`, its components, and `add`, `remove` and `replace` for them; a
    `Component` gets and sets its variables. Reads come back `Optional`; a
    write that cannot be made (no such variable, a value of another type)
    throws `WriteRefusedException` with a message for the user and changes
    nothing. The model is the file's own list, live: `add` refuses an ID the
    file already holds, `remove` takes out exactly that object, and
    `removeUnlisted(ids)` takes out an item's object only when no list in
    the file names it any more. A `GuidList` (`Component.guidList`) is a
    list of GUIDs (the worn equipment, the weapon sets, a roster) read in
    any case and written as real GUIDs; an `ItemContainer`
    (`Component.itemContainer`) is a container's `ItemList` and
    `SerializedItemList` kept in step (invariant 6): an entry and its GUID
    go in, come out and move together, and an item that moves to another
    character takes its object's `Parent` with it. `mint` makes a new object
    out of one the game wrote (invariant 7). The names it goes by were
    measured on every save ([save-format.md](save-format.md), "Names the
    save model goes by"). The import, the stronghold, the quest restore, the
    resurrection, the party and Save went over to it on 2026-11-02, and every
    other writer on 2026-11-03 (achievements, grimoires, abilities, vendors,
    the inventory, loadouts, export), with byte-identical output
    (`UnchangedWritesTest`, and the probe over every test save) except
    where an add now raises a list's `Capacity` (invariant 5).
    `EKUtils.findPacketById` and `findSubComponent` are gone; the opener,
    the comparisons and the vendor list read the mirror through
    `unwrapPacket` and `findComponent`, which is right for a read of an
    unedited file.

## Checking every write (2026-09-27)

Invariant 18 is the mechanism; this is what it found the day it went in.

- **Import put a character's belongings in twice.** A `.chr` keeps the IDs its
  objects had in the save it came from, and `CharacterImporter` regenerated
  only the character's own. Into another playthrough that is harmless; into
  another save of the same one it is not: overwrite takes out only what the
  replaced character *owns* (`Parent`), so anything the file brings that the
  target keeps somewhere else — a ring handed to Calisca since the export,
  food moved to the stash — came back under an ID something else still had,
  and keeping both copies of a character duplicated everything the copy
  carried. Each such object existed twice under one ID, which the game drops
  both of. **Measured on the test saves: 236 of the 524 overwrite imports
  possible between saves of the mid-game playthrough would have collided**
  (Aloth from the Caed Nua save into save 4: 14 objects, food and potions).
  An existing importer test failed the moment the check went in. Now anything
  the file brings under an ID the save keeps gets a fresh one
  (`save/GuidRemap`, shared with `Resurrector`, which had its own copy): the
  file's copy is renamed, its lists and slots follow, and what the save keeps
  is left as it is. **What names the character itself is never remapped** — a
  companion's ObjectID is the fixed GUID the game knows the companion by, and
  each ability's `Owner` is that. `import_chr.py` drives that Aloth import
  through the editor and reads the importer's "Gave 14 imported objects IDs
  of their own" back out of eternity.log. Checked in the game on 2026-10-05
  (`ingame_import.py`, see "Loaded in the game again"): he loads as he was
  exported, the stash keeps its own, and Player.log has nothing to say.
- **Tests that build a damaged fixture must say so.** `ShortReadTest.cutShort`
  and two `VendorManagerTest` cases wrote a deliberately broken file through
  the `DeserializedPackets` they had read, and the check refused it as the
  edit's own breach. They now write through the public constructor.
- **Before adding a check, run it over every packet file of real saves** (the
  census behind invariant 18 read all 38 on this machine, 470,257 objects, in
  a few minutes), the way every `SaveValidator` check was measured first. A
  new check only compares against what a file already had, so one that fires
  on what the game writes would not refuse an untouched save, but it would
  refuse carrying such an object into another file.
