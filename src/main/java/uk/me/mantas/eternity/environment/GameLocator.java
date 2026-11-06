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

import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the game, wherever the player actually put it.
 *
 * <p>The saves were never the problem — every desktop store puts them in
 * {@code %USERPROFILE%\Saved Games\Pillars of Eternity}. The <em>install</em>
 * is, because the portraits, the item catalog and the stronghold and identity
 * data all come out of it, and the old search looked only at
 * {@code %SYSTEMDRIVE%} plus four hardcoded paths under Program Files. A Steam
 * library on D: — where this project's own copy lives — was never found.
 *
 * <p>Strategies run in order and the first accepted answer wins:
 *
 * <ol>
 *   <li><b>Steam.</b> Find a Steam root, read {@code libraryfolders.vdf} for
 *       every library on every drive, and in each look for
 *       {@code appmanifest_291650.acf} — which names the install directory
 *       outright, so a renamed folder is still found.</li>
 *   <li><b>GOG</b>, out of its own registry entries.</li>
 *   <li><b>Epic</b>, out of the JSON manifests in ProgramData.</li>
 *   <li><b>Known layouts on every drive</b>, which is the one that actually
 *       fires when the registry is silent — and it is silent more often than
 *       you would think.</li>
 *   <li><b>Microsoft Store</b>: detected and explained, never offered. Its
 *       files sit under WindowsApps behind the {@code gameflt} driver, which
 *       blocks reads even as an administrator.</li>
 * </ol>
 *
 * <p>Every candidate has to contain {@code PillarsOfEternity_Data} before it
 * is accepted. That is what the editor actually reads, so a stale registry
 * entry or a library the game was moved out of is rejected rather than
 * leaving the editor pointed somewhere with no portraits and no explanation.
 */
public class GameLocator {
	private static final Logger logger = Logger.getLogger(GameLocator.class);

	/** Pillars of Eternity on Steam. */
	private static final String STEAM_APP_ID = "291650";

	/** What makes a directory a Pillars install rather than any other folder. */
	private static final String DATA_DIRECTORY = "PillarsOfEternity_Data";

	private static final String GOG_GAMES = "HKLM\\SOFTWARE\\WOW6432Node\\GOG.com\\Games";
	private static final String GOG_GAMES_64 = "HKLM\\SOFTWARE\\GOG.com\\Games";

	private static final String APPX_PACKAGES =
		"HKCU\\Software\\Classes\\Local Settings\\Software\\Microsoft"
		+ "\\Windows\\CurrentVersion\\AppModel\\Repository\\Packages";

	/** Steam roots to try when the registry does not say. */
	private static final List<String> STEAM_FOLDERS = Collections.unmodifiableList(
		Arrays.asList(
			"Steam"
			, "Program Files (x86)\\Steam"
			, "Program Files\\Steam"
			, "SteamLibrary"));

	/** Whole installs, relative to a drive root. */
	private static final List<String> KNOWN_FOLDERS = Collections.unmodifiableList(
		Arrays.asList(
			"Steam\\steamapps\\common\\Pillars of Eternity"
			, "SteamLibrary\\steamapps\\common\\Pillars of Eternity"
			, "Program Files (x86)\\Steam\\steamapps\\common\\Pillars of Eternity"
			, "Program Files\\Steam\\steamapps\\common\\Pillars of Eternity"
			, "GOG Games\\Pillars of Eternity"
			, "Program Files (x86)\\GOG Galaxy\\Games\\Pillars of Eternity"
			, "Program Files (x86)\\GOG Games\\Pillars of Eternity"
			, "Program Files\\GOG Games\\Pillars of Eternity"
			, "Games\\Pillars of Eternity"
			, "Pillars of Eternity"));

	/** {@code "path" "D:\\Steam"} out of a Valve key-values file. */
	private static final Pattern VDF_PATH =
		Pattern.compile("\"path\"\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

	private static final Pattern ACF_INSTALL_DIR =
		Pattern.compile("\"installdir\"\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

	/** Just enough of the Windows registry to ask these questions. */
	public interface Registry {
		Optional<String> value (String key, String name);
		List<String> subKeys (String key);
	}

	/** Where the game is, how that was worked out, and anything worth saying. */
	public static final class Result {
		public final Optional<File> installation;
		/** "Steam", "GOG", "Epic", "known folder", or empty when not found. */
		public final String source;
		/** Things the user should be told even though they are not answers. */
		public final List<String> notes;

		Result (
			final Optional<File> installation
			, final String source
			, final List<String> notes) {

			this.installation = installation;
			this.source = source;
			this.notes = Collections.unmodifiableList(notes);
		}
	}

	private final Registry registry;
	private final List<File> roots;
	private final Map<String, String> variables;
	private final List<String> notes = new ArrayList<>();

	public GameLocator (
		final Registry registry
		, final List<File> roots
		, final Map<String, String> variables) {

		this.registry = registry;
		this.roots = roots;
		this.variables = variables;
	}

	/** The one the app uses: the real registry, the real drives, the real env. */
	public static GameLocator forThisMachine () {
		return new GameLocator(
			new CommandLineRegistry(), Arrays.asList(File.listRoots()), System.getenv());
	}

	private static GameLocator instance = null;

	public static synchronized GameLocator getInstance () {
		if (instance == null) {
			instance = forThisMachine();
		}

		return instance;
	}

	/** Testing seam — answers with a locator of the caller's choosing. */
	public static synchronized void use (final GameLocator locator) {
		instance = locator;
	}

	/**
	 * Testing seam — nowhere to look, so nothing is found. Without this a test
	 * machine with the game installed answers differently from one without,
	 * which is exactly the sort of result that cannot be relied on.
	 */
	public static synchronized void useNoGame () {
		instance = new GameLocator(
			new Registry() {
				@Override
				public Optional<String> value (final String key, final String name) {
					return Optional.empty();
				}

				@Override
				public List<String> subKeys (final String key) {
					return Collections.emptyList();
				}
			}
			, Collections.emptyList()
			, Collections.emptyMap());
	}

	public static synchronized void reset () {
		instance = null;
	}

	public Result locate () {
		notes.clear();

		final Optional<File> steam = fromSteam();
		if (steam.isPresent()) {
			return found(steam, "Steam");
		}

		final Optional<File> gog = fromGOG();
		if (gog.isPresent()) {
			return found(gog, "GOG");
		}

		final Optional<File> epic = fromEpic();
		if (epic.isPresent()) {
			return found(epic, "Epic");
		}

		final Optional<File> known = fromKnownFolders();
		if (known.isPresent()) {
			return found(known, "known folder");
		}

		noteMicrosoftStore();
		return new Result(Optional.empty(), "", notes);
	}

	private Result found (final Optional<File> installation, final String source) {
		logger.info("Found the game via %s at '%s'.%n"
			, source, installation.get().getAbsolutePath());

		return new Result(installation, source, notes);
	}

	// ---- Steam ---------------------------------------------------------------

	private Optional<File> fromSteam () {
		for (final File steam : steamRoots()) {
			for (final File library : steamLibraries(steam)) {
				final Optional<File> installation = steamInstall(library);
				if (installation.isPresent()) {
					return installation;
				}
			}
		}

		return Optional.empty();
	}

	/**
	 * Every plausible Steam root. The registry first, then the usual folders on
	 * every drive — the registry key is missing more often than you would
	 * think, including on machines where Steam is installed and running.
	 */
	private Set<File> steamRoots () {
		final Set<File> found = new LinkedHashSet<>();

		registered("HKCU\\Software\\Valve\\Steam", "SteamPath").ifPresent(found::add);
		registered("HKLM\\SOFTWARE\\WOW6432Node\\Valve\\Steam", "InstallPath")
			.ifPresent(found::add);
		registered("HKLM\\SOFTWARE\\Valve\\Steam", "InstallPath").ifPresent(found::add);

		for (final File root : roots) {
			for (final String folder : STEAM_FOLDERS) {
				final File candidate = new File(root, folder);
				if (candidate.isDirectory()) {
					found.add(candidate);
				}
			}
		}

		// Linux, where Steam is under the home directory rather than a drive.
		final String home = variables.get("HOME");
		if (home != null && !home.isEmpty()) {
			for (final String folder
				: new String[]{".steam/steam", ".local/share/Steam", ".steam/root"}) {

				final File candidate = new File(home, folder);
				if (candidate.isDirectory()) {
					found.add(candidate);
				}
			}
		}

		return found;
	}

	/** A Steam root is a library itself, plus whatever its vdf lists. */
	private Set<File> steamLibraries (final File steam) {
		final Set<File> libraries = new LinkedHashSet<>();
		libraries.add(steam);

		final File vdf = new File(steam, "steamapps/libraryfolders.vdf");
		if (!vdf.isFile()) {
			return libraries;
		}

		final Matcher matcher = VDF_PATH.matcher(read(vdf));
		while (matcher.find()) {
			// The file escapes its backslashes, being a C-style key-values.
			final File library = new File(matcher.group(1).replace("\\\\", "\\"));
			if (library.isDirectory()) {
				libraries.add(library);
			}
		}

		return libraries;
	}

	/**
	 * The game inside one library. The app manifest names the install
	 * directory, so a folder the player renamed is still found; without one,
	 * fall back to what Steam would have called it.
	 */
	private Optional<File> steamInstall (final File library) {
		final File common = new File(library, "steamapps/common");
		final File manifest =
			new File(library, "steamapps/appmanifest_" + STEAM_APP_ID + ".acf");

		if (manifest.isFile()) {
			final Matcher matcher = ACF_INSTALL_DIR.matcher(read(manifest));
			if (matcher.find()) {
				final Optional<File> installation =
					accept(new File(common, matcher.group(1)));

				if (installation.isPresent()) {
					return installation;
				}
			}
		}

		return accept(new File(common, "Pillars of Eternity"));
	}

	// ---- GOG -----------------------------------------------------------------

	private Optional<File> fromGOG () {
		for (final String games : new String[]{GOG_GAMES, GOG_GAMES_64}) {
			for (final String game : registry.subKeys(games)) {
				final String name = registry.value(game, "gameName").orElse("");
				final Optional<String> path = registry.value(game, "path");

				if (!path.isPresent()) {
					continue;
				}

				if (!looksLikePillars(name) && !looksLikePillars(path.get())) {
					continue;
				}

				final Optional<File> installation = accept(new File(path.get()));
				if (installation.isPresent()) {
					return installation;
				}
			}
		}

		return Optional.empty();
	}

	// ---- Epic ----------------------------------------------------------------

	private Optional<File> fromEpic () {
		final String programData = variables.get("PROGRAMDATA");
		if (programData == null || programData.isEmpty()) {
			return Optional.empty();
		}

		final File manifests = new File(
			programData, "Epic/EpicGamesLauncher/Data/Manifests");

		final File[] entries = manifests.listFiles(
			(directory, name) -> name.toLowerCase().endsWith(".item"));

		if (entries == null) {
			return Optional.empty();
		}

		for (final File entry : entries) {
			try {
				final JSONObject manifest = new JSONObject(read(entry));
				if (!looksLikePillars(manifest.optString("DisplayName", ""))) {
					continue;
				}

				final String location = manifest.optString("InstallLocation", "");
				if (location.isEmpty()) {
					continue;
				}

				final Optional<File> installation = accept(new File(location));
				if (installation.isPresent()) {
					return installation;
				}
			} catch (final Exception e) {
				// A manifest we cannot read is one launcher's business, not a
				// reason to stop looking through the others.
				logger.warn("Skipping unreadable Epic manifest '%s': %s%n"
					, entry.getName(), e.getMessage());
			}
		}

		return Optional.empty();
	}

	// ---- the drives themselves -----------------------------------------------

	private Optional<File> fromKnownFolders () {
		for (final File root : roots) {
			for (final String folder : KNOWN_FOLDERS) {
				final Optional<File> installation = accept(new File(root, folder));
				if (installation.isPresent()) {
					return installation;
				}
			}
		}

		return Optional.empty();
	}

	// ---- the one that cannot be offered --------------------------------------

	/**
	 * A Microsoft Store copy is registered like any other appx package, so it
	 * can be detected — but its files live under WindowsApps behind the
	 * {@code gameflt} driver, which refuses reads even to an administrator.
	 * Saying so is more use than an empty box and no explanation.
	 */
	private void noteMicrosoftStore () {
		for (final String key : registry.subKeys(APPX_PACKAGES)) {
			if (!key.toLowerCase().contains("pillarsofeternity")) {
				continue;
			}

			notes.add(
				"Pillars of Eternity looks to be installed from the Microsoft "
				+ "Store. Windows keeps those files under WindowsApps and blocks "
				+ "programs from reading them, so the editor cannot use that copy "
				+ "for portraits or item names. Saves still work; point the game "
				+ "folder at a Steam or GOG copy if you have one.");

			return;
		}
	}

	// ---- shared --------------------------------------------------------------

	/**
	 * A candidate is only the game if the data directory is in it — that is
	 * what the editor actually reads.
	 */
	private Optional<File> accept (final File candidate) {
		if (candidate == null || !candidate.isDirectory()) {
			return Optional.empty();
		}

		if (!new File(candidate, DATA_DIRECTORY).isDirectory()) {
			return Optional.empty();
		}

		// A drive scan can match case-insensitively and hand back "d:\steam";
		// this is going into settings and in front of the user, so spell it the
		// way the filesystem does.
		try {
			return Optional.of(candidate.getCanonicalFile());
		} catch (final IOException e) {
			return Optional.of(candidate);
		}
	}

	private Optional<File> registered (final String key, final String name) {
		return registry.value(key, name)
			.map(String::trim)
			.filter(path -> !path.isEmpty())
			.map(File::new)
			.filter(File::isDirectory);
	}

	private static boolean looksLikePillars (final String text) {
		final String lower = text.toLowerCase();
		return lower.contains("pillars of eternity") || lower.contains("pillarsofeternity");
	}

	private static String read (final File file) {
		try {
			return new String(
				java.nio.file.Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
		} catch (final IOException e) {
			logger.warn("Unable to read '%s': %s%n", file.getAbsolutePath(), e.getMessage());
			return "";
		}
	}

	/**
	 * The registry through {@code reg query}, which is the only way to read it
	 * from Java 8 without a native library. Every failure is "no answer".
	 */
	public static final class CommandLineRegistry implements Registry {
		private static final Pattern VALUE_LINE =
			Pattern.compile("^\\s*\\S+\\s+REG_[A-Z_]+\\s+(.*)$");

		@Override
		public Optional<String> value (final String key, final String name) {
			for (final String line : run("reg", "query", key, "/v", name)) {
				final Matcher matcher = VALUE_LINE.matcher(line);
				if (matcher.matches() && line.toLowerCase().contains(name.toLowerCase())) {
					return Optional.of(matcher.group(1).trim());
				}
			}

			return Optional.empty();
		}

		@Override
		public List<String> subKeys (final String key) {
			final List<String> keys = new ArrayList<>();
			for (final String line : run("reg", "query", key)) {
				final String trimmed = line.trim();
				if (trimmed.toUpperCase().startsWith("HK")
					&& !trimmed.equalsIgnoreCase(key)) {

					keys.add(trimmed);
				}
			}

			return keys;
		}

		private static List<String> run (final String... command) {
			final List<String> output = new ArrayList<>();

			try {
				final Process process = new ProcessBuilder(command)
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
				// No reg command at all: not Windows, and not an error here.
				return Collections.emptyList();
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				return Collections.emptyList();
			}

			return output;
		}
	}
}
