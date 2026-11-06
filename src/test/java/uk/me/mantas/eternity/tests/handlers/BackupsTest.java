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
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.handlers.Backups;
import uk.me.mantas.eternity.save.SaveBackups;
import uk.me.mantas.eternity.save.SaveBackups.Backup;
import uk.me.mantas.eternity.save.SaveBackups.Reason;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** The page's side of the backups: what there is, and putting one back. */
public class BackupsTest extends TestHarness {
	private static final int WAIT = 10000;

	private File save (final String name, final String contents) throws Exception {
		final File file = new File(EKUtils.createTempDir(PREFIX).get(), name);
		FileUtils.writeStringToFile(file, contents, StandardCharsets.UTF_8);
		return file;
	}

	private CefQueryCallback ask (final JSONObject request) {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new Backups().onQuery(mock(CefBrowser.class), 0, request.toString(), false, callback);
		return callback;
	}

	private JSONObject answer (final CefQueryCallback callback) {
		final ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
		verify(callback, timeout(WAIT)).success(reply.capture());
		return new JSONObject(reply.getValue());
	}

	private String refusal (final CefQueryCallback callback) {
		final ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
		verify(callback, timeout(WAIT)).failure(anyInt(), reply.capture());
		verify(callback, never()).success(anyString());
		return reply.getValue();
	}

	@Test
	public void theListSaysWhatEachCopyIsAndWhy () throws Exception {
		SaveBackups.forThisProcess().backup(save("a 1 Encampment.savegame", "one"), Reason.RENAME);
		Thread.sleep(5);
		SaveBackups.forThisProcess().backup(save("b 2 Encampment.savegame", "two"), Reason.DELETE);

		final JSONObject list = answer(ask(new JSONObject().put("action", "list")));
		final JSONArray backups = list.getJSONArray("backups");

		assertEquals(2, backups.length());
		assertEquals(SaveBackups.KEEP, list.getInt("keep"));
		assertEquals(SaveBackups.forThisProcess().folder().getAbsolutePath(), list.getString("folder"));

		final JSONObject newest = backups.getJSONObject(0);
		assertEquals("b 2 Encampment.savegame", newest.getString("file"));
		assertEquals("DELETE", newest.getString("reason"));
		assertEquals("before it was deleted", newest.getString("why"));
		assertEquals(3, newest.getLong("size"));
		assertTrue(newest.getLong("time") > 0);
		assertTrue(newest.getString("original").endsWith("b 2 Encampment.savegame"));
		assertTrue(newest.has("id"));
	}

	@Test
	public void aBackupIsPutBack () throws Exception {
		final File save = save("a 1 Encampment.savegame", "the playthrough");
		final Backup backup = SaveBackups.forThisProcess().backup(save, Reason.DELETE);
		assertTrue(save.delete());

		final JSONObject restored =
			answer(ask(new JSONObject().put("action", "restore").put("id", backup.id)));

		assertEquals(save.getCanonicalPath(), new File(restored.getString("restored")).getCanonicalPath());
		assertEquals("the playthrough", FileUtils.readFileToString(save, StandardCharsets.UTF_8));
	}

	/** The reason is the user's to read: what is in the way, and what to do. */
	@Test
	public void aRestoreThatWouldReplaceASaveSaysWhy () throws Exception {
		final File save = save("a 1 Encampment.savegame", "the playthrough");
		final Backup backup = SaveBackups.forThisProcess().backup(save, Reason.RENAME);

		final String why = refusal(ask(new JSONObject().put("action", "restore").put("id", backup.id)));
		assertTrue(why, why.contains("already"));
	}

	@Test
	public void anUnknownActionIsRefused () {
		final String why = refusal(ask(new JSONObject().put("action", "format-drive")));
		assertTrue(why, why.contains("format-drive"));
	}

	@Test
	public void noBackupsIsAnEmptyList () {
		assertEquals(0, answer(ask(new JSONObject().put("action", "list")))
			.getJSONArray("backups").length());
	}
}
