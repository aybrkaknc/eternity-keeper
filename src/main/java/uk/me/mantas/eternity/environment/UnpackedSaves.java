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


package uk.me.mantas.eternity.environment;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import uk.me.mantas.eternity.Logger;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which of the folders the save list unpacked hold a whole save.
 *
 * <p>A save's tile in the list is drawn from eight small files -- saveinfo.xml,
 * the screenshot and the party's portraits -- so that is all a search unpacks
 * ({@code SaveGameExtractor}). Unpacking every save whole, as every search used
 * to, took 5.8 s and 767 MB of temp space for twelve real saves, each time;
 * a player with a hundred would have waited a minute at every start. The rest
 * of a save follows when something needs it -- opening it, comparing it -- by
 * {@link #complete}.
 *
 * <p>That a folder is whole is remembered here rather than read off the
 * folder. One with MobileObjects.save in it may be one whose unpacking stopped
 * half way, and a save opened from that would be written back without the
 * areas that never arrived.
 */
public class UnpackedSaves {
	private static final Logger logger = Logger.getLogger(UnpackedSaves.class);

	/** The folder a search made, to the archive it made it from. */
	private final Map<File, File> archives = new HashMap<>();
	private final Set<File> whole = new HashSet<>();

	/** A search starts from an empty folder: nothing in it is whole any more. */
	public synchronized void forgetAll () {
		archives.clear();
		whole.clear();
	}

	/** A search unpacked what it draws of {@code archive} into {@code folder}. */
	public synchronized void listed (final File folder, final File archive) {
		final File key = key(folder);
		archives.put(key, archive);
		whole.remove(key);
	}

	/**
	 * Makes sure every file of the save is in {@code folder}. A folder no
	 * search unpacked -- one a Save wrote, one handed over as it stands -- has
	 * no archive behind it and is whole already; one completed before is not
	 * unpacked again, since an edit may have changed it since.
	 *
	 * @throws IOException when the archive cannot be unpacked. The folder is
	 *         then not whole, and must not be opened as if it were.
	 */
	public synchronized void complete (final File folder) throws IOException {
		final File key = key(folder);
		final File archive = archives.get(key);
		if (archive == null || whole.contains(key)) {
			return;
		}

		try {
			new ZipFile(archive).extractAll(key.getAbsolutePath());
		} catch (final ZipException e) {
			logger.error("Unable to unzip '%s': %s%n", archive.getAbsolutePath(), e.getMessage());
			throw new IOException(archive.getName() + " could not be unpacked: " + e.getMessage(), e);
		}

		whole.add(key);
	}

	// The list hands a folder out by one spelling of its path and the page
	// hands it back by the same one, but a caller may have canonicalised it
	// in between (CompareSaves does), and on a machine whose temp folder has
	// an 8.3 name the two spellings differ.
	private static File key (final File folder) {
		try {
			return folder.getCanonicalFile();
		} catch (final IOException e) {
			return folder.getAbsoluteFile();
		}
	}
}
