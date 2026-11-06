/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2026 the authors.
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.save.SaveWriteCommand;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converts the page's Save request into the typed command the writer takes. */
public final class SaveWriteRequestAdapter {
	public SaveWriteCommand parse (final String request) throws JSONException {
		return parse(new JSONObject(request));
	}

	public SaveWriteCommand parse (final JSONObject request) throws JSONException {
		final JSONObject data = request.getJSONObject("saveData");

		final Map<String, SaveWriteCommand.CharacterUpdate> characters = new LinkedHashMap<>();
		final JSONArray sourceCharacters = data.optJSONArray("characters");
		if (sourceCharacters != null) {
			for (int i = 0; i < sourceCharacters.length(); i++) {
				final JSONObject character = sourceCharacters.getJSONObject(i);
				characters.put(character.getString("GUID"), new SaveWriteCommand.CharacterUpdate(
					values(character.optJSONObject("stats"))
					, values(character.optJSONObject("portraitPaths"))
					, values(character.optJSONObject("health"))));
			}
		}

		final Map<String, Map<String, Map<String, String>>> globals = new LinkedHashMap<>();
		final JSONObject roots = data.optJSONObject("globals");
		if (roots != null) {
			for (final String rootName : roots.keySet()) {
				final JSONObject sourceComponents = roots.getJSONObject(rootName);
				final Map<String, Map<String, String>> components = new LinkedHashMap<>();
				for (final String componentName : sourceComponents.keySet()) {
					components.put(
						componentName, values(sourceComponents.getJSONObject(componentName)));
				}

				globals.put(rootName, components);
			}
		}

		// Absent when the save records none, and from any request made before
		// the party dialog could change it.
		final Integer supplies = data.has("campingSupplies") && !data.isNull("campingSupplies")
			? data.getInt("campingSupplies")
			: null;

		return new SaveWriteCommand(
			request.getBoolean("savedYet")
			, request.getString("saveName")
			, request.getString("absolutePath")
			, data.getDouble("currency")
			, supplies
			, characters
			, globals);
	}

	// {"Might": {"value": "18"}} is Might = "18".
	private static Map<String, String> values (final JSONObject source) throws JSONException {
		final Map<String, String> result = new LinkedHashMap<>();
		if (source == null) {
			return result;
		}

		for (final String key : source.keySet()) {
			result.put(key, source.getJSONObject(key).getString("value"));
		}

		return result;
	}
}
