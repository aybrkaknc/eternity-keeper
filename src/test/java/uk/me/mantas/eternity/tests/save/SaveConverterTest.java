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

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.save.SaveConverter;
import uk.me.mantas.eternity.save.SaveConverter.Format;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.SerializerFormat;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.Assert.*;

public class SaveConverterTest extends TestHarness {
	private static final String MODERN_TYPE = "UnityEngine.Color, UnityEngine.CoreModule";
	private static final String LEGACY_TYPE = "UnityEngine.Color, UnityEngine";

	private static File fixture (final String path) throws Exception {
		return new File(SaveConverterTest.class.getResource(path).toURI());
	}

	/** A .savegame built from an extracted fixture, so archives can be tested. */
	private static File archiveOf (final String fixturePath, final String name) throws Exception {
		final File staging = EKUtils.createTempDir(PREFIX).get();
		final File contents = new File(staging, "extracted");
		FileUtils.copyDirectory(fixture(fixturePath), contents);

		final File archive = new File(staging, name);
		new ZipFile(archive).addFiles(
			new ArrayList<>(Arrays.asList(contents.listFiles())), new ZipParameters());

		return archive;
	}

	/** A string as SharpSerializer writes it in a header: present-guard, 7-bit length, UTF-8. */
	private static byte[] guarded (final String... strings) throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (final String string : strings) {
			final byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
			out.write(1);
			int length = bytes.length;
			while (length >= 0x80) {
				out.write(length | 0x80);
				length >>= 7;
			}
			out.write(length);
			out.write(bytes);
		}

		return out.toByteArray();
	}

	// ---- the rewrite itself -------------------------------------------------

	@Test
	public void rewritesAFramedTypeStringAndItsLength () throws IOException {
		final Optional<byte[]> converted =
			SaveConverter.rewrite(guarded(MODERN_TYPE), Format.LEGACY);

		assertTrue(converted.isPresent());
		assertArrayEquals(guarded(LEGACY_TYPE), converted.get());
	}

	@Test
	public void rewritesInTheOtherDirectionToo () throws IOException {
		final Optional<byte[]> converted =
			SaveConverter.rewrite(guarded(LEGACY_TYPE), Format.MODERN);

		assertTrue(converted.isPresent());
		assertArrayEquals(guarded(MODERN_TYPE), converted.get());
	}

	/**
	 * A type string long enough that shrinking it crosses back over the 7-bit
	 * length boundary: the prefix has to shrink from two bytes to one, and grow
	 * again on the way back.
	 */
	@Test
	public void handlesALengthPrefixThatChangesWidth () throws IOException {
		final StringBuilder padding = new StringBuilder();
		while (padding.length() < 100) {
			padding.append('x');
		}

		final String modern = "UnityEngine." + padding + ", UnityEngine.CoreModule";
		final String legacy = "UnityEngine." + padding + ", UnityEngine";
		assertTrue(modern.length() > 0x7f);
		assertTrue(legacy.length() < 0x80);

		final Optional<byte[]> down = SaveConverter.rewrite(guarded(modern), Format.LEGACY);
		assertTrue(down.isPresent());
		assertArrayEquals(guarded(legacy), down.get());

		final Optional<byte[]> up = SaveConverter.rewrite(down.get(), Format.MODERN);
		assertTrue(up.isPresent());
		assertArrayEquals(guarded(modern), up.get());
	}

	@Test
	public void leavesAStreamAlreadyInTheTargetFormatUntouched () throws IOException {
		final byte[] legacy = guarded(LEGACY_TYPE, "ObjectName", "Parent");
		final Optional<byte[]> converted = SaveConverter.rewrite(legacy, Format.LEGACY);

		assertTrue(converted.isPresent());
		assertArrayEquals(legacy, converted.get());
	}

	/**
	 * A `System.Type` value is written without the present-guard byte, so it is
	 * not a header entry and the deserializing converter never touches it.
	 * Neither may this one — it hands the file back to the slow path instead of
	 * guessing.
	 */
	@Test
	public void refusesAnOccurrenceItCannotFrame () throws IOException {
		final byte[] bytes = MODERN_TYPE.getBytes(StandardCharsets.UTF_8);
		final ByteArrayOutputStream unguarded = new ByteArrayOutputStream();
		unguarded.write(bytes.length);
		unguarded.write(bytes);

		assertFalse(SaveConverter.rewrite(unguarded.toByteArray(), Format.LEGACY).isPresent());
	}

	@Test
	public void refusesAnOccurrenceThatDoesNotEndItsString () throws IOException {
		final byte[] nested = guarded(
			"System.Collections.Generic.List`1[[" + MODERN_TYPE + "]], mscorlib");

		assertFalse(SaveConverter.rewrite(nested, Format.LEGACY).isPresent());
	}

	// ---- what the format is -------------------------------------------------

	@Test
	public void readsTheFormatOfARealSave () throws Exception {
		assertEquals(Format.MODERN, SaveConverter.detect(
			fixture("/SerializerTest/windowStoreSave/MobileObjects.save")));
		assertEquals(Format.MODERN, SaveConverter.detect(
			fixture("/SerializerTest/windowStoreSave/AR_0701_Encampment.lvl")));
		assertEquals(Format.LEGACY, SaveConverter.detect(
			fixture("/SerializerTest/windowStoreSaveConverted/MobileObjects.save")));
		assertEquals(Format.LEGACY, SaveConverter.detect(fixture("/MobileObjects.save")));
	}

	@Test
	public void saysUnknownWhenThereAreNoUnityTypesAtAll () throws Exception {
		final File empty = Files.createTempFile(PREFIX, null).toFile();
		FileUtils.writeByteArrayToFile(empty, guarded("ObjectName", "Parent"));

		assertEquals(Format.UNKNOWN, SaveConverter.detect(empty));
	}

	@Test
	public void readsTheFormatOfASaveFolder () throws Exception {
		assertEquals(Format.MODERN,
			SaveConverter.detectSave(fixture("/SerializerTest/windowStoreSave")));
		assertEquals(Format.LEGACY,
			SaveConverter.detectSave(fixture("/SerializerTest/windowStoreSaveConverted")));
	}

	// ---- against the converter that deserializes ----------------------------

	/**
	 * The one that matters: byte for byte what the deserializing converter
	 * produces, on the real Windows Store save and its real converted twin.
	 */
	@Test
	public void matchesTheDeserializingConverterOnARealSave () throws Exception {
		for (final String name : new String[] {"MobileObjects.save", "AR_0701_Encampment.lvl"}) {
			final File input = fixture("/SerializerTest/windowStoreSave/" + name);
			final File expected = fixture("/SerializerTest/windowStoreSaveConverted/" + name);

			final Optional<byte[]> converted =
				SaveConverter.rewrite(FileUtils.readFileToByteArray(input), Format.LEGACY);

			assertTrue(name, converted.isPresent());
			assertArrayEquals(name, FileUtils.readFileToByteArray(expected), converted.get());
		}
	}

	@Test
	public void convertsBackToTheOriginalBytes () throws Exception {
		final byte[] original = FileUtils.readFileToByteArray(
			fixture("/SerializerTest/windowStoreSave/MobileObjects.save"));

		final Optional<byte[]> legacy = SaveConverter.rewrite(original, Format.LEGACY);
		assertTrue(legacy.isPresent());

		final Optional<byte[]> modern = SaveConverter.rewrite(legacy.get(), Format.MODERN);
		assertTrue(modern.isPresent());
		assertArrayEquals(original, modern.get());
	}

	/** Rewriting bytes is only safe if the result is still a save. */
	@Test
	public void theConvertedSaveStillDeserializes () throws Exception {
		final File input = fixture("/SerializerTest/windowStoreSave/MobileObjects.save");
		final File output = Files.createTempFile(PREFIX, null).toFile();

		final Optional<byte[]> converted =
			SaveConverter.rewrite(FileUtils.readFileToByteArray(input), Format.LEGACY);
		assertTrue(converted.isPresent());
		FileUtils.writeByteArrayToFile(output, converted.get());

		final Optional<DeserializedPackets> before =
			new PacketDeserializer(input.getAbsolutePath()).deserialize();
		final Optional<DeserializedPackets> after =
			new PacketDeserializer(output.getAbsolutePath()).deserialize();

		assertTrue(before.isPresent());
		assertTrue(after.isPresent());
		assertEquals(before.get().getPackets().size(), after.get().getPackets().size());
		assertTrue(before.get().getPackets().size() > 0);
	}

	// ---- whole save folders -------------------------------------------------

	@Test
	public void convertsAWholeSaveFolder () throws Exception {
		final File input = fixture("/SerializerTest/windowStoreSave");
		final File expected = fixture("/SerializerTest/windowStoreSaveConverted");
		final File output = EKUtils.createTempDir(PREFIX).get();

		final SaveConverter.Result result = SaveConverter.convertFolder(input, output, Format.LEGACY);

		assertEquals(2, result.converted);
		assertEquals(4, result.copied);
		assertEquals(0, result.fellBack);
		assertEquals(88, result.replacements);

		final String[] names = expected.list();
		assertEquals(names.length, output.list().length);
		for (final String name : names) {
			assertArrayEquals(
				name
				, FileUtils.readFileToByteArray(new File(expected, name))
				, FileUtils.readFileToByteArray(new File(output, name)));
		}
	}

	@Test
	public void copiesASaveThatIsAlreadyInTheTargetFormat () throws Exception {
		final File input = fixture("/SerializerTest/windowStoreSaveConverted");
		final File output = EKUtils.createTempDir(PREFIX).get();

		final SaveConverter.Result result = SaveConverter.convertFolder(input, output, Format.LEGACY);

		assertEquals(0, result.replacements);
		assertEquals(input.list().length, output.list().length);
		for (final String name : input.list()) {
			assertArrayEquals(
				name
				, FileUtils.readFileToByteArray(new File(input, name))
				, FileUtils.readFileToByteArray(new File(output, name)));
		}
	}

	/**
	 * Invariant 1: an original save is never written to. The converted copy is
	 * a new file, and the source is left exactly as it was found.
	 */
	@Test
	public void neverWritesOverTheOriginal () throws Exception {
		final File input = fixture("/SerializerTest/windowStoreSave");
		final File output = EKUtils.createTempDir(PREFIX).get();

		final byte[] before =
			FileUtils.readFileToByteArray(new File(input, "MobileObjects.save"));

		SaveConverter.convertFolder(input, output, Format.LEGACY);

		assertArrayEquals(
			before, FileUtils.readFileToByteArray(new File(input, "MobileObjects.save")));
	}

	@Test
	public void refusesToConvertAFolderOntoItself () throws Exception {
		final File input = fixture("/SerializerTest/windowStoreSave");

		try {
			SaveConverter.convertFolder(input, input, Format.LEGACY);
			fail("Converting a save onto itself must be refused.");
		} catch (final IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("itself"));
		}
	}

	// ---- archives -----------------------------------------------------------

	@Test
	public void convertsASaveArchiveIntoANewOne () throws Exception {
		final File archive =
			archiveOf("/SerializerTest/windowStoreSave", "id 0 Raedrics Hold.savegame");

		final File destination = EKUtils.createTempDir(PREFIX).get();
		final SaveConverter.Result result =
			SaveConverter.convertArchive(archive, destination, Format.LEGACY);

		assertTrue(result.output.isFile());
		assertEquals(archive.getName(), result.output.getName());
		assertNotEquals(archive.getAbsolutePath(), result.output.getAbsolutePath());
		assertEquals(88, result.replacements);
		assertEquals(Format.LEGACY, SaveConverter.detectSave(result.output));

		// The original archive is still the save it was.
		assertEquals(Format.MODERN, SaveConverter.detectSave(archive));
	}

	@Test
	public void willNotOverwriteAnExistingConvertedArchive () throws Exception {
		final File archive =
			archiveOf("/SerializerTest/windowStoreSave", "id 0 Raedrics Hold.savegame");

		final File destination = EKUtils.createTempDir(PREFIX).get();
		SaveConverter.convertArchive(archive, destination, Format.LEGACY);

		try {
			SaveConverter.convertArchive(archive, destination, Format.LEGACY);
			fail("Converting twice into the same folder must be refused.");
		} catch (final IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("already"));
		}
	}

	/**
	 * The 2015-era saves write the assembly out in full —
	 * {@code UnityEngine.Color, UnityEngine, Version=0.0.0.0, Culture=neutral,
	 * PublicKeyToken=null} — so the module name sits in the middle of the type
	 * string with its version trailing it. Splicing {@code .CoreModule} in
	 * there would leave the version qualifiers describing an assembly that is
	 * not the one named, so a save in that shape is refused rather than
	 * mangled. Nothing needs it: a modern build reads those names as they are.
	 */
	@Test
	public void refusesToModerniseAFullyQualifiedAssemblyName () throws Exception {
		final File input = fixture("/ChangesSaverTest/id 0 Encampment.savegame");
		final File output = EKUtils.createTempDir(PREFIX).get();

		assertFalse(SaveConverter.rewrite(
			FileUtils.readFileToByteArray(new File(input, "MobileObjects.save"))
			, Format.MODERN).isPresent());

		try {
			SaveConverter.convertFolder(input, output, Format.MODERN);
			fail("A fully qualified assembly name must not be rewritten.");
		} catch (final IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("MobileObjects.save"));
		}
	}

	/** The same save is still recognised, and converting it down is a no-op. */
	@Test
	public void leavesAFullyQualifiedLegacySaveAlone () throws Exception {
		final File worldState =
			new File(fixture("/ChangesSaverTest/id 0 Encampment.savegame"), "MobileObjects.save");
		final byte[] contents = FileUtils.readFileToByteArray(worldState);

		assertEquals(Format.LEGACY, SaveConverter.detect(contents));

		final Optional<byte[]> converted = SaveConverter.rewrite(contents, Format.LEGACY);
		assertTrue(converted.isPresent());
		assertArrayEquals(contents, converted.get());
	}

	// ---- and the slow path still works --------------------------------------

	/**
	 * The fallback has to stay correct, because the fast path hands any file it
	 * cannot frame straight to it.
	 */
	@Test
	public void theDeserializingConverterStillProducesTheSameBytes () throws Exception {
		final File input = fixture("/SerializerTest/windowStoreSave/MobileObjects.save");
		final File expected = fixture("/SerializerTest/windowStoreSaveConverted/MobileObjects.save");
		final File output = Files.createTempFile(PREFIX, null).toFile();

		EKUtils.reserializeFile(input, output, SerializerFormat.UNITY_2017);

		assertArrayEquals(
			FileUtils.readFileToByteArray(expected), FileUtils.readFileToByteArray(output));
	}
}
