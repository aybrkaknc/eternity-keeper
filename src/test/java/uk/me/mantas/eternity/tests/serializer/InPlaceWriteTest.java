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
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.SharpSerializer;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Writing a save over itself.
 *
 * <p>Invariant 13: {@code SharpSerializer.serializeAll} opens its target for
 * append and seeks to the end, because the save pipeline normally writes a file
 * that does not exist yet. Every manager that edits a save in place therefore
 * had to clear the file first, and nine of them did it by hand. Five got it
 * wrong in the same way — when {@code delete()} failed they logged "attempting
 * to overwrite directly" and serialized anyway, which appends the new stream
 * after the old one, and every read afterwards returns the stale copy.
 *
 * <p>And none of the nine could see a failed write: {@code serializeAll}
 * caught its own {@code IOException}, so a write that died half way left a
 * truncated {@code MobileObjects.save} behind a {@code delete()} that had
 * already succeeded, and the manager still returned true.
 *
 * <p>{@code DeserializedPackets.replace} is the one way to do it now: write a
 * sibling file, then move it over the original. The original is only ever
 * touched by a completed write.
 */
public class InPlaceWriteTest extends TestHarness {
	private File copyOfFixture () throws Exception {
		final File source = new File(getClass().getResource("/MobileObjects.save").toURI());
		final File directory = EKUtils.createTempDir(PREFIX).get();
		final File copy = new File(directory, "MobileObjects.save");
		FileUtils.copyFile(source, copy);
		return copy;
	}

	private static DeserializedPackets read (final File file) throws Exception {
		return new PacketDeserializer(file).deserialize().get();
	}

	@Test
	public void replacingAFileWritesItOnceNotTwice () throws Exception {
		final File save = copyOfFixture();
		final byte[] original = FileUtils.readFileToByteArray(save);

		read(save).replace(save);

		// PRESERVE round-trips byte for byte, so anything longer is the
		// appended second stream.
		assertArrayEquals(original, FileUtils.readFileToByteArray(save));
	}

	@Test
	public void anEditActuallyReachesTheFile () throws Exception {
		final File save = copyOfFixture();
		final DeserializedPackets packets = read(save);
		final int before = packets.getPackets().size();

		final List<Property> fewer = packets.getPackets().subList(0, before - 1);
		packets.setPackets(fewer);
		Property.update(packets.getCount(), fewer.size());
		packets.replace(save);

		assertEquals(before - 1, read(save).getPackets().size());
	}

	/**
	 * On Windows a file with an open read handle cannot be deleted or moved
	 * over, which is exactly what an antivirus scanner or the search indexer
	 * does to a file that has just been written. The old code appended in
	 * this case; the new one refuses and leaves the file alone.
	 */
	@Test
	public void aFileThatCannotBeReplacedIsLeftExactlyAsItWas () throws Exception {
		assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

		final File save = copyOfFixture();
		final byte[] original = FileUtils.readFileToByteArray(save);
		final DeserializedPackets packets = read(save);

		try (FileInputStream holding = new FileInputStream(save)) {
			try {
				packets.replace(save);
				fail("Replacing a file another process holds open must be refused.");
			} catch (final IOException expected) {
				// The point: an exception, not a warning and an append.
			}
		}

		assertArrayEquals(original, FileUtils.readFileToByteArray(save));
		assertEquals("no half-written sibling is left behind"
			, 1, save.getParentFile().list().length);
	}

	/** A write that fails part way must not have destroyed the original first. */
	@Test
	public void aWriteThatFailsLeavesTheOriginalIntact () throws Exception {
		final File save = copyOfFixture();
		final byte[] original = FileUtils.readFileToByteArray(save);
		final DeserializedPackets packets = read(save);

		final Environment environment = mockEnvironment();
		final SharpSerializer serializer = mockSerializer(environment);
		doThrow(new IOException("disk full")).when(serializer).serializeAll(any(), any());

		// Re-wrapped after mocking, since the factory is looked up on construction.
		final DeserializedPackets failing =
			new DeserializedPackets(packets.getPackets(), packets.getCount());

		try {
			failing.replace(save);
			fail("A failed write must be reported, not swallowed.");
		} catch (final IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("disk full"));
		}

		assertArrayEquals(original, FileUtils.readFileToByteArray(save));
		assertEquals("no half-written sibling is left behind"
			, 1, save.getParentFile().list().length);
	}

	@Test
	public void replacingLeavesNothingElseInTheFolder () throws Exception {
		final File save = copyOfFixture();
		read(save).replace(save);

		assertArrayEquals(new String[] {"MobileObjects.save"}, save.getParentFile().list());
	}

	@Test
	public void aFailedSerializeIsNoLongerSwallowed () throws Exception {
		final File save = copyOfFixture();
		final File directory = save.getParentFile();

		// A target that is a directory cannot be opened for writing.
		final File unwritable = new File(directory, "not-a-file");
		assertTrue(unwritable.mkdir());

		try {
			new SharpSerializer(unwritable.getAbsolutePath())
				.serializeAll(read(save).getCount(), read(save).getPackets());
			fail("serializeAll must report that it could not write.");
		} catch (final IOException expected) {
			// Before, this logged and returned normally.
		}
	}
}
