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
import uk.me.mantas.eternity.game.GenericAbility.AbilityType;
import uk.me.mantas.eternity.save.AbilityCatalog;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Serves the ability catalog to the "add an ability or talent" browser.
 *
 * <p>Paged for the same reason {@link BrowseItems} is — 1,400 entries with
 * their icons would be megabytes of base64 through one JCEF query.
 *
 * <p>The interesting parameter is {@code progressionTable}: pass a class (and
 * optionally a companion's own table) and the results are restricted to what
 * that character could actually take, straight from the game's own
 * AbilityProgressionTable rather than a guess based on prefab paths.
 */
public class BrowseAbilities extends CatalogQuery {
	private static final int MAX_LIMIT = 200;

	@Override
	protected JSONObject answer (final JSONObject request) {
		final AbilityCatalog catalog = AbilityCatalog.getInstance();

		// Icon-only mode. The opener deliberately ships ability icons by name
		// rather than by value — a full party's worth of art pushed its single
		// reply past what the JCEF bridge carries — so the UI asks for the
		// handful it is about to draw.
		final JSONArray iconKeys = request.optJSONArray("iconKeys");
		if (iconKeys != null) {
			return icons(catalog, iconKeys);
		}

		final String search = request.optString("search", "").toLowerCase().trim();
		final String kind = request.optString("kind", "");
		final String spellClass = request.optString("spellClass", "");

		// "Any class" drops the progression filter entirely: the save format
		// happily holds an ability its owner could never have learned, and
		// refusing to show them would be the editor deciding for the user.
		final Map<String, AbilityCatalog.Unlock> allowed = request.optBoolean("anyClass", false)
			? null
			: catalog.unlocksFor(
				request.optString("characterClass", "")
				, request.optString("progressionTable", "")
				, request.optString("subrace", "")
				, request.optBoolean("isPlayer", false));

		List<Map.Entry<String, AbilityCatalog.Entry>> matches =
			catalog.search(search, kind, allowed);

		// Only wizards cast out of a grimoire, and the catalog records
		// which class a spell belongs to, so the grimoire editor pages
		// the wizard list rather than filtering a mixed one client-side
		// and leaving the pages ragged.
		if (!spellClass.isEmpty()) {
			matches = matches.stream()
				.filter(m -> spellClass.equalsIgnoreCase(m.getValue().characterClass))
				.collect(Collectors.toList());
		}

		// Sorted here rather than in the client, because the client pages: it
		// only ever holds a window of the list, and reordering that window
		// would put a level 8 spell above a level 1 one the moment the second
		// page arrived. The catalog already hands these back by name, so the
		// default needs no work.
		if ("level".equals(request.optString("sort", "").toLowerCase())) {
			matches.sort(Comparator
				.<Map.Entry<String, AbilityCatalog.Entry>>comparingInt(m -> sortLevel(m, allowed))
				.thenComparing(m -> m.getValue().name, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(Map.Entry::getKey));
		}

		final int offset = offset(request);
		final JSONObject response = new JSONObject();
		response.put("total", matches.size());
		response.put("offset", offset);
		response.put("abilities", page(matches, offset, limit(request, 60, MAX_LIMIT)
			, match -> row(catalog, match, allowed)));

		response.put("available", catalog.size() > 0);
		return response;
	}

	private static JSONObject icons (final AbilityCatalog catalog, final JSONArray keys) {
		final JSONObject icons = new JSONObject();
		for (int i = 0; i < keys.length(); i++) {
			final String key = keys.optString(i, "");
			catalog.lookup(key)
				.filter(entry -> !entry.icon.isEmpty())
				.map(entry -> catalog.iconData(entry.icon))
				.filter(data -> !data.isEmpty())
				.ifPresent(data -> icons.put(key.toLowerCase(), data));
		}

		final JSONObject response = new JSONObject();
		response.put("icons", icons);
		response.put("available", catalog.size() > 0);
		return response;
	}

	private static JSONObject row (
		final AbilityCatalog catalog
		, final Map.Entry<String, AbilityCatalog.Entry> match
		, final Map<String, AbilityCatalog.Unlock> allowed) {

		final AbilityCatalog.Entry entry = match.getValue();
		final JSONObject ability = new JSONObject();
		ability.put("key", match.getKey());
		ability.put("displayName", entry.name);
		ability.put("description", entry.description);
		ability.put("kind", entry.kind);
		ability.put("component", entry.component);
		ability.put("effect", entry.effect);
		ability.put("path", entry.path);
		ability.put("prefab", AbilityCatalog.prefabNameOf(entry.path, match.getKey()));
		ability.put("class", entry.characterClass);
		ability.put("spell", "spell".equals(entry.kind));
		ability.put("spellLevel", entry.spellLevel);
		ability.put("level", entry.level);
		ability.put("passive", entry.passive);
		ability.put("category", entry.category);
		ability.put("talentType", entry.talentType);
		ability.put("icon", catalog.iconData(entry.icon));

		// What adding this talent has to mint alongside it, resolved here so
		// the UI never has to know how a talent becomes an ability.
		ability.put("grants", grantsToJSON(catalog, entry));
		ability.put("modifies", array(entry.modifies));

		final JSONObject skills = new JSONObject();
		entry.skills.forEach(skills::put);
		ability.put("skills", skills);

		final AbilityCatalog.Unlock unlock = allowed == null ? null : allowed.get(match.getKey());
		if (unlock != null) {
			ability.put("unlockLevel", unlock.level);
			ability.put("unlockCategory", unlock.category);
			ability.put("automatic", unlock.automatic);
		}

		return ability;
	}

	private static JSONArray grantsToJSON (
		final AbilityCatalog catalog, final AbilityCatalog.Entry talent) {

		final JSONArray grants = new JSONArray();
		for (final String key : talent.grants) {
			final JSONObject granted = new JSONObject();
			granted.put("key", key);

			// InstantiateAbility stamps the source onto the object, so an
			// ability that arrived with a talent has to say so — the prefab's
			// own EffectType says Ability, which is what it is when a class
			// grants it directly.
			granted.put("effect", AbilityType.Talent.ordinal());

			final Optional<AbilityCatalog.Entry> entry = catalog.lookup(key);
			if (entry.isPresent()) {
				granted.put("prefab", AbilityCatalog.prefabNameOf(entry.get().path, key));
				granted.put("path", entry.get().path);
				granted.put("component", entry.get().component);
				granted.put("displayName", entry.get().name);
				granted.put("spell", "spell".equals(entry.get().kind));
				granted.put("class", entry.get().characterClass);
			} else {
				// No catalog entry: the game only cares about the file name, so
				// the key alone still resolves at load time.
				granted.put("prefab", key);
				granted.put("path", "");
				granted.put("component", "GenericAbility");
			}

			grants.put(granted);
		}

		return grants;
	}

	/**
	 * The level a player would look for. A spell's is its {@code SpellLevel} —
	 * which chapter of a grimoire it lands in — and everything else's is the
	 * character level its progression row unlocks it at. That one lives on the
	 * {@code Unlock} rather than on the ability, so it only exists once a
	 * character has been named; an ability nothing unlocks sorts last rather
	 * than first, since "no level" is not level zero.
	 */
	private static int sortLevel (
		final Map.Entry<String, AbilityCatalog.Entry> match
		, final Map<String, AbilityCatalog.Unlock> allowed) {

		final AbilityCatalog.Entry entry = match.getValue();
		if (entry.spellLevel > 0) {
			return entry.spellLevel;
		}

		final AbilityCatalog.Unlock unlock =
			allowed == null ? null : allowed.get(match.getKey());

		if (unlock != null && unlock.level > 0) {
			return unlock.level;
		}

		return entry.level > 0 ? entry.level : Integer.MAX_VALUE;
	}
}
