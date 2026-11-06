/**
 * Eternity Keeper, a Pillars of Eternity save game editor.
 * Copyright (C) 2015 the authors.
 * <p>
 * Eternity Keeper is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * Eternity Keeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package uk.me.mantas.eternity.tests.serializer;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.mockito.InOrder;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.SharpSerializer;
import uk.me.mantas.eternity.serializer.ShortReadException;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DeserializedPacketsTest extends TestHarness {
	@Test
	public void testReserialize () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final SharpSerializer mockSerializer = mockSerializer(mockEnvironment);
		final File mockFile = mock(File.class);
		final Property mockProperty = mock(Property.class);
		final SimpleProperty mockCount = mock(SimpleProperty.class);
		final List<Property> components = new ArrayList<Property>() {{add(mockProperty);}};
		final DeserializedPackets deserialized =
			new DeserializedPackets(components, mockCount);

		when(mockFile.getAbsolutePath()).thenReturn("");
		deserialized.reserialize(mockFile);

		// Everything must be written in a single serializer session; opening
		// the file once per packet made large saves take minutes to write and
		// left a huge window for concurrent readers to see a growing file.
		verify(mockSerializer).serializeAll(mockCount, components);
		verify(mockSerializer, never()).serialize(any(Property.class));
	}

	// Packets put together in memory were never read, so nothing can be
	// missing from them; only a read can come up short.
	@Test
	public void packetsPutTogetherInMemoryAreWhole () throws IOException {
		mockSerializer(mockEnvironment());
		final SimpleProperty count = mock(SimpleProperty.class);
		count.obj = 5;

		assertTrue(new DeserializedPackets(new ArrayList<>(), count).isWhole());
	}

	// The last line of defence: whatever a caller did with a short read,
	// writing it is refused before a byte reaches the disk.
	@Test
	public void aShortReadIsNeverWritten () throws IOException {
		final SharpSerializer mockSerializer = mockSerializer(mockEnvironment());
		final SimpleProperty count = mock(SimpleProperty.class);
		count.obj = 2;

		when(mockSerializer.deserialize())
			.thenReturn(Optional.of(count))
			.thenReturn(Optional.of(mock(Property.class)))
			.thenReturn(Optional.empty());

		final DeserializedPackets partial =
			new PacketDeserializer("world.save").deserializeEvenIfShort().get();

		final File target = new File(EKUtils.createTempDir(PREFIX).get(), "world.save");
		FileUtils.writeStringToFile(target, "as it was", "UTF-8");

		try {
			partial.replace(target);
			fail("replace wrote a short read");
		} catch (final ShortReadException expected) {
			assertEquals("world.save", expected.file);
		}

		try {
			partial.reserialize(target);
			fail("reserialize wrote a short read");
		} catch (final ShortReadException expected) {
			assertEquals(1, expected.read);
		}

		verify(mockSerializer, never()).serializeAll(any(), any());
		assertEquals("as it was", FileUtils.readFileToString(target, "UTF-8"));
		assertArrayEquals(new String[] {"world.save"}, target.getParentFile().list());
	}
}
