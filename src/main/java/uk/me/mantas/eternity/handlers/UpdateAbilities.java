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

import org.json.JSONArray;
import org.json.JSONObject;
import uk.me.mantas.eternity.save.AbilityCatalog;
import uk.me.mantas.eternity.save.AbilityManager;
import uk.me.mantas.eternity.save.AbilityManager.Change;
import uk.me.mantas.eternity.save.AbilityManager.NewAbility;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Adds and removes abilities, spells and talents. The request carries
// {oldSave, savedYet, changes: [{kind, character, ...}]} where kind is one of
// addAbility / removeAbility / addTalent / removeTalent. Removal addresses an
// ability by its own object GUID, since a wizard can carry two copies of the
// same spell; addition carries everything needed to mint the object, resolved
// from the catalog by the UI.
public class UpdateAbilities extends SaveMutationHandler {
	@Override
	protected String mutate (final File save, final JSONObject request) throws IOException {
		final JSONArray changesJson = request.getJSONArray("changes");
		final List<Change> changes = new ArrayList<>();

		for (int i = 0; i < changesJson.length(); i++) {
			final JSONObject change = changesJson.getJSONObject(i);
			final String kind = change.getString("kind");
			final String character = change.getString("character");

			switch (kind) {
				case "addAbility":
					changes.add(Change.addAbility(character, newAbility(change)));
					break;

				case "removeAbility":
					changes.add(Change.removeAbility(character, change.getString("abilityGuid")));
					break;

				case "addTalent":
					changes.add(Change.addTalent(
						character
						, change.getString("talent")
						, grants(change.optJSONArray("grants"))
						, skills(change.optJSONObject("skills"))));

					break;

				case "removeTalent":
					changes.add(Change.removeTalent(
						character
						, change.getString("talent")
						, prefabs(change.optJSONArray("grants"), change.getString("talent"))
						, skills(change.optJSONObject("skills"))));

					break;

				default:
					// Nothing is applied when any part of the request is
					// nonsense: half a change set is worse than none.
					return "Unknown ability change '" + kind + "'.";
			}
		}

		return new AbilityManager(save).apply(changes)
			? null : "Ability update failed. Details are in eternity.log; Settings shows where it is.";
	}

	private static NewAbility newAbility (final JSONObject json) {
		return new NewAbility(
			json.getString("prefab")
			, json.optString("path", "")
			, json.optString("component", "GenericAbility")
			, json.optInt("effect", 5)
			, json.optBoolean("spell", false)
			, json.optString("class", ""));
	}

	private static List<NewAbility> grants (final JSONArray json) {
		final List<NewAbility> abilities = new ArrayList<>();
		for (int i = 0; json != null && i < json.length(); i++) {
			abilities.add(newAbility(json.getJSONObject(i)));
		}

		return abilities;
	}

	/**
	 * Names of the objects a talent put on the character, so removing the
	 * talent can take them away with it.
	 *
	 * <p>The catalog is the authority here, not the request. The UI only knows
	 * a talent's grants for whatever its browser happens to have loaded, so
	 * trusting it meant removing a talent nobody had searched for left its
	 * ability behind — the character kept the effect with no talent explaining
	 * it. Anything the client does send is merged in, which keeps the editor
	 * working against an install with no catalog.
	 */
	private static List<String> prefabs (final JSONArray json, final String talent) {
		final AbilityCatalog catalog = AbilityCatalog.getInstance();
		final List<String> names = new ArrayList<>();

		for (final String key : catalog.lookup(talent)
			.map(entry -> entry.grants).orElse(Collections.emptyList())) {

			catalog.lookup(key).ifPresent(granted ->
				names.add(AbilityCatalog.prefabNameOf(granted.path, key)));
		}

		for (int i = 0; json != null && i < json.length(); i++) {
			final Object entry = json.get(i);
			final String name = entry instanceof JSONObject
				? ((JSONObject) entry).getString("prefab") : String.valueOf(entry);

			if (names.stream().noneMatch(name::equalsIgnoreCase)) {
				names.add(name);
			}
		}

		return names;
	}

	private static Map<String, Integer> skills (final JSONObject json) {
		final Map<String, Integer> bonuses = new LinkedHashMap<>();
		if (json != null) {
			for (final String skill : json.keySet()) {
				bonuses.put(skill, json.optInt(skill, 0));
			}
		}

		return bonuses;
	}
}
