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
import uk.me.mantas.eternity.environment.State;
import uk.me.mantas.eternity.factory.SharpSerializerFactory;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DeserializedPackets {
	private static final Logger logger = Logger.getLogger(DeserializedPackets.class);

	private List<Property> packets;
	private final SimpleProperty count;
	private final SharpSerializerFactory sharpSerializer;

	// What the read that made these found: the file, how many packets its
	// count promised and how many came back. Fixed when it was read, because
	// every writer sets the count to the packets it holds before writing,
	// which would make a short read look whole by the time it is written.
	private final String source;
	private final int declared;
	private final int read;

	// What these packets already contradicted about themselves when they were
	// read, or put together. Taken then because every writer edits them in
	// place: by the time they are written, only what was noted here tells an
	// edit's own mistake apart from one the file came with.
	private final Set<String> baseline = new HashSet<>();

	/**
	 * Packets put together in memory: never read, so nothing is missing from
	 * them, and what they contradict about themselves is judged from here on
	 * -- whatever they already get wrong as handed over is theirs, not an
	 * edit's.
	 */
	public DeserializedPackets (final List<Property> packets, final SimpleProperty count) {
		this(packets, count, null, packets.size());
	}

	/**
	 * What reading {@code source} gave back, whose leading count promised
	 * {@code declared} packets.
	 */
	DeserializedPackets (
		final List<Property> packets
		, final SimpleProperty count
		, final String source
		, final int declared) {

		this.packets = packets;
		this.count = count;
		this.source = source;
		this.declared = declared;
		this.read = packets.size();
		sharpSerializer = Environment.getInstance().factory().sharpSerializer();

		for (final PacketInvariants.Breach breach : PacketInvariants.check(packets, count)) {
			baseline.add(breach.signature);
		}
	}

	public List<Property> getPackets () {
		return packets;
	}

	public void setPackets (final List<Property> packets) {
		this.packets = packets;
	}

	public SimpleProperty getCount () {
		return count;
	}

	/** Whether every packet the file's count promised was read. */
	public boolean isWhole () {
		return read >= declared;
	}

	/**
	 * Why these packets must not be written, when they are only the start of
	 * the file they were read from.
	 */
	public Optional<ShortReadException> shortRead () {
		return isWhole()
			? Optional.empty()
			: Optional.of(new ShortReadException(source, declared, read));
	}

	/**
	 * Serializes into {@code destinationFile}, which must already exist and is
	 * appended to (invariant 13) -- right for a file created a moment ago. To
	 * write over a save that is already there, use {@link #replace(File)}.
	 */
	public void reserialize (final File destinationFile) throws IOException {
		reserialize(destinationFile, SerializerFormat.PRESERVE);
	}

	public void reserialize (final File destinationFile, final SerializerFormat outputFormat)
		throws IOException {

		checkWritable(destinationFile.getName());
		write(destinationFile, outputFormat);
	}

	/**
	 * Refuses, in the user's own words, what must not be written: a short read
	 * (whatever has been done with it since), or an edit that has left these
	 * packets contradicting themselves in a way they did not when they were
	 * read ({@link PacketInvariants}). Writing does this anyway; a writer that
	 * changes several files calls it for all of them before writing any.
	 *
	 * @throws WriteRefusedException -- a {@link ShortReadException} or an
	 *         {@link InconsistentWriteException}
	 */
	public void checkWritable () throws WriteRefusedException {
		checkWritable(source);
	}

	/**
	 * Writes these packets over {@code target}, all or nothing.
	 *
	 * <p>The stream goes into an empty sibling file first and is then moved
	 * over the original, so the original is only ever touched by a completed
	 * write. Nine managers used to do this by hand as delete, create,
	 * serialize, and between them had three ways to lose an edit: a delete
	 * that failed (a scanner holding the file open) was logged and followed by
	 * an append, which left the stale stream first in the file; a write that
	 * failed half way left a truncated file behind a delete that had already
	 * succeeded; and neither was ever reported, because the write swallowed
	 * its own exception.
	 *
	 * @throws IOException when the write or the move fails. {@code target} is
	 *         then exactly as it was, and no sibling is left behind.
	 * @throws WriteRefusedException when these packets are only the start of
	 *         the file they were read from, or an edit has left them
	 *         contradicting themselves; nothing is written at all.
	 */
	public void replace (final File target) throws IOException {
		checkWritable(target.getName());
		final File directory = target.getAbsoluteFile().getParentFile();
		final File writing = File.createTempFile(target.getName() + ".", ".writing", directory);

		try {
			write(writing, SerializerFormat.PRESERVE);

			if (writing.length() < 1) {
				throw new IOException("Nothing was written for " + target.getName());
			}

			// An Apply being recorded keeps what it replaces, so it can be
			// undone; one that cannot be kept is not written.
			keepForUndo(target);

			try {
				Files.move(writing.toPath(), target.toPath()
					, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (final AtomicMoveNotSupportedException e) {
				Files.move(writing.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			if (writing.exists() && !writing.delete()) {
				writing.deleteOnExit();
			}
		}
	}

	private static void keepForUndo (final File target) throws IOException {
		final State state = Environment.getInstance().state();
		if (state == null) {
			return;
		}

		try {
			state.workingSave().history().keep(target);
		} catch (final IOException e) {
			throw new IOException("Could not keep a copy of " + target.getName()
				+ " to undo this change with, so nothing was written: " + e.getMessage(), e);
		}
	}

	private void write (final File destination, final SerializerFormat outputFormat)
		throws IOException {

		final SharpSerializer serializer =
			sharpSerializer.forFile(destination.getAbsolutePath()).toFormat(outputFormat);

		serializer.serializeAll(count, packets);
	}

	// Whatever a caller has done with a short read since, it is not written.
	// Nor is an edit that leaves the file contradicting itself where it did
	// not before. The leading count is not the writer's to keep: it is set
	// here, to what the file is about to hold (invariant 5).
	private void checkWritable (final String name) throws WriteRefusedException {
		final Optional<ShortReadException> shortRead = shortRead();
		if (shortRead.isPresent()) {
			throw shortRead.get();
		}

		if (count != null && count.obj instanceof Number) {
			Property.update(count, packets.size());
		}

		final List<String> fresh = new ArrayList<>();
		for (final PacketInvariants.Breach breach : PacketInvariants.check(packets, count)) {
			if (!baseline.contains(breach.signature)) {
				fresh.add(breach.detail);
			}
		}

		if (!fresh.isEmpty()) {
			final String file = source != null ? source : name;
			for (final String problem : fresh) {
				logger.error("Refusing to write %s: %s%n", file, problem);
			}

			throw new InconsistentWriteException(file, fresh);
		}
	}
}
