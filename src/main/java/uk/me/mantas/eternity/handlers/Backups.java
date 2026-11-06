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

package uk.me.mantas.eternity.handlers;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.SaveBackups;
import uk.me.mantas.eternity.save.SaveBackups.Backup;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;

/**
 * The Backups dialog's side of {@link SaveBackups}:
 * {@code {"action": "list"}} answers every copy with where it came from and
 * why; {@code {"action": "restore", "id"}} puts one back in its folder, never
 * over a save that is there; {@code {"action": "open"}} shows the folder.
 */
public class Backups extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(Backups.class);

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		// A restore copies a whole save; not on the browser's thread.
		Environment.getInstance().workers().execute(Answered.to(callback, () -> answer(request, callback)));
		return true;
	}

	private void answer (final String request, final CefQueryCallback callback) {
		final JSONObject json;
		try {
			json = new JSONObject(request);
		} catch (final JSONException e) {
			callback.failure(-1, "Unreadable request: " + e.getMessage());
			return;
		}

		final SaveBackups backups = SaveBackups.forThisProcess();
		final String action = json.optString("action", "list");
		try {
			switch (action) {
				case "list":
					callback.success(list(backups).toString());
					break;

				case "restore":
					final File restored = backups.restore(json.getString("id"));
					callback.success(new JSONObject()
						.put("restored", restored.getAbsolutePath()).toString());
					break;

				case "open":
					open(backups.folder());
					callback.success(new JSONObject().put("opened", true).toString());
					break;

				default:
					callback.failure(-1, "Unknown backups action: " + action);
			}
		} catch (final IOException | JSONException | UnsupportedOperationException e) {
			logger.error("Backups %s failed: %s%n", action, e.getMessage());
			callback.failure(-1, e.getMessage());
		}
	}

	private static JSONObject list (final SaveBackups backups) {
		final JSONArray rows = new JSONArray();
		for (final Backup backup : backups.list()) {
			rows.put(new JSONObject()
				.put("id", backup.id)
				.put("file", backup.file.getName())
				.put("original", backup.original.getAbsolutePath())
				.put("reason", backup.reason.name())
				.put("why", backup.reason.description)
				.put("time", backup.time)
				.put("size", backup.size)
				.put("userSaveName", backup.userSaveName)
				.put("sceneTitle", backup.sceneTitle));
		}

		return new JSONObject()
			.put("folder", backups.folder().getAbsolutePath())
			.put("keep", SaveBackups.KEEP)
			.put("backups", rows);
	}

	private static void open (final File folder) throws IOException {
		if (!folder.isDirectory() && !folder.mkdirs()) {
			throw new IOException("Could not create " + folder.getAbsolutePath() + ".");
		}

		if (!Desktop.isDesktopSupported()) {
			throw new UnsupportedOperationException(
				"This system cannot open folders from here. The backups are in "
					+ folder.getAbsolutePath() + ".");
		}

		Desktop.getDesktop().open(folder);
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d was cancelled.%n", id);
	}
}
