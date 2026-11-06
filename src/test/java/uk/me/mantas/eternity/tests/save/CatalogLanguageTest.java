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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.save.AbilityCatalog;
import uk.me.mantas.eternity.save.GameText;
import uk.me.mantas.eternity.save.IdentityCatalog;
import uk.me.mantas.eternity.save.ItemCatalog;
import uk.me.mantas.eternity.save.StrongholdCatalog;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * The catalogs, in the language the game is played in.
 *
 * <p>The extracted game data holds every name in English, and beside it the
 * table and ID the name came from ({@code "nameId": [5, 1234]}: table 5 is
 * items, table 4 characters, and item names come from both). A catalog shows
 * the word the player's
 * language has for that entry, and its own English name where there is none:
 * no ID (data extracted before the IDs were kept), no such entry in that
 * language, or no install to read.
 */
public class CatalogLanguageTest extends TestHarness {
	private File game;
	private File data;

	private static String table (final String... entries) {
		final StringBuilder xml = new StringBuilder(
			"﻿<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<StringTableFile>\n  <Entries>\n");
		for (int i = 0; i < entries.length; i += 2) {
			xml.append("    <Entry>\n      <ID>").append(entries[i]).append("</ID>\n      <DefaultText>")
				.append(entries[i + 1]).append("</DefaultText>\n      <FemaleText />\n    </Entry>\n");
		}

		return xml.append("  </Entries>\n</StringTableFile>\n").toString();
	}

	private void polish (final String name, final String contents) throws IOException {
		FileUtils.write(new File(game
			, "PillarsOfEternity_Data/data/localized/pl/text/game/" + name + ".stringtable")
			, contents, "UTF-8");
	}

	@Before
	public void install () throws IOException {
		game = EKUtils.createTempDir(PREFIX).get();
		data = EKUtils.createTempDir(PREFIX).get();
		assertTrue(new File(game, "PillarsOfEternity_Data/data/localized/en/text/game").mkdirs());

		// The items here are named out of the characters table, as some of the
		// game's are; abilities and the stronghold have tables of their own.
		polish("characters", table("100", "Wielki miecz", "102", ""));
		polish("abilities", table("7", "Mgła chłodu", "8", "Lodowata mgła oślepia wrogów.", "20", "Berat"));
		polish("stronghold", table("79", "Główna twierdza", "80", "Naprawia wielką salę."));

		FileUtils.write(new File(data, "catalog.json"), "{"
			+ "\"great_sword\":{\"name\":\"Great Sword\",\"nameId\":[4,100],\"filter\":1"
			+ ",\"path\":\"Assets/Data/Prefabs/Items/Weapons_Shields/Great_Sword.prefab\"}"
			// The Polish table has no 101, and an empty 102.
			+ ",\"great_sword_fine\":{\"name\":\"Fine Great Sword\",\"nameId\":[4,101],\"filter\":1}"
			+ ",\"ing_bear_claw\":{\"name\":\"Bear Claw\",\"nameId\":[4,102],\"filter\":32}"
			// Extracted before the IDs were kept.
			+ ",\"q_letter\":{\"name\":\"Sealed Letter\",\"filter\":64}"
			+ "}", "UTF-8");

		FileUtils.write(new File(data, "abilities.json"), "{"
			+ "\"chill_fog\":{\"name\":\"Chill Fog\",\"nameId\":[6,7]"
			+ ",\"desc\":\"A freezing fog blinds enemies.\",\"descId\":[6,8],\"kind\":\"spell\"}"
			+ ",\"slicken\":{\"name\":\"Slicken\",\"nameId\":[6,9],\"desc\":\"Oil.\",\"descId\":[6,10],\"kind\":\"spell\"}"
			+ "}", "UTF-8");
		FileUtils.write(new File(data, "progression.json"), "{}", "UTF-8");

		FileUtils.write(new File(data, "stronghold.json"), "{\"upgrades\":{"
			+ "\"MainKeep\":{\"ordinal\":3,\"name\":\"Main Keep\",\"nameId\":[938,79]"
			+ ",\"description\":\"Repairs the Great Hall.\",\"descriptionId\":[938,80]}"
			+ ",\"Bailey\":{\"ordinal\":4,\"name\":\"Bailey\",\"nameId\":[938,81]"
			+ ",\"description\":\"Necessities.\",\"descriptionId\":[938,82]}"
			+ "}}", "UTF-8");

		FileUtils.write(new File(data, "identity.json"), "{\"deities\":{"
			+ "\"Berath\":{\"name\":\"Berath\",\"nameId\":[6,20],\"positive\":[\"Stoic\"],\"negative\":[]}"
			+ ",\"Eothas\":{\"name\":\"Eothas\",\"nameId\":[6,21],\"positive\":[],\"negative\":[]}"
			+ "},\"orders\":{}}", "UTF-8");
	}

	private void catalogs () {
		ItemCatalog.useCatalogAt(data);
		AbilityCatalog.useCatalogAt(data);
		StrongholdCatalog.useCatalogAt(data);
		IdentityCatalog.useCatalogAt(data);
	}

	@After
	public void pinOff () {
		GameText.useNoText();
		ItemCatalog.useNoCatalog();
		AbilityCatalog.useNoCatalog();
		StrongholdCatalog.useNoCatalog();
		IdentityCatalog.useNoCatalog();
	}

	@Test
	public void anItemIsNamedInTheLanguageOfTheGame () {
		GameText.useTextAt(game, "pl");
		catalogs();

		assertEquals("Wielki miecz", ItemCatalog.getInstance().lookup("great_sword").get().name);
	}

	@Test
	public void whatTheLanguageHasNoWordForKeepsItsEnglishName () {
		GameText.useTextAt(game, "pl");
		catalogs();

		final ItemCatalog items = ItemCatalog.getInstance();
		assertEquals("no such entry", "Fine Great Sword", items.lookup("great_sword_fine").get().name);
		assertEquals("an empty entry", "Bear Claw", items.lookup("ing_bear_claw").get().name);
		assertEquals("no ID in the catalog", "Sealed Letter", items.lookup("q_letter").get().name);
	}

	@Test
	public void inEnglishEveryNameIsTheCatalogsOwn () {
		GameText.useTextAt(game);
		catalogs();

		assertEquals("Great Sword", ItemCatalog.getInstance().lookup("great_sword").get().name);
		assertEquals("Chill Fog", AbilityCatalog.getInstance().lookup("chill_fog").get().name);
		assertEquals("Main Keep", StrongholdCatalog.getInstance().lookup("MainKeep").get().name);
	}

	@Test
	public void withNoInstallEveryNameIsTheCatalogsOwn () {
		// TestHarness pins the game's text off.
		catalogs();

		assertEquals("Great Sword", ItemCatalog.getInstance().lookup("great_sword").get().name);
		assertEquals("Chill Fog", AbilityCatalog.getInstance().lookup("chill_fog").get().name);
	}

	@Test
	public void aSearchFindsAnItemByTheNameShown () {
		GameText.useTextAt(game, "pl");
		catalogs();

		assertEquals(1, ItemCatalog.getInstance().search("wielki", 0).size());
		assertEquals("great_sword", ItemCatalog.getInstance().search("wielki", 0).get(0).getKey());
	}

	@Test
	public void anAbilityAndItsDescriptionAreInTheLanguageOfTheGame () {
		GameText.useTextAt(game, "pl");
		catalogs();

		final AbilityCatalog.Entry fog = AbilityCatalog.getInstance().lookup("chill_fog").get();
		assertEquals("Mgła chłodu", fog.name);
		assertEquals("Lodowata mgła oślepia wrogów.", fog.description);

		final AbilityCatalog.Entry slicken = AbilityCatalog.getInstance().lookup("slicken").get();
		assertEquals("Slicken", slicken.name);
		assertEquals("Oil.", slicken.description);
	}

	@Test
	public void anUpgradeAndItsDescriptionAreInTheLanguageOfTheGame () {
		GameText.useTextAt(game, "pl");
		catalogs();

		final StrongholdCatalog.Upgrade keep = StrongholdCatalog.getInstance().lookup("MainKeep").get();
		assertEquals("Główna twierdza", keep.name);
		assertEquals("Naprawia wielką salę.", keep.description);
		assertEquals("Bailey", StrongholdCatalog.getInstance().lookup("Bailey").get().name);
	}

	@Test
	public void aDeityIsNamedInTheLanguageOfTheGame () {
		GameText.useTextAt(game, "pl");
		catalogs();

		assertEquals("Berat", IdentityCatalog.getInstance().deity("Berath").get().name);
		assertEquals("Eothas", IdentityCatalog.getInstance().deity("Eothas").get().name);
	}

	// Data read before the IDs were kept can only ever be shown in English,
	// and Settings says so instead of leaving the player to wonder.
	@Test
	public void aCatalogSaysWhetherItCanBeShownInAnotherLanguage () throws IOException {
		catalogs();
		assertTrue(ItemCatalog.getInstance().localizable());

		FileUtils.write(new File(data, "catalog.json")
			, "{\"great_sword\":{\"name\":\"Great Sword\"},\"q_letter\":{\"name\":\"Sealed Letter\"}}", "UTF-8");
		ItemCatalog.useCatalogAt(data);
		assertFalse(ItemCatalog.getInstance().localizable());

		ItemCatalog.useNoCatalog();
		assertFalse("nothing to show at all", ItemCatalog.getInstance().localizable());
	}
}
