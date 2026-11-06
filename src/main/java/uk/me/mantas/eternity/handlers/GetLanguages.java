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
import org.json.JSONArray;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.environment.GameLanguage;
import uk.me.mantas.eternity.environment.GameLanguage.Language;
import uk.me.mantas.eternity.save.ItemCatalog;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * What Settings offers for the language the game's names are shown in.
 *
 * <p>Replies with the languages the install has text for (by the name each
 * calls itself), the one Settings holds ({@code chosen}, empty for "the same
 * as the game"), the one the game itself is set to ({@code game}, null when
 * it does not say), the one that follows from the two ({@code shown}), and
 * whether the game data that was read can be shown in another language at
 * all ({@code localizable}): data read before the catalogs kept where each
 * name came from can only be English, until it is read again.
 */
public class GetLanguages extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(GetLanguages.class);

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		Environment.getInstance().workers().execute(Answered.to(callback, () ->
			callback.success(describe().toString())));

		return true;
	}

	static JSONObject describe () {
		final JSONObject settings = Settings.getInstance().json;
		final String location = settings.optString("gameLocation", "");
		final List<Language> available =
			GameLanguage.available(location.isEmpty() ? null : new File(location));
		final Optional<String> gameSetting = GameLanguage.gameSetting();

		final JSONArray languages = new JSONArray();
		String chosen = "";
		Object game = JSONObject.NULL;

		for (final Language language : available) {
			languages.put(new JSONObject().put("code", language.code).put("label", language.label));

			if (language.code.equalsIgnoreCase(settings.optString("language", ""))) {
				chosen = language.code;
			}

			if (gameSetting.isPresent() && language.name.equalsIgnoreCase(gameSetting.get().trim())) {
				game = language.code;
			}
		}

		final ItemCatalog items = ItemCatalog.getInstance();
		return new JSONObject()
			.put("languages", languages)
			.put("chosen", chosen)
			.put("game", game)
			.put("shown", GameLanguage.choose(chosen, available, gameSetting))
			.put("catalogued", items.size() > 0)
			.put("localizable", items.localizable());
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d cancelled.%n", id);
	}
}
