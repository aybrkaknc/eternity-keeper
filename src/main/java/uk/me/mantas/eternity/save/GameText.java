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
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.environment.GameLanguage;
import uk.me.mantas.eternity.game.DatabaseString.StringTableType;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The game's own words, out of the string tables on the player's install.
 *
 * <p>A save often names something only as a table and an id: a hireling is
 * {@code SerializedNameId}, the {@code StringID} of its prefab's
 * {@code DisplayName} in the characters table, and a prisoner is a
 * {@code CharacterDatabaseString}. The words live in
 * {@code data/localized/en/text/game/<table>.stringtable} under the game's
 * data folder — one XML file per {@link StringTableType}, named after the
 * constant in lower case — and are read straight off the disk the way
 * portraits are, rather than copied into the extracted game data.
 *
 * <p>In the language the game is played in. The game ships every table once
 * per language ({@code data/localized/<code>/text}), and
 * {@link GameLanguage} says which one applies: the one Settings names, else
 * the one the game is set to, else English. Whatever that language's table
 * lacks is said in English, entry by entry. The catalogs hold the English
 * names they were extracted with and ask here only for the other language's
 * word ({@link #translated}).
 *
 * <p>A table is read the first time something asks for it and kept. With no
 * install, no such table, or one that will not parse, a lookup is simply
 * empty, and the caller says something sensible instead.
 */
public class GameText {
	private static final Logger logger = Logger.getLogger(GameText.class);
	private static final String TABLES = "data/localized/en/text/game";
	private static final String QUESTS = "quests";

	private static GameText instance = null;

	// The English tables, and the same folder of the language in use: null
	// when that is English too.
	private final File directory;
	private final File translation;
	private final String language;
	private final Map<StringTableType, Map<Integer, String>> tables = new HashMap<>();
	private final Map<StringTableType, Map<Integer, String>> translated = new HashMap<>();
	private final Map<String, Map<Integer, String>> quests = new HashMap<>();

	public static synchronized GameText getInstance () {
		if (instance == null) {
			final Optional<File> game = gameDirectory();
			instance = new GameText(game
				, GameLanguage.current(game.orElse(null), chosenLanguage()));
		}

		return instance;
	}

	/** Testing seam — forces the next getInstance() to look again. */
	public static synchronized void reset () {
		instance = null;
	}

	/**
	 * Reads the game's names from scratch the next time anything asks: the
	 * text here and the four catalogs, which take their names from it when
	 * they load. For when the install or the language has changed; names are
	 * otherwise read once and kept.
	 */
	public static void startAgain () {
		reset();
		ItemCatalog.reset();
		AbilityCatalog.reset();
		StrongholdCatalog.reset();
		IdentityCatalog.reset();
	}

	/** Testing seam — pretends the game is not installed. */
	public static synchronized void useNoText () {
		instance = new GameText(Optional.empty(), GameLanguage.ENGLISH);
	}

	/** Testing seam — reads from a game directory of the caller's choosing. */
	public static synchronized void useTextAt (final File gameDirectory) {
		instance = new GameText(Optional.of(gameDirectory), GameLanguage.ENGLISH);
	}

	/** Testing seam — the same, in one of the languages that install has. */
	public static synchronized void useTextAt (final File gameDirectory, final String language) {
		instance = new GameText(Optional.of(gameDirectory), language);
	}

	/**
	 * The install, out of settings. Mocked away in tests, so anything thrown
	 * here means "no install" rather than a failure.
	 */
	private static Optional<File> gameDirectory () {
		try {
			final JSONObject settings = Settings.getInstance().json;
			final String location = settings.getString("gameLocation");
			return location == null || location.isEmpty()
				? Optional.empty() : Optional.of(new File(location));
		} catch (final JSONException | NullPointerException e) {
			return Optional.empty();
		}
	}

	// The choice Settings holds; nothing where there are no settings to read.
	private static String chosenLanguage () {
		try {
			return Settings.getInstance().json.optString("language", "");
		} catch (final NullPointerException e) {
			return "";
		}
	}

	private GameText (final Optional<File> gameDirectory, final String wanted) {
		directory = gameDirectory
			.map(game -> new File(new File(game
				, Environment.getInstance().config().pillarsDataDirectory()), TABLES))
			.orElse(null);

		// A language the install has no text for is English: the name comes
		// from settings, and is never put into a path unless a folder of
		// exactly that name is one of the install's languages.
		final Optional<File> folder = GameLanguage.ENGLISH.equalsIgnoreCase(wanted)
			? Optional.empty()
			: GameLanguage.folder(gameDirectory.orElse(null), wanted);

		translation = folder.map(found -> new File(found, "text/game")).orElse(null);
		language = folder.isPresent() ? folder.get().getName() : GameLanguage.ENGLISH;
	}

	/** The language names are shown in, as the install's folder for it is called. */
	public String language () {
		return language;
	}

	public synchronized Optional<String> lookup (final StringTableType table, final int id) {
		if (table == null || id < 0) {
			return Optional.empty();
		}

		final Optional<String> word = translated(table, id);
		return word.isPresent()
			? word : Optional.ofNullable(tables.computeIfAbsent(table, this::read).get(id));
	}

	/**
	 * The word for an entry in the language in use, where that is not English
	 * and its table has one: what a catalog shows in place of the English
	 * name it holds.
	 */
	public synchronized Optional<String> translated (final StringTableType table, final int id) {
		if (translation == null || table == null || id < 0) {
			return Optional.empty();
		}

		final String word = translated.computeIfAbsent(table, wanted -> read(
			new File(translation, wanted.name().toLowerCase(Locale.ROOT) + ".stringtable"))).get(id);
		return word == null || word.isEmpty() ? Optional.empty() : Optional.of(word);
	}

	/**
	 * What a catalog shows for one of its entries: the text it was extracted
	 * with (English), unless the entry says where that text came from
	 * ({@code "nameId": [table, id]} beside {@code "name"}) and the language in
	 * use has a word of its own there.
	 */
	public static String said (final JSONObject entry, final String field, final String fallback) {
		final String english = entry.optString(field, fallback);
		final JSONArray source = entry.optJSONArray(field + "Id");
		if (source == null || source.length() != 2) {
			return english;
		}

		return getInstance().translated(source.optInt(0, -1), source.optInt(1, -1)).orElse(english);
	}

	/** {@link #translated}, by the number a catalog keeps for the table (5 is items). */
	public Optional<String> translated (final int table, final int id) {
		for (final StringTableType type : StringTableType.values()) {
			if (type.n == table && type != StringTableType.Unassigned) {
				return translated(type, id);
			}
		}

		return Optional.empty();
	}

	/**
	 * One quest's own table, by the quest file a save names it by
	 * ("data/quests/critical_path/act_4/cp_qst_confront_lka.quest"): entry 0 is
	 * the title, an objective's ID is its entry. The tables sit under
	 * {@code text/quests}, beside {@code text/game}, in the quests' own
	 * folders. Empty without an install, without the table, or for a name that
	 * would reach outside that folder.
	 */
	public synchronized Map<Integer, String> quest (final String questFile) {
		if (directory == null || questFile == null) {
			return Collections.emptyMap();
		}

		String name = questFile.replace('\\', '/').toLowerCase(Locale.ROOT);
		name = name.replaceFirst("^data/quests/", "").replaceFirst("\\.quest$", "");
		if (name.isEmpty() || name.startsWith("/") || Arrays.asList(name.split("/")).contains("..")) {
			return Collections.emptyMap();
		}

		return quests.computeIfAbsent(name, key -> {
			final Map<Integer, String> entries =
				read(new File(new File(directory.getParentFile(), QUESTS), key + ".stringtable"));
			if (translation == null) {
				return entries;
			}

			// Entry by entry: a title in the player's language, and an
			// objective the translation lacks in English.
			final Map<Integer, String> said = new HashMap<>(entries);
			read(new File(new File(translation.getParentFile(), QUESTS), key + ".stringtable"))
				.forEach((id, word) -> {
					if (!word.isEmpty()) {
						said.put(id, word);
					}
				});

			return said;
		});
	}

	private Map<Integer, String> read (final StringTableType table) {
		if (directory == null) {
			return Collections.emptyMap();
		}

		return read(new File(directory, table.name().toLowerCase(Locale.ROOT) + ".stringtable"));
	}

	private Map<Integer, String> read (final File file) {
		if (!file.isFile()) {
			return Collections.emptyMap();
		}

		final Map<Integer, String> entries = new HashMap<>();
		try (final InputStream in = new BufferedInputStream(new FileInputStream(file))) {
			parse(in, entries);
		} catch (final IOException | XMLStreamException | NumberFormatException e) {
			logger.error(
				"Unable to read '%s': %s%n", file.getAbsolutePath(), e.getMessage());

			// Whatever came before the fault is still the game's own text,
			// but a half-read table would answer some ids and not others for
			// no reason anyone could see, so it is all or nothing.
			return Collections.emptyMap();
		}

		return entries;
	}

	// <Entry><ID>329</ID><DefaultText>Crucible Knight</DefaultText>…</Entry>,
	// with FemaleText and GenderNeutralText beside it, usually empty.
	private static void parse (final InputStream in, final Map<Integer, String> entries)
		throws XMLStreamException {

		final XMLInputFactory factory = XMLInputFactory.newInstance();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

		final XMLStreamReader reader = factory.createXMLStreamReader(in);
		try {
			Integer id = null;
			String text = null;

			while (reader.hasNext()) {
				final int event = reader.next();
				if (event == XMLStreamConstants.START_ELEMENT) {
					switch (reader.getLocalName()) {
						case "Entry":
							id = null;
							text = null;
							break;

						case "ID":
							id = Integer.valueOf(reader.getElementText().trim());
							break;

						case "DefaultText":
							text = reader.getElementText().trim();
							break;

						default:
							break;
					}
				} else if (event == XMLStreamConstants.END_ELEMENT
					&& "Entry".equals(reader.getLocalName())
					&& id != null && text != null) {

					entries.put(id, text);
				}
			}
		} finally {
			reader.close();
		}
	}
}
