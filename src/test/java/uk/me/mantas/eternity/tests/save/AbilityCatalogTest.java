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
import uk.me.mantas.eternity.save.AbilityCatalog;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

import static org.junit.Assert.*;

// Written against a fixture rather than the machine's own game install, so the
// filtering rules are pinned down regardless of whether Pillars is installed.
//
// The rules being checked are the game's: AbilityProgressionTable rows are
// OR-ed requirement sets, so a row with any class-free set is open to everyone
// and a row where every set names a class is not. Getting that wrong offers a
// wizard the barbarian's Accurate Carnage, which is exactly the sort of thing
// that looks fine in the editor and is nonsense in the game.
public class AbilityCatalogTest extends TestHarness {
	private File catalogDirectory = null;

	private static final String ABILITIES = "{"
		+ "\"fireball\":{\"name\":\"Fireball\",\"kind\":\"spell\",\"component\":\"GenericSpell\""
		+ ",\"effect\":3,\"spellLevel\":3,\"class\":\"Wizard\",\"icon\":\"fireball.png\""
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Spells/Wizard/L_03/Fireball.prefab\"}"
		+ ",\"carnage\":{\"name\":\"Carnage\",\"kind\":\"ability\",\"component\":\"Carnage\""
		+ ",\"effect\":5,\"class\":\"Barbarian\""
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Abilities/Barbarian/Carnage.prefab\"}"
		+ ",\"cautious_attack\":{\"name\":\"Cautious Attack\",\"kind\":\"ability\""
		+ ",\"component\":\"GenericAbility\",\"effect\":5"
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Talents/Talent_Abilities/Cautious_Attack.prefab\"}"
		+ ",\"tln_cautious_attack\":{\"name\":\"Cautious Attack\",\"kind\":\"talent\""
		+ ",\"type\":\"GrantNewAbility\",\"grants\":[\"cautious_attack\"]"
		+ ",\"icon\":\"class_rogue.png\""
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Talents/TLN_Cautious_Attack.prefab\"}"
		// An ability the game gives no icon at all, and no talent grants it —
		// the shield-bash attacks and the debug spells are really like this.
		+ ",\"bashattack1\":{\"name\":\"Bash\",\"kind\":\"ability\""
		+ ",\"component\":\"GenericAbility\",\"effect\":5"
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Abilities/BashAttack1.prefab\"}"
		+ ",\"tln_accurate_carnage\":{\"name\":\"Accurate Carnage\",\"kind\":\"talent\""
		+ ",\"type\":\"ModExistingAbility\",\"modifies\":[\"carnage\"]"
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Talents/TLN_Accurate_Carnage.prefab\"}"
		+ ",\"silverstide\":{\"name\":\"Silver Tide\",\"kind\":\"ability\""
		+ ",\"component\":\"MoonGodlikeTrait\",\"effect\":8"
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Abilities/Racial/Silverstide.prefab\"}"
		+ ",\"crucible_of_the_soul\":{\"name\":\"Crucible of the Soul\",\"kind\":\"ability\""
		+ ",\"component\":\"GenericAbility\",\"effect\":5"
		+ ",\"path\":\"Assets/Data/Prefabs/RPG/Abilities/Watcher/Crucible_of_the_Soul.prefab\"}"
		+ "}";

	private static final String PROGRESSION = "{"
		+ "\"wizard\":{\"fireball\":{\"cat\":\"General\",\"level\":5}}"
		+ ",\"barbarian\":{\"carnage\":{\"cat\":\"General\",\"level\":1,\"auto\":1}}"
		+ ",\"talents\":{"
		+ "\"tln_cautious_attack\":{\"cat\":\"Talent\",\"level\":1}"
		+ ",\"tln_accurate_carnage\":{\"cat\":\"Talent\",\"level\":1"
		+ ",\"classes\":[\"Barbarian\"]}}"
		+ ",\"racial\":{"
		+ "\"silverstide\":{\"cat\":\"Racial\",\"level\":1,\"subraces\":[\"Moon_Godlike\"]}"
		+ ",\"crucible_of_the_soul\":{\"cat\":\"General\",\"level\":1,\"player\":1}}"
		+ "}";

	private AbilityCatalog catalog () throws IOException {
		final Optional<File> directory = EKUtils.createTempDir(PREFIX);
		assertTrue(directory.isPresent());
		catalogDirectory = directory.get();

		FileUtils.write(new File(catalogDirectory, "abilities.json"), ABILITIES, "UTF-8");
		FileUtils.write(new File(catalogDirectory, "progression.json"), PROGRESSION, "UTF-8");

		AbilityCatalog.useCatalogAt(catalogDirectory);
		return AbilityCatalog.getInstance();
	}

	@After
	public void restoreCatalog () {
		AbilityCatalog.useNoCatalog();
	}

	@Test
	public void anAbilityWithNoIconOfItsOwnBorrowsTheTalentThatGrantsIt ()
		throws IOException {

		// 164 of the game's 1,440 ability objects have a null Icon pointer,
		// and 90 of those are what a talent instantiates when it is bought.
		// The game's own sheet shows the talent, so the talent is where the
		// artwork lives; without this the editor draws a black square.
		final AbilityCatalog catalog = catalog();

		assertEquals("class_rogue.png", catalog.lookup("tln_cautious_attack").get().icon);
		assertEquals("class_rogue.png", catalog.lookup("cautious_attack").get().icon);
	}

	@Test
	public void anAbilityWithItsOwnIconKeepsIt () throws IOException {
		final AbilityCatalog catalog = catalog();
		assertEquals("fireball.png", catalog.lookup("fireball").get().icon);
	}

	@Test
	public void anAbilityNoTalentGrantsSimplyHasNoIcon () throws IOException {
		// There is nothing to borrow for these, so the UI has to cope with an
		// empty string rather than the catalog inventing something.
		final AbilityCatalog catalog = catalog();
		assertEquals("", catalog.lookup("bashattack1").get().icon);
	}

	@Test
	public void readsEntriesAndTheirComponentClass () throws IOException {
		final AbilityCatalog catalog = catalog();
		assertEquals(8, catalog.size());

		final Optional<AbilityCatalog.Entry> fireball = catalog.lookup("Fireball(Clone)");
		assertTrue(fireball.isPresent());
		assertEquals("Fireball", fireball.get().name);
		// Not interchangeable: the save writes this as the packet's TypeString.
		assertEquals("GenericSpell", fireball.get().component);
		assertEquals(3, fireball.get().effect);
		assertEquals("Wizard", fireball.get().characterClass);
	}

	@Test
	public void resolvesKeysFromPathsAndObjectNames () {
		assertEquals("fireball", AbilityCatalog.keyOf(
			"Assets/Data/Prefabs/RPG/Spells/Wizard/L_03/Fireball.prefab"));

		assertEquals("fireball", AbilityCatalog.keyOf("Fireball(Clone)"));
		assertEquals("fireball", AbilityCatalog.keyOf("Fireball"));
		assertEquals("", AbilityCatalog.keyOf(null));
	}

	@Test
	public void restrictsClassAbilitiesToTheirClass () throws IOException {
		final AbilityCatalog catalog = catalog();

		final Map<String, AbilityCatalog.Unlock> wizard =
			catalog.unlocksFor("Wizard", "", "Wood_Elf", false);

		final Map<String, AbilityCatalog.Unlock> barbarian =
			catalog.unlocksFor("Barbarian", "", "Wood_Elf", false);

		assertTrue(wizard.containsKey("fireball"));
		assertFalse(wizard.containsKey("carnage"));
		assertTrue(barbarian.containsKey("carnage"));
		assertFalse(barbarian.containsKey("fireball"));
	}

	@Test
	public void restrictsClassTalentsButNotGeneralOnes () throws IOException {
		final AbilityCatalog catalog = catalog();

		final Map<String, AbilityCatalog.Unlock> wizard =
			catalog.unlocksFor("Wizard", "", "Wood_Elf", false);

		final Map<String, AbilityCatalog.Unlock> barbarian =
			catalog.unlocksFor("Barbarian", "", "Wood_Elf", false);

		// Everyone can take Cautious Attack; only a barbarian has Carnage to
		// make more accurate.
		assertTrue(wizard.containsKey("tln_cautious_attack"));
		assertTrue(barbarian.containsKey("tln_cautious_attack"));
		assertFalse(wizard.containsKey("tln_accurate_carnage"));
		assertTrue(barbarian.containsKey("tln_accurate_carnage"));
	}

	@Test
	public void restrictsRacialAbilitiesToTheirSubrace () throws IOException {
		final AbilityCatalog catalog = catalog();

		assertTrue(catalog.unlocksFor("Fighter", "", "Moon_Godlike", false)
			.containsKey("silverstide"));

		assertFalse(catalog.unlocksFor("Fighter", "", "Boreal_Dwarf", false)
			.containsKey("silverstide"));
	}

	@Test
	public void keepsTheWatchersAbilitiesToThePlayer () throws IOException {
		final AbilityCatalog catalog = catalog();

		assertTrue(catalog.unlocksFor("Fighter", "", "Wood_Elf", true)
			.containsKey("crucible_of_the_soul"));

		assertFalse(catalog.unlocksFor("Fighter", "", "Wood_Elf", false)
			.containsKey("crucible_of_the_soul"));
	}

	@Test
	public void searchesWithinAKindAndTheAllowedSet () throws IOException {
		final AbilityCatalog catalog = catalog();
		final Map<String, AbilityCatalog.Unlock> wizard =
			catalog.unlocksFor("Wizard", "", "Wood_Elf", false);

		assertEquals(1, catalog.search("", "spell", wizard).size());
		assertEquals(1, catalog.search("fire", "", wizard).size());
		assertEquals(0, catalog.search("carnage", "", wizard).size());

		// A null allowed-set is the "show everything" escape hatch.
		final List<Map.Entry<String, AbilityCatalog.Entry>> everything =
			catalog.search("carnage", "", null);

		assertEquals(2, everything.size());
	}

	@Test
	public void separatesWhatATalentGrantsFromWhatItModifies () throws IOException {
		final AbilityCatalog catalog = catalog();

		// GrantNewAbility has to mint objects; ModExistingAbility must not,
		// because CharacterStats.Restored reapplies those mods itself.
		final AbilityCatalog.Entry cautious =
			catalog.lookup("TLN_Cautious_Attack").get();

		assertEquals(java.util.Collections.singletonList("cautious_attack"), cautious.grants);
		assertTrue(cautious.modifies.isEmpty());

		final AbilityCatalog.Entry accurate =
			catalog.lookup("TLN_Accurate_Carnage").get();

		assertTrue(accurate.grants.isEmpty());
		assertEquals(java.util.Collections.singletonList("carnage"), accurate.modifies);
	}

	@Test
	public void knowsWhatATalentGrantsWithoutBeingToldByTheUI () throws IOException {
		final AbilityCatalog catalog = catalog();

		// Removing a talent has to take away the ability it granted, and the UI
		// only knows that for entries its browser happens to have fetched. The
		// server looks it up here instead, so removing a talent nobody searched
		// for no longer leaves the ability behind.
		final AbilityCatalog.Entry talent =
			catalog.lookup("TLN_Cautious_Attack").get();

		assertEquals(
			java.util.Collections.singletonList("cautious_attack"), talent.grants);

		final AbilityCatalog.Entry granted = catalog.lookup("cautious_attack").get();
		assertEquals(
			"Assets/Data/Prefabs/RPG/Talents/Talent_Abilities/Cautious_Attack.prefab"
			, granted.path);
	}

	@Test
	public void degradesToNothingWithoutACatalog () {
		AbilityCatalog.useNoCatalog();
		final AbilityCatalog catalog = AbilityCatalog.getInstance();

		assertEquals(0, catalog.size());
		assertFalse(catalog.lookup("Fireball").isPresent());
		assertTrue(catalog.unlocksFor("Wizard", "", "Wood_Elf", true).isEmpty());
		assertTrue(catalog.search("", "", null).isEmpty());
	}

	/**
	 * The prefab name a save stores is the file name the catalog recorded, with
	 * its own casing. BrowseAbilities and UpdateAbilities each had a copy of
	 * this; the one here is what both use.
	 */
	@Test
	public void aPrefabIsNamedAfterItsFileWithTheCatalogsCasing () {
		assertEquals("TLN_Cautious_Attack", AbilityCatalog.prefabNameOf(
			"Assets/Data/Prefabs/RPG/Talents/TLN_Cautious_Attack.prefab", "fallback"));

		assertEquals("Fireball", AbilityCatalog.prefabNameOf("Fireball.prefab", "fallback"));
		assertEquals("NoExtension", AbilityCatalog.prefabNameOf("a/b/NoExtension", "fallback"));
		assertEquals("fallback", AbilityCatalog.prefabNameOf("", "fallback"));
		assertEquals("fallback", AbilityCatalog.prefabNameOf(null, "fallback"));
	}

	/**
	 * "Show everything" lifts the class filter, which is when the game's own
	 * debug and test spells would otherwise turn up. They are never offered.
	 */
	@Test
	public void debugAndTestAbilitiesAreNeverOffered () throws IOException {
		final Optional<File> directory = EKUtils.createTempDir(PREFIX);
		assertTrue(directory.isPresent());
		FileUtils.write(new File(directory.get(), "abilities.json"), "{"
			+ "\"debug_stun_spell\":{\"name\":\"DEBUG stun Spell\",\"kind\":\"ability\""
			+ ",\"path\":\"Assets/Data/Prefabs/RPG/Spells/_DEBUG/DEBUG_Stun_Spell.prefab\"}"
			+ ",\"explosivity\":{\"name\":\"Fireball\",\"kind\":\"ability\""
			+ ",\"path\":\"Assets/Data/Prefabs/RPG/Abilities/Test/Explosivity.prefab\"}"
			+ ",\"fireball\":{\"name\":\"Fireball\",\"kind\":\"spell\",\"spellLevel\":3"
			+ ",\"path\":\"Assets/Data/Prefabs/RPG/Spells/Wizard/L_03/Fireball.prefab\"}"
			+ "}", "UTF-8");
		FileUtils.write(new File(directory.get(), "progression.json"), "{}", "UTF-8");
		AbilityCatalog.useCatalogAt(directory.get());
		final AbilityCatalog catalog = AbilityCatalog.getInstance();

		final List<String> offered = new ArrayList<>();
		catalog.search("", "", null).forEach(match -> offered.add(match.getKey()));

		assertEquals(Collections.singletonList("fireball"), offered);
		assertTrue(catalog.lookup("debug_stun_spell").isPresent());
	}
}
