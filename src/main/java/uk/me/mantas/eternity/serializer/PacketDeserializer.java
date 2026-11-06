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

package uk.me.mantas.eternity.serializer;

import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.ReferenceTargetProperty;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// This is a specialised instance of the Deserializer when we know we are dealing with a file that
// has been serialized in PoE's particular format (i.e. the first object is a plain integer of the
// number of following ComponentPersistencePacket objects).
//
// A file cut short, or damaged part of the way through, gives back fewer packets than that count
// and nothing else to show for it. Anything that wrote such a read back would lose the rest of
// the file without a word, so deserialize() refuses one outright; deserializeEvenIfShort() is for
// looking only, and what it gives back refuses to be written.

public class PacketDeserializer {
	private static final Logger logger = Logger.getLogger(PacketDeserializer.class);

	private final File file;
	private final SharpSerializer deserializer;

	public PacketDeserializer (final File file) throws FileNotFoundException {
		this.file = file;
		deserializer =
			Environment.getInstance().factory().sharpSerializer().forFile(file.getAbsolutePath());
	}

	public PacketDeserializer (final String filename) throws FileNotFoundException {
		this(new File(filename));
	}

	public Optional<Property> followReference (final ReferenceTargetProperty property) {
		return deserializer.followReference(property);
	}

	/**
	 * Every packet the file's leading count promises. Anything that goes on to
	 * write what it read reads it with this.
	 *
	 * @return nothing when the file does not start with an object count at all
	 * @throws ShortReadException when fewer packets could be read than the
	 *         count promises; its message says so in words for the user
	 */
	public Optional<DeserializedPackets> deserialize () throws ShortReadException {
		final Optional<DeserializedPackets> read = deserializeEvenIfShort();
		if (read.isPresent()) {
			final Optional<ShortReadException> shortRead = read.get().shortRead();
			if (shortRead.isPresent()) {
				throw shortRead.get();
			}
		}

		return read;
	}

	/**
	 * As many of the file's packets as can be read, for showing a save that is
	 * damaged -- never for writing one. What comes back says whether it is the
	 * whole file ({@link DeserializedPackets#isWhole}) and refuses to be written
	 * when it is not.
	 *
	 * <p>Reading stops at the first packet the file cannot get past: a failed
	 * read leaves the reader where it was, so every later one would start at
	 * the same byte and fail the same way. Asking again for each missing packet
	 * made a real world state cut at 90% take three times as long to read as the
	 * whole file, and logged the same error 910 times. A packet that throws ends
	 * the read too, since nothing then says where it ends. One that is read but
	 * cannot be built has been read past, so the rest still come.
	 *
	 * @return nothing when the file does not start with an object count at all
	 */
	public Optional<DeserializedPackets> deserializeEvenIfShort () {
		final Optional<Property> header;
		try {
			header = deserializer.deserialize();
		} catch (final RuntimeException | StackOverflowError e) {
			logger.error(e, "%s does not start with an object count: %s%n", file.getName(), e);
			return Optional.empty();
		}

		if (!header.isPresent()
			|| !(header.get() instanceof SimpleProperty)
			|| !(header.get().obj instanceof Number)
			|| ((Number) header.get().obj).intValue() < 0) {

			return Optional.empty();
		}

		final SimpleProperty count = (SimpleProperty) header.get();
		final int declared = ((Number) count.obj).intValue();
		final List<Property> packets = new ArrayList<>();

		for (int i = 0; i < declared; i++) {
			final long before = deserializer.position();
			final Optional<Property> packet;

			// A stack that ran out counts with the rest: damage can nest
			// objects without end, and the error would end this thread with
			// the page still waiting on it.
			try {
				packet = deserializer.deserialize();
			} catch (final RuntimeException | StackOverflowError e) {
				logger.error(e, "%s: object %d of %d could not be read: %s%n"
					, file.getName(), i + 1, declared, e);

				break;
			}

			if (packet.isPresent()) {
				packets.add(packet.get());
			} else if (deserializer.position() == before) {
				logger.error("%s cannot be read past object %d of %d.%n"
					, file.getName(), i + 1, declared);

				break;
			} else {
				logger.error("%s: object %d of %d was read but could not be built.%n"
					, file.getName(), i + 1, declared);
			}
		}

		// Every object read, and still not something to hand on: damage can
		// leave a tree that reads to its end and holds what nothing expects --
		// an entry with no key, a value where an object belongs -- and the look
		// DeserializedPackets takes at what it was given trips over it. That is
		// a file the editor cannot make sense of, and one it must not write.
		try {
			return Optional.of(new DeserializedPackets(packets, count, file.getName(), declared));
		} catch (final RuntimeException | StackOverflowError e) {
			logger.error(e, "%s was read but cannot be made sense of: %s%n", file.getName(), e);
			return Optional.empty();
		}
	}
}
