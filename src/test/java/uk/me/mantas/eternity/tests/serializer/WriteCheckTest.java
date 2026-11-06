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


package uk.me.mantas.eternity.tests.serializer;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.InconsistentWriteException;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;
import uk.me.mantas.eternity.tests.TestHarness;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.*;

// Nothing that contradicts itself is written.
//
// The editor's writers edit the property tree the serializer writes, and every
// one of the save's own invariants has at some point been broken by one of
// them: an item minted with its template's GUID, a slot GUID written as text,
// a count left behind. Each surfaced only when the game dropped something
// without a word, or the save stopped reading. So a write is checked against
// the file as it was read, and refused when the edit has made it contradict
// itself in a way it did not before. What the file already got wrong is left
// alone: real saves carry their own oddities, and they are not the edit's.
//
// The fixture is the prologue world state: Player_Elwyn carries a ring and a
// pet figurine in his PlayerInventory, and Companion_Calisca an empty pack.
public class WriteCheckTest extends TestHarness {
	private static final String ELWYN = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String RING = "c4b7033d-c452-4946-a9ba-9998cb411201";
	private static final String PET = "41baf584-9203-44df-a921-62f50000ad74";

	private File copy () throws URISyntaxException, IOException {
		final Optional<File> dir = EKUtils.createTempDir(PREFIX);
		assertTrue(dir.isPresent());
		final File resources = new File(getClass().getResource("/").toURI());
		final File file = new File(dir.get(), "MobileObjects.save");
		FileUtils.copyFile(new File(resources, "MobileObjects.save"), file);
		return file;
	}

	private static DeserializedPackets read (final File file) throws IOException {
		return new PacketDeserializer(file).deserialize()
			.orElseThrow(() -> new AssertionError("the fixture reads"));
	}

	private static SaveObject packet (final DeserializedPackets packets, final String id) {
		return SaveModel.of(packets).object(id)
			.orElseThrow(() -> new AssertionError("no packet " + id));
	}

	private static Property variable (
		final DeserializedPackets packets, final String id, final String component
		, final String name) {

		return packet(packets, id).component(component)
			.flatMap(c -> c.variable(name))
			.orElseThrow(() -> new AssertionError("no " + component + "." + name + " on " + id));
	}

	// The written file is refused whole: the original bytes are still there,
	// and no half-written sibling is left beside them.
	private static InconsistentWriteException refused (
		final DeserializedPackets packets, final File file) throws IOException {

		final byte[] before = FileUtils.readFileToByteArray(file);
		try {
			packets.replace(file);
			fail("the write should have been refused");
			return null;
		} catch (final InconsistentWriteException e) {
			assertArrayEquals("nothing was written", before, FileUtils.readFileToByteArray(file));
			final File[] siblings = file.getParentFile().listFiles((d, n) -> n.endsWith(".writing"));
			assertEquals("no temporary file left behind", 0, siblings == null ? 0 : siblings.length);
			return e;
		}
	}

	@Test
	public void anUntouchedReadWritesAsItWas () throws Exception {
		final File file = copy();
		final byte[] before = FileUtils.readFileToByteArray(file);

		read(file).replace(file);
		assertArrayEquals(before, FileUtils.readFileToByteArray(file));
	}

	@Test
	public void anEditThatKeepsTheFileConsistentIsWritten () throws Exception {
		final File file = copy();
		final DeserializedPackets packets = read(file);
		assertTrue(Property.update(variable(packets, ELWYN, "CharacterStats", "Experience"), 12345));

		packets.replace(file);
		assertEquals(12345, ((SimpleProperty) variable(read(file), ELWYN, "CharacterStats", "Experience")).obj);
	}

	// Every manager used to set the count to the packets it held, and one that
	// forgot left every read after it stopping early. The count is now what
	// the file holds, whoever wrote it.
	@Test
	public void theCountIsWhatTheFileHolds () throws Exception {
		final File file = copy();
		final DeserializedPackets packets = read(file);
		final List<Property> fewer = new ArrayList<>(packets.getPackets());
		fewer.remove(fewer.size() - 1);
		packets.setPackets(fewer);

		packets.replace(file);
		final DeserializedPackets after = read(file);
		assertTrue(after.isWhole());
		assertEquals(fewer.size(), after.getPackets().size());
		assertEquals(fewer.size(), ((Number) after.getCount().obj).intValue());
	}

	// The same for a file put together in memory, with whatever count it was
	// handed.
	@Test
	public void aNewFilesCountIsWhatItHolds () throws Exception {
		final File file = copy();
		final DeserializedPackets packets = read(file);
		final List<Property> two = new ArrayList<>(packets.getPackets().subList(0, 2));

		final File written = new File(file.getParentFile(), "two.save");
		assertTrue(written.createNewFile());
		new DeserializedPackets(two, packets.getCount()).reserialize(written);

		final DeserializedPackets after = read(written);
		assertTrue(after.isWhole());
		assertEquals(2, after.getPackets().size());
	}

	// The item-minting bug: a copied packet kept its template's identity.
	@Test
	public void twoObjectsWithOneIdAreRefused () throws Exception {
		final File file = copy();
		final DeserializedPackets packets = read(file);
		assertTrue(Property.update(packet(packets, PET).property(), "ObjectID", RING));

		refused(packets, file);
	}

	@Test
	public void anInstanceIdThatNoLongerMatchesItsObjectIsRefused () throws Exception {
		final File file = copy();
		final DeserializedPackets packets = read(file);
		assertTrue(Property.update(variable(packets, RING, "InstanceID", "Guid"), UUID.randomUUID()));

		refused(packets, file);
	}

	@Test
	public void listsThatNoLongerPairUpAreRefused () throws Exception {
		final File file = copy();
		final DeserializedPackets packets = read(file);
		((CollectionProperty) variable(packets, ELWYN, "PlayerInventory", "ItemList")).items.remove(0);

		final InconsistentWriteException e = refused(packets, file);
		assertTrue(e.getMessage(), e.getMessage().contains("PlayerInventory"));
	}

	// "Purge, don't orphan": an item whose object is gone is dropped by the
	// game on load, silently.
	@Test
	public void anItemWithoutItsObjectIsRefused () throws Exception {
		final File file = copy();
		final DeserializedPackets packets = read(file);
		final List<Property> without = new ArrayList<>(packets.getPackets());
		assertTrue(without.remove(packet(packets, RING).property()));
		packets.setPackets(without);
		assertTrue(Property.update(packets.getCount(), without.size()));

		final InconsistentWriteException e = refused(packets, file);
		assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains(RING));
	}

	// The serializer writes a value by what it holds, not by what the file
	// says it should hold: a GUID written as text is a string where sixteen
	// bytes belong, and nothing after it reads again.
	@Test
	public void aGuidWrittenAsTextIsRefused () throws Exception {
		final File file = copy();
		final DeserializedPackets packets = read(file);
		final Property guid = variable(packets, RING, "InstanceID", "Guid");
		assertTrue(Property.update(guid, ((SimpleProperty) guid).obj.toString()));

		refused(packets, file);
	}

	@Test
	public void aNumberOfTheWrongKindIsRefused () throws Exception {
		final File file = copy();

		final DeserializedPackets wide = read(file);
		assertTrue(Property.update(variable(wide, ELWYN, "CharacterStats", "Experience"), 12345L));
		refused(wide, file);

		final DeserializedPackets doubled = read(file);
		assertTrue(Property.update(variable(doubled, ELWYN, "Health", "CurrentHealth"), 50.0));
		refused(doubled, file);
	}

	// An enum is written as its number either way, which is how a value the
	// mirror enum does not know survives a round trip.
	@Test
	public void anEnumMayHoldItsNumber () throws Exception {
		final File file = copy();
		final DeserializedPackets packets = read(file);
		assertTrue(Property.update(variable(packets, ELWYN, "CharacterStats", "CharacterClass"), 2));

		packets.replace(file);
	}

	// Real saves carry their own oddities -- a store's list naming items that
	// have no object, in every mid-game save -- and they are not the edit's to
	// answer for. Only what the edit newly got wrong is refused.
	@Test
	public void whatTheFileAlreadyGotWrongDoesNotStopAnEdit () throws Exception {
		final File file = copy();
		final DeserializedPackets original = read(file);
		final List<Property> without = new ArrayList<>(original.getPackets());
		assertTrue(without.remove(packet(original, PET).property()));
		assertTrue(Property.update(original.getCount(), without.size()));
		new DeserializedPackets(without, original.getCount()).replace(file);

		final DeserializedPackets packets = read(file);
		assertTrue(Property.update(variable(packets, ELWYN, "CharacterStats", "Experience"), 777));
		packets.replace(file);
		assertEquals(777, ((SimpleProperty) variable(read(file), ELWYN, "CharacterStats", "Experience")).obj);

		final DeserializedPackets worse = read(file);
		final List<Property> alsoWithout = new ArrayList<>(worse.getPackets());
		assertTrue(alsoWithout.remove(packet(worse, RING).property()));
		worse.setPackets(alsoWithout);
		assertTrue(Property.update(worse.getCount(), alsoWithout.size()));

		final InconsistentWriteException e = refused(worse, file);
		assertTrue("only the new problem is named: " + e.getMessage()
			, e.getMessage().toLowerCase().contains(RING) && !e.getMessage().toLowerCase().contains(PET));
	}

	@Test
	public void theRefusalSaysWhatAndWhere () throws Exception {
		final File file = copy();
		final DeserializedPackets packets = read(file);
		((CollectionProperty) variable(packets, ELWYN, "PlayerInventory", "ItemList")).items.remove(0);

		final String message = refused(packets, file).getMessage();
		assertTrue(message, message.contains("MobileObjects.save"));
		assertTrue(message, message.contains("nothing was written"));
		assertTrue(message, message.contains("Player_Elwyn"));
	}

	// A writer that changes several files checks them all before writing any.
	@Test
	public void aWriteCanBeCheckedBeforeItIsMade () throws Exception {
		final File file = copy();
		final DeserializedPackets packets = read(file);
		packets.checkWritable();

		((CollectionProperty) variable(packets, ELWYN, "PlayerInventory", "ItemList")).items.remove(0);
		try {
			packets.checkWritable();
			fail("the check should refuse");
		} catch (final InconsistentWriteException expected) {
			// what replace would have refused
		}
	}
}
