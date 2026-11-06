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
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.SaveValidator;
import uk.me.mantas.eternity.save.SaveValidator.Problem;
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.*;

// The invariants this project learned the hard way, asserted before a save is
// written rather than discovered in-game.
//
// Every check here was measured against four real saves first -- the 4,894
// packet mid-game one, an early-prologue one, and both unit fixtures -- and
// reports nothing on any of them. A fifth candidate did not survive that:
// "Parent names an object that exists" fires 29 times on a healthy save,
// because a dead companion's items outlive the companion the game deleted.
// A check that cries wolf on an untouched save is worse than no check.
public class SaveValidatorTest extends TestHarness {
	private static final String GUID_A = "aaaaaaaa-0000-0000-0000-000000000001";
	private static final String GUID_B = "bbbbbbbb-0000-0000-0000-000000000002";
	private static final String MISSING = "cccccccc-0000-0000-0000-000000000003";

	/** A packet with a matching InstanceID, which is the healthy shape. */
	private Property packet (final String name, final String objectID) {
		final ObjectPersistencePacket packet = new ObjectPersistencePacket();
		packet.ObjectName = name;
		packet.ObjectID = objectID;
		packet.Parent = "none";

		final ComponentPersistencePacket instanceID = new ComponentPersistencePacket();
		instanceID.TypeString = "InstanceID";
		instanceID.Variables = new HashMap<>();
		instanceID.Variables.put("Guid", UUID.fromString(objectID));

		packet.ComponentPackets = new ComponentPersistencePacket[]{instanceID};

		final Property property =
			new ComplexProperty(null, new TypePair(ObjectPersistencePacket.class, null));

		property.obj = packet;
		return property;
	}

	private ComponentPersistencePacket component (
		final Property property, final String type) {

		final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
		final ComponentPersistencePacket added = new ComponentPersistencePacket();
		added.TypeString = type;
		added.Variables = new HashMap<>();

		final List<ComponentPersistencePacket> components =
			new ArrayList<>(Arrays.asList(packet.ComponentPackets));

		components.add(added);
		packet.ComponentPackets =
			components.toArray(new ComponentPersistencePacket[components.size()]);

		return added;
	}

	private CSharpCollection collection (final Object... items) {
		final CSharpCollection collection = new CSharpCollection();
		for (final Object item : items) {
			collection.add(item);
		}

		return collection;
	}

	private List<Problem> validate (final Property... packets) {
		return SaveValidator.validate(Arrays.asList(packets), packets.length);
	}

	@Test
	public void aHealthySaveReportsNothing () {
		assertTrue(validate(packet("Player_Elwyn", GUID_A),
			packet("Sword(Clone)", GUID_B)).isEmpty());
	}

	@Test
	public void twoObjectsCannotShareAnID () {
		// This is the signature of the item-minting aliasing bug: inserting the
		// template's own Property objects into a new packet rewrote the
		// template's GUID too, and the game silently dropped both items.
		final List<Problem> problems =
			validate(packet("Sword(Clone)", GUID_A), packet("Sword(Clone)", GUID_A));

		assertEquals(1, problems.size());
		assertEquals(Problem.Kind.DUPLICATE_OBJECT_ID, problems.get(0).kind);
		assertTrue(problems.get(0).detail.contains("2"));
	}

	@Test
	public void anObjectsInstanceIDMustBeItsOwnID () {
		final Property odd = packet("Ring(Clone)", GUID_A);
		((ObjectPersistencePacket) odd.obj).ComponentPackets[0]
			.Variables.put("Guid", UUID.fromString(GUID_B));

		final List<Problem> problems = validate(odd);
		assertEquals(1, problems.size());
		assertEquals(Problem.Kind.INSTANCE_ID_MISMATCH, problems.get(0).kind);
		assertEquals("Ring(Clone)", problems.get(0).objectName);
	}

	@Test
	public void anObjectWithNoInstanceIDIsFine () {
		// Globals and a handful of others carry none; ten of the 4,894 packets
		// in the real save are like this.
		final Property property = packet("Global(Clone)", GUID_A);
		((ObjectPersistencePacket) property.obj).ComponentPackets =
			new ComponentPersistencePacket[0];

		assertTrue(validate(property).isEmpty());
	}

	@Test
	public void theTwoInventoryListsMustBeTheSameLength () {
		// Invariant 6: ItemList and SerializedItemList are parallel, same index.
		final Property owner = packet("Player_Elwyn", GUID_A);
		final ComponentPersistencePacket inventory = component(owner, "PlayerInventory");
		inventory.Variables.put("ItemList", collection("a", "b"));
		inventory.Variables.put(
			"SerializedItemList", collection(UUID.fromString(GUID_B)));

		final List<Problem> problems = validate(owner, packet("Sword", GUID_B));
		assertEquals(1, problems.size());
		assertEquals(Problem.Kind.ITEM_LIST_LENGTH, problems.get(0).kind);
	}

	@Test
	public void everyCarriedItemMustHaveItsOwnPacket () {
		// Every SerializedItemList UUID is also the ObjectID of a standalone
		// top-level packet. Removing one without the other orphans the entry
		// and the game drops the container's contents.
		final Property owner = packet("Player_Elwyn", GUID_A);
		final ComponentPersistencePacket inventory = component(owner, "PlayerInventory");
		inventory.Variables.put("ItemList", collection("a"));
		inventory.Variables.put(
			"SerializedItemList", collection(UUID.fromString(MISSING)));

		final List<Problem> problems = validate(owner);
		assertEquals(1, problems.size());
		assertEquals(Problem.Kind.UNRESOLVED_ITEM, problems.get(0).kind);
		assertTrue(problems.get(0).detail.toLowerCase().contains(MISSING));
	}

	@Test
	public void everyWornItemMustHaveItsOwnPacketToo () {
		final Property owner = packet("Player_Elwyn", GUID_A);
		final ComponentPersistencePacket equipment = component(owner, "Equipment");
		equipment.Variables.put("EquipmentSetSerialized"
			, collection(UUID.fromString(MISSING)));

		final List<Problem> problems = validate(owner);
		assertEquals(1, problems.size());
		assertEquals(Problem.Kind.UNRESOLVED_EQUIPMENT, problems.get(0).kind);
	}

	@Test
	public void anEmptyEquipmentSlotIsNotAProblem () {
		// Ten of the eleven slots are usually the all-zero UUID, and the
		// deprecated Cape slot always is.
		final Property owner = packet("Player_Elwyn", GUID_A);
		final ComponentPersistencePacket equipment = component(owner, "Equipment");
		equipment.Variables.put("EquipmentSetSerialized"
			, collection(new UUID(0, 0), null, new UUID(0, 0)));

		assertTrue(validate(owner).isEmpty());
	}

	@Test
	public void theLeadingCountMustMatchTheContents () {
		// Invariant 5. A count that is too low makes every read stop early, so
		// the save silently loses everything past it.
		final List<Problem> problems = SaveValidator.validate(
			Arrays.asList(packet("Player_Elwyn", GUID_A), packet("Sword", GUID_B)), 1);

		assertEquals(1, problems.size());
		assertEquals(Problem.Kind.OBJECT_COUNT, problems.get(0).kind);
		assertTrue(problems.get(0).detail.contains("1"));
		assertTrue(problems.get(0).detail.contains("2"));
	}

	@Test
	public void severalProblemsAreAllReported () {
		final Property odd = packet("Ring(Clone)", GUID_A);
		((ObjectPersistencePacket) odd.obj).ComponentPackets[0]
			.Variables.put("Guid", UUID.fromString(GUID_B));

		final List<Problem> problems = SaveValidator.validate(
			Arrays.asList(odd, packet("Ring(Clone)", GUID_A)), 5);

		assertEquals(3, problems.size());
		assertTrue(problems.stream()
			.anyMatch(p -> p.kind == Problem.Kind.DUPLICATE_OBJECT_ID));
		assertTrue(problems.stream()
			.anyMatch(p -> p.kind == Problem.Kind.INSTANCE_ID_MISMATCH));
		assertTrue(problems.stream()
			.anyMatch(p -> p.kind == Problem.Kind.OBJECT_COUNT));
	}

	@Test
	public void nothingIsSaidAboutNothing () {
		assertTrue(SaveValidator.validate(null, 0).isEmpty());
		assertTrue(SaveValidator.validate(new ArrayList<>(), 0).isEmpty());
	}

	// A file read short is its own problem, not a wrong count: the count may be
	// perfectly right, and what is missing is whatever the file held after the
	// damage. It is said once, as what it is.
	@Test
	public void aFileReadShortSaysSo () throws Exception {
		final File resources = new File(getClass().getResource("/").toURI());
		final File world = new File(EKUtils.createTempDir(PREFIX).get(), "MobileObjects.save");
		FileUtils.copyFile(new File(resources, "MobileObjects.save"), world);
		ShortReadTest.cutShort(world, "Global(Clone)");

		final DeserializedPackets partial =
			new PacketDeserializer(world).deserializeEvenIfShort().get();

		final Optional<Problem> problem = SaveValidator.shortRead(partial);
		assertTrue(problem.isPresent());
		assertEquals(Problem.Kind.SHORT_READ, problem.get().kind);
		assertTrue(problem.get().detail
			, problem.get().detail.startsWith("only 22 of the 23 objects in MobileObjects.save"));

		final List<Problem> problems = SaveValidator.validate(partial);
		assertEquals(problems.toString(), 1, problems.size());
		assertEquals(Problem.Kind.SHORT_READ, problems.get(0).kind);

		assertFalse(SaveValidator.shortRead(new PacketDeserializer(
			new File(resources, "MobileObjects.save")).deserialize().get()).isPresent());
	}

	@Test
	public void theRealFixturesPassCleanly ()
		throws URISyntaxException, IOException {

		// The point of the whole thing: a save nobody has broken says nothing.
		final File resources = new File(getClass().getResource("/").toURI());

		for (final String fixture
			: new String[]{"MobileObjects.save", "GrimoireManagerTest/MobileObjects.save"}) {

			final Optional<DeserializedPackets> packets =
				new PacketDeserializer(new File(resources, fixture)).deserialize();

			assertTrue(fixture, packets.isPresent());

			final List<Problem> problems = SaveValidator.validate(packets.get());
			assertEquals(fixture + ": " + problems, 0, problems.size());
		}
	}
}
