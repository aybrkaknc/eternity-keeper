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

import org.junit.Test;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.SharpSerializer;
import uk.me.mantas.eternity.serializer.ShortReadException;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

// A mocked SharpSerializer never moves: position() answers 0 unless a test
// says otherwise, so an empty read is one the file can never get past.
public class PacketDeserializerTest extends TestHarness {
	private static SimpleProperty count (final Object value) {
		final SimpleProperty count = mock(SimpleProperty.class);
		count.obj = value;
		return count;
	}

	private static ShortReadException refused (final PacketDeserializer deserializer)
		throws IOException {

		try {
			deserializer.deserialize();
		} catch (final ShortReadException e) {
			return e;
		}

		fail("a short read was handed back as if it were whole");
		return null;
	}

	@Test
	public void testDeserializeNoObjectCount () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final SharpSerializer mockSerializer = mockSerializer(mockEnvironment);
		final PacketDeserializer deserializer = new PacketDeserializer("");

		when(mockSerializer.deserialize()).thenReturn(Optional.empty());
		assertFalse(deserializer.deserialize().isPresent());
		assertFalse(deserializer.deserializeEvenIfShort().isPresent());
	}

	@Test
	public void aCountThatIsNotANumberIsNotAPacketFile () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final SharpSerializer mockSerializer = mockSerializer(mockEnvironment);

		when(mockSerializer.deserialize()).thenReturn(Optional.of(count("six")));
		assertFalse(new PacketDeserializer("").deserialize().isPresent());
	}

	@Test
	public void testDeserialize () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final SharpSerializer mockSerializer = mockSerializer(mockEnvironment);
		final SimpleProperty count = count(2);
		final Property first = mock(Property.class);
		final Property second = mock(Property.class);

		when(mockSerializer.deserialize())
			.thenReturn(Optional.of(count))
			.thenReturn(Optional.of(first))
			.thenReturn(Optional.of(second));

		final Optional<DeserializedPackets> deserialized = new PacketDeserializer("").deserialize();

		assertTrue(deserialized.isPresent());
		assertSame(count, deserialized.get().getCount());
		assertEquals(Arrays.asList(first, second), deserialized.get().getPackets());
		assertTrue(deserialized.get().isWhole());
		assertFalse(deserialized.get().shortRead().isPresent());
	}

	// Writing this back would lose the second object, and nothing downstream
	// can tell: every manager sets the count to what it holds before writing.
	@Test
	public void aReadThatComesUpShortIsRefused () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final SharpSerializer mockSerializer = mockSerializer(mockEnvironment);

		when(mockSerializer.deserialize())
			.thenReturn(Optional.of(count(2)))
			.thenReturn(Optional.of(mock(Property.class)))
			.thenReturn(Optional.empty());

		final ShortReadException e = refused(new PacketDeserializer("world.save"));
		assertEquals("world.save", e.file);
		assertEquals(2, e.declared);
		assertEquals(1, e.read);
	}

	@Test
	public void whatWasReadCanStillBeLookedAt () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final SharpSerializer mockSerializer = mockSerializer(mockEnvironment);
		final SimpleProperty count = count(2);
		final Property first = mock(Property.class);

		when(mockSerializer.deserialize())
			.thenReturn(Optional.of(count))
			.thenReturn(Optional.of(first))
			.thenReturn(Optional.empty());

		final DeserializedPackets partial =
			new PacketDeserializer("world.save").deserializeEvenIfShort().get();

		assertSame(count, partial.getCount());
		assertEquals(Arrays.asList(first), partial.getPackets());
		assertFalse(partial.isWhole());
		assertEquals(2, partial.shortRead().get().declared);
		assertEquals(1, partial.shortRead().get().read);
	}

	// A read that fails without moving starts the next one at the same byte,
	// which fails the same way. Asking again once per missing object made a
	// real world state cut at 90% take three times as long to read as the
	// whole file, with an error in the log for each of the 910 objects it
	// could not reach.
	@Test
	public void readingStopsWhereTheFileStops () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final SharpSerializer mockSerializer = mockSerializer(mockEnvironment);

		when(mockSerializer.deserialize())
			.thenReturn(Optional.of(count(5)))
			.thenReturn(Optional.of(mock(Property.class)))
			.thenReturn(Optional.empty());

		assertEquals(1, new PacketDeserializer("").deserializeEvenIfShort().get().getPackets().size());
		verify(mockSerializer, times(3)).deserialize();
	}

	// An object the stream holds but that no class can be built for comes back
	// empty with the stream moved past it, so the ones after it still read.
	// They are still not the whole file.
	@Test
	public void anObjectThatCannotBeBuiltDoesNotStopTheRest () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final SharpSerializer mockSerializer = mockSerializer(mockEnvironment);
		final Property second = mock(Property.class);
		final Property third = mock(Property.class);
		final AtomicLong position = new AtomicLong();

		when(mockSerializer.position()).thenAnswer(invocation -> position.addAndGet(100));
		when(mockSerializer.deserialize())
			.thenReturn(Optional.of(count(3)))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(second))
			.thenReturn(Optional.of(third));

		final DeserializedPackets partial =
			new PacketDeserializer("").deserializeEvenIfShort().get();

		assertEquals(Arrays.asList(second, third), partial.getPackets());
		assertFalse(partial.isWhole());
	}

	// A damaged file can throw from deep inside the reader -- a name-cache
	// index read out of the middle of a string is an IndexOutOfBoundsException
	// -- and that is a short read too, not a crash on a worker thread that
	// leaves the page waiting for an answer that never comes.
	@Test
	public void aReadThatThrowsEndsTheRead () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final SharpSerializer mockSerializer = mockSerializer(mockEnvironment);

		when(mockSerializer.deserialize())
			.thenReturn(Optional.of(count(3)))
			.thenReturn(Optional.of(mock(Property.class)))
			.thenThrow(new IndexOutOfBoundsException("Index: 1936024436, Size: 12"));

		final ShortReadException e = refused(new PacketDeserializer(""));
		assertEquals(3, e.declared);
		assertEquals(1, e.read);
		verify(mockSerializer, times(3)).deserialize();
	}

	@Test
	public void aCountThatCannotBeReadAtAllIsNoPacketFile () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final SharpSerializer mockSerializer = mockSerializer(mockEnvironment);

		when(mockSerializer.deserialize()).thenThrow(new IndexOutOfBoundsException());
		assertFalse(new PacketDeserializer("").deserialize().isPresent());
	}
}
