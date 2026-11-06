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

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.game.CharacterStats;
import uk.me.mantas.eternity.game.Religion;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What each identity choice is actually worth on the character sheet.
 *
 * <p>The important thing about these tables is that they are <em>live</em>.
 * Nothing about race, culture, class or background is baked into the save at
 * character creation: {@code CharacterStats.GetAttributeScore()} adds
 * {@code RaceAbilityAdjustment} and {@code CultureAbilityAdjustment} to the
 * stored {@code BaseX} every time it is read, and
 * {@code CalculateSkillInternal()} adds {@code ClassSkillAdjustment} and
 * {@code BackgroundSkillAdjustment} to the skill rank the same way. So an
 * identity edit really does move the sheet, and the editor can say by how
 * much.
 *
 * <p>The two column orders are the game's own enums and they disagree with
 * each other — attributes run Resolve, Might, Dexterity, Intellect,
 * Constitution, Perception while skills run Stealth, Athletics, Lore,
 * Mechanics, Survival, Crafting. Reading a row under the wrong order relabels
 * every bonus without ever looking wrong, so the rows below are copied
 * verbatim from the assembly and named through the mirror enums rather than
 * transcribed into something more readable.
 *
 * <p>Two halves of the picture are not in the assembly. A subrace's racial
 * ability comes from the game's own {@code racial} AbilityProgressionTable,
 * which {@link AbilityCatalog} already carries; and what a priest's deity or a
 * paladin's order favours lives on the {@code Religion} behaviour attached to
 * the InGameGlobal prefab, extracted by {@code tools/gamedata/identity.py}
 * into {@code identity.json}. Both degrade to silence when absent — the
 * compiled tables are always available.
 */
public class IdentityCatalog {
	private static final Logger logger = Logger.getLogger(IdentityCatalog.class);
	private static IdentityCatalog instance = null;

	/** {@code CharacterStats.AttributeScoreType} order — the table's columns. */
	public static final List<String> ATTRIBUTES = names(
		CharacterStats.AttributeScoreType.values());

	/** {@code CharacterStats.SkillType} order — a different set of columns. */
	public static final List<String> SKILLS = names(
		CharacterStats.SkillType.values());

	/**
	 * The races a player character can be. The enum also carries creature
	 * types (Beast, Spirit, Vessel, Wilder) whose rows are all zero anyway.
	 */
	public static final List<String> PLAYABLE_RACES = Collections.unmodifiableList(
		Arrays.asList("Human", "Elf", "Dwarf", "Godlike", "Orlan", "Aumaua"));

	/** Exactly the classes {@code CharacterStats.IsPlayableClass()} allows. */
	public static final List<String> PLAYABLE_CLASSES =
		Collections.unmodifiableList(Arrays.asList(
			"Fighter", "Rogue", "Priest", "Wizard", "Barbarian", "Ranger"
			, "Druid", "Paladin", "Monk", "Cipher", "Chanter"));

	// CharacterStats.RaceAbilityAdjustment, indexed by Race then
	// AttributeScoreType.
	private static final int[][] RACE_ATTRIBUTES = {
		{0, 0, 0, 0, 0, 0}     // Undefined
		, {1, 1, 0, 0, 0, 0}   // Human
		, {0, 0, 1, 0, 0, 1}   // Elf
		, {0, 2, -1, 0, 1, 0}  // Dwarf
		, {0, 0, 1, 1, 0, 0}   // Godlike
		, {1, -1, 0, 0, 0, 2}  // Orlan
		, {0, 0, 0, 0, 0, 0}   // Undead_DO_NOT_USE
		, {0, 2, 0, 0, 0, 0}   // Aumaua
		, {0, 0, 0, 0, 0, 0}   // Faunal_DO_NOT_USE
		, {0, 0, 0, 0, 0, 0}   // Giant_DO_NOT_USE
		, {0, 0, 0, 0, 0, 0}   // Beast
		, {0, 0, 0, 0, 0, 0}   // Primordial
		, {0, 0, 0, 0, 0, 0}   // Spirit
		, {0, 0, 0, 0, 0, 0}   // Vessel
		, {0, 0, 0, 0, 0, 0}   // Wilder
	};

	// CharacterStats.CultureAbilityAdjustment, indexed by Culture then
	// AttributeScoreType.
	private static final int[][] CULTURE_ATTRIBUTES = {
		{0, 0, 0, 0, 0, 0}    // Undefined
		, {1, 0, 0, 0, 0, 0}  // Aedyr
		, {0, 0, 1, 0, 0, 0}  // DeadfireArchipelago
		, {1, 0, 0, 0, 0, 0}  // IxamitlPlains
		, {0, 0, 0, 1, 0, 0}  // OldVailia
		, {0, 0, 0, 0, 1, 0}  // Ruatai
		, {0, 1, 0, 0, 0, 0}  // TheLivingLands
		, {0, 0, 0, 0, 0, 1}  // TheWhiteThatWends
		, {1, 0, 0, 0, 0, 0}  // TheDyrwood
		, {0, 0, 0, 1, 0, 0}  // TheVailianRepublics
		, {0, 0, 0, 0, 0, 1}  // Nassitaq
		, {0, 0, 0, 0, 1, 0}  // EirGlanfath
	};

	// CharacterStats.ClassSkillAdjustment, indexed by Class then SkillType.
	// Only the first twelve rows exist: CalculateSkillInternal() guards the
	// lookup with IsPlayableClass(), so a creature class adds nothing.
	private static final int[][] CLASS_SKILLS = {
		{0, 0, 0, 0, 0, 0}    // Undefined
		, {0, 1, 1, 0, 1, 0}  // Fighter
		, {1, 0, 0, 2, 0, 0}  // Rogue
		, {0, 1, 2, 0, 0, 0}  // Priest
		, {0, 0, 2, 1, 0, 0}  // Wizard
		, {0, 2, 0, 0, 1, 0}  // Barbarian
		, {1, 0, 0, 0, 2, 0}  // Ranger
		, {0, 0, 1, 0, 2, 0}  // Druid
		, {0, 2, 1, 0, 0, 0}  // Paladin
		, {1, 1, 0, 0, 1, 0}  // Monk
		, {1, 0, 1, 1, 0, 0}  // Cipher
		, {0, 0, 2, 1, 0, 0}  // Chanter
	};

	// CharacterStats.BackgroundSkillAdjustment, indexed by Background then
	// SkillType.
	private static final int[][] BACKGROUND_SKILLS = {
		{0, 0, 0, 0, 0, 0}    // Undefined
		, {0, 0, 2, 0, 0, 0}  // Aristocrat
		, {0, 0, 2, 0, 0, 0}  // Artist
		, {0, 0, 0, 0, 2, 0}  // Colonist
		, {1, 0, 1, 0, 0, 0}  // Dissident
		, {1, 0, 0, 1, 0, 0}  // Drifter
		, {0, 0, 1, 0, 1, 0}  // Explorer
		, {1, 0, 0, 0, 1, 0}  // Hunter
		, {0, 1, 0, 1, 0, 0}  // Laborer
		, {0, 1, 1, 0, 0, 0}  // Mercenary
		, {0, 0, 1, 1, 0, 0}  // Merchant
		, {0, 0, 2, 0, 0, 0}  // Mystic
		, {0, 0, 2, 0, 0, 0}  // Philosopher
		, {0, 0, 2, 0, 0, 0}  // Priest
		, {1, 1, 0, 0, 0, 0}  // Raider
		, {0, 1, 0, 0, 1, 0}  // Slave
		, {0, 0, 2, 0, 0, 0}  // Scholar
		, {0, 0, 1, 1, 0, 0}  // Scientist
		, {0, 1, 0, 0, 1, 0}  // Farmer
		, {0, 1, 1, 0, 0, 0}  // Soldier
		, {0, 0, 1, 0, 1, 0}  // Midwife
		, {0, 0, 2, 0, 0, 0}  // Gentry
		, {1, 0, 0, 1, 0, 0}  // Trapper
	};

	/** The single ability a subrace is born with. */
	public static final class RacialAbility {
		/** Catalog key, so the Abilities tab can be pointed straight at it. */
		public final String key;
		public final String name;
		public final String description;
		/** Base64 PNG, or empty when the icon directory has nothing. */
		public final String icon;

		RacialAbility (
			final String key
			, final String name
			, final String description
			, final String icon) {

			this.key = key;
			this.name = name;
			this.description = description;
			this.icon = icon;
		}
	}

	/**
	 * A deity or a paladin order: the dispositions it rewards and punishes.
	 *
	 * <p>{@code Religion.GetCurrentBonusMultiplier()} reads these for the
	 * player character alone, so a companion's order is flavour.
	 */
	public static final class Devotion {
		public final String name;
		public final List<String> positive;
		public final List<String> negative;

		Devotion (
			final String name
			, final List<String> positive
			, final List<String> negative) {

			this.name = name;
			this.positive = Collections.unmodifiableList(positive);
			this.negative = Collections.unmodifiableList(negative);
		}
	}

	private final Map<String, Devotion> deities = new LinkedHashMap<>();
	private final Map<String, Devotion> orders = new LinkedHashMap<>();
	private final Map<String, RacialAbility> racialAbilities = new HashMap<>();
	private double[] positiveBonus = new double[0];
	private double[] negativeBonus = new double[0];

	public static synchronized IdentityCatalog getInstance () {
		if (instance == null) {
			instance = new IdentityCatalog(
				ItemCatalog.locateDataDirectory("identity.json"), false);
		}

		return instance;
	}

	/** Testing seam — forces the next getInstance() to re-read from disk. */
	public static synchronized void reset () {
		instance = null;
	}

	/**
	 * Testing seam — pretends nothing was extracted, so a machine with a game
	 * install produces the same result as one without.
	 */
	public static synchronized void useNoCatalog () {
		instance = new IdentityCatalog(Optional.empty(), true);
	}

	/** Testing seam — reads a catalog from a directory of the caller's choosing. */
	public static synchronized void useCatalogAt (final File directory) {
		instance = new IdentityCatalog(Optional.of(directory), true);
	}

	private IdentityCatalog (final Optional<File> directory, final boolean quiet) {
		loadRacialAbilities();

		if (!directory.isPresent()) {
			if (!quiet) {
				logger.info(
					"No identity catalog found; deities and orders unavailable.%n");
			}

			return;
		}

		load(new File(directory.get(), "identity.json"));
	}

	/**
	 * Which ability each subrace is born with, out of the game's own racial
	 * progression table rather than a list typed out here.
	 */
	private void loadRacialAbilities () {
		final AbilityCatalog abilities = AbilityCatalog.getInstance();

		for (final Map.Entry<String, AbilityCatalog.Unlock> unlock
			: abilities.progressionTable("racial").entrySet()) {

			final Optional<AbilityCatalog.Entry> entry =
				abilities.lookup(unlock.getKey());

			if (!entry.isPresent()) {
				continue;
			}

			final RacialAbility ability = new RacialAbility(
				unlock.getKey()
				, entry.get().name
				, entry.get().description
				, entry.get().icon.isEmpty()
					? "" : abilities.iconData(entry.get().icon));

			// The progression tables are stored lower-cased, so put them back
			// on the enum constant the rest of the editor speaks.
			for (final String subrace : unlock.getValue().subraces) {
				racialAbilities.put(canonicalSubrace(subrace), ability);
			}
		}
	}

	private void load (final File catalogFile) {
		try (final Reader reader = new BufferedReader(
			new InputStreamReader(new FileInputStream(catalogFile), "UTF-8"))) {

			final JSONObject json = new JSONObject(new JSONTokener(reader));
			readDevotions(json.optJSONObject("deities"), deities);
			readDevotions(json.optJSONObject("orders"), orders);

			final JSONObject bonus = json.optJSONObject("dispositionBonus");
			if (bonus != null) {
				positiveBonus = doubles(bonus.optJSONArray("positive"));
				negativeBonus = doubles(bonus.optJSONArray("negative"));
			}
		} catch (final Exception e) {
			logger.error("Unable to read %s: %s%n"
				, catalogFile.getAbsolutePath(), e.getMessage());
		}
	}

	private static void readDevotions (
		final JSONObject source, final Map<String, Devotion> into) {

		if (source == null) {
			return;
		}

		for (final String key : source.keySet()) {
			final JSONObject entry = source.optJSONObject(key);
			if (entry == null) {
				continue;
			}

			into.put(key, new Devotion(
				GameText.said(entry, "name", key)
				, strings(entry.optJSONArray("positive"))
				, strings(entry.optJSONArray("negative"))));
		}
	}

	private static List<String> strings (final JSONArray array) {
		final List<String> result = new ArrayList<>();
		if (array != null) {
			for (int i = 0; i < array.length(); i++) {
				result.add(array.optString(i, ""));
			}
		}

		return result;
	}

	private static double[] doubles (final JSONArray array) {
		if (array == null) {
			return new double[0];
		}

		final double[] result = new double[array.length()];
		for (int i = 0; i < array.length(); i++) {
			result[i] = array.optDouble(i, 0d);
		}

		return result;
	}

	private static List<String> names (final Enum<?>[] values) {
		final List<String> result = new ArrayList<>();
		for (final Enum<?> value : values) {
			if (!"Count".equals(value.name())) {
				result.add(value.name());
			}
		}

		return Collections.unmodifiableList(result);
	}

	/**
	 * One row of an adjustment table as {column name: adjustment}, dropping the
	 * zeroes. An unrecognised constant simply adjusts nothing.
	 */
	private static Map<String, Integer> row (
		final int[][] table
		, final List<String> columns
		, final int index) {

		final Map<String, Integer> result = new LinkedHashMap<>();
		if (index < 0 || index >= table.length) {
			return result;
		}

		final int[] adjustments = table[index];
		for (int i = 0; i < adjustments.length && i < columns.size(); i++) {
			if (adjustments[i] != 0) {
				result.put(columns.get(i), adjustments[i]);
			}
		}

		return result;
	}

	private static <T extends Enum<T>> int ordinalOf (
		final java.lang.Class<T> type, final String name) {

		if (name == null || name.isEmpty()) {
			return -1;
		}

		try {
			return Enum.valueOf(type, name).ordinal();
		} catch (final IllegalArgumentException e) {
			return -1;
		}
	}

	/** What a race adds to the stored attribute scores. */
	public static Map<String, Integer> raceAttributes (final String race) {
		return row(RACE_ATTRIBUTES, ATTRIBUTES
			, ordinalOf(CharacterStats.Race.class, race));
	}

	/** What a culture adds to the stored attribute scores — always one point. */
	public static Map<String, Integer> cultureAttributes (final String culture) {
		return row(CULTURE_ATTRIBUTES, ATTRIBUTES
			, ordinalOf(CharacterStats.Culture.class, culture));
	}

	/**
	 * What a class adds to the skill ranks. Empty for anything
	 * {@code IsPlayableClass()} rejects, exactly as the game's own guard does.
	 */
	public static Map<String, Integer> classSkills (final String characterClass) {
		if (!PLAYABLE_CLASSES.contains(characterClass)) {
			return Collections.emptyMap();
		}

		return row(CLASS_SKILLS, SKILLS
			, ordinalOf(CharacterStats.Class.class, characterClass));
	}

	/** What a background adds to the skill ranks — always two points. */
	public static Map<String, Integer> backgroundSkills (final String background) {
		return row(BACKGROUND_SKILLS, SKILLS
			, ordinalOf(CharacterStats.Background.class, background));
	}

	/** The cultures the game actually configures, in enum order. */
	public static List<String> cultures () {
		return real(CharacterStats.Culture.values());
	}

	/** The backgrounds the game actually configures, in enum order. */
	public static List<String> backgrounds () {
		return real(CharacterStats.Background.values());
	}

	private static List<String> real (final Enum<?>[] values) {
		final List<String> result = new ArrayList<>();
		for (final Enum<?> value : values) {
			final String name = value.name();
			if (!"Count".equals(name) && !"Undefined".equals(name)
				&& !name.contains("DO_NOT_USE")) {

				result.add(name);
			}
		}

		return result;
	}

	/** The ability a subrace is born with, if the catalog knows of one. */
	public Optional<RacialAbility> racialAbility (final String subrace) {
		return Optional.ofNullable(racialAbilities.get(canonicalSubrace(subrace)));
	}

	private static String canonicalSubrace (final String subrace) {
		if (subrace == null) {
			return "";
		}

		for (final CharacterStats.Subrace value
			: CharacterStats.Subrace.values()) {

			if (value.name().equalsIgnoreCase(subrace)) {
				return value.name();
			}
		}

		return subrace;
	}

	public Optional<Devotion> deity (final String deity) {
		return Optional.ofNullable(deities.get(deity));
	}

	public Optional<Devotion> order (final String order) {
		return Optional.ofNullable(orders.get(order));
	}

	/**
	 * Everything the identity panel needs, in one object. The UI does the
	 * arithmetic itself because the numbers have to move as the dropdowns move,
	 * without a round trip.
	 */
	public JSONObject asJSON () {
		final JSONObject json = new JSONObject();
		json.put("attributes", array(ATTRIBUTES));
		json.put("skills", array(SKILLS));

		json.put("race", adjustments(
			PLAYABLE_RACES, "attributes", IdentityCatalog::raceAttributes));
		json.put("culture", adjustments(
			cultures(), "attributes", IdentityCatalog::cultureAttributes));
		json.put("characterClass", adjustments(
			PLAYABLE_CLASSES, "skills", IdentityCatalog::classSkills));
		json.put("background", adjustments(
			backgrounds(), "skills", IdentityCatalog::backgroundSkills));

		final JSONObject subraces = new JSONObject();
		for (final Map.Entry<String, RacialAbility> entry
			: racialAbilities.entrySet()) {

			final JSONObject ability = new JSONObject();
			ability.put("key", entry.getValue().key);
			ability.put("name", entry.getValue().name);
			ability.put("description", entry.getValue().description);
			ability.put("icon", entry.getValue().icon);
			subraces.put(entry.getKey(), ability);
		}

		json.put("subrace", subraces);
		json.put("deity", devotions(deities, Religion.Deity.values()));
		json.put("order", devotions(orders, Religion.PaladinOrder.values()));

		final JSONObject bonus = new JSONObject();
		bonus.put("positive", ladder(positiveBonus));
		bonus.put("negative", ladder(negativeBonus));
		json.put("dispositionBonus", bonus);

		return json;
	}

	// Built by hand because org.json 20141113 declared JSONArray(Collection<Object>)
	// and JSONObject(Map<String, Object>): a List<String> or a Map<String, Integer>
	// bound to the Object overload instead and threw or was reflected over as a
	// bean. The current org.json takes wildcards; building by hand stays correct.
	private static JSONArray array (final List<String> values) {
		final JSONArray result = new JSONArray();
		for (final String value : values) {
			result.put(value);
		}

		return result;
	}

	private static JSONObject object (final Map<String, Integer> values) {
		final JSONObject result = new JSONObject();
		for (final Map.Entry<String, Integer> entry : values.entrySet()) {
			result.put(entry.getKey(), entry.getValue().intValue());
		}

		return result;
	}

	private static JSONArray ladder (final double[] values) {
		final JSONArray result = new JSONArray();
		for (final double value : values) {
			result.put(value);
		}

		return result;
	}

	private interface Lookup {
		Map<String, Integer> of (String name);
	}

	private static JSONObject adjustments (
		final List<String> constants, final String field, final Lookup lookup) {

		final JSONObject result = new JSONObject();
		for (final String constant : constants) {
			final JSONObject entry = new JSONObject();
			entry.put(field, object(lookup.of(constant)));
			result.put(constant, entry);
		}

		return result;
	}

	private static JSONObject devotions (
		final Map<String, Devotion> source, final Enum<?>[] order) {

		final JSONObject result = new JSONObject();

		// Emitted in enum order so the panel lists them the way the game does.
		for (final Enum<?> constant : order) {
			final Devotion devotion = source.get(constant.name());
			if (devotion == null) {
				continue;
			}

			final JSONObject entry = new JSONObject();
			entry.put("name", devotion.name);
			entry.put("positive", array(devotion.positive));
			entry.put("negative", array(devotion.negative));
			result.put(constant.name(), entry);
		}

		return result;
	}
}
