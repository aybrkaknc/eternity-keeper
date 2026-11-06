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
import net.lingala.zip4j.exception.ZipException;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.environment.UnpackedSaves;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static uk.me.mantas.eternity.save.SaveGameInfo.*;

/**
 * Unpacks Pillars of Eternity {@code .savegame} archives into the working
 * directory and constructs {@link SaveGameInfo} metadata models.
 *
 * <p>Supports both parallel directory-wide lazy tile extraction
 * ({@link #unpackAllSaves()}) and single-archive extraction
 * ({@link #unpackSingleSave(File)}) for direct drag-and-drop opening.
 */
public class SaveGameExtractor {
	private static final Logger logger = Logger.getLogger(SaveGameExtractor.class);
	private final String savesLocation;
	private final File workingDirectory;
	private final UnpackedSaves unpacked;
	public final AtomicInteger totalFiles = new AtomicInteger(0);
	public final AtomicInteger currentCount = new AtomicInteger(0);

	private static final List<String> LISTED_FILES = new ArrayList<>();
	static {
		LISTED_FILES.addAll(Arrays.asList(REQUIRED_FILES));
		LISTED_FILES.addAll(Arrays.asList(OPTIONAL_FILES));
	}

	/** A file in the saves folder that the list leaves out, and why. */
	public static class Unreadable {
		public final String name;

		/** In words that finish "... is not listed, because". */
		public final String reason;

		Unreadable (final String name, final String reason) {
			this.name = name;
			this.reason = reason;
		}
	}

	private final List<Unreadable> unreadable = new ArrayList<>();

	public SaveGameExtractor (final String savesLocation, final File workingDirectory) {
		this(savesLocation, workingDirectory, Environment.getInstance().state().unpacked());
	}

	public SaveGameExtractor (
		final String savesLocation, final File workingDirectory, final UnpackedSaves unpacked) {

		this.savesLocation = savesLocation;
		this.workingDirectory = workingDirectory;
		this.unpacked = Objects.requireNonNull(unpacked, "unpacked");
	}

	/** What the last search left out of the list, sorted deterministically by file name. */
	public synchronized List<Unreadable> unreadable () {
		final List<Unreadable> copy = new ArrayList<>(unreadable);
		copy.sort(Comparator.comparing(u -> u.name));
		return copy;
	}

	private synchronized void leaveOut (final File save, final String reason) {
		logger.error("'%s' is not listed: %s%n", save.getAbsolutePath(), reason);
		unreadable.add(new Unreadable(save.getName(), reason));
	}

	// One save's tile, or nothing and the reason. The saves folder is the one
	// place the editor reads what it did not write, so whatever is wrong with
	// a file in it -- cut short, not a zip at all, a summary with a field
	// missing -- costs that file its tile and nothing else. An exception here
	// used to end the whole search, and the list with it.
	private SaveGameInfo tile (final File save) {
		return tile(save, false);
	}

	private SaveGameInfo tile (final File save, final boolean isolateByArchive) {
		try {
			return extractInfo(unpackSave(save, isolateByArchive));
		} catch (final ZipException e) {
			leaveOut(save, "it could not be unpacked (" + e.getMessage() + ")");
		} catch (final IOException e) {
			leaveOut(save, "it could not be prepared for extraction (" + e.getMessage() + ")");
		} catch (final SaveFileInfoException e) {
			leaveOut(save, e.getMessage());
		} catch (final RuntimeException e) {
			logger.error(e, "Unable to list '%s'.%n", save.getAbsolutePath());
			leaveOut(save, "it could not be read (" + e + ")");
		} finally {
			currentCount.getAndIncrement();
		}

		return null;
	}

	/**
	 * Unpacks a single {@code .savegame} archive (for example, dropped onto the
	 * editor window) into {@link #workingDirectory}, registers it with
	 * {@code UnpackedSaves}, and parses its {@link SaveGameInfo} metadata.
	 *
	 * @param save the {@code .savegame} archive file
	 * @return the extracted {@link SaveGameInfo}, or {@link Optional#empty()} if
	 *         the archive could not be unpacked or lacked required metadata
	 */
	public Optional<SaveGameInfo> unpackSingleSave (final File save) {
		if (save == null || !save.isFile()) {
			return Optional.empty();
		}

		totalFiles.set(1);
		currentCount.set(0);
		synchronized (this) {
			unreadable.clear();
		}

		return Optional.ofNullable(tile(save, true));
	}

	// Only what the save's tile in the list is drawn from. The rest of it --
	// the world state and a couple of hundred area files, 110 MB of a late
	// save -- is unpacked when the save is opened or compared
	// (UnpackedSaves.complete), not for every save on every search.
	private File unpackSave (final File save, final boolean isolateByArchive)
		throws ZipException, IOException {

		final File destination = isolateByArchive
			? isolatedDestination(save)
			: new File(workingDirectory, save.getName());
		if (isolateByArchive && destination.exists()) {
			org.apache.commons.io.FileUtils.deleteDirectory(destination);
		}

		final ZipFile archive = new ZipFile(save);
		for (final String name : LISTED_FILES) {
			if (archive.getFileHeader(name) != null) {
				archive.extractFile(name, destination.getAbsolutePath());
			}
		}

		unpacked.listed(destination, save);
		return destination;
	}

	/**
	 * Gives each externally opened archive its own staging parent while keeping
	 * the archive's basename as the leaf directory (SaveGameInfo reads it).
	 * Clearing that leaf before each extraction prevents removed archive entries
	 * from surviving when the same archive is opened again after it changes.
	 */
	private File isolatedDestination (final File save) throws IOException {
		final String identity = save.getCanonicalPath();
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(identity.getBytes(StandardCharsets.UTF_8));
			final StringBuilder folder = new StringBuilder(digest.length * 2);
			for (final byte value : digest) {
				folder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
			}
			return new File(new File(workingDirectory, "dropped"), folder.toString())
				.toPath().resolve(save.getName()).toFile();
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	private static SaveGameInfo extractInfo (final File saveFolder) throws SaveFileInfoException {
		final File[] contents = saveFolder.listFiles();
		final Set<String> requiredFiles = new TreeSet<>(Arrays.asList(REQUIRED_FILES));
		final Set<String> optionalFiles = new HashSet<>(Arrays.asList(OPTIONAL_FILES));
		final Map<String, File> importantFiles =
			Arrays.stream(contents == null ? new File[0] : contents)
				.filter(f -> requiredFiles.contains(f.getName()) || optionalFiles.contains(f.getName()))
				.collect(Collectors.toMap(File::getName, Function.identity()));

		requiredFiles.removeAll(importantFiles.keySet());
		if (!requiredFiles.isEmpty()) {
			throw new SaveFileInfoException("it has no " + String.join(" or ", requiredFiles)
				+ ", and every save the game writes has one");
		}

		return new SaveGameInfo(saveFolder, importantFiles);
	}

	public Optional<SaveGameInfo[]> unpackAllSaves () {
		final File savesDirectory = new File(savesLocation);
		if (!savesDirectory.exists()) {
			return Optional.empty();
		}

		final File[] saves = savesDirectory.listFiles();
		if (saves == null) {
			return Optional.empty();
		}

		// Only what the game writes. The saves folder collects other things -- a
		// log file, the converter's own "converted" folder -- and every one of
		// them used to be handed to the unzipper on every search, logged as an
		// error, and counted towards the progress bar.
		final File[] saveFiles = Arrays.stream(saves)
			.filter(File::isFile)
			.filter(f -> f.getName().toLowerCase().endsWith(".savegame"))
			.toArray(File[]::new);
		totalFiles.set(saveFiles.length);
		currentCount.set(0);
		synchronized (this) {
			unreadable.clear();
		}

		Arrays.sort(saveFiles); // Deterministic encounter order for parallel stream.
		final SaveGameInfo[] info =
			Arrays.stream(saveFiles)
				.parallel()
				.map(this::tile)
				.filter(Objects::nonNull)
				.toArray(SaveGameInfo[]::new);

		return Optional.of(info);
	}
}
