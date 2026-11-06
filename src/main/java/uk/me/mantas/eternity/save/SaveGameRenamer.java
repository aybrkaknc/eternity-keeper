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
import org.apache.commons.io.FileUtils;
import uk.me.mantas.eternity.EKUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Changes a save's user-visible name (the UserSaveName inside saveinfo.xml)
 * both in the extracted working copy and inside the original .savegame
 * archive on disk. The archive keeps its file name; the game reads the
 * display name from saveinfo.xml. The archive is replaced whole, never edited
 * in place, and a rename that fails changes neither copy.
 */
public class SaveGameRenamer {
	private final File saveArchive;
	private final File extractedDirectory;

	public SaveGameRenamer (final String saveArchivePath, final String extractedPath)
		throws FileNotFoundException {

		saveArchive = new File(saveArchivePath);
		extractedDirectory = new File(extractedPath);

		if (!saveArchive.isFile()) {
			throw new FileNotFoundException(saveArchivePath);
		}

		if (!extractedDirectory.isDirectory()) {
			throw new FileNotFoundException(extractedPath);
		}
	}

	public void rename (final String newUserSaveName) throws IOException {
		// This replaces the player's own file, so it is copied first, and a
		// save that cannot be copied is not touched.
		SaveBackups.forThisProcess().backup(saveArchive, SaveBackups.Reason.RENAME);

		// The new saveinfo.xml is made apart and put into a copy of the file
		// beside it; only a finished copy replaces the file, and only then
		// does the working copy take the name. Taking saveinfo.xml out of the
		// file itself and then adding the new one left a save without it if
		// the second step failed, and a working copy named for a rename that
		// never happened if the file could not be changed at all.
		final File staging = EKUtils.createTempDir("EK-rename")
			.orElseThrow(() -> new IOException("Could not make a folder to rename the save in."));
		final File directory = saveArchive.getAbsoluteFile().getParentFile();
		final File renaming = File.createTempFile(saveArchive.getName() + ".", ".renaming", directory);

		try {
			final File saveinfo = new File(staging, "saveinfo.xml");
			FileUtils.copyFile(new File(extractedDirectory, "saveinfo.xml"), saveinfo);
			SaveGameInfo.updateSaveInfo(staging, newUserSaveName);

			FileUtils.copyFile(saveArchive, renaming);
			new ZipFile(renaming).addFile(saveinfo);

			try {
				Files.move(renaming.toPath(), saveArchive.toPath()
					, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (final AtomicMoveNotSupportedException e) {
				Files.move(renaming.toPath(), saveArchive.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}

			FileUtils.copyFile(saveinfo, new File(extractedDirectory, "saveinfo.xml"));
		} finally {
			if (renaming.exists() && !renaming.delete()) {
				renaming.deleteOnExit();
			}

			FileUtils.deleteQuietly(staging);
		}
	}
}
