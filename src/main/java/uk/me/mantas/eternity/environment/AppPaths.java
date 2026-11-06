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

package uk.me.mantas.eternity.environment;

import org.apache.commons.io.FileUtils;
import org.cef.OS;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where the editor's own files are, whichever folder it was started from.
 *
 * <ul>
 * <li><b>home</b> — the folder holding the jar: what ships with the app
 *     (the UI, the native browser, the game-data extractor).</li>
 * <li><b>data</b> — the user's own: settings, the log, the extracted game
 *     data. {@code %APPDATA%\Eternity Keeper} on Windows, because an install
 *     under Program Files cannot be written to by a normal user; the
 *     {@code ek.data} system property overrides it (the development launcher
 *     keeps everything in the checkout).</li>
 * </ul>
 *
 * <p>Everything used to be relative to the working directory, which holds
 * only when the app is started from its own folder.
 */
public final class AppPaths {
	public static final String DATA_PROPERTY = "ek.data";
	public static final String UI_PROPERTY = "ek.ui";
	public static final String DEBUG_PORT_PROPERTY = "ek.debugPort";
	public static final String EXTRACTOR_PROPERTY = "ek.extractor";

	private static final String EXTRACTOR = "extract_gamedata";

	private static final String DATA_FOLDER_WINDOWS = "Eternity Keeper";
	private static final String DATA_FOLDER_OTHER = ".eternity-keeper";

	private final File code;
	private final File workingDirectory;
	private final Function<String, String> property;
	private final Function<String, String> environment;
	private final boolean windows;

	private AppPaths (
		final File code, final File workingDirectory
		, final Function<String, String> property, final Function<String, String> environment
		, final boolean windows) {

		this.code = code;
		this.workingDirectory = canonical(workingDirectory);
		this.property = property;
		this.environment = environment;
		this.windows = windows;
	}

	/** The paths of this running copy of the editor. */
	public static AppPaths forThisProcess () {
		File code = null;
		try {
			code = new File(AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (final URISyntaxException | SecurityException | NullPointerException ignored) {
			// No code source (unusual class loader): fall back on the working directory.
		}

		return new AppPaths(code, new File(System.getProperty("user.dir", "."))
			, System::getProperty, System::getenv, OS.isWindows());
	}

	/** For tests: every input the answers depend on. */
	public static AppPaths of (
		final File code, final File workingDirectory
		, final Function<String, String> property, final Function<String, String> environment
		, final boolean windows) {

		return new AppPaths(code, workingDirectory, property, environment, windows);
	}

	/** The folder holding the jar; the working directory when running from classes. */
	public File home () {
		if (code != null && code.isFile()) {
			return canonical(code.getAbsoluteFile().getParentFile());
		}

		return workingDirectory;
	}

	/**
	 * The folder holding {@code index.html}: {@code ek.ui} if set, then
	 * {@code ui} beside the jar (a release), then the checkout's {@code src/ui}
	 * seen from {@code target} (a development build), then from the working
	 * directory (tests, an IDE).
	 */
	public Optional<File> ui () {
		final List<File> candidates = new ArrayList<>();
		final String configured = property.apply(UI_PROPERTY);
		if (configured != null && !configured.isEmpty()) {
			candidates.add(new File(configured));
		}

		candidates.add(new File(home(), "ui"));
		final File parent = home().getParentFile();
		if (parent != null) {
			candidates.add(new File(parent, "src/ui"));
		}
		candidates.add(new File(workingDirectory, "src/ui"));

		return candidates.stream()
			.filter(candidate -> new File(candidate, "index.html").isFile())
			.map(AppPaths::canonical)
			.findFirst();
	}

	/** Where the user's settings, log and extracted game data live. */
	public File data () {
		final String configured = property.apply(DATA_PROPERTY);
		if (configured != null && !configured.isEmpty()) {
			return canonical(new File(configured));
		}

		if (windows) {
			final String appData = environment.apply("APPDATA");
			if (appData != null && !appData.isEmpty()) {
				return canonical(new File(appData, DATA_FOLDER_WINDOWS));
			}
		} else {
			final String userHome = property.apply("user.home");
			if (userHome != null && !userHome.isEmpty()) {
				return canonical(new File(userHome, DATA_FOLDER_OTHER));
			}
		}

		return home();
	}

	public File settingsFile () {
		return new File(data(), "settings.json");
	}

	public File logFile () {
		return new File(data(), "eternity.log");
	}

	/** The embedded browser's own log, which it otherwise drops in the working directory. */
	public File cefLogFile () {
		return new File(data(), "cef.log");
	}

	/** Copies of saves taken before the editor deletes, renames or replaces one. */
	public File backups () {
		return new File(data(), "backups");
	}

	/** Where the game-data extractor writes. */
	public File gameData () {
		return new File(data(), "gamedata");
	}

	/**
	 * Where extracted game data may already be, most likely first: where the
	 * extractor writes now, then the {@code itemdata} folders the editor used
	 * before — beside the jar, in the working directory and beside it.
	 */
	public List<File> gameDataCandidates () {
		final List<File> candidates = new ArrayList<>(Arrays.asList(
			gameData(), new File(home(), "itemdata"), new File(workingDirectory, "itemdata")));

		final File parent = workingDirectory.getParentFile();
		if (parent != null) {
			candidates.add(new File(parent, "itemdata"));
		}

		final List<File> distinct = new ArrayList<>();
		candidates.forEach(candidate -> {
			if (!distinct.contains(candidate)) {
				distinct.add(candidate);
			}
		});

		return distinct;
	}

	/**
	 * The command that runs the game-data extractor, before its arguments:
	 * {@code ek.extractor} if set, then the frozen copy a release ships in
	 * {@code gamedata} beside the jar, then the checkout's script under Python.
	 */
	public Optional<List<String>> extractor () {
		final List<File> candidates = new ArrayList<>();
		final String configured = property.apply(EXTRACTOR_PROPERTY);
		if (configured != null && !configured.isEmpty()) {
			candidates.add(new File(configured));
		} else {
			candidates.add(new File(home(), "gamedata/" + EXTRACTOR + (windows ? ".exe" : "")));
			final File parent = home().getParentFile();
			if (parent != null) {
				candidates.add(new File(parent, "tools/gamedata/" + EXTRACTOR + ".py"));
			}
			candidates.add(new File(workingDirectory, "tools/gamedata/" + EXTRACTOR + ".py"));
		}

		return candidates.stream()
			.filter(File::isFile)
			.map(AppPaths::canonical)
			.findFirst()
			.map(program -> program.getName().endsWith(".py")
				? Arrays.asList("python", program.getPath())
				: Arrays.asList(program.getPath()));
	}

	/**
	 * The embedded browser's remote-debugging port, or 0 for none. The page has
	 * a bridge into Java that reads and writes files, and a debugging port lets
	 * any program on the machine drive that page, so it is off unless
	 * {@code ek.debugPort} asks for it (the UI test scripts do).
	 */
	public int remoteDebuggingPort () {
		final String configured = property.apply(DEBUG_PORT_PROPERTY);
		if (configured == null) {
			return 0;
		}

		try {
			final int port = Integer.parseInt(configured.trim());
			return port > 0 && port <= 65535 ? port : 0;
		} catch (final NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * Copies a {@code settings.json} kept beside an older copy of the editor —
	 * the working directory or the install folder — into the data folder, once.
	 * Never over settings the data folder already has.
	 *
	 * @return whether settings were adopted
	 */
	public boolean adoptLegacySettings () throws IOException {
		final File target = settingsFile();
		if (target.exists()) {
			return false;
		}

		for (final File legacy : Arrays.asList(
			new File(workingDirectory, "settings.json"), new File(home(), "settings.json"))) {

			if (legacy.isFile() && !canonical(legacy).equals(canonical(target))) {
				FileUtils.copyFile(legacy, target);
				return true;
			}
		}

		return false;
	}

	private static File canonical (final File file) {
		try {
			return file.getCanonicalFile();
		} catch (final IOException e) {
			return file.getAbsoluteFile();
		}
	}
}
