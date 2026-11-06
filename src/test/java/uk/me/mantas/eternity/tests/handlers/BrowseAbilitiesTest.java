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

package uk.me.mantas.eternity.tests.handlers;

import org.apache.commons.io.FileUtils;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.handlers.BrowseAbilities;
import uk.me.mantas.eternity.save.AbilityCatalog;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * What order the browser offers things in.
 *
 * <p>The grimoire's spell list and the ability browser both page the catalog,
 * so the order has to be decided on this side: sorting the page that happens
 * to be loaded would put level 8 spells above level 1 ones as soon as the
 * second page arrived.
 *
 * <p>The two lists sort by different levels, and both are the level a player
 * would look for. A spell's is its {@code SpellLevel}, which is the chapter of
 * the grimoire it lands in. An ability or talent's is the character level its
 * progression row unlocks it at, which lives on the {@code Unlock} rather than
 * on the ability, so it only exists once a character has been named.
 */
public class BrowseAbilitiesTest extends TestHarness {
	private File catalogDirectory = null;

	// Names deliberately out of step with levels, so a sort by one is visibly
	// not a sort by the other.
	private static final String ABILITIES = "{"
		+ "\"arkemyrs\":{\"name\":\"Arkemyr's Wondrous Torment\",\"kind\":\"spell\""
		+ ",\"component\":\"GenericSpell\",\"effect\":3,\"spellLevel\":3,\"class\":\"Wizard\""
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Spells/Wizard/L_03/Arkemyrs.prefab\"}"
		+ ",\"chillfog\":{\"name\":\"Chill Fog\",\"kind\":\"spell\""
		+ ",\"component\":\"GenericSpell\",\"effect\":3,\"spellLevel\":1,\"class\":\"Wizard\""
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Spells/Wizard/L_01/Chill_Fog.prefab\"}"
		+ ",\"bounding\":{\"name\":\"Bounding Missiles\",\"kind\":\"spell\""
		+ ",\"component\":\"GenericSpell\",\"effect\":3,\"spellLevel\":2,\"class\":\"Wizard\""
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Spells/Wizard/L_02/Bounding.prefab\"}"
		+ ",\"druid_spell\":{\"name\":\"Autumn's Decay\",\"kind\":\"spell\""
		+ ",\"component\":\"GenericSpell\",\"effect\":3,\"spellLevel\":2,\"class\":\"Druid\""
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Spells/Druid/L_02/Autumn.prefab\"}"
		+ ",\"blast\":{\"name\":\"Blast\",\"kind\":\"ability\""
		+ ",\"component\":\"GenericAbility\",\"effect\":5,\"class\":\"Wizard\""
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Abilities/Wizard/Blast.prefab\"}"
		+ ",\"aegis\":{\"name\":\"Aegis of Loyalty\",\"kind\":\"ability\""
		+ ",\"component\":\"GenericAbility\",\"effect\":5,\"class\":\"Wizard\""
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Abilities/Wizard/Aegis.prefab\"}"
		+ "}";

	// Blast unlocks before Aegis, but sorts after it by name.
	private static final String PROGRESSION = "{"
		+ "\"wizard\":{"
		+ "\"blast\":{\"cat\":\"General\",\"level\":1}"
		+ ",\"aegis\":{\"cat\":\"General\",\"level\":9}"
		+ ",\"chillfog\":{\"cat\":\"General\",\"level\":1}"
		+ ",\"bounding\":{\"cat\":\"General\",\"level\":3}"
		+ ",\"arkemyrs\":{\"cat\":\"General\",\"level\":5}}"
		+ "}";

	private void useFixtureCatalog () throws IOException {
		final Optional<File> directory = EKUtils.createTempDir(PREFIX);
		assertTrue(directory.isPresent());
		catalogDirectory = directory.get();

		FileUtils.write(new File(catalogDirectory, "abilities.json"), ABILITIES, "UTF-8");
		FileUtils.write(new File(catalogDirectory, "progression.json"), PROGRESSION, "UTF-8");
		AbilityCatalog.useCatalogAt(catalogDirectory);
	}

	@After
	public void restoreCatalog () {
		AbilityCatalog.useNoCatalog();
	}

	private JSONObject browse (final JSONObject request) {
		final AtomicReference<String> reply = new AtomicReference<>();
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		doAnswer(invocation -> {
			reply.set(invocation.getArgument(0));
			return null;
		}).when(callback).success(anyString());

		new BrowseAbilities().onQuery(
			mock(CefBrowser.class), 0, request.toString(), false, callback);

		verify(callback, timeout(60000)).success(anyString());
		return new JSONObject(reply.get());
	}

	private List<String> names (final JSONObject response) {
		final JSONArray abilities = response.getJSONArray("abilities");
		final List<String> out = new ArrayList<>();
		for (int i = 0; i < abilities.length(); i++) {
			out.add(abilities.getJSONObject(i).getString("displayName"));
		}

		return out;
	}

	private List<Integer> levels (final JSONObject response, final String field) {
		final JSONArray abilities = response.getJSONArray("abilities");
		final List<Integer> out = new ArrayList<>();
		for (int i = 0; i < abilities.length(); i++) {
			out.add(abilities.getJSONObject(i).optInt(field, 0));
		}

		return out;
	}

	private JSONObject wizardSpells (final String sort) {
		final JSONObject request = new JSONObject();
		request.put("spellClass", "Wizard");
		request.put("kind", "spell");
		request.put("anyClass", true);
		request.put("limit", 50);
		if (sort != null) {
			request.put("sort", sort);
		}

		return browse(request);
	}

	// ---- the grimoire's spell list -----------------------------------------

	@Test
	public void spellsSortByNameByDefault () throws IOException {
		useFixtureCatalog();

		assertEquals(
			java.util.Arrays.asList(
				"Arkemyr's Wondrous Torment", "Bounding Missiles", "Chill Fog")
			, names(wizardSpells(null)));
	}

	@Test
	public void spellsCanSortByTheirChapter () throws IOException {
		useFixtureCatalog();

		final JSONObject response = wizardSpells("level");
		assertEquals(java.util.Arrays.asList(1, 2, 3), levels(response, "spellLevel"));
		assertEquals(
			java.util.Arrays.asList(
				"Chill Fog", "Bounding Missiles", "Arkemyr's Wondrous Torment")
			, names(response));
	}

	@Test
	public void aLevelSortStillBreaksTiesByName () throws IOException {
		useFixtureCatalog();

		// Two level-2 spells, one a druid's: with the class filter dropped they
		// share a level and must come back alphabetically.
		final JSONObject request = new JSONObject();
		request.put("kind", "spell");
		request.put("anyClass", true);
		request.put("sort", "level");
		request.put("limit", 50);

		final JSONObject response = browse(request);
		assertEquals(java.util.Arrays.asList(1, 2, 2, 3), levels(response, "spellLevel"));
		assertEquals(
			java.util.Arrays.asList(
				"Chill Fog", "Autumn's Decay", "Bounding Missiles"
				, "Arkemyr's Wondrous Torment")
			, names(response));
	}

	// ---- the ability browser ------------------------------------------------

	@Test
	public void abilitiesSortByTheLevelTheyUnlockAt () throws IOException {
		useFixtureCatalog();

		final JSONObject request = new JSONObject();
		request.put("characterClass", "Wizard");
		request.put("kind", "ability");
		request.put("sort", "level");
		request.put("limit", 50);

		final JSONObject response = browse(request);

		// Blast at 1 before Aegis at 9, which is the opposite of by name.
		assertEquals(java.util.Arrays.asList("Blast", "Aegis of Loyalty"), names(response));
		assertEquals(java.util.Arrays.asList(1, 9), levels(response, "unlockLevel"));
	}

	@Test
	public void sortingSurvivesPaging () throws IOException {
		useFixtureCatalog();

		// The client appends pages, so a page boundary must not reorder
		// anything: page 1 then page 2 has to equal the whole list.
		final List<String> whole = names(wizardSpells("level"));

		final List<String> paged = new ArrayList<>();
		for (int offset = 0; offset < whole.size(); offset++) {
			final JSONObject request = new JSONObject();
			request.put("spellClass", "Wizard");
			request.put("kind", "spell");
			request.put("anyClass", true);
			request.put("sort", "level");
			request.put("offset", offset);
			request.put("limit", 1);
			paged.addAll(names(browse(request)));
		}

		assertEquals(whole, paged);
	}

	@Test
	public void anUnknownSortIsTheDefaultRatherThanAnError () throws IOException {
		useFixtureCatalog();

		assertEquals(names(wizardSpells(null)), names(wizardSpells("nonsense")));
	}
}
