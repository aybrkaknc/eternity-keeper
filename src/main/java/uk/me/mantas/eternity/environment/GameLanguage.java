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


package uk.me.mantas.eternity.environment;

import uk.me.mantas.eternity.Logger;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which language the game's own names are shown in.
 *
 * <p>The game ships its text once per language: {@code data/localized/<code>}
 * under its data folder, each with a {@code language.xml} that gives the name
 * the game's own setting uses ("polish") and the name the language calls
 * itself ("Polski"), and the string tables under {@code text}. The editor
 * follows the language the game is set to, unless Settings names another,
 * and says in English whatever the install has no other word for.
 *
 * <p>The game's setting is a Unity PlayerPrefs value, which on Windows is in
 * the registry under the game's own key: {@code LanguageName_h2027703280},
 * binary, the name as text with a zero after it. Anywhere else, or with no
 * such value, there is no setting and the answer is English.
 */
public final class GameLanguage {
	private static final Logger logger = Logger.getLogger(GameLanguage.class);

	public static final String ENGLISH = "en";

	private static final String LOCALIZED = "data/localized";
	private static final String KEY =
		"HKCU\\Software\\Obsidian Entertainment\\Pillars of Eternity";
	private static final String VALUE = "LanguageName";

	// A folder name the editor is willing to put into a path.
	private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z_-]{0,15}");
	private static final Pattern REGISTRY_LINE =
		Pattern.compile("^\\s*" + VALUE + "\\S*\\s+(REG_[A-Z_]+)\\s+(.*)$");

	/** What the game itself is set to, as its {@code language.xml} names it. */
	public interface GameSetting {
		Optional<String> name ();
	}

	private static GameSetting setting = GameLanguage::askRegistry;

	/** One language an install has text for. */
	public static final class Language {
		/** Its folder under data/localized: "pl". */
		public final String code;
		/** What the game's own setting calls it: "polish". */
		public final String name;
		/** What it calls itself: "Polski". */
		public final String label;

		Language (final String code, final String name, final String label) {
			this.code = code;
			this.name = name;
			this.label = label;
		}
	}

	private GameLanguage () {}

	/** Testing seam, and the place a platform without a registry would plug in. */
	public static synchronized void use (final GameSetting source) {
		setting = source;
	}

	/** Testing seam: the game says nothing, whatever this machine's is set to. */
	public static synchronized void useNoSetting () {
		setting = Optional::empty;
	}

	public static synchronized Optional<String> gameSetting () {
		return setting.name();
	}

	public static boolean isCode (final String code) {
		return code != null && CODE.matcher(code).matches();
	}

	/** Where an install keeps one language's text, or nothing for a name that is no language's. */
	public static Optional<File> folder (final File gameDirectory, final String code) {
		if (gameDirectory == null || !isCode(code)) {
			return Optional.empty();
		}

		final File folder = new File(new File(new File(gameDirectory
			, Environment.getInstance().config().pillarsDataDirectory()), LOCALIZED), code);
		return new File(folder, "text/game").isDirectory()
			? Optional.of(folder) : Optional.empty();
	}

	/**
	 * The languages the install has text for: English first, then the rest by
	 * the name each calls itself. A folder with no description of itself, or
	 * no text, is not a language.
	 */
	public static List<Language> available (final File gameDirectory) {
		if (gameDirectory == null) {
			return Collections.emptyList();
		}

		final File[] folders = new File(new File(gameDirectory
			, Environment.getInstance().config().pillarsDataDirectory()), LOCALIZED).listFiles();
		if (folders == null) {
			return Collections.emptyList();
		}

		final List<Language> languages = new ArrayList<>();
		for (final File folder : folders) {
			if (folder.isDirectory() && folder(gameDirectory, folder.getName()).isPresent()) {
				describe(folder).ifPresent(languages::add);
			}
		}

		languages.sort((a, b) -> {
			final boolean english = ENGLISH.equalsIgnoreCase(a.code);
			if (english != ENGLISH.equalsIgnoreCase(b.code)) {
				return english ? -1 : 1;
			}

			return String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
		});

		return languages;
	}

	/**
	 * The language to show names in: the one Settings chose, where the install
	 * has it; else the one the game is set to; else English.
	 */
	public static String choose (final String chosen, final List<Language> available
		, final Optional<String> gameSetting) {

		if (chosen != null && !chosen.isEmpty()) {
			for (final Language language : available) {
				if (language.code.equalsIgnoreCase(chosen)) {
					return language.code;
				}
			}
		}

		if (gameSetting.isPresent()) {
			for (final Language language : available) {
				if (language.name.equalsIgnoreCase(gameSetting.get().trim())) {
					return language.code;
				}
			}
		}

		return ENGLISH;
	}

	/** {@link #choose} for this install, this machine's game and the choice given. */
	public static String current (final File gameDirectory, final String chosen) {
		final List<Language> available = available(gameDirectory);
		if (available.isEmpty()) {
			return ENGLISH;
		}

		// The registry is only asked when Settings leaves it to the game.
		for (final Language language : available) {
			if (chosen != null && language.code.equalsIgnoreCase(chosen)) {
				return language.code;
			}
		}

		return choose("", available, gameSetting());
	}

	/**
	 * The game's setting out of what {@code reg query <key> /f LanguageName}
	 * printed: the value's name carries a hash, and its text is bytes.
	 */
	public static Optional<String> parse (final List<String> output) {
		for (final String line : output) {
			final Matcher matcher = REGISTRY_LINE.matcher(line);
			if (!matcher.matches()) {
				continue;
			}

			final String data = matcher.group(2).trim();
			final String name = "REG_BINARY".equals(matcher.group(1)) ? text(data) : data;
			if (name != null && !name.isEmpty()) {
				return Optional.of(name);
			}
		}

		return Optional.empty();
	}

	// "706F6C69736800" is "polish" and the zero that ends it.
	private static String text (final String hex) {
		if (hex.length() % 2 != 0) {
			return null;
		}

		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for (int i = 0; i < hex.length(); i += 2) {
			final int high = Character.digit(hex.charAt(i), 16);
			final int low = Character.digit(hex.charAt(i + 1), 16);
			if (high < 0 || low < 0) {
				return null;
			}

			final int value = high * 16 + low;
			if (value == 0) {
				break;
			}

			bytes.write(value);
		}

		try {
			return bytes.toString("UTF-8").trim();
		} catch (final UnsupportedEncodingException e) {
			return null;
		}
	}

	private static Optional<Language> describe (final File folder) {
		String name = null;
		String label = null;

		try (final InputStream in =
			new BufferedInputStream(new FileInputStream(new File(folder, "language.xml")))) {

			final XMLInputFactory factory = XMLInputFactory.newInstance();
			factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
			factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

			final XMLStreamReader reader = factory.createXMLStreamReader(in);
			try {
				while (reader.hasNext()) {
					if (reader.next() != XMLStreamConstants.START_ELEMENT) {
						continue;
					}

					if ("Name".equals(reader.getLocalName())) {
						name = reader.getElementText().trim();
					} else if ("GUIString".equals(reader.getLocalName())) {
						label = reader.getElementText().trim();
					}
				}
			} finally {
				reader.close();
			}
		} catch (final IOException | XMLStreamException e) {
			// No description, or one that cannot be read: not a language.
			return Optional.empty();
		}

		if (name == null || name.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new Language(
			folder.getName(), name, label == null || label.isEmpty() ? name : label));
	}

	// Every failure is "no setting": no reg command at all means this is not
	// Windows, and no such key means the game has never been started here.
	private static Optional<String> askRegistry () {
		final List<String> output = new ArrayList<>();

		try {
			final Process process = new ProcessBuilder("reg", "query", KEY, "/f", VALUE)
				.redirectErrorStream(true)
				.start();

			try (final BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream()))) {

				String line;
				while ((line = reader.readLine()) != null) {
					output.add(line);
				}
			}

			process.waitFor();
		} catch (final IOException e) {
			return Optional.empty();
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}

		final Optional<String> name = parse(output);
		name.ifPresent(found -> logger.info("The game is set to '%s'.%n", found));
		return name;
	}
}
