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
import uk.me.mantas.eternity.save.SaveBackups;
import uk.me.mantas.eternity.save.SaveBackups.Backup;
import uk.me.mantas.eternity.save.SaveBackups.Reason;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * A copy of a save, taken before the editor changes or removes a file the
 * player already has: Delete removes it, Rename rewrites it in place, and Save
 * replaces a file of the same name. None of those could be undone before.
 */
public class SaveBackupsTest extends TestHarness {
	private static final File SAVEINFO = new File(SaveBackupsTest.class
		.getResource("/ChangesSaverTest/id 0 Encampment.savegame/saveinfo.xml").getPath()
		.replace("%20", " "));

	private File saves;
	private File root;
	private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

	@Before
	public void folders () {
		saves = EKUtils.createTempDir(PREFIX).get();
		root = new File(EKUtils.createTempDir(PREFIX).get(), "backups");
	}

	/** A real .savegame: a zip with the game's own saveinfo.xml in it. */
	private File save (final String name, final String contents) throws IOException {
		final File staging = EKUtils.createTempDir(PREFIX).get();
		final File mobileObjects = new File(staging, "MobileObjects.save");
		FileUtils.writeStringToFile(mobileObjects, contents, StandardCharsets.UTF_8);

		final File save = new File(saves, name);
		final ZipFile zip = new ZipFile(save);
		zip.addFile(SAVEINFO);
		zip.addFile(mobileObjects);
		return save;
	}

	private SaveBackups backups (final int keep) {
		return new SaveBackups(root, keep, clock::get);
	}

	@Test
	public void aBackupIsAnExactCopyAndSaysWhy () throws IOException {
		final File save = save("abc 12 Encampment.savegame", "world state");
		final Backup backup = backups(10).backup(save, Reason.DELETE);

		assertTrue(FileUtils.contentEquals(save, backup.file));
		assertEquals(save.getCanonicalFile(), backup.original);
		assertEquals(Reason.DELETE, backup.reason);
		assertEquals("abc 12 Encampment.savegame", backup.file.getName());

		final List<Backup> listed = backups(10).list();
		assertEquals(1, listed.size());
		assertEquals(backup.id, listed.get(0).id);
		assertEquals("Start", listed.get(0).userSaveName);
		assertEquals("Encampment", listed.get(0).sceneTitle);
		assertEquals(save.length(), listed.get(0).size);
		assertEquals(clock.get(), listed.get(0).time);
	}

	@Test
	public void theNewestComeFirst () throws IOException {
		final SaveBackups backups = backups(10);
		final Backup first = backups.backup(save("a 1 X.savegame", "one"), Reason.RENAME);
		clock.addAndGet(60_000);
		final Backup second = backups.backup(save("b 2 X.savegame", "two"), Reason.DELETE);

		final List<Backup> listed = backups.list();
		assertEquals(second.id, listed.get(0).id);
		assertEquals(first.id, listed.get(1).id);
	}

	/** Saves run to tens of megabytes, so the folder cannot grow forever. */
	@Test
	public void onlyTheNewestAreKept () throws IOException {
		final SaveBackups backups = backups(3);
		Backup oldest = null;
		for (int i = 0; i < 5; i++) {
			final Backup backup = backups.backup(save(i + " 1 X.savegame", "v" + i), Reason.DELETE);
			if (oldest == null) {
				oldest = backup;
			}
			clock.addAndGet(1000);
		}

		assertEquals(3, backups.list().size());
		assertFalse("the oldest copy is gone from disk", oldest.file.exists());
		assertEquals("4 1 X.savegame", backups.list().get(0).file.getName());
	}

	// Every Save after the first in a session replaces the file the first one
	// wrote, and backs it up. With one limit for every reason, ten Saves pushed
	// out the copy a Delete had taken, and with it the only copy of a save the
	// player had deleted (found by review, 2026-10-27). The limit is counted
	// for each reason on its own.
	@Test
	public void repeatedSavesNeverPushOutTheCopyADeleteTook () throws IOException {
		final SaveBackups backups = backups(3);
		final Backup deleted = backups.backup(save("a 1 X.savegame", "deleted"), Reason.DELETE);
		clock.addAndGet(1000);
		final Backup renamed = backups.backup(save("b 1 X.savegame", "renamed"), Reason.RENAME);
		clock.addAndGet(1000);

		for (int i = 0; i < 11; i++) {
			backups.backup(save("c 1 X.savegame", "written " + i), Reason.OVERWRITE);
			clock.addAndGet(1000);
		}

		final List<Backup> kept = backups.list();
		assertTrue("the deleted save's copy is kept", deleted.file.exists());
		assertTrue("the renamed save's copy is kept", renamed.file.exists());
		assertEquals(3, kept.stream().filter(b -> b.reason == Reason.OVERWRITE).count());
		assertEquals(5, kept.size());
	}

	@Test
	public void twoBackupsInTheSameMomentAreBothKept () throws IOException {
		final SaveBackups backups = backups(10);
		final File save = save("a 1 X.savegame", "one");
		final Backup first = backups.backup(save, Reason.RENAME);
		final Backup second = backups.backup(save, Reason.OVERWRITE);

		assertNotEquals(first.id, second.id);
		assertEquals(2, backups.list().size());
	}

	@Test
	public void aRestoredSaveGoesBackUnderItsOwnName () throws IOException {
		final File save = save("abc 12 Encampment.savegame", "world state");
		final byte[] before = FileUtils.readFileToByteArray(save);
		final SaveBackups backups = backups(10);
		final Backup backup = backups.backup(save, Reason.DELETE);
		assertTrue(save.delete());

		final File restored = backups.restore(backup.id);
		assertEquals(save.getCanonicalFile(), restored.getCanonicalFile());
		assertArrayEquals(before, FileUtils.readFileToByteArray(save));
	}

	/** Restoring must not become a way to lose the save that is there now. */
	@Test
	public void restoreNeverOverwrites () throws IOException {
		final File save = save("abc 12 Encampment.savegame", "world state");
		final SaveBackups backups = backups(10);
		final Backup backup = backups.backup(save, Reason.RENAME);
		FileUtils.writeStringToFile(save, "renamed since", StandardCharsets.UTF_8);

		try {
			backups.restore(backup.id);
			fail("restored over an existing save");
		} catch (final IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("already"));
		}

		assertEquals("renamed since", FileUtils.readFileToString(save, StandardCharsets.UTF_8));
	}

	@Test(expected = IOException.class)
	public void thereIsNothingToBackUpWhenTheSaveIsMissing () throws IOException {
		backups(10).backup(new File(saves, "gone.savegame"), Reason.DELETE);
	}

	@Test(expected = IOException.class)
	public void anUnknownBackupCannotBeRestored () throws IOException {
		backups(10).restore("../../somewhere");
	}

	@Test
	public void noBackupsYetIsAnEmptyList () {
		assertTrue(backups(10).list().isEmpty());
	}
}
