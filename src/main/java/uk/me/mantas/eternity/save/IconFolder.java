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

import org.apache.commons.io.FileUtils;
import uk.me.mantas.eternity.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A folder of icons, handed out as base64 PNG and cached for the life of the
 * process. Icons repeat heavily across a party, so a save's worth of art comes
 * off disk once.
 *
 * <p>The cache is concurrent because its readers are: the item browser, the
 * ability browser and the save opener all run on the shared worker pool, and
 * the two catalogs that used to keep their own plain {@code HashMap} were
 * being written from several of those threads at once.
 */
public class IconFolder {
	private static final Logger logger = Logger.getLogger(IconFolder.class);

	private final File directory;
	private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

	/** {@code directory} may be null, when the catalog is not installed. */
	public IconFolder (final File directory) {
		this.directory = directory;
	}

	/**
	 * The icon as base64, or an empty string when there is no folder, no such
	 * file, or a name that tries to reach outside the folder.
	 */
	public String data (final String iconFile) {
		if (iconFile == null || iconFile.isEmpty() || directory == null) {
			return "";
		}

		return cache.computeIfAbsent(iconFile, this::read);
	}

	/** How many distinct icons have been read. For tests. */
	public int cachedCount () {
		return cache.size();
	}

	private String read (final String iconFile) {
		final File file = new File(directory, iconFile);

		try {
			// Catalog icon names are file names; anything that resolves
			// somewhere else is not one of ours.
			if (!file.getCanonicalFile().getParentFile()
				.equals(directory.getCanonicalFile())) {

				return "";
			}

			return file.isFile()
				? Base64.getEncoder().encodeToString(FileUtils.readFileToByteArray(file))
				: "";
		} catch (final IOException e) {
			logger.error("Unable to read icon '%s': %s%n", file.getAbsolutePath(), e.getMessage());
			return "";
		}
	}
}
