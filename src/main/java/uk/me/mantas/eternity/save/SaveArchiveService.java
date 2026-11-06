/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2026 the authors.
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

import org.apache.commons.io.FileUtils;
import uk.me.mantas.eternity.environment.UnpackedSaves;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Headless application service for listing, staging, and completing save
 * archives. Browser handlers adapt its typed results to the existing bridge
 * protocol; this class has no JCEF or JSON dependency.
 */
public final class SaveArchiveService {
	public enum Failure {
		INVALID_ARCHIVE,
		MISSING_METADATA
	}

	/** A user-actionable archive failure with its underlying extraction reason. */
	public static final class SaveArchiveException extends Exception {
		private final Failure failure;

		SaveArchiveException (final Failure failure, final String message) {
			super(message);
			this.failure = failure;
		}

		public Failure failure () {
			return failure;
		}
	}

	/** Immutable result of a directory scan. */
	public static final class SaveListResult {
		private final List<SaveGameInfo> saves;
		private final List<SaveGameExtractor.Unreadable> unreadable;
		private final boolean directoryFound;

		SaveListResult (
			final List<SaveGameInfo> saves
			, final List<SaveGameExtractor.Unreadable> unreadable
			, final boolean directoryFound) {

			this.saves = Collections.unmodifiableList(new ArrayList<>(saves));
			this.unreadable = Collections.unmodifiableList(new ArrayList<>(unreadable));
			this.directoryFound = directoryFound;
		}

		public List<SaveGameInfo> saves () { return saves; }
		public List<SaveGameExtractor.Unreadable> unreadable () { return unreadable; }
		public boolean directoryFound () { return directoryFound; }
	}

	private final File workingDirectory;
	private final UnpackedSaves unpacked;
	private volatile SaveGameExtractor extractor;

	public SaveArchiveService (final File workingDirectory, final UnpackedSaves unpacked) {
		this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
		this.unpacked = Objects.requireNonNull(unpacked, "unpacked");
	}

	/** Scans a save folder and returns its parsed tiles without involving the UI. */
	public SaveListResult list (final File savesDirectory) throws IOException {
		return list(savesDirectory, extractor -> {});
	}

	/** Scans a folder and publishes progress metadata before extraction begins. */
	public SaveListResult list (
		final File savesDirectory, final Consumer<SaveGameExtractor> progressSource) throws IOException {

		if (!workingDirectory.exists() && !workingDirectory.mkdirs()) {
			throw new IOException("Unable to create save staging directory: "
				+ workingDirectory.getAbsolutePath());
		}
		FileUtils.cleanDirectory(workingDirectory);
		unpacked.forgetAll();
		extractor = new SaveGameExtractor(
			savesDirectory == null ? "" : savesDirectory.getAbsolutePath()
			, workingDirectory, unpacked);
		progressSource.accept(extractor);
		final Optional<SaveGameInfo[]> found = extractor.unpackAllSaves();
		final List<SaveGameInfo> saves = new ArrayList<>();
		if (found.isPresent()) {
			Collections.addAll(saves, found.get());
		}
		return new SaveListResult(saves, extractor.unreadable()
			, savesDirectory != null && savesDirectory.isDirectory());
	}

	/** Extracts a dropped archive's tile into an isolated staging directory. */
	public SaveGameInfo stageArchive (final File archive) throws SaveArchiveException {
		if (archive == null || !archive.isFile()
			|| !archive.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".savegame")) {
			throw new SaveArchiveException(Failure.INVALID_ARCHIVE,
				"Choose a Pillars of Eternity .savegame archive.");
		}

		final File parent = archive.getParentFile();
		extractor = new SaveGameExtractor(parent == null ? "" : parent.getAbsolutePath()
			, workingDirectory, unpacked);
		final Optional<SaveGameInfo> result = extractor.unpackSingleSave(archive);
		if (result.isPresent()) {
			return result.get();
		}

		final List<SaveGameExtractor.Unreadable> unreadable = extractor.unreadable();
		final String reason = unreadable.isEmpty()
			? "The archive could not be unpacked."
			: unreadable.get(0).reason;
		final Failure failure = reason.contains("saveinfo.xml") || reason.contains("file name")
			? Failure.MISSING_METADATA : Failure.INVALID_ARCHIVE;
		throw new SaveArchiveException(failure, reason);
	}

	/** Completes lazy extraction before any opener or editor reads the folder. */
	public void complete (final File folder) throws IOException {
		unpacked.complete(folder);
	}

	/** Progress source used by the CEF adapter while a list operation runs. */
	public SaveGameExtractor extractor () {
		return extractor;
	}
}
