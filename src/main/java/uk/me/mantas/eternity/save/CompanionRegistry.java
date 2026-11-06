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


package uk.me.mantas.eternity.save;

import com.google.common.collect.ImmutableList;
import uk.me.mantas.eternity.game.DatabaseString.StringTableType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The game's record of companion death, verified empirically against a
 * matrix of real saves (one per dead companion): the companion's mobile
 * object is deleted outright and, for most companions, a bespoke global in
 * InGameGlobal(Clone)/GlobalVariables.m_data is set to 1. Some leave extra
 * traces:
 *
 * - Zahua sets NO flag at all — his death is detectable only by the
 *   orphaned objects still parented to his deleted mobile object.
 * - Grieving Mother's death also sets b_companion_gm_unavailable.
 * - Sagani's pet Itumaak (CRE_ArcticFox_Animal_Companion) dies IN PLACE:
 *   the object stays, with zeroed health, ShouldDecay=true and
 *   Persistence.m_objDestroyed=true — resurrection must replace it.
 * - Devil of Caroc's mobile object is Companion_Caroc; Durance's is
 *   Companion_GGP.
 *
 * Player-created companions (Companion_Generic) leave no trace at all when
 * they die — no flag, no orphans — so they cannot be auto-detected.
 */
public final class CompanionRegistry {
	public static final class Companion {
		public final String key;               // stable identifier used by the UI
		public final String displayName;       // shown in the character list
		public final String objectNamePrefix;  // e.g. "Companion_Eder"
		public final String deathFlag;         // e.g. "b_Eder_Dead"; null = flagless
		public final List<String> extraFlagsToClear;   // lenient: cleared if present
		public final String linkedCreaturePrefix;      // pet that dies with them
		public final String questPath;                 // companion quest, as keyed in QuestTrackers
		public final int nameStringId;                 // Characters string-table entry

		Companion (
			final String key
			, final String displayName
			, final String objectNamePrefix
			, final String deathFlag
			, final List<String> extraFlagsToClear
			, final String linkedCreaturePrefix
			, final String questPath
			, final int nameStringId) {

			this.key = key;
			this.displayName = displayName;
			this.objectNamePrefix = objectNamePrefix;
			this.deathFlag = deathFlag;
			this.extraFlagsToClear = extraFlagsToClear;
			this.linkedCreaturePrefix = linkedCreaturePrefix;
			this.questPath = questPath;
			this.nameStringId = nameStringId;
		}

		// Matches the game's companion portrait naming scheme,
		// e.g. "Devil of Caroc" -> portrait_devil_of_caroc_lg.png.
		public String portraitName () {
			return displayName.toLowerCase().replace(" ", "_");
		}

		/**
		 * Is this companion dead in a save, given its globals, the
		 * lower-cased ObjectNames of present characters, and the lower-cased
		 * Parent names of objects whose parent object no longer exists?
		 * Flagged companions are dead when their flag is nonzero; flagless
		 * ones when their object is gone but orphans still point at it.
		 */
		public boolean isDeadIn (
			final Map<String, Object> globals
			, final Set<String> presentNamesLower
			, final Set<String> orphanParentsLower) {

			final String prefix = objectNamePrefix.toLowerCase();
			final boolean present = presentNamesLower.stream().anyMatch(n -> n.startsWith(prefix));
			if (present) {
				return false;
			}

			if (deathFlag != null) {
				final Object flag = globals.get(deathFlag);
				return flag instanceof Number && ((Number) flag).intValue() != 0;
			}

			return orphanParentsLower.stream().anyMatch(n -> n.startsWith(prefix));
		}
	}

	private static final List<String> NONE = ImmutableList.of();

	// Quest paths are the exact QuestTrackers dictionary keys observed in real
	// saves. Note the oddballs: Hiravias's companion quest lives under the
	// Twin Elms area quests, and Zahua's px2 quest sits in the px1 folder.
	private static final List<Companion> ALL = ImmutableList.of(
		new Companion("Eder", "Eder", "Companion_Eder", "b_Eder_Dead", NONE, null,
			"data/quests/companions/companion_qst_eder.quest", 13)
		, new Companion("Aloth", "Aloth", "Companion_Aloth", "b_Aloth_dead", NONE, null,
			"data/quests/companions/companion_qst_aloth_and_iselmyr.quest", 11)
		// Durance's mobile object is named Companion_GGP in real saves.
		, new Companion("Durance", "Durance", "Companion_GGP", "bDuranceDead", NONE, null,
			"data/quests/companions/companion_qst_durance.quest", 635)
		, new Companion("Kana", "Kana", "Companion_Kana", "b_Kana_Dead", NONE, null,
			"data/quests/companions/companion_qst_kana.quest", 533)
		// Itumaak dies with her and must be replaced from the donor too.
		, new Companion("Sagani", "Sagani", "Companion_Sagani", "b_Sagani_dead", NONE,
			"CRE_ArcticFox_Animal_Companion",
			"data/quests/companions/companion_qst_sagani.quest", 15)
		, new Companion("Pallegina", "Pallegina", "Companion_Pallegina", "b_Pallegina_Dead",
			NONE, null, "data/quests/companions/companion_qst_pallegina.quest", 79)
		, new Companion("Hiravias", "Hiravias", "Companion_Hiravias", "b_Hiravias_Dead",
			NONE, null, "data/quests/13_twin_elms_elms_reach/13_qst_true_to_form.quest", 837)
		, new Companion("GrievingMother", "Grieving Mother", "Companion_GM",
			"b_companion_gm_dead", ImmutableList.of("b_companion_gm_unavailable"), null,
			"data/quests/companions/companion_qst_gm.quest", 634)
		, new Companion("Maneha", "Maneha", "Companion_Maneha", "b_maneha_dead", NONE, null,
			"data/quests/px2_companions/px2_companion_qst_maneha.quest", 1734)
		// The Devil's mobile object is Companion_Caroc.
		, new Companion("DevilOfCaroc", "Devil of Caroc", "Companion_Caroc", "b_devil_dead",
			NONE, null, "data/quests/px1_companions/px1_companion_qst_devil_of_caroc.quest", 1409)
		// Zahua's death sets no global at all; detection is orphan-based.
		, new Companion("Zahua", "Zahua", "Companion_Zahua", null, NONE, null,
			"data/quests/px1_companions/px2_companion_qst_zahua.quest", 1483)
	);

	private CompanionRegistry () {}

	public static List<Companion> all () {
		return ALL;
	}

	public static Optional<Companion> byKey (final String key) {
		return ALL.stream().filter(c -> c.key.equals(key)).findFirst();
	}

	/** Returns a companion's translated official name, falling back to English. */
	public static String displayName (final Companion companion) {
		return uk.me.mantas.eternity.save.GameText.getInstance()
			.translated(StringTableType.Characters, companion.nameStringId)
			.orElse(companion.displayName);
	}

	/**
	 * The display name for a saved object, preserving a user-entered override.
	 * An empty result means the object is not a registered story companion.
	 */
	public static Optional<String> displayNameFor (
		final String objectName, final String override) {

		if (override != null && !override.trim().isEmpty()) {
			return Optional.of(override.trim());
		}
		if (objectName == null) {
			return Optional.empty();
		}

		return ALL.stream()
			.filter(companion -> namedFor(objectName, companion.objectNamePrefix))
			.findFirst()
			.map(CompanionRegistry::displayName);
	}

	// "Companion_Eder(Clone)" and "Companion_Eder_2" are Edér; an object whose
	// name merely begins the same way is not.
	private static boolean namedFor (final String objectName, final String prefix) {
		return objectName.startsWith(prefix) && (objectName.length() == prefix.length()
			|| "(_".indexOf(objectName.charAt(prefix.length())) >= 0);
	}
}
