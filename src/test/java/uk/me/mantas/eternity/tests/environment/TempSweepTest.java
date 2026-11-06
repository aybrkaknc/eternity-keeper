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


package uk.me.mantas.eternity.tests.environment;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.TempSweep;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Clearing out what earlier runs of the editor left in the temp folder.
 *
 * <p>An editor closed normally deletes its working copy of the open save and
 * the files Undo keeps. One that crashes, is killed or loses power cannot, and
 * each of those folders is a copy of a save: 43 of them, 1.7 GB, had collected
 * on the machine this was written on. They are swept at the next start --
 * but only what is a day old, so an editor or a test run that is still going
 * keeps what is its own.
 */
public class TempSweepTest extends TestHarness {
	private static final long NOW = System.currentTimeMillis();
	private static final long TWO_DAYS_AGO = NOW - TimeUnit.DAYS.toMillis(2);

	private static File folder (final File temp, final String name, final long modified) throws Exception {
		final File folder = new File(temp, name);
		final File save = new File(folder, "session 1 Scene.savegame");
		assertTrue(save.mkdirs());
		FileUtils.writeStringToFile(new File(save, "MobileObjects.save"), "a world state", "UTF-8");
		assertTrue(save.setLastModified(modified));
		assertTrue(folder.setLastModified(modified));
		return folder;
	}

	@Test
	public void whatAnEarlierRunLeftBehindIsSwept () throws Exception {
		final File temp = EKUtils.createTempDir(PREFIX).get();
		final File editing = folder(temp, "EK-editing-1234", TWO_DAYS_AGO);
		final File history = folder(temp, "EK-history-5678", TWO_DAYS_AGO);
		final File donor = folder(temp, "EK-donor91011", TWO_DAYS_AGO);
		final File conversion = folder(temp, "EK-convert121314", TWO_DAYS_AGO);
		final File plain = folder(temp, "EK-1516171819", TWO_DAYS_AGO);
		final File working = folder(temp, "EK-unpacked-saves", TWO_DAYS_AGO);

		assertEquals(5, TempSweep.sweep(temp, working, NOW));
		assertFalse(editing.exists());
		assertFalse(history.exists());
		assertFalse(donor.exists());
		assertFalse(conversion.exists());
		assertFalse(plain.exists());
		assertTrue("the folder the save list unpacks into is the editor's own", working.exists());
	}

	@Test
	public void whatIsStillInUseIsLeftAlone () throws Exception {
		final File temp = EKUtils.createTempDir(PREFIX).get();
		final File fresh = folder(temp, "EK-editing-1234", NOW - TimeUnit.HOURS.toMillis(3));

		// Opened two days ago and edited an hour ago: the edit replaced a file
		// inside the copy, which is what moves the copy's own date.
		final File busy = folder(temp, "EK-editing-5678", TWO_DAYS_AGO);
		assertTrue(new File(busy, "session 1 Scene.savegame")
			.setLastModified(NOW - TimeUnit.HOURS.toMillis(1)));

		assertEquals(0, TempSweep.sweep(temp, new File(temp, "EK-unpacked-saves"), NOW));
		assertTrue(fresh.exists());
		assertTrue(busy.exists());
	}

	@Test
	public void nothingThatIsNotTheEditorsIsTouched () throws Exception {
		final File temp = EKUtils.createTempDir(PREFIX).get();
		// The temp folder is everyone's, and "EK-" is three letters.
		final File someoneElses = folder(temp, "EK-editing-notes", TWO_DAYS_AGO);
		final File anothers = folder(temp, "EK-backup", TWO_DAYS_AGO);
		final File lookalike = folder(temp, "EKS-editing-1", TWO_DAYS_AGO);
		final File unrelated = folder(temp, "editing-EK-1", TWO_DAYS_AGO);
		final File aFile = new File(temp, "EK-notes.txt");
		FileUtils.writeStringToFile(aFile, "a file, not a folder", "UTF-8");
		assertTrue(aFile.setLastModified(TWO_DAYS_AGO));

		assertEquals(0, TempSweep.sweep(temp, new File(temp, "EK-unpacked-saves"), NOW));
		assertTrue(someoneElses.exists());
		assertTrue(anothers.exists());
		assertTrue(lookalike.exists());
		assertTrue(unrelated.exists());
		assertTrue(aFile.exists());
	}

	@Test
	public void aTempFolderThatIsNotThereIsNoError () {
		assertEquals(0, TempSweep.sweep(new File("404"), new File("404/EK-unpacked-saves"), NOW));
	}
}
