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
import uk.me.mantas.eternity.game.CharacterStats;
import uk.me.mantas.eternity.serializer.*;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

import static org.junit.Assert.*;

public class SerializerTest extends TestHarness {

	// These used to wrap their assertions in `try { … } catch (Exception e)
	// { e.printStackTrace(); }`, which swallowed the AssertionError too — they
	// could not fail. Anything thrown is now the test result.

	@Test
	public void serializesSaveFile () throws Exception {
		final File saveFile = new File(getClass().getResource("/MobileObjects.save").toURI());
		final File saveOutputFile = Files.createTempFile(PREFIX, null).toFile();

		EKUtils.reserializeFile(saveFile, saveOutputFile, SerializerFormat.PRESERVE);
		assertFileContentsEquals(saveFile, saveOutputFile);
	}

	@Test
	public void serializesLevelFile () throws Exception {
		final File saveFile = new File(getClass().getResource("/SerializerTest/windowStoreSave/AR_0701_Encampment.lvl").toURI());
		final File saveOutputFile = Files.createTempFile(PREFIX, null).toFile();

		EKUtils.reserializeFile(saveFile, saveOutputFile, SerializerFormat.PRESERVE);
		assertFileContentsEquals(saveFile, saveOutputFile);
	}

	@Test
	public void serializesWindowsStoreToSteamSaveFile () throws Exception {
		final File saveFile = new File(getClass().getResource("/SerializerTest/windowStoreSave/MobileObjects.save").toURI());
		final File saveOutputFile = Files.createTempFile(PREFIX, null).toFile();

		EKUtils.reserializeFile(saveFile, saveOutputFile, SerializerFormat.UNITY_2017);

		final File expectedSaveFile = new File(getClass().getResource("/SerializerTest/windowStoreSaveConverted/MobileObjects.save").toURI());
		assertFileContentsEquals(expectedSaveFile, saveOutputFile);
	}

	// Newer versions of the game store enum values that this port's enum
	// definitions don't know about (e.g. CharacterStats+Class #53,
	// StatusEffect+ModifiedStat #282 in 2025-era saves). Unknown values must
	// survive a deserialize/reserialize round trip instead of being nulled.
	@Test
	public void preservesUnknownEnumValues () throws IOException {
		final int unknownValue = 999;
		final File file = Files.createTempFile(PREFIX, null).toFile();

		final SimpleProperty count = new SimpleProperty(
			"Root", new TypePair(int.class, "System.Int32"));
		count.value = 1;
		count.obj = 1;

		final SimpleProperty enumProperty = new SimpleProperty(
			"Root", new TypePair(CharacterStats.Class.class, "CharacterStats+Class"));
		enumProperty.value = unknownValue;
		enumProperty.obj = unknownValue;

		final SharpSerializer serializer = new SharpSerializer(file.getAbsolutePath());
		serializer.serialize(count);
		serializer.serialize(enumProperty);

		// The raw value must be preserved in memory...
		final SharpSerializer deserializer = new SharpSerializer(file.getAbsolutePath());
		assertTrue(deserializer.deserialize().isPresent()); // skip count
		final Optional<Property> read = deserializer.deserialize();
		assertTrue(read.isPresent());
		assertEquals(unknownValue, read.get().obj);

		// ...and the file must round trip byte-identically.
		final File output = Files.createTempFile(PREFIX, null).toFile();
		EKUtils.reserializeFile(file, output, SerializerFormat.PRESERVE);
		assertFileContentsEquals(file, output);
	}

	public static void assertFileContentsEquals(File expectedFile, File actualFile) throws IOException {
		final byte[] actual = FileUtils.readFileToByteArray(actualFile);
		final byte[] expected = FileUtils.readFileToByteArray(expectedFile);

		assertArrayEquals(actualFile.getName() + " contents", expected, actual);
	}
}
