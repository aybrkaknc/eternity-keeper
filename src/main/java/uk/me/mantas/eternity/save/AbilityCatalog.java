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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Real names, icons and rules for abilities, spells and talents.
 *
 * <p>Extracted from the game's asset bundles by the same helper script that
 * builds the item catalog, into {@code abilities.json} and
 * {@code progression.json} beside {@code catalog.json}; icons are shared with
 * the item catalog. Everything degrades gracefully: with no catalog installed
 * the editor still lists what a save contains, just under prettified prefab
 * names.
 *
 * <p>Two things here are not cosmetic. {@code component} is the exact C# class
 * the save writes as a packet's {@code TypeString} — the ~60 ability classes are
 * not interchangeable. {@code grants} is what a talent instantiates when it is
 * bought: {@code CharacterStats.Restored} rebuilds a character's abilities from
 * the objects already in the save and never re-runs {@code GenericTalent
 * .Purchase}, so those objects have to be minted alongside the talent itself.
 */
public class AbilityCatalog {
	private static final Logger logger = Logger.getLogger(AbilityCatalog.class);
	private static AbilityCatalog instance = null;

	private final Map<String, Entry> entries = new HashMap<>();
	private final Map<String, Map<String, Unlock>> progression = new HashMap<>();

	/** One catalogued ability, spell or talent. Most fields may be absent. */
	public static final class Entry {
		public final String name;
		public final String description;
		public final String icon;
		/** "ability", "spell" or "talent". */
		public final String kind;
		/** Exact component class the packet's TypeString must carry. */
		public final String component;
		/** GenericAbility.AbilityType ordinal to write as EffectType. */
		public final int effect;
		/** AcquisitionLevel — the level the game first offers this. */
		public final int level;
		public final int spellLevel;
		/** Owning class, where the game records one; empty otherwise. */
		public final String characterClass;
		public final boolean passive;
		/** Talents only: GenericTalent.TalentCategory. */
		public final String category;
		/** Talents only: GrantNewAbility or ModExistingAbility. */
		public final String talentType;
		/** Talents only: abilities the talent instantiates when bought. */
		public final List<String> grants;
		/** Talents only: abilities it modifies — the game reapplies these. */
		public final List<String> modifies;
		/** Talents only: skill bonuses baked into &lt;Skill&gt;Bonus. */
		public final Map<String, Integer> skills;
		/** Prefab path, written verbatim into the packet's PrefabResource. */
		public final String path;

		Entry (
			final String name
			, final String description
			, final String icon
			, final String kind
			, final String component
			, final int effect
			, final int level
			, final int spellLevel
			, final String characterClass
			, final boolean passive
			, final String category
			, final String talentType
			, final List<String> grants
			, final List<String> modifies
			, final Map<String, Integer> skills
			, final String path) {

			this.name = name;
			this.description = description;
			this.icon = icon;
			this.kind = kind;
			this.component = component;
			this.effect = effect;
			this.level = level;
			this.spellLevel = spellLevel;
			this.characterClass = characterClass;
			this.passive = passive;
			this.category = category;
			this.talentType = talentType;
			this.grants = grants;
			this.modifies = modifies;
			this.skills = skills;
			this.path = path;
		}

		public boolean isTalent () {
			return "talent".equals(kind);
		}

		/** The same entry wearing a different icon. */
		Entry withIcon (final String replacement) {
			return new Entry(
				name, description, replacement, kind, component, effect, level
				, spellLevel, characterClass, passive, category, talentType
				, grants, modifies, skills, path);
		}
	}

	/** One row of an AbilityProgressionTable: when a character may take this. */
	public static final class Unlock {
		/** "General", "Racial" or "Talent". */
		public final String category;
		public final int level;
		/** The game grants this automatically rather than offering a choice. */
		public final boolean automatic;
		/**
		 * Classes eligible for this; empty means anyone. The shared talent
		 * table is the reason this matters — 92 of its 140 rows are class
		 * talents sitting right next to the general ones.
		 */
		public final List<String> classes;
		/** Subraces eligible for this; empty means anyone. */
		public final List<String> subraces;
		/** Only the player character may take it (Watcher abilities). */
		public final boolean playerOnly;

		Unlock (
			final String category
			, final int level
			, final boolean automatic
			, final List<String> classes
			, final List<String> subraces
			, final boolean playerOnly) {

			this.category = category;
			this.level = level;
			this.automatic = automatic;
			this.classes = classes;
			this.subraces = subraces;
			this.playerOnly = playerOnly;
		}

		boolean allows (
			final String characterClass, final String subrace, final boolean isPlayer) {

			if (playerOnly && !isPlayer) {
				return false;
			}

			if (!classes.isEmpty() && !containsIgnoreCase(classes, characterClass)) {
				return false;
			}

			return subraces.isEmpty() || containsIgnoreCase(subraces, subrace);
		}

		private static boolean containsIgnoreCase (
			final List<String> values, final String needle) {

			if (needle == null || needle.isEmpty()) {
				return false;
			}

			for (final String value : values) {
				if (value.equalsIgnoreCase(needle)) {
					return true;
				}
			}

			return false;
		}
	}

	public static synchronized AbilityCatalog getInstance () {
		if (instance == null) {
			instance = new AbilityCatalog(ItemCatalog.locateDataDirectory("abilities.json"));
		}

		return instance;
	}

	/** Testing seam — forces the next getInstance() to re-read from disk. */
	public static synchronized void reset () {
		instance = null;
	}

	/**
	 * Testing seam — pretends no catalog is installed, so results don't depend
	 * on whether the machine running the tests has a game install.
	 */
	public static synchronized void useNoCatalog () {
		instance = new AbilityCatalog(Optional.empty(), true);
	}

	/**
	 * Testing seam — reads a catalog from a directory of the caller's choosing,
	 * so the filtering rules can be tested against a fixture instead of against
	 * whatever the machine's game install happens to contain.
	 */
	public static synchronized void useCatalogAt (final File directory) {
		instance = new AbilityCatalog(Optional.of(directory), true);
	}

	private AbilityCatalog (final Optional<File> directory) {
		this(directory, false);
	}

	private AbilityCatalog (final Optional<File> directory, final boolean quiet) {
		if (!directory.isPresent()) {
			if (!quiet) {
				logger.info(
					"No ability catalog found; falling back to prettified file names.%n");
			}

			return;
		}

		load(new File(directory.get(), "abilities.json"));
		loadProgression(new File(directory.get(), "progression.json"));
	}

	private void load (final File catalogFile) {
		final JSONObject json = readJSON(catalogFile);
		if (json == null) {
			return;
		}

		for (final String key : json.keySet()) {
			final JSONObject ability = json.optJSONObject(key);
			if (ability == null) {
				continue;
			}

			entries.put(key.toLowerCase(), new Entry(
				GameText.said(ability, "name", "")
				, GameText.said(ability, "desc", "")
				, ability.optString("icon", "")
				, ability.optString("kind", "ability")
				, ability.optString("component", "GenericAbility")
				, ability.optInt("effect", 5)
				, ability.optInt("level", 0)
				, ability.optInt("spellLevel", 0)
				, ability.optString("class", "")
				, ability.optInt("passive", 0) != 0
				, ability.optString("category", "")
				, ability.optString("type", "")
				, stringList(ability.optJSONArray("grants"))
				, stringList(ability.optJSONArray("modifies"))
				, intMap(ability.optJSONObject("skills"))
				, ability.optString("path", "")));
		}

		inheritTalentIcons();
		logger.info("Loaded %d catalogued abilities.%n", entries.size());
	}

	/**
	 * Lets an ability borrow the icon of the talent that grants it.
	 *
	 * <p>164 of the game's 1,440 ability objects have a null {@code Icon}
	 * pointer — the artwork is on the talent, because the game's own character
	 * sheet shows the talent rather than the ability object it instantiated.
	 * 90 of them are reachable this way; the rest (shield-bash attacks, debug
	 * spells) really have no icon anywhere, and the UI draws a placeholder
	 * instead of a blank tile.
	 */
	private void inheritTalentIcons () {
		for (final Entry talent : new ArrayList<>(entries.values())) {
			if (!talent.isTalent() || talent.icon.isEmpty()) {
				continue;
			}

			// Only what the talent instantiates: a ModExistingAbility talent
			// decorates an ability that already has its own artwork.
			for (final String granted : talent.grants) {
				final String key = granted.toLowerCase();
				final Entry ability = entries.get(key);
				if (ability != null && ability.icon.isEmpty()) {
					entries.put(key, ability.withIcon(talent.icon));
				}
			}
		}
	}

	private void loadProgression (final File progressionFile) {
		final JSONObject json = readJSON(progressionFile);
		if (json == null) {
			return;
		}

		for (final String table : json.keySet()) {
			final JSONObject unlocks = json.optJSONObject(table);
			if (unlocks == null) {
				continue;
			}

			final Map<String, Unlock> byKey = new HashMap<>();
			for (final String key : unlocks.keySet()) {
				final JSONObject unlock = unlocks.optJSONObject(key);
				if (unlock == null) {
					continue;
				}

				byKey.put(key.toLowerCase(), new Unlock(
					unlock.optString("cat", "General")
					, unlock.optInt("level", 1)
					, unlock.optInt("auto", 0) != 0
					, stringList(unlock.optJSONArray("classes"))
					, stringList(unlock.optJSONArray("subraces"))
					, unlock.optInt("player", 0) != 0));
			}

			progression.put(table.toLowerCase(), Collections.unmodifiableMap(byKey));
		}
	}

	private static JSONObject readJSON (final File file) {
		if (!file.isFile()) {
			return null;
		}

		try (final Reader reader = new BufferedReader(
			new InputStreamReader(new FileInputStream(file), "UTF-8"))) {

			return new JSONObject(new JSONTokener(reader));
		} catch (final Exception e) {
			logger.error(
				"Unable to read '%s': %s%n", file.getAbsolutePath(), e.getMessage());
			return null;
		}
	}

	private static List<String> stringList (final JSONArray array) {
		final List<String> values = new ArrayList<>();
		if (array != null) {
			for (int i = 0; i < array.length(); i++) {
				values.add(array.getString(i).toLowerCase());
			}
		}

		return Collections.unmodifiableList(values);
	}

	private static Map<String, Integer> intMap (final JSONObject object) {
		final Map<String, Integer> values = new LinkedHashMap<>();
		if (object != null) {
			for (final String key : object.keySet()) {
				values.put(key, object.optInt(key, 0));
			}
		}

		return Collections.unmodifiableMap(values);
	}

	/**
	 * Turns the name a save records — either a prefab path or an ObjectName
	 * like {@code Fireball(Clone)} — into its catalog key.
	 */
	public static String keyOf (final String reference) {
		if (reference == null || reference.isEmpty()) {
			return "";
		}

		String name = reference.substring(reference.lastIndexOf('/') + 1);
		if (name.endsWith(".prefab")) {
			name = name.substring(0, name.length() - ".prefab".length());
		}

		final int clone = name.indexOf("(Clone)");
		if (clone >= 0) {
			name = name.substring(0, clone);
		}

		return name.trim().toLowerCase();
	}

	public Optional<Entry> lookup (final String reference) {
		return Optional.ofNullable(entries.get(keyOf(reference)));
	}

	public int size () {
		return entries.size();
	}

	/**
	 * What a particular character may take, as catalog key to unlock. The game
	 * keeps one table per class, one shared talent table, one racial table, and
	 * a table per story companion carrying their unique options — a character
	 * sees the union of the ones that apply to them, minus the rows whose own
	 * requirements they don't meet.
	 */
	public Map<String, Unlock> unlocksFor (
		final String characterClass
		, final String companion
		, final String subrace
		, final boolean isPlayer) {

		final Map<String, Unlock> combined = new LinkedHashMap<>();
		final List<String> tables = new ArrayList<>();

		if (characterClass != null && !characterClass.isEmpty()) {
			tables.add(characterClass.toLowerCase());
		}

		tables.add("talents");
		tables.add("racial");

		if (companion != null && !companion.isEmpty()) {
			tables.add(companion.toLowerCase());
		}

		for (final String table : tables) {
			final Map<String, Unlock> unlocks = progression.get(table);
			if (unlocks == null) {
				continue;
			}

			for (final Map.Entry<String, Unlock> unlock : unlocks.entrySet()) {
				if (!unlock.getValue().allows(characterClass, subrace, isPlayer)) {
					continue;
				}

				final Unlock existing = combined.get(unlock.getKey());
				if (existing == null || unlock.getValue().level < existing.level) {
					combined.put(unlock.getKey(), unlock.getValue());
				}
			}
		}

		return combined;
	}

	/**
	 * One of the game's progression tables whole, or an empty map when the
	 * catalog has no such table. The {@code racial} one is how
	 * {@link IdentityCatalog} learns which ability a subrace is born with.
	 */
	public Map<String, Unlock> progressionTable (final String table) {
		if (table == null) {
			return Collections.emptyMap();
		}

		final Map<String, Unlock> unlocks = progression.get(table.toLowerCase());
		return unlocks == null ? Collections.emptyMap() : unlocks;
	}

	public boolean hasProgressionTable (final String table) {
		return table != null && progression.containsKey(table.toLowerCase());
	}

	/**
	 * Catalogued abilities matching a name fragment and a kind, sorted by
	 * display name so paging is stable. A non-empty {@code allowed} set
	 * restricts results to what a particular character can actually take.
	 */
	public List<Map.Entry<String, Entry>> search (
		final String needle, final String kind, final Map<String, Unlock> allowed) {

		final List<Map.Entry<String, Entry>> matches = new ArrayList<>();

		for (final Map.Entry<String, Entry> candidate : entries.entrySet()) {
			final Entry entry = candidate.getValue();

			if (kind != null && !kind.isEmpty() && !kind.equals(entry.kind)) {
				continue;
			}

			// The game's debug and test spells; see ShippedContent.
			if (ShippedContent.isDevelopmentPrefab(entry.path)) {
				continue;
			}

			if (allowed != null && !allowed.isEmpty()
				&& !allowed.containsKey(candidate.getKey())) {

				continue;
			}

			if (needle != null && !needle.isEmpty()
				&& !entry.name.toLowerCase().contains(needle)
				&& !candidate.getKey().contains(needle)) {

				continue;
			}

			matches.add(candidate);
		}

		matches.sort((a, b) -> {
			final int byName = a.getValue().name.compareToIgnoreCase(b.getValue().name);
			return byName != 0 ? byName : a.getKey().compareTo(b.getKey());
		});

		return matches;
	}

	/**
	 * The prefab name a save stores for this path: the file name, with the
	 * casing the catalog recorded and without {@code .prefab}. The catalog's
	 * own keys are lower-cased, so they cannot stand in for it.
	 */
	public static String prefabNameOf (final String path, final String fallback) {
		if (path == null || path.isEmpty()) {
			return fallback;
		}

		final String file = path.substring(path.lastIndexOf('/') + 1);
		return file.endsWith(".prefab")
			? file.substring(0, file.length() - ".prefab".length()) : file;
	}

	/** Base64 PNG for an icon, shared with the item catalog's icon directory. */
	public String iconData (final String iconFile) {
		return ItemCatalog.getInstance().iconData(iconFile);
	}
}
