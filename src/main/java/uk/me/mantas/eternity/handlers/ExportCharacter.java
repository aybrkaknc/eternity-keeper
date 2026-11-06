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
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.CharacterExporter;
import uk.me.mantas.eternity.serializer.WriteRefusedException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import static org.cef.handler.CefDialogHandler.FileDialogMode;

public class ExportCharacter extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(ExportCharacter.class);

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		ChrDialog.choose(browser, FileDialogMode.FILE_DIALOG_SAVE, "Save Character"
			, callback, "NO_SAVENAME", filename -> export(request, filename, callback));

		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private static void export (
		final String request, final String filename, final CefQueryCallback callback) {

		try {
			final JSONObject json = new JSONObject(request);
			final File save = Environment.getInstance().state().workingSave().forReading(
				new File(json.getString("absolutePath")), json.optBoolean("savedYet", false));

			final boolean exported = new CharacterExporter(save.getAbsolutePath()
				, json.getString("GUID"), ChrDialog.withChrExtension(filename)).export();

			if (exported) {
				callback.success("true");
			} else {
				callback.failure(-1, "EXPORT_ERR");
			}
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "BAD_REQUEST");
		} catch (final WriteRefusedException e) {
			// Not a code: the page shows a reason in words as it stands.
			logger.error("Export refused: %s%n", e.getMessage());
			callback.failure(-1, e.getMessage());
		} catch (final FileNotFoundException e) {
			logger.error("Unable to find file : %s%n", e.getMessage());
			callback.failure(-1, "FILE_NOT_FOUND");
		} catch (final IOException e) {
			logger.error("Filesystem error: %s%n", e.getMessage());
			callback.failure(-1, "FILESYSTEM_ERR");
		}
	}
}
