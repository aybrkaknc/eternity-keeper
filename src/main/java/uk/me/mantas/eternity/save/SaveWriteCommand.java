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

package uk.me.mantas.eternity.save;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, Java 8 compatible input to the save writer. Browser JSON is
 * converted to this command by the CEF adapter before save work is queued. */
public final class SaveWriteCommand {
	private final boolean savedYet;
	private final String saveName;
	private final String absolutePath;
	private final double currency;
	private final Integer campingSupplies;
	private final Map<String, CharacterUpdate> characters;
	private final Map<String, Map<String, Map<String, String>>> globals;

	public SaveWriteCommand(boolean savedYet, String saveName, String absolutePath, double currency,
		Integer campingSupplies, Map<String, CharacterUpdate> characters,
		Map<String, Map<String, Map<String, String>>> globals) {
		this.savedYet = savedYet;
		this.saveName = saveName;
		this.absolutePath = absolutePath;
		this.currency = currency;
		this.campingSupplies = campingSupplies;
		this.characters = immutableMap(characters);
		this.globals = immutableNestedMap(globals);
	}

	public boolean isSavedYet() { return savedYet; }
	public String getSaveName() { return saveName; }
	public String getAbsolutePath() { return absolutePath; }
	public double getCurrency() { return currency; }
	public Integer getCampingSupplies() { return campingSupplies; }
	public Map<String, CharacterUpdate> getCharacters() { return characters; }
	public Map<String, Map<String, Map<String, String>>> getGlobals() { return globals; }

	private static <K, V> Map<K, V> immutableMap(Map<K, V> input) {
		return Collections.unmodifiableMap(new LinkedHashMap<K, V>(input));
	}

	private static Map<String, Map<String, Map<String, String>>> immutableNestedMap(
		Map<String, Map<String, Map<String, String>>> input) {
		Map<String, Map<String, Map<String, String>>> outer = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, Map<String, String>>> root : input.entrySet()) {
			Map<String, Map<String, String>> components = new LinkedHashMap<>();
			for (Map.Entry<String, Map<String, String>> component : root.getValue().entrySet()) {
				components.put(component.getKey(), immutableMap(component.getValue()));
			}
			outer.put(root.getKey(), Collections.unmodifiableMap(components));
		}
		return Collections.unmodifiableMap(outer);
	}

	/** Scalar values for one character, including display-independent portrait and health writes. */
	public static final class CharacterUpdate {
		private final Map<String, String> stats;
		private final Map<String, String> portraitPaths;
		private final Map<String, String> health;

		public CharacterUpdate(Map<String, String> stats, Map<String, String> portraitPaths,
			Map<String, String> health) {
			this.stats = immutableMap(stats);
			this.portraitPaths = immutableMap(portraitPaths);
			this.health = immutableMap(health);
		}
		public Map<String, String> getStats() { return stats; }
		public Map<String, String> getPortraitPaths() { return portraitPaths; }
		public Map<String, String> getHealth() { return health; }
	}
}
