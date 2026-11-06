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
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.save.ItemCatalog;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

// What an item is worth, and what a store would pay for it.
//
// The numbers here are the game's own, from the decompiled Item:
//
//     GetDefaultSellValue() = floor(GetValue() * 0.2)
//                             floor(GetValue()) when FullValueSell is set
//
// and Equippable.GetValue() adds each item mod's Cost * ItemModCostMultiplier
// on top of the base value, doubled for a two-hander. The catalog does that sum
// once during extraction, so these tests only pin down what the editor makes of
// the result.
public class ItemCatalogTest extends TestHarness {
	private static final String CATALOG = "{"
		// A plain greatsword: 50cp base, no enchantment.
		+ "\"great_sword\":{\"name\":\"Great Sword\",\"value\":50,\"filter\":1"
		+ ",\"path\":\"Assets/Data/Prefabs/Items/Weapons_Shields/Great_Sword.prefab\"}"
		// The Fine version: 50 base + (Cost 2 * 1000 * 2 for two-handed).
		+ ",\"great_sword_fine\":{\"name\":\"Fine Great Sword\",\"value\":4050"
		+ ",\"quality\":\"fine\",\"filter\":1"
		+ ",\"path\":\"Assets/Data/Prefabs/Items/Weapons_Shields/Great_Sword_Fine.prefab\"}"
		// Ingredients are the FullValueSell case: stores pay the whole price.
		+ ",\"ing_bear_claw\":{\"name\":\"Bear Claw\",\"value\":100"
		+ ",\"fullValueSell\":1,\"maxStack\":10,\"filter\":32"
		+ ",\"path\":\"Assets/Data/Prefabs/Items/Ingredients/ING_Bear_Claw.prefab\"}"
		// A quest item, which should never be offered up for sale.
		+ ",\"q_letter\":{\"name\":\"Sealed Letter\",\"value\":0,\"quest\":1,\"filter\":64"
		+ ",\"path\":\"Assets/Data/Prefabs/Items/Quest/Q_Letter.prefab\"}"
		// Something the game gives no price at all.
		+ ",\"worthless_junk\":{\"name\":\"Junk\",\"filter\":128"
		+ ",\"path\":\"Assets/Data/Prefabs/Items/Misc/Junk.prefab\"}"
		+ "}";

	private ItemCatalog catalog () throws IOException {
		final Optional<File> directory = EKUtils.createTempDir(PREFIX);
		assertTrue(directory.isPresent());
		FileUtils.write(new File(directory.get(), "catalog.json"), CATALOG, "UTF-8");
		ItemCatalog.useCatalogAt(directory.get());
		return ItemCatalog.getInstance();
	}

	@After
	public void restoreCatalog () {
		ItemCatalog.useNoCatalog();
	}

	@Test
	public void readsThePriceOfAnItem () throws IOException {
		final ItemCatalog catalog = catalog();
		assertEquals(50, catalog.lookup("great_sword").get().value);
		assertEquals(4050, catalog.lookup("great_sword_fine").get().value);
	}

	@Test
	public void anItemWithNoRecordedPriceIsWorthNothing () throws IOException {
		final ItemCatalog catalog = catalog();
		assertEquals(0, catalog.lookup("worthless_junk").get().value);
		assertEquals(0, catalog.sellValue("worthless_junk", 1));
	}

	@Test
	public void aStorePaysAFifthOfTheValue () throws IOException {
		final ItemCatalog catalog = catalog();

		// floor(50 * 0.2) and floor(4050 * 0.2)
		assertEquals(10, catalog.sellValue("great_sword", 1));
		assertEquals(810, catalog.sellValue("great_sword_fine", 1));
	}

	@Test
	public void someItemsSellForTheirFullValue () throws IOException {
		final ItemCatalog catalog = catalog();
		assertTrue(catalog.lookup("ing_bear_claw").get().fullValueSell);
		assertEquals(100, catalog.sellValue("ing_bear_claw", 1));
	}

	@Test
	public void aStackIsWorthItsWholeCount () throws IOException {
		final ItemCatalog catalog = catalog();

		// Each item in the stack is priced, then the stack is counted — the
		// rounding is per item, exactly as selling them one at a time would be.
		assertEquals(700, catalog.sellValue("ing_bear_claw", 7));
		assertEquals(30, catalog.sellValue("great_sword", 3));
	}

	@Test
	public void aMissingCatalogPricesNothing () {
		ItemCatalog.useNoCatalog();
		final ItemCatalog catalog = ItemCatalog.getInstance();
		assertEquals(0, catalog.sellValue("great_sword", 1));
		assertFalse(catalog.lookup("great_sword").isPresent());
	}

	@Test
	public void aQuestItemIsNeverWorthSelling () throws IOException {
		final ItemCatalog catalog = catalog();

		// The game will not let you sell one, so neither should the editor —
		// and it has no price to offer anyway.
		assertTrue(catalog.lookup("q_letter").get().quest);
		assertEquals(0, catalog.sellValue("q_letter", 1));
	}

	/**
	 * The extracted catalog holds the game's development leftovers and, worse,
	 * creature and NPC prefabs that picked up the name of an item they carry —
	 * "Leather Armor" for a summoned beetle. The browser offers neither: the
	 * first are test props, the second would mint a creature as an item. Both
	 * stay resolvable by key, so a save that already holds one still shows it.
	 */
	@Test
	public void theBrowserOffersOnlyShippedItems () throws IOException {
		final Optional<File> directory = EKUtils.createTempDir(PREFIX);
		assertTrue(directory.isPresent());
		FileUtils.write(new File(directory.get(), "catalog.json"), "{"
			+ "\"sword_debug_the_blade_of_assuring_quality\":{\"name\":\"*~ The Blade of Assuring Quality ~*\""
			+ ",\"filter\":1,\"path\":\"Assets/Data/Prefabs/Items/Weapons_Shields/Sword/Sword_DEBUG_The_Blade_of_Assuring_Quality.prefab\"}"
			+ ",\"cre_beetle_adra_summon\":{\"name\":\"Leather Armor\",\"filter\":2"
			+ ",\"path\":\"Assets/Data/Prefabs/Characters/Character_TEMPLATES/Creature/Summons/CRE_Beetle_Adra_Summon.prefab\"}"
			+ ",\"leather_armor\":{\"name\":\"Leather Armor\",\"filter\":2"
			+ ",\"path\":\"Assets/Data/Prefabs/Items/Armor/Leather/Leather_Armor.prefab\"}"
			+ ",\"item_pet_tiny_beetle\":{\"name\":\"Tiny Beetle\",\"filter\":128"
			+ ",\"path\":\"Assets/Data/Prefabs/Characters/Character_TEMPLATES/Pet/SummonItems/ITEM_PET_Tiny_Beetle.prefab\"}"
			+ "}", "UTF-8");
		ItemCatalog.useCatalogAt(directory.get());
		final ItemCatalog catalog = ItemCatalog.getInstance();

		final List<String> offered = new ArrayList<>();
		catalog.search("", 0).forEach(match -> offered.add(match.getKey()));

		assertEquals(Arrays.asList("leather_armor", "item_pet_tiny_beetle"), offered);
		assertEquals(Collections.singletonList("leather_armor")
			, keys(catalog.search("leather", 0)));
		assertTrue("still named when a save holds one"
			, catalog.lookup("sword_debug_the_blade_of_assuring_quality").isPresent());
	}

	private static List<String> keys (final List<Map.Entry<String, ItemCatalog.Entry>> matches) {
		final List<String> keys = new ArrayList<>();
		matches.forEach(match -> keys.add(match.getKey()));
		return keys;
	}
}
