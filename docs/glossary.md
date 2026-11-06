# Glossary

The words the code and these notes use for what is inside a save, and for
what the editor does to one. Where a word names a class, the class is given.

## Inside a save

- **Packet file**: A file of game objects in the SharpSerializer format: `MobileObjects.save`
  (the world state) and each `.lvl` area file. A leading object count, then
  the objects. `serializer/DeserializedPackets` is one, read.
- **Object** (`save/model/SaveObject`): One game object of a packet file, an `ObjectPersistencePacket` in the game:
  a character, an item, a store, one of the two globals. It has **fields** of
  its own (`ObjectName`, `ObjectID`, `LevelName`, `Location`, `Packed`,
  `Parent`) and a list of components. Also called a packet in older code.
- **Component** (`save/model/Component`): One of an object's behaviours, a `ComponentPersistencePacket` in the game:
  `CharacterStats`, `Health`, `PlayerInventory`, `Stronghold`. Named by its
  `TypeString`, it holds **variables**.
- **Variable**: One entry of a component's `Variables`: a plain value (a number, a name, an
  enum), or a list or object to read further.
- **Tree**: What the serializer reads a packet file into and writes back out of: the
  `Property` objects of `serializer/properties`. An edit is made here, and
  only here (invariant 26).
- **Mirror**: The plain Java objects (`game/ObjectPersistencePacket` and the rest) the
  deserializer also builds from a packet file, each property's `obj`. Easy to
  read, but an edit to the tree does not reach them, and the serializer never
  writes them. `SavedGameOpener` and the save comparison read them; writers do
  not.
- **Save model** (`save/model/SaveModel`): The objects of one packet file, read and written through the tree, with
  the game's names for what an editor looks for. The way a writer finds an
  object, a component or a variable, and writes a value as the type the save
  holds.
- **GUID list** (`save/model/GuidList`): A variable holding a list of GUIDs: the worn equipment
  (`EquipmentSetSerialized`), the weapon sets, a container's
  `SerializedItemList`, the stronghold's rosters. An empty slot holds the zero
  GUID. A list's `Capacity` is written with it and is never below its length
  (invariant 5).
- **Item container** (`save/model/ItemContainer`): A component that holds items (`PlayerInventory`, a
  companion's `Inventory`, `QuickbarInventory`, `StashInventory`, a store):
  an `ItemList` of `InventoryItem` entries (`BaseItem`, `uiSlot`, `stackSize`)
  and a `SerializedItemList` naming each entry's item object, at the same
  place in both lists (invariant 6).
- **Minting**: Making a new object out of one the game wrote (`SaveModel.mint`), as a new
  item or ability is made: its own name, ID and parent, fresh copies of the
  components it keeps (invariant 7).
- **Main character**, **player**: The one object whose name starts `Player_` (`SaveModel.player`).
- **Companion**: An object whose name starts `Companion_`. Its `ObjectID` is a GUID fixed
  for that companion in every playthrough.
- **Stored record**: The `<name>_stored` object the stronghold keeps for each character on its
  roster, pointed at from the Stronghold's `SerializedStoredGuids`.
- **In-game global** (`SaveModel.inGameGlobal`): `InGameGlobal(Clone)`: the object holding the global variables, the quests
  and the stronghold.
- **World global** (`SaveModel.worldGlobal`): `Global(Clone)` (or plain `Global`): the world's own state, the game's
  settings among it.
- **Global variable**: One entry of the in-game global's `GlobalVariables.m_data`, the game's
  scripting variables. One a save does not hold reads as 0 in the game.

## What the editor does

- **Working save** (`environment/WorkingSave`): The editor's private copy of the open save. Every edit goes there; the
  saves folder only gets a new file, on Save.
- **Apply**: One tab's staged changes written into the working save, as one step Undo
  can take back (`handlers/SaveMutationHandler`).
- **Save**: The working save packed into a new `.savegame` in the saves folder
  (`save/ChangesSaver`).
- **Refusal** (`serializer/WriteRefusedException`): A write the editor declined to make, with a message meant for the user as
  it stands. Nothing was written.
