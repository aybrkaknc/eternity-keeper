/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2016 the authors.
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

package uk.me.mantas.eternity.tests.save;

import net.lingala.zip4j.ZipFile;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.ChangesSaver;
import uk.me.mantas.eternity.save.SaveBackups;
import uk.me.mantas.eternity.save.SaveBackups.Backup;
import uk.me.mantas.eternity.save.SaveBackups.Reason;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Save writes a new file into the saves folder, and if one of that name is
 * already there it is deleted to make room. That file is the player's — an
 * earlier edit, or a save that happens to share the name — so it is copied
 * into the backups first.
 */
public class PackageSaveGameTest extends TestHarness {
	private File saves;
	private File written;

	@Before
	public void folders () throws Exception {
		saves = EKUtils.createTempDir(PREFIX).get();
		Settings.getInstance().json.put("savesLocation", saves.getAbsolutePath());

		// What Save has assembled in its working folder, ready to be zipped.
		written = new File(EKUtils.createTempDir(PREFIX).get(), "abc 0 Encampment.savegame");
		assertTrue(written.mkdirs());
		FileUtils.writeStringToFile(
			new File(written, "MobileObjects.save"), "the new edit", StandardCharsets.UTF_8);
	}

	private void packageIt () {
		expose(ChangesSaver.class).call("packageSaveGame", written);
	}

	@Test
	public void aSaveItReplacesIsBackedUpFirst () throws Exception {
		final File existing = new File(saves, written.getName());
		FileUtils.writeStringToFile(existing, "an earlier edit", StandardCharsets.UTF_8);

		packageIt();

		final List<Backup> backups = SaveBackups.forThisProcess().list();
		assertEquals(1, backups.size());
		assertEquals(Reason.OVERWRITE, backups.get(0).reason);
		assertEquals("an earlier edit"
			, FileUtils.readFileToString(backups.get(0).file, StandardCharsets.UTF_8));
		assertTrue("the new save is written", new ZipFile(existing).isValidZipFile());
	}

	@Test
	public void aNewNameNeedsNoBackup () {
		packageIt();

		assertTrue(new File(saves, written.getName()).isFile());
		assertTrue(SaveBackups.forThisProcess().list().isEmpty());
	}

	// The number in a new save's name is the first one free, and it used to be
	// looked for only among the saves the list had unpacked. The list is as old
	// as the last search, and it leaves out what it cannot read: a backup put
	// back since, a save cut short. Such a file's number counted as free, and
	// the first Save of an edit replaced it.
	@Test
	public void aNewSaveTakesNoNameAlreadyInTheSavesFolder () throws Exception {
		final File listed = EKUtils.createTempDir(PREFIX).get();
		Environment.getInstance().directory().working(listed);
		final File opened = new File(listed, "abc 7 Encampment.savegame");
		assertTrue(opened.mkdirs());

		// Nothing of this session below 7 in the list, so 0 it would have been.
		assertEquals("abc 0 Encampment.savegame"
			, ChangesSaver.previewSaveFileName(opened.getAbsolutePath()));

		FileUtils.writeStringToFile(
			new File(saves, "abc 0 Encampment.savegame"), "put back from a backup", StandardCharsets.UTF_8);
		FileUtils.writeStringToFile(
			new File(saves, "abc 1 CaedNua.savegame"), "cut short", StandardCharsets.UTF_8);
		FileUtils.writeStringToFile(
			new File(saves, "xyz 2 Encampment.savegame"), "another playthrough's", StandardCharsets.UTF_8);

		assertEquals("abc 2 Encampment.savegame"
			, ChangesSaver.previewSaveFileName(opened.getAbsolutePath()));
	}

	// Whatever else is in the saves folder -- a log, a folder, a file with one
	// word for a name -- has no number to take.
	@Test
	public void whatIsNotASaveTakesNoNumber () throws Exception {
		final File listed = EKUtils.createTempDir(PREFIX).get();
		Environment.getInstance().directory().working(listed);
		final File opened = new File(listed, "abc 7 Encampment.savegame");
		assertTrue(opened.mkdirs());

		FileUtils.writeStringToFile(new File(saves, "abc.savegame"), "one word", StandardCharsets.UTF_8);
		FileUtils.writeStringToFile(new File(saves, "abc notes.txt"), "not a save", StandardCharsets.UTF_8);
		assertTrue(new File(saves, "abc 0 converted").mkdir());
		assertTrue(new File(listed, "abc.savegame").mkdir());

		assertEquals("abc 0 Encampment.savegame"
			, ChangesSaver.previewSaveFileName(opened.getAbsolutePath()));
	}
}
