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

package uk.me.mantas.eternity.tests.environment;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.WorkingSave;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Which directory holds the current state of the save the editor has open.
 *
 * <p>The save list unpacks every save once, and the editor opens that unpacked
 * copy. Every manager used to edit it in place, so an Apply the user then
 * discarded was still there the next time they opened the same save from the
 * list — measured on the running editor, a companion resurrected and never
 * saved was alive again on reopening — and a Save from that point would have
 * written it into a file. Edits go to a private copy now, made by the first
 * one, and opening a save from the list throws that copy away.
 */
public class WorkingSaveTest extends TestHarness {
	private static File unpacked (final String contents) throws Exception {
		final File save = new File(
			EKUtils.createTempDir(PREFIX).get(), "0945952c 32111932 CaedNua.savegame");

		assertTrue(save.mkdir());
		write(new File(save, "MobileObjects.save"), contents);
		write(new File(save, "saveinfo.xml"), "<info/>");
		return save;
	}

	private static void write (final File file, final String contents) throws Exception {
		FileUtils.writeStringToFile(file, contents, StandardCharsets.UTF_8);
	}

	private static String read (final File save) throws Exception {
		return FileUtils.readFileToString(new File(save, "MobileObjects.save"), StandardCharsets.UTF_8);
	}

	@Test
	public void readingAnUntouchedSaveReadsTheOneThatWasOpened () throws Exception {
		final File opened = unpacked("original");
		final WorkingSave working = new WorkingSave();

		assertEquals(opened, working.forReading(opened, false));
	}

	@Test
	public void theFirstEditGetsAPrivateCopyAndTheOpenedSaveIsLeftAlone () throws Exception {
		final File opened = unpacked("original");
		final WorkingSave working = new WorkingSave();

		final File editing = working.forEditing(opened, false);
		assertNotEquals(opened.getAbsoluteFile(), editing.getAbsoluteFile());
		assertEquals("original", read(editing));
		assertTrue(new File(editing, "saveinfo.xml").isFile());

		write(new File(editing, "MobileObjects.save"), "edited");
		assertEquals("original", read(opened));
	}

	@Test
	public void theCopyKeepsTheSavesNameBecauseSavingDerivesTheNewFileNameFromIt ()
		throws Exception {

		final File opened = unpacked("original");
		assertEquals(opened.getName(), new WorkingSave().forEditing(opened, false).getName());
	}

	@Test
	public void laterEditsAndReadsUseTheSameCopy () throws Exception {
		final File opened = unpacked("original");
		final WorkingSave working = new WorkingSave();

		final File editing = working.forEditing(opened, false);
		write(new File(editing, "MobileObjects.save"), "edited");

		assertEquals(editing, working.forEditing(opened, false));
		assertEquals(editing, working.forReading(opened, false));
		assertEquals("edited", read(working.forReading(opened, false)));
	}

	@Test
	public void openingASaveFromTheListDiscardsWhatWasNeverSaved () throws Exception {
		final File opened = unpacked("original");
		final WorkingSave working = new WorkingSave();

		final File editing = working.forEditing(opened, false);
		write(new File(editing, "MobileObjects.save"), "resurrected, never saved");

		working.opening();

		assertEquals(opened, working.forReading(opened, false));
		assertEquals("original", read(working.forReading(opened, false)));
		assertFalse("the copy is not left behind in the temp folder", editing.exists());
	}

	@Test
	public void aCopyBelongsToTheSaveItWasMadeFrom () throws Exception {
		final File first = unpacked("first");
		final File second = unpacked("second");
		final WorkingSave working = new WorkingSave();

		working.forEditing(first, false);

		assertEquals(second, working.forReading(second, false));
		assertEquals("second", read(working.forEditing(second, false)));
	}

	@Test
	public void afterASaveEverythingUsesTheDirectoryThatWasWritten () throws Exception {
		final File opened = unpacked("original");
		final File written = unpacked("written");
		final WorkingSave working = new WorkingSave();

		working.forEditing(opened, false);
		working.written(written);

		assertEquals(written, working.forEditing(opened, true));
		assertEquals(written, working.forReading(opened, true));
	}

	@Test
	public void aClientThatThinksItSavedWhenNothingWasWrittenGetsItsOwnCopy () throws Exception {
		final File opened = unpacked("original");
		final WorkingSave working = new WorkingSave();

		final File editing = working.forEditing(opened, true);
		assertNotEquals(opened.getAbsoluteFile(), editing.getAbsoluteFile());
		assertNull(working.written());
	}

	@Test
	public void openingASaveForgetsTheLastOneWritten () throws Exception {
		final File opened = unpacked("original");
		final WorkingSave working = new WorkingSave();

		working.written(unpacked("written"));
		working.opening();

		assertNull(working.written());
		assertEquals(opened, working.forReading(opened, true));
	}

	@Test
	public void aSaveThatDoesNotExistIsNotCopied () throws Exception {
		final File missing = new File(EKUtils.createTempDir(PREFIX).get(), "404.savegame");
		final WorkingSave working = new WorkingSave();

		assertEquals(missing, working.forEditing(missing, false));
		assertFalse(missing.exists());
	}
}
