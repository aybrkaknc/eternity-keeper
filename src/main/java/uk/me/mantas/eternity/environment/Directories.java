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

package uk.me.mantas.eternity.environment;

import org.apache.commons.io.FileUtils;
import uk.me.mantas.eternity.Logger;

import java.io.File;
import java.io.IOException;

public class Directories {
	private static final Logger logger = Logger.getLogger(Directories.class);

	private final AppPaths paths = AppPaths.forThisProcess();
	private File settingsFile = paths.settingsFile();
	private File working = new File(System.getProperty("java.io.tmpdir"), "EK-unpacked-saves");

	/** Where the app's own files and the user's data live; see {@link AppPaths}. */
	public AppPaths paths() {
		return paths;
	}

	public File settingsFile() {
		return settingsFile;
	}

	public void settingsFile(final File f) {
		settingsFile = f;
	}

	public File working() {
		return working;
	}

	public void working(final File dir) {
		working = dir;
	}

	Directories() {
		createWorking();
	}

	public void createWorking() {
		if (!working().exists() && !working().mkdirs()) {
			logger.error(
					"Unable to create working directory in '%s'.%n", working().getAbsolutePath());
		}
	}

	public void deleteWorking() {
		try {
			FileUtils.deleteDirectory(working());
		} catch (final IOException e) {
			logger.error(
					"Unable to delete working directory at '%s': %s%n", working().getAbsolutePath(), e.getMessage());
		}
	}

	public void emptyWorking() {
		deleteWorking();
		createWorking();
	}
}
