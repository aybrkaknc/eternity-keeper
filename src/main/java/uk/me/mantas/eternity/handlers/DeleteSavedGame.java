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

import org.apache.commons.io.FileUtils;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.SaveBackups;
import uk.me.mantas.eternity.save.SaveBackups.Reason;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Deletes a save from the saves folder. The request is the save's path.
 *
 * <p>A copy goes into the backups first (File → Backups puts it back), and
 * only a {@code .savegame} file is ever deleted: the path comes from the
 * page, and this used to delete whatever it named.
 */
public class DeleteSavedGame extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(DeleteSavedGame.class);

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		// The copy of a large save takes a moment; not on the browser's thread.
		Environment.getInstance().workers().execute(Answered.to(callback, () -> delete(new File(request), callback)));
		return true;
	}

	private void delete (final File save, final CefQueryCallback callback) {
		if (!save.exists()) {
			callback.failure(404, error("That save is no longer there: " + save.getAbsolutePath()));
			return;
		}

		if (!save.isFile() || !save.getName().toLowerCase(Locale.ROOT).endsWith(".savegame")) {
			logger.error("Refused to delete '%s': not a save.%n", save.getAbsolutePath());
			callback.failure(400, error("Only a .savegame file can be deleted."));
			return;
		}

		try {
			SaveBackups.forThisProcess().backup(save, Reason.DELETE);
		} catch (final IOException e) {
			logger.error("Could not back up '%s', so it was not deleted: %s%n"
				, save.getAbsolutePath(), e.getMessage());
			callback.failure(500, error(
				"The save could not be backed up, so it was not deleted: " + e.getMessage()));
			return;
		}

		try {
			remove(save);
		} catch (final IOException e) {
			logger.error("Failed to delete '%s': %s%n", save.getAbsolutePath(), e.getMessage());
			callback.failure(500, error("Unable to delete the save: " + e.getMessage()));
			return;
		}

		if (save.exists()) {
			logger.error("'%s' is still there after deleting it.%n", save.getAbsolutePath());
			callback.failure(500, error(
				"The save reappeared after it was deleted. Steam Cloud may be restoring it."));
			return;
		}

		logger.info("Deleted '%s'.%n", save.getAbsolutePath());
		callback.success(new JSONObject().put("success", true).toString());
	}

	// Windows sometimes holds a file a moment after it was last read (the save
	// list has just unpacked it), so a failed delete is retried once.
	private static void remove (final File save) throws IOException {
		if (!save.canWrite() && !save.setWritable(true)) {
			logger.warn("'%s' is read-only and could not be made writable.%n", save.getAbsolutePath());
		}

		try {
			FileUtils.forceDelete(save);
		} catch (final IOException first) {
			logger.warn("Delete failed (%s); retrying.%n", first.getMessage());
			System.gc();
			try {
				Thread.sleep(200);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			Files.delete(save.toPath());
		}
	}

	private static String error (final String message) {
		return new JSONObject().put("error", message).toString();
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d was cancelled.%n", id);
	}
}
