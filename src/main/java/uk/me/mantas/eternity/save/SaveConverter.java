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


package uk.me.mantas.eternity.save;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.serializer.SerializerFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Converts a save between the two ways Unity has named its core assembly, by
 * rewriting the type strings in each packet's header and copying every other
 * byte through untouched.
 *
 * <p>That really is the whole difference between a save the Windows Store
 * build writes and one an older Steam or GOG build can read: a type is
 * serialized as an assembly-qualified name, and Unity 2017.2 split
 * {@code UnityEngine} into modules, so {@code UnityEngine.Color, UnityEngine}
 * became {@code UnityEngine.Color, UnityEngine.CoreModule}. Nothing else about
 * the format changed.
 *
 * <p>The existing route through {@code EKUtils.reserializeFile} gets there by
 * deserializing the entire object graph and writing it out again, which for a
 * late-game save means 395 files and 119 MB of packets built in memory to
 * change a few hundred strings. This does the same job as a byte rewrite:
 * find each type string, shorten or lengthen it, fix its length prefix.
 *
 * <p><b>It only rewrites what it can prove is a type string.</b> A header
 * string is written as a present-guard byte, a 7-bit-encoded length, then the
 * UTF-8 bytes ({@code BinaryWriter.writeStringGuarded}), so an occurrence is
 * only rewritten when a length prefix and guard are found that frame it
 * exactly and the module name ends the string. A {@code System.Type} written
 * as a value carries no guard byte, and the deserializing converter does not
 * touch those either. Anything that cannot be framed hands the whole file to
 * that slower converter rather than being guessed at.
 */
public class SaveConverter {
	private static final Logger logger = Logger.getLogger(SaveConverter.class);

	private static final byte GUARD = 1;

	// A 7-bit-encoded length of three bytes already allows a two-million
	// character type name; nothing in a save comes near it.
	private static final int MAX_LENGTH_WIDTH = 3;

	private static final byte[] MODERN_MODULE = bytes(", UnityEngine.CoreModule");
	private static final byte[] LEGACY_MODULE = bytes(", UnityEngine");
	private static final byte[] MODULE_SUFFIX = bytes(".CoreModule");

	/** The two namings a Pillars save's type headers can use. */
	public enum Format {
		/** {@code UnityEngine.CoreModule} — Unity 2017.2 and later. */
		MODERN,

		/** {@code UnityEngine} — the single assembly of Unity 5. */
		LEGACY,

		/** No Unity types in the file at all, so there is nothing to tell from. */
		UNKNOWN
	}

	public static class Result {
		/** Serialized files rewritten by the fast path. */
		public final int converted;

		/** Everything else in the save, copied through byte for byte. */
		public final int copied;

		/** Files handed to the deserializing converter because framing failed. */
		public final int fellBack;

		/** Type strings rewritten, across every file. */
		public final int replacements;

		/** Where the converted save was written, when one file was asked for. */
		public final File output;

		Result (
			final int converted, final int copied, final int fellBack
			, final int replacements, final File output) {

			this.converted = converted;
			this.copied = copied;
			this.fellBack = fellBack;
			this.replacements = replacements;
			this.output = output;
		}
	}

	/** Where a type string sits in the stream, and how long it is. */
	private static class Frame {
		final int guardIndex;
		final int contentStart;
		final int contentEnd;

		Frame (final int guardIndex, final int contentStart, final int contentEnd) {
			this.guardIndex = guardIndex;
			this.contentStart = contentStart;
			this.contentEnd = contentEnd;
		}
	}

	private SaveConverter () {}

	// ---- the rewrite --------------------------------------------------------

	/**
	 * Rewrites one serialized stream into {@code target}, or reports that it
	 * could not: an empty result means an occurrence of the module name turned
	 * up somewhere this cannot prove is a type header, and the caller should
	 * fall back to the converter that deserializes.
	 */
	public static Optional<byte[]> rewrite (final byte[] contents, final Format target) {
		final byte[] needle = target == Format.LEGACY ? MODERN_MODULE : LEGACY_MODULE;
		final byte[] replacement = target == Format.LEGACY ? LEGACY_MODULE : MODERN_MODULE;

		final List<Frame> frames = new ArrayList<>();
		int at = indexOf(contents, needle, 0);

		while (at >= 0) {
			final int end = at + needle.length;

			// Going the other way, every modern type string also starts with
			// the legacy one. Those are already where they should be.
			if (target == Format.MODERN && startsWith(contents, end, MODULE_SUFFIX)) {
				at = indexOf(contents, needle, end);
				continue;
			}

			final Optional<Frame> frame = frame(contents, at, end, true);
			if (!frame.isPresent()) {
				return Optional.empty();
			}

			frames.add(frame.get());
			at = indexOf(contents, needle, end);
		}

		if (frames.isEmpty()) {
			return Optional.of(contents);
		}

		return Optional.of(apply(contents, frames, needle, replacement));
	}

	/**
	 * Finds the guarded, length-prefixed string the module name sits in. The
	 * length prefix is the whole proof: a byte that happens to equal the
	 * distance back to a {@code 0x01} is not something a save stumbles into.
	 *
	 * <p>{@code mustEndString} is what separates rewriting from recognising.
	 * Rewriting only ever touches a type string the module name <em>ends</em>,
	 * because that is the only shape where swapping the name leaves the rest
	 * of the string still describing the same assembly.
	 */
	private static Optional<Frame> frame (
		final byte[] contents, final int matchStart, final int matchEnd
		, final boolean mustEndString) {

		// The content is printable ASCII, so it lies inside the run of
		// printable bytes around the match.
		int runStart = matchStart;
		while (runStart > 0 && isPrintable(contents[runStart - 1])) {
			runStart--;
		}

		int runEnd = matchEnd;
		while (runEnd < contents.length && isPrintable(contents[runEnd])) {
			runEnd++;
		}

		Optional<Frame> found = Optional.empty();

		for (int contentStart = matchStart; contentStart >= runStart; contentStart--) {
			for (int width = 1; width <= MAX_LENGTH_WIDTH; width++) {
				final int prefixStart = contentStart - width;
				final int guardIndex = prefixStart - 1;

				if (guardIndex < 0 || contents[guardIndex] != GUARD) {
					continue;
				}

				final int length = decode7Bit(contents, prefixStart, width);
				final int contentEnd = contentStart + length;

				if (length < 0 || contentEnd < matchEnd || contentEnd > runEnd) {
					continue;
				}

				if (mustEndString && contentEnd != matchEnd) {
					continue;
				}

				if (found.isPresent()) {
					// Two readings of the same bytes: refuse rather than pick one.
					logger.warn("Ambiguous type string framing at byte %d.%n", matchStart);
					return Optional.empty();
				}

				found = Optional.of(new Frame(guardIndex, contentStart, contentEnd));
			}
		}

		return found;
	}

	private static byte[] apply (
		final byte[] contents, final List<Frame> frames
		, final byte[] needle, final byte[] replacement) {

		final ByteArrayOutputStream out = new ByteArrayOutputStream(contents.length);
		int copiedTo = 0;

		for (final Frame frame : frames) {
			out.write(contents, copiedTo, frame.guardIndex - copiedTo);

			final int length =
				frame.contentEnd - frame.contentStart - needle.length + replacement.length;
			final byte[] prefix = encode7Bit(length);

			out.write(GUARD);
			out.write(prefix, 0, prefix.length);
			out.write(contents, frame.contentStart, frame.contentEnd - frame.contentStart - needle.length);
			out.write(replacement, 0, replacement.length);

			copiedTo = frame.contentEnd;
		}

		out.write(contents, copiedTo, contents.length - copiedTo);
		return out.toByteArray();
	}

	// ---- which format a save is in ------------------------------------------

	/**
	 * Which naming a stream uses. This is more forgiving than the rewrite:
	 * 2015-era saves qualify the assembly in full, so the module name sits in
	 * the middle of its type string rather than ending it, and that is still a
	 * legacy save whatever else can be done with it.
	 */
	public static Format detect (final byte[] contents) {
		if (framedOccurrence(contents, MODERN_MODULE, false)) {
			return Format.MODERN;
		}

		if (framedOccurrence(contents, LEGACY_MODULE, true)) {
			return Format.LEGACY;
		}

		return Format.UNKNOWN;
	}

	public static Format detect (final File file) throws IOException {
		return detect(FileUtils.readFileToByteArray(file));
	}

	/**
	 * The format of a whole save: an extracted folder, a {@code .savegame}
	 * archive, or a single serialized file. The world state is the one that
	 * matters, since it is what the editor and the game read first.
	 */
	public static Format detectSave (final File save) throws IOException {
		if (save.isDirectory()) {
			final File worldState = new File(save, "MobileObjects.save");
			return worldState.isFile() ? detect(worldState) : Format.UNKNOWN;
		}

		if (save.getName().toLowerCase().endsWith(".savegame")) {
			return detect(readFromArchive(save, "MobileObjects.save"));
		}

		return detect(save);
	}

	private static boolean framedOccurrence (
		final byte[] contents, final byte[] needle, final boolean skipModern) {

		int at = indexOf(contents, needle, 0);
		while (at >= 0) {
			final int end = at + needle.length;
			if (!(skipModern && startsWith(contents, end, MODULE_SUFFIX))
				&& frame(contents, at, end, false).isPresent()) {

				return true;
			}

			at = indexOf(contents, needle, end);
		}

		return false;
	}

	// ---- whole saves --------------------------------------------------------

	public static boolean isSerialized (final String fileName) {
		final String lower = fileName.toLowerCase();
		return lower.endsWith(".save") || lower.endsWith(".lvl");
	}

	/**
	 * Converts every serialized file in an extracted save, copying the rest
	 * through. The source folder is only ever read.
	 */
	public static Result convertFolder (final File input, final File output, final Format target)
		throws IOException {

		if (input.getCanonicalFile().equals(output.getCanonicalFile())) {
			throw new IOException("A save cannot be converted onto itself.");
		}

		final File[] files = input.listFiles();
		if (files == null) {
			throw new IOException("Cannot read the save folder: " + input.getAbsolutePath());
		}

		if (!output.isDirectory() && !output.mkdirs()) {
			throw new IOException("Cannot create " + output.getAbsolutePath());
		}

		int converted = 0;
		int copied = 0;
		int fellBack = 0;
		int replacements = 0;

		for (final File file : files) {
			final File destination = new File(output, file.getName());

			if (!file.isFile()) {
				continue;
			}

			if (!isSerialized(file.getName())) {
				FileUtils.copyFile(file, destination);
				copied++;
				continue;
			}

			final byte[] contents = FileUtils.readFileToByteArray(file);
			final Optional<byte[]> rewritten = rewrite(contents, target);

			if (rewritten.isPresent()) {
				FileUtils.writeByteArrayToFile(destination, rewritten.get());
				converted++;
				replacements += countReplacements(contents, target);
				continue;
			}

			if (target != Format.LEGACY) {
				throw new IOException(
					"Cannot convert " + file.getName() + " to the modern format: it holds a "
						+ "module name that is not a type header.");
			}

			logger.warn(
				"%s could not be rewritten directly; deserializing it instead.%n"
				, file.getName());

			if (!destination.exists() && !destination.createNewFile()) {
				throw new IOException("Cannot create " + destination.getAbsolutePath());
			}

			EKUtils.reserializeFile(file, destination, SerializerFormat.UNITY_2017);
			fellBack++;
		}

		return new Result(converted, copied, fellBack, replacements, output);
	}

	/**
	 * Converts a {@code .savegame} into a new one of the same name in
	 * {@code destinationFolder}. The original archive is never written to, and
	 * an existing converted copy is never overwritten.
	 */
	public static Result convertArchive (
		final File archive, final File destinationFolder, final Format target)
		throws IOException {

		final File output = new File(destinationFolder, archive.getName());

		if (output.getCanonicalFile().equals(archive.getCanonicalFile())) {
			throw new IOException("A save cannot be converted onto itself.");
		}

		if (output.exists()) {
			throw new IOException(
				"There is already a converted copy of this save at " + output.getAbsolutePath());
		}

		final File staging = EKUtils.createTempDir("EK-convert").orElseThrow(
			() -> new IOException("Unable to create a working folder for the conversion."));

		try {
			final File extracted = new File(staging, "in");
			final File rewritten = new File(staging, "out");
			new ZipFile(archive).extractAll(extracted.getAbsolutePath());

			final Result result = convertFolder(extracted, rewritten, target);

			final File[] contents = rewritten.listFiles();
			if (contents == null || contents.length < 1) {
				throw new IOException("The conversion produced nothing to package.");
			}

			if (!destinationFolder.isDirectory() && !destinationFolder.mkdirs()) {
				throw new IOException("Cannot create " + destinationFolder.getAbsolutePath());
			}

			new ZipFile(output).addFiles(
				new ArrayList<>(Arrays.asList(contents)), new ZipParameters());

			return new Result(
				result.converted, result.copied, result.fellBack, result.replacements, output);
		} finally {
			FileUtils.deleteQuietly(staging);
		}
	}

	// ---- plumbing -----------------------------------------------------------

	/** How many type strings the rewrite moved, for the report the user sees. */
	private static int countReplacements (final byte[] before, final Format target) {
		final byte[] needle = target == Format.LEGACY ? MODERN_MODULE : LEGACY_MODULE;

		int count = 0;
		int at = indexOf(before, needle, 0);

		while (at >= 0) {
			final int end = at + needle.length;
			if (!(target == Format.MODERN && startsWith(before, end, MODULE_SUFFIX))) {
				count++;
			}

			at = indexOf(before, needle, end);
		}

		return count;
	}

	private static byte[] readFromArchive (final File archive, final String entryName)
		throws IOException {

		final ZipFile zip = new ZipFile(archive);
		final FileHeader header = zip.getFileHeader(entryName);

		if (header == null) {
			throw new IOException(entryName + " is missing from " + archive.getName());
		}

		try (InputStream stream = zip.getInputStream(header)) {
			return IOUtils.toByteArray(stream);
		}
	}

	private static byte[] bytes (final String string) {
		return string.getBytes(StandardCharsets.UTF_8);
	}

	private static boolean isPrintable (final byte b) {
		return b >= 0x20 && b <= 0x7e;
	}

	private static byte[] encode7Bit (final int value) {
		int remaining = value;
		int width = 1;
		while (remaining >= 0x80) {
			remaining >>= 7;
			width++;
		}

		final byte[] encoded = new byte[width];
		remaining = value;
		int i = 0;
		while (remaining >= 0x80) {
			encoded[i++] = (byte) (remaining | 0x80);
			remaining >>= 7;
		}

		encoded[i] = (byte) remaining;
		return encoded;
	}

	/**
	 * Reads a 7-bit-encoded length of exactly {@code width} bytes, low byte
	 * first, or -1 if those bytes are not one — every byte but the last must
	 * carry the continuation bit, and the last must not.
	 */
	private static int decode7Bit (final byte[] contents, final int at, final int width) {
		if (at < 0 || at + width > contents.length) {
			return -1;
		}

		int value = 0;
		for (int i = 0; i < width; i++) {
			final int b = contents[at + i] & 0xff;
			final boolean continues = (b & 0x80) != 0;

			if (continues == (i == width - 1)) {
				return -1;
			}

			value |= (b & 0x7f) << (7 * i);
		}

		return value;
	}

	private static boolean matches (final byte[] contents, final int at, final byte[] expected) {
		if (at < 0 || at + expected.length > contents.length) {
			return false;
		}

		for (int i = 0; i < expected.length; i++) {
			if (contents[at + i] != expected[i]) {
				return false;
			}
		}

		return true;
	}

	private static boolean startsWith (final byte[] contents, final int at, final byte[] expected) {
		return matches(contents, at, expected);
	}

	private static int indexOf (final byte[] contents, final byte[] needle, final int from) {
		final int last = contents.length - needle.length;
		final byte first = needle[0];

		for (int i = Math.max(from, 0); i <= last; i++) {
			if (contents[i] != first) {
				continue;
			}

			int j = 1;
			while (j < needle.length && contents[i + j] == needle[j]) {
				j++;
			}

			if (j == needle.length) {
				return i;
			}
		}

		return -1;
	}
}
