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

import org.junit.Test;
import uk.me.mantas.eternity.save.SaveDiff;
import uk.me.mantas.eternity.save.SaveDiff.Change;
import uk.me.mantas.eternity.save.SaveDiff.Leaf;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

// Two world states compared object by object: the raw half of comparing two
// saves, which SaveComparison then puts into words. The fixture is the
// prologue save every other test uses; each test changes one thing in a
// second reading of it.
public class SaveDiffTest extends TestHarness {
	static final String ELWYN = "09517a0d-4fec-407c-a749-a531f3be64e0";
	static final String RING = "c4b7033d-c452-4946-a9ba-9998cb411201";
	static final String IN_GAME_GLOBAL = "f6906576-1112-478c-93cb-79672b9c7652";

	static List<Property> read () throws Exception {
		final File file = new File(new File(SaveDiffTest.class.getResource("/").toURI()), "MobileObjects.save");
		final Optional<DeserializedPackets> packets = new PacketDeserializer(file).deserialize();
		assertTrue(packets.isPresent());
		return packets.get().getPackets();
	}

	static ComplexProperty packet (final List<Property> packets, final String id) {
		for (final Property property : packets) {
			final ComplexProperty packet = (ComplexProperty) property;
			if (id.equalsIgnoreCase(String.valueOf(packet.findProperty("ObjectID").get().obj))) {
				return packet;
			}
		}

		throw new AssertionError("no object " + id);
	}

	static DictionaryProperty variables (final ComplexProperty packet, final String component) {
		final SingleDimensionalArrayProperty components =
			(SingleDimensionalArrayProperty) packet.findProperty("ComponentPackets").get();

		for (final Object item : components.items) {
			final ComplexProperty candidate = (ComplexProperty) item;
			if (component.equals(candidate.findProperty("TypeString").get().obj)) {
				return (DictionaryProperty) candidate.findProperty("Variables").get();
			}
		}

		throw new AssertionError("no " + component);
	}

	static Property variable (final ComplexProperty packet, final String component, final String name) {
		return variables(packet, component).findEntry(name)
			.orElseThrow(() -> new AssertionError("no " + component + "." + name));
	}

	static void set (final Property property, final Object value) {
		assertTrue(Property.update(property, value));
	}

	@Test
	public void aSaveHasNothingToTellApartFromItself () throws Exception {
		final SaveDiff diff = SaveDiff.between(read(), read());

		assertEquals(23, diff.before.size());
		assertEquals(23, diff.after.size());
		assertEquals(Collections.emptyList(), diff.added);
		assertEquals(Collections.emptyList(), diff.removed);
		assertEquals(Collections.emptyList(), diff.changes);
	}

	@Test
	public void aChangedValueIsFoundWhereItIs () throws Exception {
		final List<Property> after = read();
		final Property might = variable(packet(after, ELWYN), "CharacterStats", "BaseMight");
		final Object was = might.obj;
		set(might, (Integer) was + 8);

		final SaveDiff diff = SaveDiff.between(read(), after);

		assertEquals(1, diff.changes.size());
		final Change change = diff.changes.get(0);
		assertEquals(ELWYN, change.id);
		assertEquals("CharacterStats", change.component);
		assertEquals("BaseMight", change.variable);
		assertEquals(was, change.before.obj);
		assertEquals((Integer) was + 8, change.after.obj);
	}

	@Test
	public void anObjectOnlyOneSideHasIsAddedOrRemoved () throws Exception {
		final List<Property> before = read();
		final List<Property> after = read();
		after.remove(packet(after, RING));

		SaveDiff diff = SaveDiff.between(before, after);
		assertEquals(Collections.singletonList(RING), diff.removed);
		assertEquals(Collections.emptyList(), diff.added);
		assertEquals("nothing else differs", Collections.emptyList(), diff.changes);

		diff = SaveDiff.between(after, before);
		assertEquals(Collections.singletonList(RING), diff.added);
		assertEquals(Collections.emptyList(), diff.removed);
	}

	// The world state keeps its global variables in a .NET Hashtable, whose
	// order is the table's, not the game's: two saves holding the same values
	// may list them differently.
	@Test
	public void aDictionaryInAnotherOrderHoldsTheSameValues () throws Exception {
		final List<Property> after = read();
		final DictionaryProperty globals = (DictionaryProperty) variable(
			packet(after, IN_GAME_GLOBAL), "GlobalVariables", "m_data");

		assertTrue(globals.items.size() > 10);
		Collections.reverse(globals.items);
		assertEquals(Collections.emptyList(), SaveDiff.between(read(), after).changes);

		final Map.Entry<Property, Property> entry = globals.items.get(3);
		final Object was = entry.getValue().obj;
		set(entry.getValue(), (Integer) was + 1);

		final List<Change> changes = SaveDiff.between(read(), after).changes;
		assertEquals(1, changes.size());
		assertEquals("m_data", changes.get(0).variable);

		final List<Leaf> leaves = SaveDiff.leaves(changes.get(0).before, changes.get(0).after);
		assertEquals(1, leaves.size());
		assertEquals("[" + entry.getKey().obj + "]", leaves.get(0).path);
		assertEquals(was, leaves.get(0).before.obj);
		assertEquals((Integer) was + 1, leaves.get(0).after.obj);
	}

	@Test
	public void anObjectsOwnFieldsAreComparedToo () throws Exception {
		final List<Property> after = read();
		final ComplexProperty location =
			(ComplexProperty) packet(after, RING).findProperty("Location").get();
		final Property x = location.findProperty("x").get();
		set(x, (Float) x.obj + 2.5f);
		set(packet(after, RING).findProperty("Parent").get(), "Companion_Calisca(Clone)_1");

		final List<Change> changes = SaveDiff.between(read(), after).changes;

		assertEquals(2, changes.size());
		assertEquals("", changes.get(0).component);
		assertEquals("Location", changes.get(0).variable);
		assertEquals("Parent", changes.get(1).variable);
		assertEquals("Player_Elwyn", changes.get(1).before.obj);

		final List<Leaf> leaves = SaveDiff.leaves(changes.get(0).before, changes.get(0).after);
		assertEquals(1, leaves.size());
		assertEquals(".x", leaves.get(0).path);
	}

	// The leaves of a list are its places, and of an object its fields, so a
	// change deep inside a value is named by how to reach it.
	@Test
	public void aLeafIsNamedByThePathToIt () throws Exception {
		final List<Property> after = read();
		final CollectionProperty items = (CollectionProperty)
			variable(packet(after, ELWYN), "PlayerInventory", "ItemList");
		final ComplexProperty first = (ComplexProperty) items.items.get(0);
		final Property uiSlot = first.findProperty("uiSlot").get();
		set(uiSlot, (Integer) uiSlot.obj + 5);

		final List<Change> changes = SaveDiff.between(read(), after).changes;
		assertEquals(1, changes.size());
		assertEquals("ItemList", changes.get(0).variable);

		final List<Leaf> leaves = SaveDiff.leaves(changes.get(0).before, changes.get(0).after);
		assertEquals(1, leaves.size());
		assertEquals("[0].uiSlot", leaves.get(0).path);
	}

	// A list that grew has leaves only on one side.
	@Test
	public void aLongerListEndsInLeavesWithNothingBefore () throws Exception {
		final List<Property> after = read();
		final CollectionProperty items = (CollectionProperty)
			variable(packet(after, ELWYN), "PlayerInventory", "ItemList");
		final int was = items.items.size();
		items.items.add(items.items.get(0));

		final List<Change> changes = SaveDiff.between(read(), after).changes;
		final List<Leaf> leaves = SaveDiff.leaves(changes.get(0).before, changes.get(0).after);
		assertEquals(1, leaves.size());
		assertEquals("[" + was + "]", leaves.get(0).path);
		assertNull(leaves.get(0).before);
		assertNotNull(leaves.get(0).after);
	}

	// A list has fields of its own beside its items -- a .NET List keeps the
	// size of its buffer -- and a difference there is a leaf like any other.
	@Test
	public void aListsOwnFieldsAreLeavesToo () throws Exception {
		final List<Property> after = read();
		final CollectionProperty items = (CollectionProperty)
			variable(packet(after, ELWYN), "PlayerInventory", "ItemList");
		set(items.findProperty("Capacity").get(), 8);

		final List<Change> changes = SaveDiff.between(read(), after).changes;
		assertEquals(1, changes.size());

		final List<Leaf> leaves = SaveDiff.leaves(changes.get(0).before, changes.get(0).after);
		assertEquals(1, leaves.size());
		assertEquals(".Capacity", leaves.get(0).path);
	}

	// Quest progress and the conversations read are .NET binary blobs held as
	// byte arrays: two readings of one file are two arrays, and must still be
	// the same value.
	@Test
	public void binaryDataIsComparedByWhatItHolds () throws Exception {
		final List<Property> after = read();
		final Property trackers = variable(packet(after, IN_GAME_GLOBAL), "QuestManager", "QuestTrackers");
		assertTrue(trackers.obj instanceof Byte[]);
		assertEquals(Collections.emptyList(), SaveDiff.between(read(), after).changes);

		final Byte[] copy = ((Byte[]) trackers.obj).clone();
		copy[copy.length / 2] = (byte) (copy[copy.length / 2] + 1);
		set(trackers, copy);

		final List<Change> changes = SaveDiff.between(read(), after).changes;
		assertEquals(1, changes.size());
		assertEquals("QuestTrackers", changes.get(0).variable);
	}

	@Test
	public void sameTellsValuesApart () throws Exception {
		final List<Property> one = read();
		final List<Property> two = read();
		final List<Property> left = new ArrayList<>();
		final List<Property> right = new ArrayList<>();
		left.add(packet(one, ELWYN));
		right.add(packet(two, ELWYN));

		assertTrue(SaveDiff.same(left.get(0), right.get(0)));
		assertFalse(SaveDiff.same(left.get(0), packet(two, RING)));
		assertFalse(SaveDiff.same(left.get(0), null));
		assertTrue(SaveDiff.same(null, null));

		final SimpleProperty might = (SimpleProperty) variable(packet(two, ELWYN), "CharacterStats", "BaseMight");
		set(might, (Integer) might.obj + 1);
		assertFalse(SaveDiff.same(left.get(0), right.get(0)));
	}
}
