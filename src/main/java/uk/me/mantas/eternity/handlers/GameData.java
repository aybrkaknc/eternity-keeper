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
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.AppPaths;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.environment.GameDataExtraction;
import uk.me.mantas.eternity.save.ItemCatalog;

import java.io.File;

/**
 * Reading the game's own data — item names and icons, abilities, stronghold
 * upgrades, deities — out of the install. {@code {"action": "status"}} answers
 * what there is and how a run is going (the UI polls it); {@code {"action":
 * "start"}} reads the install the Settings dialog names; {@code {"action":
 * "cancel"}} stops a run.
 */
public class GameData extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(GameData.class);

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		final GameDataExtraction extraction = GameDataExtraction.getInstance();
		final String action;
		try {
			action = new JSONObject(request).optString("action", "status");
		} catch (final JSONException e) {
			callback.failure(-1, "Unreadable request: " + e.getMessage());
			return true;
		}

		final String game = Settings.getInstance().json.optString("gameLocation", "");
		if ("start".equals(action) && !game.isEmpty()) {
			extraction.start(new File(game));
		} else if ("cancel".equals(action)) {
			extraction.cancel();
		}

		final JSONObject status = extraction.status();
		status.put("game", game);

		// What the editor is using right now, wherever it came from — an
		// itemdata folder from before the editor ran the extractor itself
		// counts as much as its own.
		status.put("items", ItemCatalog.getInstance().size());

		// The Settings dialog says where the editor keeps its own files, so an
		// error message can point at eternity.log without guessing a path.
		final AppPaths paths = Environment.getInstance().directory().paths();
		status.put("dataFolder", paths.data().getAbsolutePath());
		status.put("logFile", paths.logFile().getAbsolutePath());

		callback.success(status.toString());
		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d was cancelled.%n", id);
	}
}
