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

package uk.me.mantas.eternity.tests.handlers;

import org.apache.commons.io.FileUtils;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.handlers.DeleteSavedGame;
import uk.me.mantas.eternity.save.SaveBackups;
import uk.me.mantas.eternity.save.SaveBackups.Backup;
import uk.me.mantas.eternity.save.SaveBackups.Reason;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Deleting a save is the one thing on the save list that cannot be taken
 * back, and it took whatever path the page sent. Now it keeps a copy first,
 * and only ever deletes a save.
 */
public class DeleteSavedGameTest extends TestHarness {
	private static final int WAIT = 10000;

	private File file (final String name, final String contents) throws Exception {
		final File file = new File(EKUtils.createTempDir(PREFIX).get(), name);
		FileUtils.writeStringToFile(file, contents, StandardCharsets.UTF_8);
		return file;
	}

	private CefQueryCallback delete (final File file) {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new DeleteSavedGame().onQuery(
			mock(CefBrowser.class), 0, file.getAbsolutePath(), false, callback);
		return callback;
	}

	@Test
	public void aDeletedSaveIsBackedUpFirst () throws Exception {
		final File save = file("abc 3 Encampment.savegame", "the whole playthrough");
		final CefQueryCallback callback = delete(save);

		verify(callback, timeout(WAIT)).success(anyString());
		assertFalse(save.exists());

		final List<Backup> backups = SaveBackups.forThisProcess().list();
		assertEquals(1, backups.size());
		assertEquals(Reason.DELETE, backups.get(0).reason);
		assertEquals(save.getCanonicalFile(), backups.get(0).original);
		assertEquals("the whole playthrough"
			, FileUtils.readFileToString(backups.get(0).file, StandardCharsets.UTF_8));
	}

	/** The request is a path from the page; it must not reach anything else on the disk. */
	@Test
	public void onlySavesCanBeDeleted () throws Exception {
		final File other = file("settings.json", "{}");
		final CefQueryCallback callback = delete(other);

		verify(callback, timeout(WAIT)).failure(anyInt(), anyString());
		verify(callback, never()).success(anyString());
		assertTrue(other.exists());
		assertTrue(SaveBackups.forThisProcess().list().isEmpty());
	}

	@Test
	public void aFolderIsNotASave () throws Exception {
		final File folder = new File(EKUtils.createTempDir(PREFIX).get(), "x.savegame");
		assertTrue(folder.mkdirs());
		final CefQueryCallback callback = delete(folder);

		verify(callback, timeout(WAIT)).failure(anyInt(), anyString());
		assertTrue(folder.isDirectory());
	}

	@Test
	public void aMissingSaveIsReported () throws Exception {
		final File gone = new File(EKUtils.createTempDir(PREFIX).get(), "gone.savegame");
		final CefQueryCallback callback = delete(gone);

		verify(callback, timeout(WAIT)).failure(eq(404), anyString());
	}
}
