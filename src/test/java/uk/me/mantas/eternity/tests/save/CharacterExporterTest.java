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
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.CharacterExporter;
import uk.me.mantas.eternity.save.model.ComponentBuilder;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;
import uk.me.mantas.eternity.tests.ExposedClass;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static uk.me.mantas.eternity.EKUtils.unwrapPacket;

public class CharacterExporterTest extends TestHarness {
	private static final String PLAYER_GUID = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String COMPANION_GUID = "b1a7e809-0000-0000-0000-000000000000";

	private File resourceDirectory () throws URISyntaxException {
		return new File(getClass().getResource("/").toURI());
	}

	private File tempChrFile () {
		final Optional<File> tempDir = EKUtils.createTempDir(PREFIX);
		assertTrue(tempDir.isPresent());
		return new File(tempDir.get(), "exported.chr");
	}

	@Test(expected = FileNotFoundException.class)
	public void constructorThrowsWhenSaveDirectoryMissing () throws IOException {
		new CharacterExporter("404", PLAYER_GUID, tempChrFile().getAbsolutePath());
	}

	@Test(expected = FileNotFoundException.class)
	public void exportThrowsWhenMobileObjectsFileMissing () throws IOException {
		final Optional<File> emptySaveDir = EKUtils.createTempDir(PREFIX);
		assertTrue(emptySaveDir.isPresent());

		final CharacterExporter exporter = new CharacterExporter(
			emptySaveDir.get().getAbsolutePath()
			, PLAYER_GUID
			, tempChrFile().getAbsolutePath());

		exporter.export();
	}

	@Test
	public void exportReturnsFalseWhenGUIDNotInSave ()
		throws IOException
		, URISyntaxException {

		final CharacterExporter exporter = new CharacterExporter(
			resourceDirectory().getAbsolutePath()
			, "00000000-dead-beef-0000-000000000000"
			, tempChrFile().getAbsolutePath());

		assertFalse(exporter.export());
	}

	@Test
	public void exportsPlayerCharacterToChrFile ()
		throws IOException
		, URISyntaxException {

		assertChrFileContainsCharacter(PLAYER_GUID);
	}

	@Test
	public void exportsCompanionToChrFile ()
		throws IOException
		, URISyntaxException {

		assertChrFileContainsCharacter(COMPANION_GUID);
	}

	@Test
	public void exportDoesNotModifyOriginalSave ()
		throws IOException
		, URISyntaxException {

		final File mobileObjectsFile = new File(resourceDirectory(), "MobileObjects.save");
		final byte[] before = FileUtils.readFileToByteArray(mobileObjectsFile);

		final CharacterExporter exporter = new CharacterExporter(
			resourceDirectory().getAbsolutePath()
			, PLAYER_GUID
			, tempChrFile().getAbsolutePath());

		assertTrue(exporter.export());

		final byte[] after = FileUtils.readFileToByteArray(mobileObjectsFile);
		assertArrayEquals(before, after);
	}

	@Test
	public void extractCharactersObjectsToleratesHostileData ()
		throws IOException
		, URISyntaxException {

		final String guid = "11111111-2222-3333-4444-555555555555";
		final CharacterExporter exporter = new CharacterExporter(
			resourceDirectory().getAbsolutePath()
			, guid
			, tempChrFile().getAbsolutePath());

		// Real saves contain objects with null ObjectIDs, Parents and
		// ObjectNames as well as the odd property that isn't an
		// ObjectPersistencePacket at all. None of them should trip us up.
		final Property nullFields = packetProperty(null, null, null);
		final Property character = packetProperty("Player_Test", guid, "");
		final Property child = packetProperty("Equipment", null, "Player_Test");
		final Property unrelated = packetProperty("Other", "some-guid", "NotOurCharacter");

		final Property garbage = new ComplexProperty("Root", null);
		garbage.obj = "not a packet";

		final List<Property> mobileObjects = new ArrayList<>();
		mobileObjects.add(nullFields);
		mobileObjects.add(garbage);
		mobileObjects.add(child);
		mobileObjects.add(character);
		mobileObjects.add(unrelated);

		final Map<Object, Class> argMap = new LinkedHashMap<>();
		argMap.put(SaveModel.of(mobileObjects), SaveModel.class);

		final ExposedClass exposedExporter = expose(exporter);
		final List<Property> extracted =
			exposedExporter.call("extractCharactersObjects", argMap);

		assertEquals(2, extracted.size());
		assertTrue(extracted.contains(character));
		assertTrue(extracted.contains(child));
	}

	@Test
	public void extractsCharacterWithNullObjectNameWithoutChildren ()
		throws IOException
		, URISyntaxException {

		final String guid = "11111111-2222-3333-4444-555555555555";
		final CharacterExporter exporter = new CharacterExporter(
			resourceDirectory().getAbsolutePath()
			, guid
			, tempChrFile().getAbsolutePath());

		final Property character = packetProperty(null, guid, null);
		final Property orphan = packetProperty("Equipment", null, "Player_Test");

		final List<Property> mobileObjects = new ArrayList<>();
		mobileObjects.add(character);
		mobileObjects.add(orphan);

		final Map<Object, Class> argMap = new LinkedHashMap<>();
		argMap.put(SaveModel.of(mobileObjects), SaveModel.class);

		final ExposedClass exposedExporter = expose(exporter);
		final List<Property> extracted =
			exposedExporter.call("extractCharactersObjects", argMap);

		assertEquals(1, extracted.size());
		assertTrue(extracted.contains(character));
	}

	private Property packetProperty (
		final String objectName
		, final String objectID
		, final String parent) {

		// The tree's fields, which the exporter reads; any may hold nothing.
		final ComplexProperty property =
			new ComplexProperty("Root", new TypePair(ObjectPersistencePacket.class, null));
		property.properties.add(ComponentBuilder.field("ObjectName", String.class, objectName));
		property.properties.add(ComponentBuilder.field("ObjectID", String.class, objectID));
		property.properties.add(ComponentBuilder.field("Parent", String.class, parent));
		property.properties.add(new SingleDimensionalArrayProperty("ComponentPackets", null));
		return property;
	}

	private void assertChrFileContainsCharacter (final String guid)
		throws IOException
		, URISyntaxException {

		final File chrFile = tempChrFile();
		final CharacterExporter exporter = new CharacterExporter(
			resourceDirectory().getAbsolutePath()
			, guid
			, chrFile.getAbsolutePath());

		assertTrue(exporter.export());
		assertTrue(chrFile.exists());
		assertTrue(chrFile.length() > 0);

		final PacketDeserializer deserializer = new PacketDeserializer(chrFile);
		final Optional<DeserializedPackets> deserialized = deserializer.deserialize();
		assertTrue(deserialized.isPresent());

		final List<Property> packets = deserialized.get().getPackets();
		final int count = (int) deserialized.get().getCount().obj;

		assertEquals(count, packets.size());
		assertTrue(packets.size() >= 1);

		final List<ObjectPersistencePacket> characterPackets = packets.stream()
			.map(EKUtils::unwrapPacket)
			.filter(packet -> guid.equals(packet.ObjectID))
			.collect(Collectors.toList());

		assertEquals(1, characterPackets.size());
		final String objectName = characterPackets.get(0).ObjectName;

		for (final Property property : packets) {
			final ObjectPersistencePacket packet = unwrapPacket(property);
			assertTrue(
				"Every exported object must be the character itself or owned by it"
				, guid.equals(packet.ObjectID) || objectName.equals(packet.Parent));
		}
	}
}
