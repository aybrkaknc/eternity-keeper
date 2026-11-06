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

package uk.me.mantas.eternity.tests.save;

import org.apache.commons.io.FileUtils;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.save.AbilityCatalog;
import uk.me.mantas.eternity.save.IdentityCatalog;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

// What each identity choice is actually worth.
//
// Every number here is a row of a static table on CharacterStats, and the
// tables are live: GetAttributeScore() adds RaceAbilityAdjustment and
// CultureAbilityAdjustment to the stored BaseX on every read, and
// CalculateSkillInternal() adds ClassSkillAdjustment and
// BackgroundSkillAdjustment to the rank. Nothing bakes them into the save, so
// changing race or culture in the editor really does move the character
// sheet -- which is exactly what the panel has to be able to say.
//
// The column orders are the game's own enums and disagree with each other:
// attributes run Resolve, Might, Dexterity, Intellect, Constitution,
// Perception, while skills run Stealth, Athletics, Lore, Mechanics, Survival,
// Crafting. Getting either wrong silently relabels every bonus, so the
// expectations below are written out as names.
public class IdentityCatalogTest extends TestHarness {
	private static final String IDENTITY = "{"
		+ "\"deities\":{\"Berath\":{\"name\":\"Berath\""
		+ ",\"positive\":[\"Stoic\",\"Rational\"]"
		+ ",\"negative\":[\"Passionate\",\"Cruel\"]}}"
		+ ",\"orders\":{\"BleakWalkers\":{\"name\":\"Bleak Walkers\""
		+ ",\"positive\":[\"Cruel\",\"Aggressive\"]"
		+ ",\"negative\":[\"Benevolent\",\"Diplomatic\"]}"
		+ ",\"FrermasMesCancSuolias\":{\"name\":\"Frermas mes Canc Suolias\""
		+ ",\"positive\":[],\"negative\":[]}}"
		+ ",\"dispositionBonus\":{\"positive\":[0.2,0.4,0.6]"
		+ ",\"negative\":[-0.2,-0.4,-0.6]}}";

	private static final String ABILITIES = "{"
		+ "\"silvertide\":{\"name\":\"Silver Tide\",\"kind\":\"ability\""
		+ ",\"desc\":\"A wave of healing.\",\"icon\":\"race_godlike.png\"}"
		+ ",\"haleandhardy\":{\"name\":\"Hale and Hardy\",\"kind\":\"ability\""
		+ ",\"desc\":\"A bonus to Fortitude.\"}}";

	private static final String PROGRESSION = "{\"racial\":{"
		+ "\"silvertide\":{\"level\":1,\"auto\":1,\"cat\":\"Racial\""
		+ ",\"subraces\":[\"Moon_Godlike\"]}"
		+ ",\"haleandhardy\":{\"level\":1,\"auto\":1,\"cat\":\"Racial\""
		+ ",\"subraces\":[\"Mountain_Dwarf\"]}}}";

	private File catalogDirectory () throws IOException {
		final Optional<File> directory = EKUtils.createTempDir(PREFIX);
		assertTrue(directory.isPresent());

		FileUtils.write(
			new File(directory.get(), "identity.json"), IDENTITY, "UTF-8");
		FileUtils.write(
			new File(directory.get(), "abilities.json"), ABILITIES, "UTF-8");
		FileUtils.write(
			new File(directory.get(), "progression.json"), PROGRESSION, "UTF-8");

		return directory.get();
	}

	private IdentityCatalog catalog () throws IOException {
		final File directory = catalogDirectory();
		AbilityCatalog.useCatalogAt(directory);
		IdentityCatalog.useCatalogAt(directory);
		return IdentityCatalog.getInstance();
	}

	@After
	public void restoreCatalog () {
		IdentityCatalog.useNoCatalog();
	}

	@Test
	public void raceAdjustsAttributes () {
		final Map<String, Integer> dwarf = IdentityCatalog.raceAttributes("Dwarf");
		assertEquals(3, dwarf.size());
		assertEquals(Integer.valueOf(2), dwarf.get("Might"));
		assertEquals(Integer.valueOf(1), dwarf.get("Constitution"));
		assertEquals(Integer.valueOf(-1), dwarf.get("Dexterity"));

		final Map<String, Integer> human = IdentityCatalog.raceAttributes("Human");
		assertEquals(Integer.valueOf(1), human.get("Might"));
		assertEquals(Integer.valueOf(1), human.get("Resolve"));

		// The one race with a penalty as well as a bonus, and the one whose
		// columns would look plausible under the wrong order.
		final Map<String, Integer> orlan = IdentityCatalog.raceAttributes("Orlan");
		assertEquals(Integer.valueOf(2), orlan.get("Perception"));
		assertEquals(Integer.valueOf(1), orlan.get("Resolve"));
		assertEquals(Integer.valueOf(-1), orlan.get("Might"));

		assertEquals(Integer.valueOf(2)
			, IdentityCatalog.raceAttributes("Aumaua").get("Might"));
	}

	@Test
	public void cultureAdjustsOneAttribute () {
		assertEquals(Integer.valueOf(1)
			, IdentityCatalog.cultureAttributes("Ruatai").get("Constitution"));
		assertEquals(Integer.valueOf(1)
			, IdentityCatalog.cultureAttributes("TheLivingLands").get("Might"));
		assertEquals(Integer.valueOf(1)
			, IdentityCatalog.cultureAttributes("OldVailia").get("Intellect"));

		// Every configured culture is worth exactly one point somewhere.
		for (final String culture : IdentityCatalog.cultures()) {
			assertEquals(culture, 1
				, IdentityCatalog.cultureAttributes(culture).size());
		}
	}

	@Test
	public void classAdjustsSkills () {
		final Map<String, Integer> rogue = IdentityCatalog.classSkills("Rogue");
		assertEquals(Integer.valueOf(1), rogue.get("Stealth"));
		assertEquals(Integer.valueOf(2), rogue.get("Mechanics"));
		assertEquals(2, rogue.size());

		final Map<String, Integer> wizard = IdentityCatalog.classSkills("Wizard");
		assertEquals(Integer.valueOf(2), wizard.get("Lore"));
		assertEquals(Integer.valueOf(1), wizard.get("Mechanics"));

		assertEquals(Integer.valueOf(1)
			, IdentityCatalog.classSkills("Monk").get("Survival"));
	}

	@Test
	public void onlyPlayableClassesAdjustSkills () {
		// CalculateSkillInternal() guards the lookup with IsPlayableClass(),
		// which is true for Fighter..Chanter and nothing else -- a creature
		// class gets no skill bonus even though the table has rows past it.
		assertTrue(IdentityCatalog.classSkills("Troll").isEmpty());
		assertTrue(IdentityCatalog.classSkills("Undefined").isEmpty());
		assertTrue(IdentityCatalog.classSkills("NotAClass").isEmpty());
	}

	@Test
	public void backgroundAdjustsSkills () {
		final Map<String, Integer> explorer =
			IdentityCatalog.backgroundSkills("Explorer");
		assertEquals(Integer.valueOf(1), explorer.get("Lore"));
		assertEquals(Integer.valueOf(1), explorer.get("Survival"));

		assertEquals(Integer.valueOf(2)
			, IdentityCatalog.backgroundSkills("Aristocrat").get("Lore"));
		assertEquals(Integer.valueOf(1)
			, IdentityCatalog.backgroundSkills("Laborer").get("Athletics"));
		assertEquals(Integer.valueOf(1)
			, IdentityCatalog.backgroundSkills("Laborer").get("Mechanics"));

		// Every background is worth two points, as one +2 or two +1s.
		for (final String background : IdentityCatalog.backgrounds()) {
			int total = 0;
			for (final int value
				: IdentityCatalog.backgroundSkills(background).values()) {

				total += value;
			}

			assertEquals(background, 2, total);
		}
	}

	@Test
	public void unknownNamesAdjustNothing () {
		assertTrue(IdentityCatalog.raceAttributes("Vessel").isEmpty());
		assertTrue(IdentityCatalog.raceAttributes("Undefined").isEmpty());
		assertTrue(IdentityCatalog.raceAttributes("Nonsense").isEmpty());
		assertTrue(IdentityCatalog.raceAttributes(null).isEmpty());
		assertTrue(IdentityCatalog.cultureAttributes("Nonsense").isEmpty());
		assertTrue(IdentityCatalog.backgroundSkills("Nonsense").isEmpty());
	}

	@Test
	public void subraceCarriesItsRacialAbility () throws IOException {
		final IdentityCatalog catalog = catalog();

		final Optional<IdentityCatalog.RacialAbility> moon =
			catalog.racialAbility("Moon_Godlike");
		assertTrue(moon.isPresent());
		assertEquals("silvertide", moon.get().key);
		assertEquals("Silver Tide", moon.get().name);
		assertEquals("A wave of healing.", moon.get().description);

		assertEquals("Hale and Hardy"
			, catalog.racialAbility("Mountain_Dwarf").get().name);
		assertFalse(catalog.racialAbility("Wood_Elf").isPresent());
	}

	@Test
	public void deitiesAndOrdersCarryTheirDispositions () throws IOException {
		final IdentityCatalog catalog = catalog();

		final Optional<IdentityCatalog.Devotion> berath = catalog.deity("Berath");
		assertTrue(berath.isPresent());
		assertEquals("Berath", berath.get().name);
		assertEquals("Stoic", berath.get().positive.get(0));
		assertEquals("Rational", berath.get().positive.get(1));
		assertEquals("Passionate", berath.get().negative.get(0));

		final Optional<IdentityCatalog.Devotion> bleak =
			catalog.order("BleakWalkers");
		assertTrue(bleak.isPresent());
		assertEquals("Bleak Walkers", bleak.get().name);
		assertEquals("Cruel", bleak.get().positive.get(0));

		// Pallegina's own order is configured with no dispositions at all.
		assertTrue(catalog.order("FrermasMesCancSuolias").get().positive.isEmpty());
		assertFalse(catalog.deity("Skaen").isPresent());
	}

	@Test
	public void noCatalogStillKnowsTheCompiledTables () {
		IdentityCatalog.useNoCatalog();
		final IdentityCatalog catalog = IdentityCatalog.getInstance();

		// The adjustment tables are fields on CharacterStats, so they are
		// always available; only the prefab-sourced halves go missing.
		assertEquals(Integer.valueOf(2)
			, IdentityCatalog.raceAttributes("Dwarf").get("Might"));
		assertFalse(catalog.deity("Berath").isPresent());
		assertFalse(catalog.racialAbility("Moon_Godlike").isPresent());
	}

	@Test
	public void theJSONTheUIGetsIsCompleteAndNamed () throws IOException {
		final JSONObject json = catalog().asJSON();

		assertEquals("Resolve", json.getJSONArray("attributes").getString(0));
		assertEquals("Stealth", json.getJSONArray("skills").getString(0));

		assertEquals(2, json.getJSONObject("race").getJSONObject("Dwarf")
			.getJSONObject("attributes").getInt("Might"));
		assertEquals(2, json.getJSONObject("characterClass").getJSONObject("Wizard")
			.getJSONObject("skills").getInt("Lore"));
		assertEquals(1, json.getJSONObject("background").getJSONObject("Explorer")
			.getJSONObject("skills").getInt("Survival"));
		assertEquals(1, json.getJSONObject("culture").getJSONObject("Aedyr")
			.getJSONObject("attributes").getInt("Resolve"));

		assertEquals("Silver Tide", json.getJSONObject("subrace")
			.getJSONObject("Moon_Godlike").getString("name"));
		assertEquals("Stoic", json.getJSONObject("deity").getJSONObject("Berath")
			.getJSONArray("positive").getString(0));
		assertEquals(0.6d, json.getJSONObject("dispositionBonus")
			.getJSONArray("positive").getDouble(2), 0.0001d);

		// Only the playable races and classes are described; the enums also
		// carry creature types the panel never offers.
		assertEquals(6, json.getJSONObject("race").length());
		assertEquals(11, json.getJSONObject("characterClass").length());
	}
}
