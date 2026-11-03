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
import uk.me.mantas.eternity.game.InventoryItem;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.VendorManager;
import uk.me.mantas.eternity.save.VendorManager.Entry;
import uk.me.mantas.eternity.save.VendorManager.Removal;
import uk.me.mantas.eternity.save.VendorStock;
import uk.me.mantas.eternity.save.VendorStock.Vendor;
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;
import uk.me.mantas.eternity.tests.TestHarness;
import uk.me.mantas.eternity.save.model.SaveModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static uk.me.mantas.eternity.tests.save.VendorStockTest.*;

// Taking items out of a vendor's stock, in all three places an item lives:
// the store's ItemList entry, the parallel SerializedItemList GUID, and the
// item's own packet in the same area file. See VendorStockTest for the
// fixture.
public class VendorManagerTest extends TestHarness {
	private static DeserializedPackets packets (final File file) throws IOException {
		final Optional<DeserializedPackets> packets = new PacketDeserializer(file).deserialize();
		assertTrue(file.getName() + " reads back", packets.isPresent());
		return packets.get();
	}

	private static boolean hasPacket (final File file, final String id) throws IOException {
		return SaveModel.of(packets(file)).object(id).isPresent();
	}

	private static Vendor vendor (final File save, final String file, final String id)
		throws IOException {

		return VendorStock.read(save, file, id)
			.orElseThrow(() -> new AssertionError("no vendor " + id));
	}

	private static List<Integer> slots (final File file, final String store) throws IOException {
		final ObjectPersistencePacket packet = EKUtils.unwrapPacket(
			SaveModel.of(packets(file)).object(store).get().property());

		final List<Integer> slots = new ArrayList<>();
		final Object items = EKUtils.findComponent(packet.ComponentPackets, "Store").get()
			.Variables.get("ItemList");

		for (final Iterator it = ((CSharpCollection) items).iterator(); it.hasNext();) {
			slots.add(((InventoryItem) it.next()).uiSlot);
		}

		return slots;
	}

	private static Removal removal (final String file, final String vendor, final Entry... items) {
		return new Removal(file, vendor, Arrays.asList(items));
	}

	// An entry is its place in the store's lists, with the GUID it should
	// hold there as the check that the list has not changed.
	private static Entry at (final int index, final String guid) {
		return new Entry(index, guid);
	}

	@Test
	public void anItemGoesFromAllThreePlaces () throws Exception {
		final File save = setupSave(getClass());
		final File hall = new File(save, ARTIFICER_HALL);
		assertTrue(hasPacket(hall, TRAP_ARROW));

		final VendorManager manager = new VendorManager(save);
		assertTrue(manager.apply(Collections.singletonList(
			removal(ARTIFICER_HALL, STORE_ARTIFICER, at(0, TRAP_ARROW)))));
		assertFalse(manager.problem().isPresent());

		final Vendor artificer = vendor(save, ARTIFICER_HALL, STORE_ARTIFICER);
		assertEquals(1, artificer.items.size());
		assertEquals(TRAP_FLAMES, artificer.items.get(0).guid);
		assertEquals(2, artificer.items.get(0).stack);
		assertFalse("the item's own packet goes with it", hasPacket(hall, TRAP_ARROW));
		assertTrue(hasPacket(hall, TRAP_FLAMES));

		final DeserializedPackets written = packets(hall);
		assertEquals(
			"the leading count still matches the contents"
			, written.getPackets().size(), ((Number) written.getCount().obj).intValue());
	}

	// Store.Sort and BaseInventory.CompressSlots both number a store 0..n-1 in
	// list order, and every store in the real saves reads that way.
	@Test
	public void theRestCloseUp () throws Exception {
		final File save = setupSave(getClass());
		assertEquals(21, slots(new File(save, WORLD), HEODAN).size());

		assertTrue(new VendorManager(save).apply(Collections.singletonList(
			removal(WORLD, HEODAN, at(0, HEODAN_SHIELD), at(1, HEODAN_LOCKPICKS)))));

		final List<Integer> expected = new ArrayList<>();
		for (int i = 0; i < 19; i++) {
			expected.add(i);
		}

		assertEquals(expected, slots(new File(save, WORLD), HEODAN));
		assertFalse(hasPacket(new File(save, WORLD), HEODAN_SHIELD));
		assertFalse(hasPacket(new File(save, WORLD), HEODAN_LOCKPICKS));
	}

	@Test
	public void originalStockGoesOnlyWhenNamed () throws Exception {
		final File save = setupSave(getClass());

		assertTrue(new VendorManager(save).apply(Collections.singletonList(
			removal(FISHERY, FISHERY_STORE, at(1, RING_OF_OVERSEEING)))));

		final Vendor fishery = vendor(save, FISHERY, FISHERY_STORE);
		assertEquals(1, fishery.items.size());
		assertEquals(IVORY_WURM, fishery.items.get(0).guid);
		assertTrue(fishery.items.get(0).original);
	}

	@Test
	public void filesNothingWasTakenFromAreNotRewritten () throws Exception {
		final File save = setupSave(getClass());
		final byte[] fishery = FileUtils.readFileToByteArray(new File(save, FISHERY));
		final byte[] chapel = FileUtils.readFileToByteArray(new File(save, CHAPEL));
		final byte[] world = FileUtils.readFileToByteArray(new File(save, WORLD));

		assertTrue(new VendorManager(save).apply(Collections.singletonList(
			removal(ARTIFICER_HALL, STORE_ARTIFICER, at(1, TRAP_FLAMES)))));

		assertArrayEquals(fishery, FileUtils.readFileToByteArray(new File(save, FISHERY)));
		assertArrayEquals(chapel, FileUtils.readFileToByteArray(new File(save, CHAPEL)));
		assertArrayEquals(world, FileUtils.readFileToByteArray(new File(save, WORLD)));
	}

	@Test
	public void theOtherStoreInTheSameAreaIsUntouched () throws Exception {
		final File save = setupSave(getClass());

		assertTrue(new VendorManager(save).apply(Collections.singletonList(
			removal(ARTIFICER_HALL, STORE_ARTIFICER, at(0, TRAP_ARROW), at(1, TRAP_FLAMES)))));

		assertTrue(vendor(save, ARTIFICER_HALL, STORE_ARTIFICER).items.isEmpty());
		assertTrue(vendor(save, ARTIFICER_HALL, NPC_ARTIFICER).items.isEmpty());
		assertEquals(5, VendorStock.read(save).vendors.size());
	}

	@Test
	public void severalAreasInOneGo () throws Exception {
		final File save = setupSave(getClass());

		assertTrue(new VendorManager(save).apply(Arrays.asList(
			removal(ARTIFICER_HALL, STORE_ARTIFICER, at(0, TRAP_ARROW))
			, removal(CHAPEL, CHAPEL_PRIEST, at(0, "dc9bd0e1-e804-4bd6-a89a-7a4ccb9fe381"))
			, removal(WORLD, HEODAN, at(1, HEODAN_LOCKPICKS)))));

		assertEquals(1, vendor(save, ARTIFICER_HALL, STORE_ARTIFICER).items.size());
		assertEquals(2, vendor(save, CHAPEL, CHAPEL_PRIEST).items.size());
		assertEquals(20, vendor(save, WORLD, HEODAN).items.size());
	}

	// A request built from a list the page fetched earlier can be stale. It is
	// refused whole: nothing is written, not even the areas that were fine.
	@Test
	public void aStaleRequestChangesNothing () throws Exception {
		final File save = setupSave(getClass());
		final byte[] hall = FileUtils.readFileToByteArray(new File(save, ARTIFICER_HALL));

		final VendorManager manager = new VendorManager(save);
		assertFalse(manager.apply(Arrays.asList(
			removal(ARTIFICER_HALL, STORE_ARTIFICER, at(0, TRAP_ARROW))
			, removal(FISHERY, FISHERY_STORE, at(1, TRAP_FLAMES)))));

		assertTrue(manager.problem().isPresent());
		assertTrue(manager.problem().get(), manager.problem().get().contains("Fishery"));
		assertArrayEquals(hall, FileUtils.readFileToByteArray(new File(save, ARTIFICER_HALL)));
	}

	@Test
	public void aVendorThatIsNotThereIsRefused () throws Exception {
		final File save = setupSave(getClass());
		final VendorManager manager = new VendorManager(save);

		assertFalse(manager.apply(Collections.singletonList(
			removal(CHAPEL, STORE_ARTIFICER, at(0, TRAP_ARROW)))));
		assertTrue(manager.problem().isPresent());
	}

	// The file name crosses the bridge from a page. Only an area file or the
	// world state of this save may be named.
	@Test
	public void onlyTheSavesOwnPacketFilesCanBeNamed () throws Exception {
		final File save = setupSave(getClass());
		FileUtils.copyFile(new File(save, ARTIFICER_HALL), new File(save.getParentFile(), "outside.lvl"));

		for (final String file : new String[] {
			"../outside.lvl", "..\\outside.lvl", new File(save.getParentFile(), "outside.lvl").getAbsolutePath()
			, "saveinfo.xml", "", "AR_0611_Artificer_Hall.fog"}) {

			final VendorManager manager = new VendorManager(save);
			assertFalse(file, manager.apply(Collections.singletonList(
				removal(file, STORE_ARTIFICER, at(0, TRAP_ARROW)))));
			assertTrue(file, manager.problem().isPresent());
		}

		assertTrue(hasPacket(new File(save.getParentFile(), "outside.lvl"), TRAP_ARROW));
	}

	@Test
	public void nothingToDoIsNotAnError () throws Exception {
		final VendorManager manager = new VendorManager(setupSave(getClass()));
		assertTrue(manager.apply(Collections.emptyList()));
		assertFalse(manager.problem().isPresent());
	}

	// A GUID can outlive its packet: 242 entries of the stronghold merchant's
	// stock in a real save point at nothing, and the game keeps such an entry
	// as the bare prefab. Removing one is just the two list entries.
	@Test
	public void anEntryWithNoPacketStillGoes () throws Exception {
		final File save = setupSave(getClass());
		final File hall = new File(save, ARTIFICER_HALL);

		final DeserializedPackets before = packets(hall);
		final List<Property> kept = new ArrayList<>(before.getPackets());
		assertTrue(kept.removeIf(p -> TRAP_ARROW.equalsIgnoreCase(EKUtils.unwrapPacket(p).ObjectID)));
		assertTrue(Property.update(before.getCount(), kept.size()));
		// Damage the writer would refuse as an edit, so it is put together in
		// memory: judged against itself as it stands.
		new DeserializedPackets(kept, before.getCount()).replace(hall);

		assertFalse(vendor(save, ARTIFICER_HALL, STORE_ARTIFICER).items.get(0).hasPacket);

		assertTrue(new VendorManager(save).apply(Collections.singletonList(
			removal(ARTIFICER_HALL, STORE_ARTIFICER, at(0, TRAP_ARROW)))));
		assertEquals(1, vendor(save, ARTIFICER_HALL, STORE_ARTIFICER).items.size());
		assertEquals(kept.size(), packets(hall).getPackets().size());
	}

	// If anything else in the area still lists the item, its packet stays, or
	// that list would be left pointing at nothing.
	@Test
	public void aPacketAnotherListStillNamesIsKept () throws Exception {
		final File save = setupSave(getClass());
		final File hall = new File(save, ARTIFICER_HALL);

		final DeserializedPackets deserialized = packets(hall);
		final CollectionProperty source = storeList(deserialized, STORE_ARTIFICER);
		final CollectionProperty other = storeList(deserialized, NPC_ARTIFICER);
		final SimpleProperty original = (SimpleProperty) source.items.get(0);
		final SimpleProperty copy = new SimpleProperty(
			original.name, new TypePair(original.type.type, original.type.cSharpType));
		copy.value = original.value;
		copy.obj = original.obj;
		other.items.add(copy);
		// NPC_Artificer's two lists no longer pair up, which the writer would
		// refuse as an edit; put together in memory, it is judged as it stands.
		new DeserializedPackets(deserialized.getPackets(), deserialized.getCount()).replace(hall);

		assertTrue(new VendorManager(save).apply(Collections.singletonList(
			removal(ARTIFICER_HALL, STORE_ARTIFICER, at(0, TRAP_ARROW)))));

		assertEquals(1, vendor(save, ARTIFICER_HALL, STORE_ARTIFICER).items.size());
		assertTrue(hasPacket(hall, TRAP_ARROW));
	}

	// A GUID does not name an entry. 18 entries of the stronghold merchant's
	// stock in a real save share nine GUIDs between different items -- a
	// Dyrwoodan outfit and a monk's outfit under one -- none of them with a
	// packet. Taking out the second must not take the first.
	@Test
	public void anEntryIsFoundByItsPlaceNotItsGuid () throws Exception {
		final File save = setupSave(getClass());
		final File hall = new File(save, ARTIFICER_HALL);

		final DeserializedPackets deserialized = packets(hall);
		final CollectionProperty guids = storeList(deserialized, STORE_ARTIFICER);
		final SimpleProperty first = (SimpleProperty) guids.items.get(0);
		final SimpleProperty second = (SimpleProperty) guids.items.get(1);
		second.value = first.value;
		second.obj = first.obj;
		deserialized.replace(hall);

		final Vendor before = vendor(save, ARTIFICER_HALL, STORE_ARTIFICER);
		assertEquals(TRAP_ARROW, before.items.get(1).guid);
		assertEquals("Trap_Item_Fan_of_Flames", before.items.get(1).prefab);

		assertTrue(new VendorManager(save).apply(Collections.singletonList(
			removal(ARTIFICER_HALL, STORE_ARTIFICER, at(1, TRAP_ARROW)))));

		final Vendor after = vendor(save, ARTIFICER_HALL, STORE_ARTIFICER);
		assertEquals(1, after.items.size());
		assertEquals("the one asked for went", "Trap_Item_Arrow", after.items.get(0).prefab);
		assertTrue("and the arrow trap still has its packet", hasPacket(hall, TRAP_ARROW));
	}

	@Test
	public void aPlaceTheListDoesNotHaveIsRefused () throws Exception {
		final File save = setupSave(getClass());
		final byte[] hall = FileUtils.readFileToByteArray(new File(save, ARTIFICER_HALL));

		for (final Entry entry : new Entry[] {at(2, TRAP_ARROW), at(-1, TRAP_ARROW), at(1, TRAP_ARROW)}) {
			final VendorManager manager = new VendorManager(save);
			assertFalse(entry.index + "", manager.apply(Collections.singletonList(
				removal(ARTIFICER_HALL, STORE_ARTIFICER, entry))));
			assertTrue(manager.problem().isPresent());
		}

		assertArrayEquals(hall, FileUtils.readFileToByteArray(new File(save, ARTIFICER_HALL)));
	}

	@Test
	public void aPlaceNamedTwiceGoesOnce () throws Exception {
		final File save = setupSave(getClass());

		assertTrue(new VendorManager(save).apply(Collections.singletonList(
			removal(ARTIFICER_HALL, STORE_ARTIFICER, at(0, TRAP_ARROW), at(0, TRAP_ARROW)))));

		final Vendor artificer = vendor(save, ARTIFICER_HALL, STORE_ARTIFICER);
		assertEquals(1, artificer.items.size());
		assertEquals(TRAP_FLAMES, artificer.items.get(0).guid);
	}

	private static CollectionProperty storeList (
		final DeserializedPackets packets, final String store) {

		return SaveModel.of(packets).object(store)
			.flatMap(o -> o.component("Store"))
			.flatMap(c -> c.variable("SerializedItemList"))
			.map(list -> (CollectionProperty) list)
			.orElseThrow(() -> new AssertionError("no SerializedItemList on " + store));
	}
}
