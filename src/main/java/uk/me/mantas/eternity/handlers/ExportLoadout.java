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

package uk.me.mantas.eternity.handlers;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefDialogHandler.FileDialogMode;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.Loadout;
import uk.me.mantas.eternity.serializer.WriteRefusedException;

import java.io.File;
import java.io.IOException;

/**
 * Saves one character's gear to a {@code .loadout} file the user chooses.
 *
 * <p>The request is {@code {absolutePath, savedYet, GUID, name}}; the gear is
 * read from the working save, so it is what the last Apply left. The reply is
 * {@code {file, items}}. A character wearing and holding nothing is told so,
 * and no file is written.
 */
public class ExportLoadout extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(ExportLoadout.class);

	/** What the page is told when the dialog was closed without a file. */
	public static final String CANCELLED = "CANCELLED";

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		final JSONObject json;
		try {
			json = new JSONObject(request);
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return true;
		}

		final String name = json.optString("name", "").replaceAll("[\\\\/:*?\"<>|]", "").trim();
		ChrDialog.choose(browser, FileDialogMode.FILE_DIALOG_SAVE, "Save loadout", Loadout.EXTENSION
			, name.isEmpty() ? "" : name + "." + Loadout.EXTENSION, callback, CANCELLED
			, path -> export(json, path, callback));

		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	/** Writes the file chosen; public so it can be asked without a dialog. */
	public static void export (final JSONObject request, final String path, final CefQueryCallback callback) {
		try {
			final File save = Environment.getInstance().state().workingSave().forReading(
				new File(request.getString("absolutePath")), request.optBoolean("savedYet", false));
			final File file = new File(ChrDialog.withExtension(path, Loadout.EXTENSION));

			final int items = Loadout.save(save, request.getString("GUID"), file);
			if (items < 1) {
				callback.failure(-1, request.optString("name", "This character")
					+ " wears and holds nothing, so there is no loadout to save.");
				return;
			}

			callback.success(new JSONObject()
				.put("file", file.getAbsolutePath())
				.put("items", items)
				.toString());
		} catch (final JSONException e) {
			logger.error("Error reading request %s: %s%n", request, e.getMessage());
			callback.failure(-1, "Error parsing JSON request.");
		} catch (final WriteRefusedException e) {
			logger.error("Saving a loadout refused: %s%n", e.getMessage());
			callback.failure(-1, e.getMessage());
		} catch (final IOException e) {
			logger.error("Saving a loadout to %s failed: %s%n", path, e.getMessage());
			callback.failure(-1, "Could not write the loadout: " + e.getMessage());
		}
	}
}
