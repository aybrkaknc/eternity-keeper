/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2015 the authors.
 *
 *  Eternity Keeper is free software: you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  Eternity Keeper is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package uk.me.mantas.eternity.tests.save;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.InventoryItem;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.InventoryManager;
import uk.me.mantas.eternity.save.InventoryManager.Change;
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;
import uk.me.mantas.eternity.tests.TestHarness;
import uk.me.mantas.eternity.save.model.SaveObject;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;

// The fixture's Player_Elwyn carries exactly two PlayerInventory items (a ring
// and a pet figurine) plus an empty stash, and Companion_Calisca carries an
// empty 16-slot Inventory of her own — enough to exercise remove, restack and
// both flavours of move (to the shared stash, and to another character) without
// needing a live-game save.
public class InventoryManagerTest extends TestHarness {
	private static final String ELWYN = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String CALISCA = "b1a7e809-0000-0000-0000-000000000000";
	private static final String RING_GUID = "c4b7033d-c452-4946-a9ba-9998cb411201";
	private static final String PET_GUID = "41baf584-9203-44df-a921-62f50000ad74";

	private File setupSave () throws URISyntaxException, IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());
		final File saveDir = new File(workingDir.get(), "cadena 0 Test.savegame");
		assertTrue(saveDir.mkdir());

		final File resources = new File(getClass().getResource("/").toURI());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), saveDir);
		return saveDir;
	}

	private DeserializedPackets deserialize (final File saveDir) throws IOException {
		final Optional<DeserializedPackets> deserialized =
			new PacketDeserializer(new File(saveDir, "MobileObjects.save")).deserialize();
		assertTrue(deserialized.isPresent());
		return deserialized.get();
	}

	private ObjectPersistencePacket packetOf (
		final List<Property> packets, final String objectID) {

		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket packet = (ObjectPersistencePacket) p.obj;
			if (objectID.equalsIgnoreCase(packet.ObjectID)) {
				return packet;
			}
		}

		fail("no packet with ObjectID " + objectID);
		return null;
	}

	private List<InventoryItem> itemsOf (
		final List<Property> packets, final String characterID, final String component) {

		final Optional<ComponentPersistencePacket> comp = EKUtils.findComponent(
			packetOf(packets, characterID).ComponentPackets, component);
		assertTrue(component + " component missing", comp.isPresent());

		final List<InventoryItem> items = new ArrayList<>();
		final Object itemList = comp.get().Variables.get("ItemList");
		assertTrue(itemList instanceof CSharpCollection);
		final Iterator itemIterator = ((CSharpCollection) itemList).iterator();
		while (itemIterator.hasNext()) {
			items.add((InventoryItem) itemIterator.next());
		}

		return items;
	}

	private List<String> serializedGuidsOf (
		final List<Property> packets, final String characterID, final String component) {

		final Optional<ComponentPersistencePacket> comp = EKUtils.findComponent(
			packetOf(packets, characterID).ComponentPackets, component);
		assertTrue(comp.isPresent());

		final List<String> guids = new ArrayList<>();
		final Object list = comp.get().Variables.get("SerializedItemList");
		assertTrue(list instanceof CSharpCollection);
		final Iterator guidIterator = ((CSharpCollection) list).iterator();
		while (guidIterator.hasNext()) {
			guids.add(guidIterator.next().toString());
		}

		return guids;
	}

	private boolean packetExists (final List<Property> packets, final String objectId) {
		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			if (objectId.equalsIgnoreCase(((ObjectPersistencePacket) p.obj).ObjectID)) {
				return true;
			}
		}

		return false;
	}

	@Test
	public void removesAnItem () throws URISyntaxException, IOException {
		final File saveDir = setupSave();
		final DeserializedPackets before = deserialize(saveDir);
		final int countBefore = before.getPackets().size();

		final Change change = new Change(ELWYN, "PlayerInventory", RING_GUID, 0);
		assertTrue(new InventoryManager(saveDir).apply(singleton(change)));

		final DeserializedPackets after = deserialize(saveDir);
		final List<InventoryItem> items =
			itemsOf(after.getPackets(), ELWYN, "PlayerInventory");
		final List<String> guids =
			serializedGuidsOf(after.getPackets(), ELWYN, "PlayerInventory");

		assertEquals(1, items.size());
		assertEquals(1, guids.size());
		assertFalse("ring's own packet should be gone",
			packetExists(after.getPackets(), RING_GUID));
		assertFalse(guids.contains(RING_GUID));

		// The remaining pet figurine is intact.
		assertEquals("Assets/Data/Prefabs/Items/Consumables/Figurines/ITEM_PET_Astral_Piglet.prefab",
			items.get(0).BaseItem);

		// Leading count shrank by exactly one removed top-level packet.
		assertEquals(countBefore - 1, after.getPackets().size());
		assertEquals((int) (Integer) after.getCount().obj, after.getPackets().size());
	}

	@Test
	public void restacksAnItem () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		final Change change = new Change(ELWYN, "PlayerInventory", PET_GUID, 5);
		assertTrue(new InventoryManager(saveDir).apply(singleton(change)));

		final DeserializedPackets after = deserialize(saveDir);
		final List<InventoryItem> items =
			itemsOf(after.getPackets(), ELWYN, "PlayerInventory");
		final InventoryItem pet = items.stream()
			.filter(i -> i.BaseItem.contains("Astral_Piglet"))
			.findFirst().orElse(null);

		assertNotNull(pet);
		assertEquals(5, pet.stackSize);
		assertEquals(5, pet.StackSize);

		// The other item and the packet count are untouched.
		assertEquals(2, items.size());
		assertTrue(packetExists(after.getPackets(), PET_GUID));
	}

	// Setting stack size to zero (or below) via the same Change is treated
	// as a removal — the UI decides once, the manager just honors it.
	@Test
	public void restackingToZeroRemovesTheItem () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		final Change change = new Change(ELWYN, "PlayerInventory", RING_GUID, 0);
		assertTrue(new InventoryManager(saveDir).apply(singleton(change)));

		final DeserializedPackets after = deserialize(saveDir);
		assertFalse(packetExists(after.getPackets(), RING_GUID));
		assertEquals(1, itemsOf(after.getPackets(), ELWYN, "PlayerInventory").size());
	}

	@Test
	public void movesAnItemToStash () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		final Change change = new Change(
			ELWYN, "PlayerInventory", RING_GUID, 1, ELWYN, "StashInventory", -1);
		assertTrue(new InventoryManager(saveDir).apply(singleton(change)));

		final DeserializedPackets after = deserialize(saveDir);

		final List<InventoryItem> playerItems =
			itemsOf(after.getPackets(), ELWYN, "PlayerInventory");
		assertEquals(1, playerItems.size());
		assertFalse(playerItems.stream().anyMatch(i -> i.BaseItem.contains("Ring_PREORDER")));

		final List<InventoryItem> stashItems =
			itemsOf(after.getPackets(), ELWYN, "StashInventory");
		assertEquals(1, stashItems.size());
		assertTrue(stashItems.get(0).BaseItem.contains("Ring_PREORDER"));

		final List<String> stashGuids =
			serializedGuidsOf(after.getPackets(), ELWYN, "StashInventory");
		assertEquals(1, stashGuids.size());
		assertEquals(RING_GUID, stashGuids.get(0));

		// The item's own packet survives the move (only its list membership
		// changed) — no duplicate, no orphan.
		assertTrue(packetExists(after.getPackets(), RING_GUID));
		assertNoDuplicateObjectIDs(after.getPackets());
	}

	// Handing an item to another party member has to move it between two
	// different characters' components AND re-point the item packet's Parent,
	// otherwise the save still claims the old owner carries it.
	@Test
	public void movesAnItemToAnotherCharacter () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		final Change change = new Change(
			ELWYN, "PlayerInventory", RING_GUID, 1, CALISCA, "Inventory", -1);
		assertTrue(new InventoryManager(saveDir).apply(singleton(change)));

		final DeserializedPackets after = deserialize(saveDir);

		assertEquals(1, itemsOf(after.getPackets(), ELWYN, "PlayerInventory").size());
		assertFalse(serializedGuidsOf(after.getPackets(), ELWYN, "PlayerInventory")
			.contains(RING_GUID));

		final List<InventoryItem> calisca =
			itemsOf(after.getPackets(), CALISCA, "Inventory");
		assertEquals(1, calisca.size());
		assertTrue(calisca.get(0).BaseItem.contains("Ring_PREORDER"));
		assertEquals(RING_GUID,
			serializedGuidsOf(after.getPackets(), CALISCA, "Inventory").get(0));

		// Ownership actually transferred.
		final ObjectPersistencePacket ring = packetOf(after.getPackets(), RING_GUID);
		final ObjectPersistencePacket owner = packetOf(after.getPackets(), CALISCA);
		assertEquals(owner.ObjectName, ring.Parent);

		assertNoDuplicateObjectIDs(after.getPackets());
	}

	// A pack has a real capacity (16 in the fixture, shrunk here to make the
	// test cheap); moving into a full one must fail cleanly rather than
	// silently overfilling it, and must leave the save untouched.
	@Test
	public void refusesToMoveIntoAFullInventory () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		// Move the ring to the stash first, leaving PlayerInventory holding
		// just the pet — then cap PlayerInventory at exactly one slot.
		assertTrue(new InventoryManager(saveDir).apply(singleton(new Change(
			ELWYN, "PlayerInventory", RING_GUID, 1, ELWYN, "StashInventory", -1))));

		setMaxItems(saveDir, "PlayerInventory", 1);

		final Change moveBack = new Change(
			ELWYN, "StashInventory", RING_GUID, 1, ELWYN, "PlayerInventory", -1);
		assertFalse(new InventoryManager(saveDir).apply(singleton(moveBack)));

		// Nothing moved: the ring is still in the stash, PlayerInventory
		// still holds only the pet.
		final DeserializedPackets after = deserialize(saveDir);
		assertEquals(1, itemsOf(after.getPackets(), ELWYN, "PlayerInventory").size());
		assertEquals(1, itemsOf(after.getPackets(), ELWYN, "StashInventory").size());
		assertTrue(serializedGuidsOf(after.getPackets(), ELWYN, "StashInventory")
			.contains(RING_GUID));
	}

	// Dropping an item onto a specific tile should land it there; asking for a
	// tile that is already taken falls back to the lowest free one rather than
	// letting two items share a slot.
	@Test
	public void placesItemsOnRequestedSlots () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		// The ring sits on slot 0 and the pet on slot 1; move the pet to 7.
		assertTrue(new InventoryManager(saveDir).apply(singleton(new Change(
			ELWYN, "PlayerInventory", PET_GUID, 1, ELWYN, "PlayerInventory", 7))));

		DeserializedPackets after = deserialize(saveDir);
		InventoryItem pet = itemsOf(after.getPackets(), ELWYN, "PlayerInventory").stream()
			.filter(i -> i.BaseItem.contains("Astral_Piglet"))
			.findFirst().orElse(null);
		assertNotNull(pet);
		assertEquals(7, pet.uiSlot);

		// Slot 0 is still the ring's, so asking for it must not collide.
		assertTrue(new InventoryManager(saveDir).apply(singleton(new Change(
			ELWYN, "PlayerInventory", PET_GUID, 1, ELWYN, "PlayerInventory", 0))));

		after = deserialize(saveDir);
		final List<InventoryItem> items =
			itemsOf(after.getPackets(), ELWYN, "PlayerInventory");
		final Set<Integer> slots = new HashSet<>();
		for (final InventoryItem item : items) {
			assertTrue("two items claimed slot " + item.uiSlot, slots.add(item.uiSlot));
		}
	}

	// Equipment is a fixed 11-slot array of UUIDs rather than a list, so
	// equipping means clearing the item out of its pack and writing its GUID
	// into the right slot — the item's own packet just stays where it is.
	@Test
	public void equipsAnItemFromAPack () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		// Slot 4 is RightRing in EquipmentSet.SerializedEquipment order.
		final Change change = Change.equip(ELWYN, "PlayerInventory", RING_GUID, ELWYN, 4);
		assertTrue(new InventoryManager(saveDir).apply(singleton(change)));

		final DeserializedPackets after = deserialize(saveDir);

		assertEquals(1, itemsOf(after.getPackets(), ELWYN, "PlayerInventory").size());
		assertFalse(serializedGuidsOf(after.getPackets(), ELWYN, "PlayerInventory")
			.contains(RING_GUID));
		assertEquals(RING_GUID, equipmentSlot(after.getPackets(), ELWYN, 4));

		// The ring still exists as its own object, owned by the same character.
		assertTrue(packetExists(after.getPackets(), RING_GUID));
		assertNoDuplicateObjectIDs(after.getPackets());
	}

	// Unequipping has to manufacture a fresh ItemList entry, since the pack
	// only ever held a GUID for equipped gear.
	@Test
	public void unequipsAnItemIntoAPack () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		assertTrue(new InventoryManager(saveDir).apply(
			singleton(Change.equip(ELWYN, "PlayerInventory", RING_GUID, ELWYN, 4))));

		assertTrue(new InventoryManager(saveDir).apply(
			singleton(Change.unequip(ELWYN, 4, RING_GUID, ELWYN, "PlayerInventory", 5))));

		final DeserializedPackets after = deserialize(saveDir);

		assertEquals("", equipmentSlot(after.getPackets(), ELWYN, 4));

		final List<InventoryItem> items =
			itemsOf(after.getPackets(), ELWYN, "PlayerInventory");
		assertEquals(2, items.size());

		final InventoryItem ring = items.stream()
			.filter(i -> i.BaseItem != null && i.BaseItem.contains("Ring_PREORDER"))
			.findFirst().orElse(null);

		assertNotNull("unequipped ring should be back in the pack", ring);
		assertEquals(1, ring.stackSize);
		assertEquals(1, ring.StackSize);
		assertEquals(5, ring.uiSlot);
		assertTrue(serializedGuidsOf(after.getPackets(), ELWYN, "PlayerInventory")
			.contains(RING_GUID));

		assertNoDuplicateObjectIDs(after.getPackets());
	}

	// Equipping into an occupied slot swaps: the incoming item goes on, the
	// outgoing one takes its place in the pack.
	@Test
	public void swapsWithTheItemAlreadyInTheSlot () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		assertTrue(new InventoryManager(saveDir).apply(
			singleton(Change.equip(ELWYN, "PlayerInventory", RING_GUID, ELWYN, 4))));

		// Now put the pet figurine into the same slot; the ring must come back.
		assertTrue(new InventoryManager(saveDir).apply(
			singleton(Change.equip(ELWYN, "PlayerInventory", PET_GUID, ELWYN, 4))));

		final DeserializedPackets after = deserialize(saveDir);

		assertEquals(PET_GUID, equipmentSlot(after.getPackets(), ELWYN, 4));

		final List<String> guids =
			serializedGuidsOf(after.getPackets(), ELWYN, "PlayerInventory");
		assertTrue("displaced ring should be back in the pack", guids.contains(RING_GUID));
		assertFalse(guids.contains(PET_GUID));
		assertEquals(1, itemsOf(after.getPackets(), ELWYN, "PlayerInventory").size());

		assertNoDuplicateObjectIDs(after.getPackets());
	}

	@Test
	public void refusesToEquipIntoAnOutOfRangeSlot () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		assertFalse(new InventoryManager(saveDir).apply(
			singleton(Change.equip(ELWYN, "PlayerInventory", RING_GUID, ELWYN, 11))));

		// Nothing moved.
		final DeserializedPackets after = deserialize(saveDir);
		assertEquals(2, itemsOf(after.getPackets(), ELWYN, "PlayerInventory").size());
	}

	// Adding an item the save has never seen means minting a whole object:
	// its own top-level packet (so the SerializedItemList GUIDLink resolves),
	// an ItemList entry, and the GUID linking the two.
	@Test
	public void addsABrandNewItem () throws URISyntaxException, IOException {
		final File saveDir = setupSave();
		final int countBefore = deserialize(saveDir).getPackets().size();
		final String guid = "11112222-3333-4444-5555-666677778888";

		final Change change = new Change(
			ELWYN, "PlayerInventory", guid, 3, ELWYN, "PlayerInventory", 6);
		change.newItemPrefab = "Food_Beer";
		change.newItemPath = "assets/data/prefabs/items/consumables/food/food_beer.prefab";

		assertTrue(new InventoryManager(saveDir).apply(singleton(change)));

		final DeserializedPackets after = deserialize(saveDir);

		// The item is in the pack, with the stack size and tile we asked for.
		final List<InventoryItem> items =
			itemsOf(after.getPackets(), ELWYN, "PlayerInventory");
		assertEquals(3, items.size());

		final InventoryItem beer = items.stream()
			.filter(i -> i.BaseItem != null && i.BaseItem.contains("food_beer"))
			.findFirst().orElse(null);

		assertNotNull("new item should be in the pack", beer);
		assertEquals(3, beer.stackSize);
		assertEquals(3, beer.StackSize);
		assertEquals(6, beer.uiSlot);
		assertTrue(serializedGuidsOf(after.getPackets(), ELWYN, "PlayerInventory")
			.contains(guid));

		// And it exists as a real object the GUIDLink can resolve.
		final ObjectPersistencePacket packet = packetOf(after.getPackets(), guid);
		assertEquals("Food_Beer(Clone)", packet.ObjectName);
		assertEquals("assets/data/prefabs/items/consumables/food/food_beer.prefab"
			, packet.PrefabResource);
		assertEquals(packetOf(after.getPackets(), ELWYN).ObjectName, packet.Parent);

		// Minimal component set: the prefab supplies the rest on load.
		final List<String> componentTypes = new ArrayList<>();
		for (final ComponentPersistencePacket component : packet.ComponentPackets) {
			componentTypes.add(component.TypeString);
		}

		assertTrue(componentTypes.contains("InstanceID"));
		assertTrue(componentTypes.contains("Persistence"));

		assertEquals(countBefore + 1, after.getPackets().size());
		assertEquals((int) (Integer) after.getCount().obj, after.getPackets().size());
		assertNoDuplicateObjectIDs(after.getPackets());

		// The new packet is modelled on an existing item; if it shares
		// property objects with that template instead of copying them, writing
		// the new GUID silently rewrites the template's InstanceID too and the
		// game quietly drops both items on load.
		for (final Property property : after.getPackets()) {
			if (!(property.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket item = (ObjectPersistencePacket) property.obj;
			if (item.ObjectID == null || item.ComponentPackets == null) continue;

			for (final ComponentPersistencePacket component : item.ComponentPackets) {
				if (component == null || !"InstanceID".equals(component.TypeString)) continue;
				final Object instanceGuid = component.Variables.get("Guid");
				assertEquals(
					"InstanceID.Guid must match its own packet: " + item.ObjectName
					, item.ObjectID.toLowerCase()
					, String.valueOf(instanceGuid).toLowerCase());
			}
		}
	}

	private String equipmentSlot (
		final List<Property> packets, final String characterID, final int index) {

		final Optional<ComponentPersistencePacket> equipment = EKUtils.findComponent(
			packetOf(packets, characterID).ComponentPackets, "Equipment");
		assertTrue(equipment.isPresent());

		final Object slots = equipment.get().Variables.get("EquipmentSetSerialized");
		assertTrue(slots instanceof CSharpCollection);

		int i = 0;
		final Iterator iterator = ((CSharpCollection) slots).iterator();
		while (iterator.hasNext()) {
			final Object guid = iterator.next();
			if (i++ != index) {
				continue;
			}

			final String text = guid == null ? "" : guid.toString();
			return "00000000-0000-0000-0000-000000000000".equals(text) ? "" : text;
		}

		fail("no equipment slot " + index);
		return null;
	}

	private void assertNoDuplicateObjectIDs (final List<Property> packets) {
		final Set<String> seen = new HashSet<>();
		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final String id = ((ObjectPersistencePacket) p.obj).ObjectID;
			if (id != null) {
				assertTrue("duplicate ObjectID " + id, seen.add(id));
			}
		}
	}

	private void setMaxItems (final File saveDir, final String component, final int maxItems)
		throws IOException {

		final DeserializedPackets deserialized = deserialize(saveDir);

		boolean updated = false;
		for (final Property p : deserialized.getPackets()) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			if (!ELWYN.equalsIgnoreCase(((ObjectPersistencePacket) p.obj).ObjectID)) continue;

			final Optional<Property> entry = SaveObject.of(p)
				.flatMap(o -> o.component(component))
				.flatMap(inv -> inv.variable("MaxItems"));

			assertTrue(entry.isPresent());
			assertTrue(Property.update(entry.get(), maxItems));
			updated = true;
		}

		assertTrue(updated);
		final File mobileObjects = new File(saveDir, "MobileObjects.save");
		assertTrue(mobileObjects.delete());
		assertTrue(mobileObjects.createNewFile());
		deserialized.reserialize(mobileObjects);
	}

	private List<Change> singleton (final Change change) {
		final List<Change> list = new ArrayList<>();
		list.add(change);
		return list;
	}
}
