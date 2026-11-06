/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2016 the authors.
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

import org.junit.Test;
import uk.me.mantas.eternity.save.ShippedContent;
import uk.me.mantas.eternity.tests.TestHarness;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the browsers may offer. Every path here is a real one out of the
 * catalogs extracted from the game.
 */
public class ShippedContentTest extends TestHarness {
	private static void development (final String path) {
		assertTrue(path, ShippedContent.isDevelopmentPrefab(path));
	}

	private static void shipped (final String path) {
		assertFalse(path, ShippedContent.isDevelopmentPrefab(path));
	}

	@Test
	public void debugTestTempAndUnusedPrefabsAreDevelopmentContent () {
		development("Assets/Data/Prefabs/Items/Weapons_Shields/Sword/Sword_DEBUG_The_Blade_of_Assuring_Quality.prefab");
		development("Assets/Data/Prefabs/Items/Weapons_Shields/Wand/DEBUG_DO_NOT_USE_Wand Of Fireball.prefab");
		development("Assets/Data/Prefabs/Items/Keys/Backup_Keys/_UNUSED_ Key_Backup_02.prefab");
		development("Assets/Data/Prefabs/Items/Armor/Plate/Plate_Armor_Temp.prefab");
		development("Assets/Data/Prefabs/Items/Weapons_Shields/Spell_Weapons/Wand_Rot_SkullTest.prefab");
		development("Assets/Data/Prefabs/Characters/Items/Test_Talking_Sword.prefab");
		development("Assets/Data/Prefabs/RPG/Spells/_DEBUG/DEBUG_Stun_Spell.prefab");
		development("Assets/Data/Prefabs/RPG/Abilities/Test/Explosivity.prefab");
		development("Assets/Data/Prefabs/RPG/Abilities/Racial/LongStride_Aumaua_DO_NOT_USE.prefab");
	}

	/** Words that merely contain one of the markers are real content. */
	@Test
	public void realContentThatOnlyLooksLikeItIsKept () {
		shipped("Assets/Data/Prefabs/Items/Hand/Gloves_Blood_Testament.prefab");
		shipped("Assets/Data/Prefabs/Items/Head/PX1/PX1_Tempered_Helm.prefab");
		shipped("Assets/Data/Prefabs/Items/Neck/PX2/Cape_of_the_Cheat.prefab");
		shipped("Assets/Data/Prefabs/Items/Weapons_Shields/Shield_Large/Shield_Large_Old_Geruns_Wall.prefab");
		shipped("Assets/Data/Prefabs/Items/Quest/02_Defiance_Bay_First_Fires/Quest_Item_Vianna_Research_Copy.prefab");
		shipped("Assets/Data/Prefabs/RPG/Spells/Wizard/Fireball.prefab");
		shipped("Assets/Data/Prefabs/Characters/Character_TEMPLATES/Pet/SummonItems/ITEM_PET_Tiny_Beetle.prefab");
	}

	/**
	 * The folder name reads like a marker, but the shipped game uses what is in
	 * it: these keys are in every mid-game save and Korgrak's Head is in seven
	 * of one save's area files.
	 */
	@Test
	public void thePrototypeFolderIsRealContent () {
		shipped("Assets/Data/Prefabs/Items/Keys/Prototype/Key_Crypt_Master.prefab");
		shipped("Assets/Data/Prefabs/Items/Keys/Prototype/Key_Tanner.prefab");
		shipped("Assets/Data/Prefabs/Items/Quest/Prototype/QI_Korgrak_Head.prefab");
		shipped("");
		shipped(null);
	}

	@Test
	public void onlyItemPrefabsAreItems () {
		assertTrue(ShippedContent.isItemPrefab("Assets/Data/Prefabs/Items/Neck/PX2/Cape_of_the_Cheat.prefab"));
		assertTrue("a pet is summoned by an item in the Pet slot", ShippedContent.isItemPrefab(
			"Assets/Data/Prefabs/Characters/Character_TEMPLATES/Pet/SummonItems/ITEM_PET_Tiny_Beetle.prefab"));

		// Creatures and NPCs whose bundles carried an item, and got its name.
		assertFalse(ShippedContent.isItemPrefab(
			"Assets/Data/Prefabs/Characters/Character_TEMPLATES/Creature/Summons/CRE_Beetle_Adra_Summon.prefab"));
		assertFalse(ShippedContent.isItemPrefab(
			"Assets/Data/Prefabs/Characters/NPC/PX2_05_Wildernesses/PX2_0504_Whitestone_Hollow/PX2_NPC_Kern.prefab"));
		assertFalse(ShippedContent.isItemPrefab(
			"Assets/Data/Prefabs/Characters/Character_TEMPLATES/Pet/PET_Tiny_Animat.prefab"));
		assertFalse(ShippedContent.isItemPrefab(null));
	}
}
