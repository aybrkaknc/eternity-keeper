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
import uk.me.mantas.eternity.save.SaveGameRenamer;

import java.io.FileNotFoundException;
import java.io.IOException;

// Renames a saved game (its user-visible UserSaveName). Request:
// {savePath: <original .savegame>, extractedPath: <working copy>, newName}.
public class RenameSavedGame extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(RenameSavedGame.class);

	@Override
	public boolean onQuery (
		CefBrowser browser
		, long id
		, String request
		, boolean persistent
		, CefQueryCallback callback) {

		Environment.getInstance().mutationWorker().execute(Answered.to(callback, () -> rename(request, callback)));
		return true;
	}

	@Override
	public void onQueryCanceled (CefBrowser browser, long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private void rename (final String request, final CefQueryCallback callback) {
		try {
			final JSONObject json = new JSONObject(request);
			final String newName = json.getString("newName").trim();

			if (newName.isEmpty()) {
				callback.failure(-1, "The save name cannot be empty.");
				return;
			}

			final SaveGameRenamer renamer = new SaveGameRenamer(
				json.getString("savePath")
				, json.getString("extractedPath"));

			renamer.rename(newName);

			final JSONObject response = new JSONObject();
			response.put("success", true);
			response.put("userSaveName", newName);
			callback.success(response.toString());
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
		} catch (final FileNotFoundException e) {
			logger.error("File not found: %s%n", e.getMessage());
			callback.failure(-1, "Unable to find the save file.");
		} catch (final IOException e) {
			logger.error("Error renaming save: %s%n", e.getMessage());
			callback.failure(-1, "Error renaming the save file.");
		}
	}
}
