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
import org.json.JSONStringer;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.environment.GameLocator;
import uk.me.mantas.eternity.save.GameText;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static uk.me.mantas.eternity.environment.Variables.Key.*;

public class GetDefaultSaveLocation extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(GetDefaultSaveLocation.class);

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		final Environment environment = Environment.getInstance();
		final JSONObject settings = Settings.getInstance().json;
		String defaultSaveLocation = null;
		String defaultGameLocation = null;

		try {
			defaultSaveLocation = settings.getString("savesLocation");
		} catch (final JSONException ignored) {}

		try {
			defaultGameLocation = settings.getString("gameLocation");
		} catch (final JSONException ignored) {}

		if (defaultSaveLocation == null || defaultSaveLocation.length() < 1) {
			final Optional<String> userProfile = environment.variables().get(USERPROFILE);
			final Optional<String> xdgDataHome = environment.variables().get(XDG_DATA_HOME);
			final Optional<String> home = environment.variables().get(HOME);
			final String linuxSaves = "PillarsOfEternity/SavedGames";

			if (userProfile.isPresent()) {
				final Path defaultLocation =
					Paths.get(userProfile.get()).resolve("Saved Games\\Pillars of Eternity");

				if (defaultLocation.toFile().exists()) {
					defaultSaveLocation = defaultLocation.toString();
				}
			} else if (xdgDataHome.isPresent()) {
				final Path defaultLocation = Paths.get(xdgDataHome.get()).resolve(linuxSaves);
				if (defaultLocation.toFile().exists()) {
					defaultSaveLocation = defaultLocation.toString();
				}
			} else if (home.isPresent()) {
				// Linux first, then macOS. Both hang off HOME, and the two
				// layouts cannot be confused for one another, so trying each in
				// turn costs nothing.
				//
				// The macOS paths come from the roadmap's own note rather than
				// from a machine -- there is no Mac here to check them on, and
				// the app cannot start there yet anyway (no JCEF native
				// bundle). Both spellings are tried for that reason.
				for (final String candidate : new String[]{
					".local/share/" + linuxSaves
					, "Library/Application Support/Pillars of Eternity/SavedGames"
					, "Library/Application Support/Pillars of Eternity"}) {

					final Path defaultLocation = Paths.get(home.get()).resolve(candidate);
					if (defaultLocation.toFile().exists()) {
						defaultSaveLocation = defaultLocation.toString();
						break;
					}
				}
			}
		}

		if (defaultSaveLocation == null) {
			defaultSaveLocation = "";
		}

		// The saves were never the hard part -- every desktop store puts them
		// in the same folder. Finding the install is, and GameLocator does it
		// across every drive and every store rather than the system drive and
		// four hardcoded paths.
		final List<String> notes = new ArrayList<>();
		if (defaultGameLocation == null || defaultGameLocation.length() < 1) {
			final GameLocator.Result located = GameLocator.getInstance().locate();
			notes.addAll(located.notes);

			if (located.installation.isPresent()) {
				defaultGameLocation = located.installation.get().getAbsolutePath();
			}
		}

		if (defaultGameLocation == null) {
			defaultGameLocation = "";
		} else {
			final boolean found = !defaultGameLocation.equals(settings.optString("gameLocation", ""));
			settings.put("gameLocation", defaultGameLocation);

			// The game's names are read once and kept, out of the install that
			// settings name: whatever asked before this one was found was told
			// there is none.
			if (found) {
				GameText.startAgain();
			}
		}

		callback.success(foundDefault(defaultSaveLocation, defaultGameLocation, notes));
		return true;
	}

	private String foundDefault (
		final String savesLocation, final String gameLocation, final List<String> notes) {

		final JSONStringer json = new JSONStringer();
		json.object()
			.key("savesLocation").value(savesLocation)
			.key("gameLocation").value(gameLocation)
			.key("notes").array();

		for (final String note : notes) {
			json.value(note);
		}

		json.endArray().endObject();
		return json.toString();
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		// Not really sure what this means yet so log it for now.
		logger.error("Query #%d was cancelled.%n", id);
	}
}
