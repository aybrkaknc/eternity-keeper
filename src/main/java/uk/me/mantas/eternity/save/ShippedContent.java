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

package uk.me.mantas.eternity.save;

import java.util.regex.Pattern;

/**
 * What the item and ability browsers may offer.
 *
 * <p>The catalogs are extracted from every prefab bundle the game ships, and
 * the game ships its development leftovers along with everything else: a
 * {@code Sword_DEBUG_The_Blade_of_Assuring_Quality}, spells under
 * {@code Spells/_DEBUG/}, abilities under {@code Abilities/Test/}, keys marked
 * {@code _UNUSED_}. Measured in the extracted catalogs: 12 items and 9
 * abilities, and not one of them in any file of twelve real saves.
 *
 * <p>{@code Prototype/} is deliberately not a marker. It reads like one, but
 * the shipped game uses what is in it: Currier's Key and Crypt's Master Key
 * are in every mid-game save, and Korgrak's Head in seven of its area files.
 *
 * <p>A quarter of the item catalog is not items at all: 549 of 2,156 entries
 * are companions, NPCs, creatures, placed traps, crafting recipes and data
 * tables. Their bundles carry an item as a dependency and the extractor named
 * each one after it — a summoned beetle is "Leather Armor", and
 * {@code Companion_Aloth} is "Aloth's Grimoire". Adding one to a pack would
 * mint a character as an item, so the item browser offers only prefabs that
 * live where items do.
 *
 * <p>Nothing here removes an entry from a catalog: a save that already holds
 * one of these still looks it up by key and shows its real name and icon.
 */
public final class ShippedContent {
	private ShippedContent () {}

	// A marker as a whole word of the file or folder name: DEBUG_, _DEBUG/,
	// _UNUSED_, Test/, _Temp.prefab. "Testament", "Tempered" and TEMPLATES are
	// words of their own and stay.
	private static final Pattern MARKER_WORD = Pattern.compile(
		"(?i)(^|[/_\\s.-])(debug|test|temp|unused|do_not_use)(?=$|[/_\\s.-])");

	// ...or a CamelCase suffix, as in Wand_Rot_SkullTest.
	private static final Pattern MARKER_SUFFIX = Pattern.compile("(?<=[a-z])(Test|Debug)(?=$|[/_\\s.-])");

	private static final Pattern DO_NOT_USE = Pattern.compile("(?i)do_not_use");

	/** A prefab the developers left in the build rather than put in the game. */
	public static boolean isDevelopmentPrefab (final String path) {
		if (path == null || path.isEmpty()) {
			return false;
		}

		return MARKER_WORD.matcher(path).find()
			|| MARKER_SUFFIX.matcher(path).find()
			|| DO_NOT_USE.matcher(path).find();
	}

	/**
	 * A prefab that is an item: under {@code Prefabs/Items/}, or one of the
	 * pet-summoning items that live beside the pets they summon.
	 */
	public static boolean isItemPrefab (final String path) {
		if (path == null) {
			return false;
		}

		final String lower = path.toLowerCase();
		return lower.contains("/prefabs/items/") || lower.contains("/pet/summonitems/");
	}
}
