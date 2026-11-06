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

package uk.me.mantas.eternity.tests.environment;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.AppPaths;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Where the editor's own files are, whichever folder it was started from.
 *
 * <p>Everything used to be relative to the working directory: the UI at
 * {@code src/ui}, {@code settings.json}, {@code eternity.log} and the game data.
 * That holds when the app is started from its own folder and nowhere else — a
 * shortcut with a different "Start in", or an install under Program Files
 * (which a normal user cannot write to), and nothing opens or nothing saves.
 */
public class AppPathsTest extends TestHarness {
	private File root;
	private final Map<String, String> properties = new HashMap<>();
	private final Map<String, String> environment = new HashMap<>();

	@Before
	public void makeRoot () {
		root = EKUtils.createTempDir(PREFIX).get();
		properties.clear();
		environment.clear();
	}

	private File dir (final String path) {
		final File dir = new File(root, path);
		assertTrue(dir.mkdirs() || dir.isDirectory());
		return dir;
	}

	private File file (final String path, final String contents) throws Exception {
		final File file = new File(root, path);
		FileUtils.writeStringToFile(file, contents, StandardCharsets.UTF_8);
		return file;
	}

	/** An install: the jar, and the UI beside it, started from somewhere else. */
	private AppPaths installed () throws Exception {
		file("Eternity Keeper/eternity-keeper.jar", "jar");
		file("Eternity Keeper/ui/index.html", "<html>");
		return paths(new File(root, "Eternity Keeper/eternity-keeper.jar"), dir("Desktop"), true);
	}

	private AppPaths paths (final File code, final File workingDirectory, final boolean windows) {
		return AppPaths.of(code, workingDirectory, properties::get, environment::get, windows);
	}

	@Test
	public void theHomeIsTheFolderHoldingTheJarNotTheWorkingDirectory () throws Exception {
		assertEquals(new File(root, "Eternity Keeper").getCanonicalFile(), installed().home());
	}

	@Test
	public void theUiIsFoundBesideTheJar () throws Exception {
		assertEquals(new File(root, "Eternity Keeper/ui").getCanonicalFile(), installed().ui().get());
	}

	/** Running target/eternity-keeper.jar from a checkout finds src/ui. */
	@Test
	public void aDevelopmentBuildFindsTheSourceUi () throws Exception {
		file("repo/target/eternity-keeper.jar", "jar");
		file("repo/src/ui/index.html", "<html>");
		final AppPaths paths = paths(new File(root, "repo/target/eternity-keeper.jar"), dir("elsewhere"), true);

		assertEquals(new File(root, "repo/src/ui").getCanonicalFile(), paths.ui().get());
	}

	/** Tests and IDEs run from a classes folder; the working directory is the checkout. */
	@Test
	public void runningFromClassesUsesTheWorkingDirectory () throws Exception {
		file("repo/src/ui/index.html", "<html>");
		final AppPaths paths = paths(dir("repo/target/classes"), new File(root, "repo"), true);

		assertEquals(new File(root, "repo/src/ui").getCanonicalFile(), paths.ui().get());
	}

	@Test
	public void noUiAnywhereIsReportedNotGuessed () throws Exception {
		file("Eternity Keeper/eternity-keeper.jar", "jar");
		assertFalse(paths(new File(root, "Eternity Keeper/eternity-keeper.jar"), dir("Desktop"), true)
			.ui().isPresent());
	}

	@Test
	public void userDataLivesInAppDataOnWindows () throws Exception {
		environment.put("APPDATA", new File(root, "Roaming").getAbsolutePath());
		final AppPaths paths = installed();

		final File data = new File(root, "Roaming/Eternity Keeper").getCanonicalFile();
		assertEquals(data, paths.data());
		assertEquals(new File(data, "settings.json"), paths.settingsFile());
		assertEquals(new File(data, "eternity.log"), paths.logFile());
		assertEquals(new File(data, "cef.log"), paths.cefLogFile());
		assertEquals(new File(data, "gamedata"), paths.gameData());
	}

	@Test
	public void userDataLivesInTheHomeFolderElsewhere () throws Exception {
		properties.put("user.home", new File(root, "someone").getAbsolutePath());
		file("app/eternity-keeper.jar", "jar");
		final AppPaths paths = paths(new File(root, "app/eternity-keeper.jar"), dir("x"), false);

		assertEquals(new File(root, "someone/.eternity-keeper").getCanonicalFile(), paths.data());
	}

	/** The development launcher keeps settings and the log in the checkout. */
	@Test
	public void anExplicitDataFolderWins () throws Exception {
		environment.put("APPDATA", new File(root, "Roaming").getAbsolutePath());
		properties.put("ek.data", new File(root, "repo").getAbsolutePath());

		assertEquals(new File(root, "repo").getCanonicalFile(), installed().data());
	}

	@Test
	public void withoutAppDataTheDataFolderIsTheHome () throws Exception {
		assertEquals(installed().home(), installed().data());
	}

	@Test
	public void gameDataIsLookedForWhereItIsWrittenFirstThenTheOldPlaces () throws Exception {
		environment.put("APPDATA", new File(root, "Roaming").getAbsolutePath());
		final AppPaths paths = installed();
		final File desktop = new File(root, "Desktop").getCanonicalFile();

		assertEquals(Arrays.asList(
			new File(root, "Roaming/Eternity Keeper/gamedata").getCanonicalFile()
			, new File(root, "Eternity Keeper/itemdata").getCanonicalFile()
			, new File(desktop, "itemdata")
			, new File(desktop.getParentFile(), "itemdata")
		), paths.gameDataCandidates());
	}

	/**
	 * The page has a bridge into Java that writes files, and a debugging port
	 * lets any program on the machine drive that page. Off unless asked for.
	 */
	@Test
	public void theRemoteDebuggingPortIsOffUnlessAskedFor () throws Exception {
		assertEquals(0, installed().remoteDebuggingPort());

		properties.put("ek.debugPort", "13002");
		assertEquals(13002, installed().remoteDebuggingPort());

		properties.put("ek.debugPort", "not a port");
		assertEquals(0, installed().remoteDebuggingPort());

		properties.put("ek.debugPort", "70000");
		assertEquals(0, installed().remoteDebuggingPort());
	}

	/** Settings from a copy that kept them beside the jar come along once. */
	@Test
	public void settingsKeptBesideAnOlderCopyAreAdopted () throws Exception {
		environment.put("APPDATA", new File(root, "Roaming").getAbsolutePath());
		final AppPaths paths = installed();
		file("Desktop/settings.json", "{\"savesLocation\":\"C:\\\\saves\"}");

		assertTrue(paths.adoptLegacySettings());
		assertEquals("{\"savesLocation\":\"C:\\\\saves\"}"
			, FileUtils.readFileToString(paths.settingsFile(), StandardCharsets.UTF_8));

		file("Desktop/settings.json", "{\"savesLocation\":\"changed\"}");
		assertFalse("never over settings the app already has", paths.adoptLegacySettings());
		assertTrue(FileUtils.readFileToString(paths.settingsFile(), StandardCharsets.UTF_8)
			.contains("C:\\\\saves"));
	}

	@Test
	public void nothingToAdoptIsNotAnError () throws Exception {
		environment.put("APPDATA", new File(root, "Roaming").getAbsolutePath());
		final AppPaths paths = installed();
		assertFalse(paths.adoptLegacySettings());
		assertFalse(paths.settingsFile().exists());
	}

	/** A release ships the extractor frozen, in a folder of its own beside the jar. */
	@Test
	public void aReleaseRunsTheFrozenExtractor () throws Exception {
		final AppPaths paths = installed();
		final File exe = file("Eternity Keeper/gamedata/extract_gamedata.exe", "exe");
		assertEquals(Arrays.asList(exe.getCanonicalPath()), paths.extractor().get());
	}

	/** A checkout has no frozen copy, so the script runs under Python. */
	@Test
	public void aDevelopmentBuildRunsTheScript () throws Exception {
		file("checkout/target/eternity-keeper.jar", "jar");
		final File script = file("checkout/tools/gamedata/extract_gamedata.py", "py");
		final AppPaths paths = paths(new File(root, "checkout/target/eternity-keeper.jar")
			, dir("elsewhere"), true);

		assertEquals(Arrays.asList("python", script.getCanonicalPath()), paths.extractor().get());
	}

	@Test
	public void anExplicitExtractorWins () throws Exception {
		installed();
		file("Eternity Keeper/gamedata/extract_gamedata.exe", "exe");
		final File mine = file("mine/extract.py", "py");
		properties.put("ek.extractor", mine.getAbsolutePath());

		assertEquals(Arrays.asList("python", mine.getCanonicalPath()), installed().extractor().get());
	}

	@Test
	public void noExtractorIsReportedNotGuessed () throws Exception {
		assertFalse(installed().extractor().isPresent());

		properties.put("ek.extractor", new File(root, "missing.exe").getAbsolutePath());
		assertFalse("a setting naming nothing is not an extractor"
			, installed().extractor().isPresent());
	}
}
