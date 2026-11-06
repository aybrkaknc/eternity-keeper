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


package uk.me.mantas.eternity.tests.handlers;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.mockito.ArgumentCaptor;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.environment.GameLocator;
import uk.me.mantas.eternity.handlers.GetDefaultSaveLocation;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static uk.me.mantas.eternity.environment.Variables.Key.*;

public class GetDefaultSaveLocationTest extends TestHarness {
	private static final String NO_DEFAULT =
		"{\"savesLocation\":\"\",\"gameLocation\":\"\",\"notes\":[]}";
	private static final String JSON_SKELETON =
		"{\"savesLocation\":\"%s\",\"gameLocation\":\"%s\",\"notes\":[]}";

	@Before
	public void setup () {
		super.setup();
		Settings.getInstance().json = new JSONObject();
	}

	@After
	public void restoreLocator () {
		GameLocator.useNoGame();
	}

	@Test
	public void onQueryNoUserProfileTest () {
		final Environment environment = Environment.getInstance();
		final GetDefaultSaveLocation cls = new GetDefaultSaveLocation();
		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		// No USERPROFILE environment variable.
		environment.variables().set(USERPROFILE, null);
		environment.variables().set(SYSTEMDRIVE, null);
		environment.variables().set(XDG_DATA_HOME, null);
		environment.variables().set(HOME, null);
		cls.onQuery(mockBrowser, 0, "", false, mockCallback);
		verify(mockCallback).success(NO_DEFAULT);
	}

	@Test
	public void onQueryNoPillarsSavesTest () {
		final Environment environment = Environment.getInstance();
		final GetDefaultSaveLocation cls = new GetDefaultSaveLocation();
		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		final Optional<File> saveLocation = EKUtils.createTempDir(PREFIX);
		assertTrue(saveLocation.isPresent());

		environment.variables().set(USERPROFILE, saveLocation.get().getAbsolutePath());
		environment.variables().set(SYSTEMDRIVE, "404");
		environment.variables().set(XDG_DATA_HOME, null);
		environment.variables().set(HOME, null);

		// USERPROFILE environment variable is set but no Pillars directory.
		cls.onQuery(mockBrowser, 0, "", false, mockCallback);
		verify(mockCallback).success(NO_DEFAULT);
	}

	@Test
	public void onQueryFoundSaves () {
		final Environment environment = Environment.getInstance();
		final GetDefaultSaveLocation cls = new GetDefaultSaveLocation();
		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		final Optional<File> saveLocation = EKUtils.createTempDir(PREFIX);
		assertTrue(saveLocation.isPresent());

		environment.variables().set(USERPROFILE, saveLocation.get().getAbsolutePath());
		environment.variables().set(SYSTEMDRIVE, "404");
		environment.variables().set(XDG_DATA_HOME, null);
		environment.variables().set(HOME, null);

		final File pillarsSaves =
			saveLocation.get().toPath().resolve("Saved Games\\Pillars of Eternity").toFile();

		assertTrue(pillarsSaves.mkdirs());

		// We actually have a default save directory.
		cls.onQuery(mockBrowser, 0, "", false, mockCallback);
		verify(mockCallback).success(
			String.format(JSON_SKELETON, pillarsSaves.getAbsolutePath().replace("\\", "\\\\"), ""));
	}

	@Test
	public void whateverTheLocatorFindsIsWhatComesBack () throws IOException {
		// Where the game is found is GameLocator's problem and has its own
		// tests; what the handler owes is asking, answering with it, and
		// remembering it in settings so the search does not run again.
		final Environment environment = Environment.getInstance();
		final GetDefaultSaveLocation cls = new GetDefaultSaveLocation();
		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		final Optional<File> drive = EKUtils.createTempDir(PREFIX);
		assertTrue(drive.isPresent());

		final File installation = new File(drive.get(), "Pillars of Eternity");
		assertTrue(new File(installation, "PillarsOfEternity_Data").mkdirs());

		environment.variables().set(USERPROFILE, "404");
		environment.variables().set(SYSTEMDRIVE, null);
		environment.variables().set(XDG_DATA_HOME, null);
		environment.variables().set(HOME, null);

		GameLocator.use(locatorFinding(drive.get()));

		cls.onQuery(mockBrowser, 0, "", false, mockCallback);
		verify(mockCallback).success(
			String.format(
				JSON_SKELETON
				, ""
				, installation.getCanonicalPath().replace("\\", "\\\\")));

		assertEquals(installation.getCanonicalPath()
			, Settings.getInstance().json.getString("gameLocation"));
	}

	// The game's own names are read once and kept, out of the install that
	// settings name. Whatever asked before the install was found was told
	// there is none; finding it has to start that again.
	@Test
	public void theGamesTextIsReadOnceTheGameIsFound () throws IOException {
		final Environment environment = Environment.getInstance();
		final Optional<File> drive = EKUtils.createTempDir(PREFIX);
		assertTrue(drive.isPresent());

		final File installation = new File(drive.get(), "Pillars of Eternity");
		org.apache.commons.io.FileUtils.write(new File(installation
			, "PillarsOfEternity_Data/data/localized/en/text/game/characters.stringtable")
			, "<StringTableFile><Entries><Entry><ID>329</ID><DefaultText>Crucible Knight"
				+ "</DefaultText></Entry></Entries></StringTableFile>", "UTF-8");

		environment.variables().set(USERPROFILE, "404");
		environment.variables().set(SYSTEMDRIVE, null);
		environment.variables().set(XDG_DATA_HOME, null);
		environment.variables().set(HOME, null);

		uk.me.mantas.eternity.save.GameText.reset();
		assertEquals(Optional.empty(), uk.me.mantas.eternity.save.GameText.getInstance().lookup(
			uk.me.mantas.eternity.game.DatabaseString.StringTableType.Characters, 329));

		GameLocator.use(locatorFinding(drive.get()));
		new GetDefaultSaveLocation().onQuery(
			mock(CefBrowser.class), 0, "", false, mock(CefQueryCallback.class));

		assertEquals(Optional.of("Crucible Knight")
			, uk.me.mantas.eternity.save.GameText.getInstance().lookup(
				uk.me.mantas.eternity.game.DatabaseString.StringTableType.Characters, 329));
		uk.me.mantas.eternity.save.GameText.useNoText();
	}

	@Test
	public void aStoreThatCannotBeUsedIsExplainedRatherThanIgnored () {
		// The Microsoft Store copy is detectable but unreadable, so the answer
		// is empty and the reason travels with it.
		final Environment environment = Environment.getInstance();
		final GetDefaultSaveLocation cls = new GetDefaultSaveLocation();
		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		environment.variables().set(USERPROFILE, "404");
		environment.variables().set(SYSTEMDRIVE, null);
		environment.variables().set(XDG_DATA_HOME, null);
		environment.variables().set(HOME, null);

		GameLocator.use(new GameLocator(
			new GameLocator.Registry() {
				@Override
				public Optional<String> value (final String key, final String name) {
					return Optional.empty();
				}

				@Override
				public List<String> subKeys (final String key) {
					return key.contains("AppModel")
						? Collections.singletonList(
							key + "\\ParadoxInteractive.PillarsofEternity-Microsof_1.0")
						: Collections.emptyList();
				}
			}
			, Collections.emptyList()
			, new HashMap<>()));

		cls.onQuery(mockBrowser, 0, "", false, mockCallback);

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(response.capture());

		final JSONObject json = new JSONObject(response.getValue());
		assertEquals("", json.getString("gameLocation"));
		assertEquals(1, json.getJSONArray("notes").length());
		assertTrue(json.getJSONArray("notes").getString(0).contains("Microsoft Store"));
	}

	/** A locator looking at one "drive" with the game sitting on it. */
	private GameLocator locatorFinding (final File drive) {
		return new GameLocator(
			emptyRegistry(), Collections.singletonList(drive), new HashMap<>());
	}

	private GameLocator.Registry emptyRegistry () {
		return new GameLocator.Registry() {
			@Override
			public Optional<String> value (final String key, final String name) {
				return Optional.empty();
			}

			@Override
			public List<String> subKeys (final String key) {
				return Collections.emptyList();
			}
		};
	}

	@Test
	public void findsLinuxSaveDirectory () {
		final Environment environment = Environment.getInstance();
		final GetDefaultSaveLocation cls = new GetDefaultSaveLocation();
		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		final Optional<File> saveLocation = EKUtils.createTempDir(PREFIX);
		assertTrue(saveLocation.isPresent());

		environment.variables().set(USERPROFILE, null);
		environment.variables().set(SYSTEMDRIVE, null);
		environment.variables().set(XDG_DATA_HOME, saveLocation.get().getAbsolutePath());

		final File pillarsSaves =
			saveLocation.get().toPath().resolve("PillarsOfEternity/SavedGames").toFile();

		assertTrue(pillarsSaves.mkdirs());

		cls.onQuery(mockBrowser, 0, "", false, mockCallback);
		verify(mockCallback).success(
			String.format(JSON_SKELETON, pillarsSaves.getAbsolutePath().replace("\\", "\\\\"), ""));

		final File localPillarsSaves =
			saveLocation.get().toPath()
				.resolve(".local/share/PillarsOfEternity/SavedGames")
				.toFile();

		assertTrue(localPillarsSaves.mkdirs());

		environment.variables().set(XDG_DATA_HOME, null);
		environment.variables().set(HOME, saveLocation.get().getAbsolutePath());

		cls.onQuery(mockBrowser, 0, "", false, mockCallback);
		verify(mockCallback).success(
			String.format(
				JSON_SKELETON
				, localPillarsSaves.getAbsolutePath().replace("\\", "\\\\")
				, ""));
	}

	@Test
	public void findsMacSaveDirectory () {
		// macOS keeps them under Application Support. Written from the
		// roadmap's note rather than from a Mac -- there is none here, and the
		// app cannot start there yet anyway -- so both spellings are accepted
		// and this pins whichever one exists being picked up.
		final Environment environment = Environment.getInstance();
		final GetDefaultSaveLocation cls = new GetDefaultSaveLocation();
		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		final Optional<File> home = EKUtils.createTempDir(PREFIX);
		assertTrue(home.isPresent());

		environment.variables().set(USERPROFILE, null);
		environment.variables().set(SYSTEMDRIVE, null);
		environment.variables().set(XDG_DATA_HOME, null);
		environment.variables().set(HOME, home.get().getAbsolutePath());

		final File saves = home.get().toPath()
			.resolve("Library/Application Support/Pillars of Eternity/SavedGames")
			.toFile();

		assertTrue(saves.mkdirs());

		cls.onQuery(mockBrowser, 0, "", false, mockCallback);
		verify(mockCallback).success(
			String.format(
				JSON_SKELETON
				, saves.getAbsolutePath().replace("\\", "\\\\")
				, ""));
	}

	@Test
	public void findsLinuxGameDirectory () throws IOException {
		// Steam under the home directory rather than on a drive. The locator
		// reads HOME out of the environment it is handed, which in production
		// is System.getenv().
		final Environment environment = Environment.getInstance();
		final GetDefaultSaveLocation cls = new GetDefaultSaveLocation();
		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		final Optional<File> home = EKUtils.createTempDir(PREFIX);
		assertTrue(home.isPresent());

		final File installation = new File(
			home.get(), ".steam/steam/steamapps/common/Pillars of Eternity");

		assertTrue(new File(installation, "PillarsOfEternity_Data").mkdirs());

		environment.variables().set(USERPROFILE, null);
		environment.variables().set(SYSTEMDRIVE, null);
		environment.variables().set(XDG_DATA_HOME, null);
		environment.variables().set(HOME, home.get().getAbsolutePath());

		final Map<String, String> variables = new HashMap<>();
		variables.put("HOME", home.get().getAbsolutePath());
		GameLocator.use(new GameLocator(
			emptyRegistry(), Collections.emptyList(), variables));

		cls.onQuery(mockBrowser, 0, "", false, mockCallback);
		verify(mockCallback).success(
			String.format(
				JSON_SKELETON
				, ""
				, installation.getCanonicalPath().replace("\\", "\\\\")));
	}
}
