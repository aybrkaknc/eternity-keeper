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

import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Every portrait the player could actually choose, read off their own install.
 *
 * <p>A save stores nothing but two strings on the {@code Portrait} component:
 * {@code m_textureLargePath} and {@code m_textureSmallPath}, both relative to
 * {@code PillarsOfEternity_Data}. Decompiled {@code Portrait.Start()} loads
 * whatever they name and only derives a path from {@code CompanionInstanceID}
 * when one is <em>empty</em>, so a non-empty path is used verbatim and nothing
 * recomputes it on load. {@code GUIUtils.LoadTexture2DFromPathCallback} then
 * does {@code Path.Combine(Application.dataPath, path)} and reads the file
 * straight off disk — which is why a portrait the player dropped in themselves
 * works exactly like a shipped one, and why this lists the directory rather
 * than a table typed out here.
 *
 * <p>The two paths are a pair by convention — {@code X_lg.png} beside
 * {@code X_sm.png}, 210×330 and 76×96 — and a portrait is only offered when
 * both exist. Writing a large path whose small counterpart is missing leaves
 * the party bar showing the white fallback texture.
 *
 * <p>Nothing is cached in memory but the listing: the images are read on demand
 * so a full set (118 pairs in the shipped game) never has to cross the JCEF
 * bridge at once.
 */
public class PortraitCatalog {
	private static final Logger logger = Logger.getLogger(PortraitCatalog.class);
	private static PortraitCatalog instance = null;

	/** Where the game keeps them, under the data directory. */
	private static final String PORTRAIT_DIRECTORY = "data/art/gui/portraits";

	private static final String LARGE_SUFFIX = "_lg.png";
	private static final String SMALL_SUFFIX = "_sm.png";

	private final Map<String, Portrait> byLargePath = new LinkedHashMap<>();
	private final List<Portrait> ordered = new ArrayList<>();
	private final TreeSet<String> categories = new TreeSet<>();
	private final File portraitDirectory;
	/** PillarsOfEternity_Data — what the stored paths are relative to. */
	private final File dataDirectory;

	/** One choosable portrait: a large and a small that belong together. */
	public static final class Portrait {
		/** Stable identity, and what the UI keys its selection on. */
		public final String key;
		/** The file name without the {@code _lg}/{@code _sm} suffix. */
		public final String name;
		/** The directory it sits in, relative to the portraits folder. */
		public final String category;
		/** Written to {@code m_textureLargePath}, verbatim. */
		public final String large;
		/** Written to {@code m_textureSmallPath}, verbatim. */
		public final String small;

		Portrait (
			final String name
			, final String category
			, final String large
			, final String small) {

			this.key = category.isEmpty() ? name : category + "/" + name;
			this.name = name;
			this.category = category;
			this.large = large;
			this.small = small;
		}
	}

	public static synchronized PortraitCatalog getInstance () {
		if (instance == null) {
			instance = new PortraitCatalog(gameDirectory(), false);
		}

		return instance;
	}

	/** Testing seam — forces the next getInstance() to re-read from disk. */
	public static synchronized void reset () {
		instance = null;
	}

	/** Testing seam — pretends the game is not installed. */
	public static synchronized void useNoCatalog () {
		instance = new PortraitCatalog(Optional.empty(), true);
	}

	/** Testing seam — reads from a game directory of the caller's choosing. */
	public static synchronized void useCatalogAt (final File gameDirectory) {
		instance = new PortraitCatalog(Optional.of(gameDirectory), true);
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

	private PortraitCatalog (
		final Optional<File> gameDirectory, final boolean quiet) {

		dataDirectory = gameDirectory
			.map(directory -> new File(directory
				, Environment.getInstance().config().pillarsDataDirectory()))
			.orElse(null);

		portraitDirectory = dataDirectory == null
			? null
			: Optional.of(new File(dataDirectory, PORTRAIT_DIRECTORY))
				.filter(File::isDirectory)
				.orElse(null);

		if (portraitDirectory == null) {
			if (!quiet) {
				logger.info("No portrait directory found; the picker is empty.%n");
			}

			return;
		}

		scan(portraitDirectory, "");
		ordered.sort((a, b) -> {
			final int byCategory = a.category.compareTo(b.category);
			return byCategory != 0
				? byCategory : a.name.compareToIgnoreCase(b.name);
		});
	}

	/**
	 * Walks the portrait folders, pairing each {@code _lg} with its
	 * {@code _sm}. Anything without a partner is skipped and said so: the game
	 * would load it and hand the other slot a blank white texture.
	 */
	private void scan (final File directory, final String category) {
		final File[] entries = directory.listFiles();
		if (entries == null) {
			return;
		}

		for (final File entry : entries) {
			if (entry.isDirectory()) {
				scan(entry, category.isEmpty()
					? entry.getName() : category + "/" + entry.getName());

				continue;
			}

			final String fileName = entry.getName();
			if (!endsWithIgnoreCase(fileName, LARGE_SUFFIX)) {
				continue;
			}

			final String name =
				fileName.substring(0, fileName.length() - LARGE_SUFFIX.length());

			final File small = new File(directory, name + SMALL_SUFFIX);
			if (!small.isFile()) {
				logger.info(
					"Portrait '%s' has no %s counterpart; not offering it.%n"
					, name, SMALL_SUFFIX);

				continue;
			}

			final String prefix = PORTRAIT_DIRECTORY + "/"
				+ (category.isEmpty() ? "" : category + "/");

			final Portrait portrait = new Portrait(
				name, category, prefix + fileName, prefix + small.getName());

			byLargePath.put(normalise(portrait.large), portrait);
			ordered.add(portrait);

			if (!category.isEmpty()) {
				categories.add(category);
			}
		}
	}

	public List<Portrait> all () {
		return Collections.unmodifiableList(ordered);
	}

	public List<String> categories () {
		return Collections.unmodifiableList(new ArrayList<>(categories));
	}

	public int size () {
		return ordered.size();
	}

	/**
	 * The portrait a stored {@code m_textureLargePath} names. Matched
	 * case-insensitively and with either slash: the save writes what the game
	 * wrote, which need not agree with what is on this disk.
	 */
	public Optional<Portrait> lookup (final String largePath) {
		if (largePath == null || largePath.isEmpty()) {
			return Optional.empty();
		}

		return Optional.ofNullable(byLargePath.get(normalise(largePath)));
	}

	/**
	 * Base64 PNG for one portrait file, named the way the save names it.
	 *
	 * <p>The path is resolved and then checked to still sit inside the portrait
	 * directory, so a caller cannot walk out of it — the request crosses the
	 * JCEF bridge from a page, and a page should not be able to read arbitrary
	 * files off the disk.
	 */
	public String imageData (final String relativePath) {
		if (portraitDirectory == null || relativePath == null
			|| relativePath.isEmpty()) {

			return "";
		}

		final Path root = portraitDirectory.toPath().toAbsolutePath().normalize();
		final Path file = dataDirectory.toPath()
			.resolve(relativePath).toAbsolutePath().normalize();

		if (!file.startsWith(root)) {
			logger.error("Refusing to read '%s': outside the portrait directory.%n"
				, relativePath);

			return "";
		}

		if (!file.toFile().isFile()) {
			return "";
		}

		try {
			return Base64.getEncoder().encodeToString(
				FileUtils.readFileToByteArray(file.toFile()));
		} catch (final IOException e) {
			logger.error("Unable to read portrait '%s': %s%n"
				, relativePath, e.getMessage());

			return "";
		}
	}

	private static String normalise (final String path) {
		return path.replace('\\', '/').toLowerCase();
	}

	private static boolean endsWithIgnoreCase (
		final String value, final String suffix) {

		return value.length() >= suffix.length()
			&& value.regionMatches(
				true, value.length() - suffix.length(), suffix, 0, suffix.length());
	}
}
