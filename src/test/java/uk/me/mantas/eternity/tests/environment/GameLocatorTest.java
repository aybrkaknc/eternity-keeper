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

package uk.me.mantas.eternity.tests.environment;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.GameLocator;
import uk.me.mantas.eternity.environment.GameLocator.Registry;
import uk.me.mantas.eternity.environment.GameLocator.Result;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

// Finding the game, wherever the player actually put it.
//
// The old search looked at %SYSTEMDRIVE% and four hardcoded paths under
// Program Files, so a Steam library on D: -- which is where this project's own
// copy lives -- was never found. That matters more than it sounds: the saves
// are always in %USERPROFILE%\Saved Games and were fine, but portraits, the
// item catalog and the stronghold and identity data all come out of the
// install.
//
// The registry cannot be relied on either. On the development machine here
// Steam is installed and running from D:\Steam and there is no
// HKCU\Software\Valve\Steam key at all, so the drive scan is not a
// last-resort nicety, it is the path that actually fires.
public class GameLocatorTest extends TestHarness {
	private static final String DATA = "PillarsOfEternity_Data";

	/** A registry that answers from a map, or not at all. */
	private static final class FakeRegistry implements Registry {
		private final Map<String, String> values = new HashMap<>();
		private final Map<String, List<String>> subKeys = new HashMap<>();

		FakeRegistry value (final String key, final String name, final String value) {
			values.put(key.toLowerCase() + "|" + name.toLowerCase(), value);
			return this;
		}

		FakeRegistry subKeys (final String key, final String... keys) {
			subKeys.put(key.toLowerCase(), Arrays.asList(keys));
			return this;
		}

		@Override
		public Optional<String> value (final String key, final String name) {
			return Optional.ofNullable(values.get(key.toLowerCase() + "|" + name.toLowerCase()));
		}

		@Override
		public List<String> subKeys (final String key) {
			return subKeys.getOrDefault(key.toLowerCase(), Collections.emptyList());
		}
	}

	// Canonical, because that is what the locator answers with. Temp can be an
	// 8.3 short path (C:\Users\RUNNER~1\... on GitHub's runners), and the long
	// name the locator hands back is the same folder spelled differently.
	private File tempDir () throws IOException {
		final Optional<File> directory = EKUtils.createTempDir(PREFIX);
		assertTrue(directory.isPresent());
		return directory.get().getCanonicalFile();
	}

	/** A directory the editor would accept: it has the data folder in it. */
	private File install (final File parent, final String name) {
		final File installation = new File(parent, name);
		assertTrue(new File(installation, DATA).mkdirs());
		return installation;
	}

	/** A Steam root with a library list and, optionally, the game itself. */
	private File steamRoot (final File parent, final String... libraries)
		throws IOException {

		final File steam = new File(parent, "Steam");
		final File steamapps = new File(steam, "steamapps");
		assertTrue(steamapps.mkdirs());

		final StringBuilder vdf = new StringBuilder("\"libraryfolders\"\n{\n");
		for (int i = 0; i < libraries.length; i++) {
			vdf.append("\t\"").append(i).append("\"\n\t{\n")
				.append("\t\t\"path\"\t\t\"")
				.append(libraries[i].replace("\\", "\\\\"))
				.append("\"\n\t\t\"label\"\t\t\"\"\n\t}\n");
		}

		vdf.append("}\n");
		FileUtils.write(new File(steamapps, "libraryfolders.vdf"), vdf.toString(), "UTF-8");
		return steam;
	}

	private void appManifest (final File library, final String installDir)
		throws IOException {

		FileUtils.write(
			new File(library, "steamapps/appmanifest_291650.acf")
			, "\"AppState\"\n{\n\t\"appid\"\t\t\"291650\"\n"
				+ "\t\"name\"\t\t\"Pillars of Eternity\"\n"
				+ "\t\"installdir\"\t\t\"" + installDir + "\"\n}\n"
			, "UTF-8");
	}

	private GameLocator locator (final Registry registry, final File... roots) {
		return new GameLocator(registry, Arrays.asList(roots), new HashMap<>());
	}

	// ---- Steam --------------------------------------------------------------

	@Test
	public void itFollowsSteamsOwnRecordOfWhereTheGameIs () throws IOException {
		// The app manifest is the authoritative answer: it names the install
		// directory, which need not be "Pillars of Eternity" at all.
		final File drive = tempDir();
		final File steam = steamRoot(drive, steam(drive));
		appManifest(steam, "PoE Renamed");
		install(new File(steam, "steamapps/common"), "PoE Renamed");

		final Result result = locator(
			new FakeRegistry().value(
				"HKCU\\Software\\Valve\\Steam", "SteamPath", steam.getAbsolutePath())
			).locate();

		assertTrue(result.installation.isPresent());
		assertEquals("PoE Renamed", result.installation.get().getName());
		assertEquals("Steam", result.source);
	}

	@Test
	public void aLibraryOnAnotherDriveIsFound () throws IOException {
		// The whole point of the exercise: Steam installed on one drive, the
		// game in a library on another.
		final File systemDrive = tempDir();
		final File otherDrive = tempDir();

		final File library = new File(otherDrive, "SteamLibrary");
		assertTrue(new File(library, "steamapps/common").mkdirs());
		appManifest(library, "Pillars of Eternity");
		install(new File(library, "steamapps/common"), "Pillars of Eternity");

		final File steam = steamRoot(systemDrive, steam(systemDrive)
			, library.getAbsolutePath());

		final Result result = locator(
			new FakeRegistry().value(
				"HKCU\\Software\\Valve\\Steam", "SteamPath", steam.getAbsolutePath())
			).locate();

		assertTrue(result.installation.isPresent());
		assertTrue(result.installation.get().getAbsolutePath()
			.startsWith(otherDrive.getAbsolutePath()));
	}

	@Test
	public void aLibraryWithNoManifestStillGetsTheUsualFolder () throws IOException {
		final File drive = tempDir();
		final File steam = steamRoot(drive, steam(drive));
		install(new File(steam, "steamapps/common"), "Pillars of Eternity");

		final Result result = locator(
			new FakeRegistry().value(
				"HKCU\\Software\\Valve\\Steam", "SteamPath", steam.getAbsolutePath())
			).locate();

		assertTrue(result.installation.isPresent());
		assertEquals("Pillars of Eternity", result.installation.get().getName());
	}

	@Test
	public void aFolderWithoutTheGameInItIsNotTheGame () throws IOException {
		// A stale manifest, or a library the player moved the game out of.
		// Accepting it would leave the editor with no portraits and no catalog
		// and no idea why.
		final File drive = tempDir();
		final File steam = steamRoot(drive, steam(drive));
		appManifest(steam, "Pillars of Eternity");
		assertTrue(new File(steam, "steamapps/common/Pillars of Eternity").mkdirs());

		final Result result = locator(
			new FakeRegistry().value(
				"HKCU\\Software\\Valve\\Steam", "SteamPath", steam.getAbsolutePath())
			).locate();

		assertFalse(result.installation.isPresent());
	}

	// ---- when the registry says nothing -------------------------------------

	@Test
	public void withNoRegistryAtAllTheDrivesAreSearched () throws IOException {
		// This is the development machine's own case: Steam runs from D:\Steam
		// and there is no HKCU\Software\Valve\Steam key to be found.
		final File drive = tempDir();
		final File steam = steamRoot(drive, steam(drive));
		appManifest(steam, "Pillars of Eternity");
		install(new File(steam, "steamapps/common"), "Pillars of Eternity");

		final Result result = locator(new FakeRegistry(), drive).locate();

		assertTrue(result.installation.isPresent());
		assertEquals("Steam", result.source);
	}

	@Test
	public void aPlainInstallFolderOnAnyDriveIsFound () throws IOException {
		final File drive = tempDir();
		install(new File(drive, "GOG Games"), "Pillars of Eternity");

		final Result result = locator(new FakeRegistry(), drive).locate();

		assertTrue(result.installation.isPresent());
		assertEquals("known folder", result.source);
	}

	// ---- the other stores ---------------------------------------------------

	@Test
	public void gogIsReadOutOfItsOwnRegistryEntries () throws IOException {
		final File drive = tempDir();
		final File installation = install(drive, "Pillars of Eternity");

		final FakeRegistry registry = new FakeRegistry()
			.subKeys("HKLM\\SOFTWARE\\WOW6432Node\\GOG.com\\Games"
				, "HKLM\\SOFTWARE\\WOW6432Node\\GOG.com\\Games\\1207666843"
				, "HKLM\\SOFTWARE\\WOW6432Node\\GOG.com\\Games\\1495134320")
			.value("HKLM\\SOFTWARE\\WOW6432Node\\GOG.com\\Games\\1207666843"
				, "gameName", "Some Other Game")
			.value("HKLM\\SOFTWARE\\WOW6432Node\\GOG.com\\Games\\1207666843"
				, "path", new File(drive, "Some Other Game").getAbsolutePath())
			.value("HKLM\\SOFTWARE\\WOW6432Node\\GOG.com\\Games\\1495134320"
				, "gameName", "Pillars of Eternity - Definitive Edition")
			.value("HKLM\\SOFTWARE\\WOW6432Node\\GOG.com\\Games\\1495134320"
				, "path", installation.getAbsolutePath());

		final Result result = new GameLocator(
			registry, Collections.emptyList(), new HashMap<>()).locate();

		assertTrue(result.installation.isPresent());
		assertEquals(installation.getAbsolutePath()
			, result.installation.get().getAbsolutePath());
		assertEquals("GOG", result.source);
	}

	@Test
	public void epicIsReadOutOfItsManifestFiles () throws IOException {
		final File programData = tempDir();
		final File drive = tempDir();
		final File installation = install(drive, "PillarsOfEternity");

		final File manifests =
			new File(programData, "Epic/EpicGamesLauncher/Data/Manifests");

		assertTrue(manifests.mkdirs());
		FileUtils.write(new File(manifests, "AAAA.item")
			, "{\"DisplayName\":\"Fortnite\",\"InstallLocation\":\"X:\\\\nope\"}", "UTF-8");
		FileUtils.write(new File(manifests, "BBBB.item")
			, "{\"DisplayName\":\"Pillars of Eternity\",\"InstallLocation\":"
				+ jsonPath(installation) + "}", "UTF-8");

		final Map<String, String> variables = new HashMap<>();
		variables.put("PROGRAMDATA", programData.getAbsolutePath());

		final Result result = new GameLocator(
			new FakeRegistry(), Collections.emptyList(), variables).locate();

		assertTrue(result.installation.isPresent());
		assertEquals(installation.getAbsolutePath()
			, result.installation.get().getAbsolutePath());
		assertEquals("Epic", result.source);
	}

	@Test
	public void aBrokenManifestIsSteppedOver () throws IOException {
		final File programData = tempDir();
		final File manifests =
			new File(programData, "Epic/EpicGamesLauncher/Data/Manifests");

		assertTrue(manifests.mkdirs());
		FileUtils.write(new File(manifests, "AAAA.item"), "not json at all", "UTF-8");

		final Map<String, String> variables = new HashMap<>();
		variables.put("PROGRAMDATA", programData.getAbsolutePath());

		final Result result = new GameLocator(
			new FakeRegistry(), Collections.emptyList(), variables).locate();

		assertFalse(result.installation.isPresent());
	}

	// ---- nothing found ------------------------------------------------------

	@Test
	public void findingNothingIsNotAFailure () throws IOException {
		final Result result = locator(new FakeRegistry(), tempDir()).locate();

		assertFalse(result.installation.isPresent());
		assertEquals("", result.source);
		assertNotNull(result.notes);
	}

	@Test
	public void theMicrosoftStoreIsExplainedRatherThanOffered () {
		// Its files sit under WindowsApps behind the gameflt driver, which
		// blocks reads even as an administrator. Saying so beats an empty box.
		final FakeRegistry registry = new FakeRegistry().subKeys(
			"HKCU\\Software\\Classes\\Local Settings\\Software\\Microsoft"
			+ "\\Windows\\CurrentVersion\\AppModel\\Repository\\Packages"
			, "...\\Packages\\ParadoxInteractive.PillarsofEternity-Microsof_1.2.6.0_x64__zfnrdv");

		final Result result = new GameLocator(
			registry, Collections.emptyList(), new HashMap<>()).locate();

		assertFalse(result.installation.isPresent());
		assertEquals(1, result.notes.size());
		assertTrue(result.notes.get(0).toLowerCase().contains("microsoft store"));
	}

	private static String steam (final File drive) {
		return new File(drive, "Steam").getAbsolutePath();
	}

	private static String jsonPath (final File file) {
		return "\"" + file.getAbsolutePath().replace("\\", "\\\\") + "\"";
	}
}
