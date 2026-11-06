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

import org.apache.commons.io.FileUtils;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.environment.GameLanguage;
import uk.me.mantas.eternity.handlers.GetLanguages;
import uk.me.mantas.eternity.handlers.SaveSettings;
import uk.me.mantas.eternity.save.GameText;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * What Settings offers for the language of the game's names, and what
 * choosing one does.
 */
public class GetLanguagesTest extends TestHarness {
	private File game;

	private void language (final String code, final String name, final String label)
		throws IOException {

		final File folder = new File(game, "PillarsOfEternity_Data/data/localized/" + code);
		FileUtils.write(new File(folder, "language.xml")
			, "<Language><Name>" + name + "</Name><GUIString>" + label + "</GUIString></Language>"
			, "UTF-8");
		FileUtils.write(new File(folder, "text/game/characters.stringtable")
			, "<StringTableFile><Entries><Entry><ID>329</ID><DefaultText>" + label
				+ " knight</DefaultText></Entry></Entries></StringTableFile>", "UTF-8");
	}

	@Before
	public void install () throws IOException {
		game = EKUtils.createTempDir(PREFIX).get();
		language("en", "english", "English");
		language("pl", "polish", "Polski");
		language("de", "german", "Deutsch");

		final File settings = new File(EKUtils.createTempDir(PREFIX).get(), "settings.json");
		Environment.getInstance().directory().settingsFile(settings);
		Settings.initialise();
		Settings.getInstance().json.put("gameLocation", game.getAbsolutePath());
	}

	private static JSONObject ask () {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new GetLanguages().onQuery(mock(CefBrowser.class), 0, "true", false, callback);

		final ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
		verify(callback, timeout(5000)).success(reply.capture());
		return new JSONObject(reply.getValue());
	}

	private static void save (final String request) {
		new SaveSettings().onQuery(
			mock(CefBrowser.class), 0, request, false, mock(CefQueryCallback.class));
	}

	@Test
	public void theInstallsLanguagesAreOffered () {
		final JSONObject reply = ask();

		assertEquals(3, reply.getJSONArray("languages").length());
		assertEquals("en", reply.getJSONArray("languages").getJSONObject(0).getString("code"));
		assertEquals("Deutsch", reply.getJSONArray("languages").getJSONObject(1).getString("label"));
		assertEquals("Polski", reply.getJSONArray("languages").getJSONObject(2).getString("label"));
	}

	@Test
	public void withNothingChosenTheGameIsFollowed () {
		GameLanguage.use(() -> Optional.of("polish"));
		final JSONObject reply = ask();

		assertEquals("", reply.getString("chosen"));
		assertEquals("pl", reply.getString("game"));
		assertEquals("pl", reply.getString("shown"));
	}

	@Test
	public void aGameThatSaysNothingIsEnglish () {
		// TestHarness pins the game's own setting off.
		final JSONObject reply = ask();

		assertEquals("", reply.getString("chosen"));
		assertTrue(reply.isNull("game"));
		assertEquals("en", reply.getString("shown"));
	}

	@Test
	public void aChoiceIsShownAsChosen () {
		GameLanguage.use(() -> Optional.of("polish"));
		Settings.getInstance().json.put("language", "de");
		final JSONObject reply = ask();

		assertEquals("de", reply.getString("chosen"));
		assertEquals("pl", reply.getString("game"));
		assertEquals("de", reply.getString("shown"));
	}

	@Test
	public void aChoiceTheInstallLacksIsNoChoice () {
		Settings.getInstance().json.put("language", "fr");
		final JSONObject reply = ask();

		assertEquals("", reply.getString("chosen"));
		assertEquals("en", reply.getString("shown"));
	}

	@Test
	public void withNoInstallThereIsNothingToChoose () {
		Settings.getInstance().json.put("gameLocation", "");
		final JSONObject reply = ask();

		assertEquals(0, reply.getJSONArray("languages").length());
		assertEquals("en", reply.getString("shown"));
	}

	// The names are read once and kept, so choosing a language has to start
	// that again: before, a change of game folder went unnoticed until the
	// editor was restarted.
	@Test
	public void choosingALanguageChangesWhatIsShownAtOnce () {
		GameText.reset();
		assertEquals("en", GameText.getInstance().language());

		save("{\"language\":\"pl\"}");
		assertEquals("pl", GameText.getInstance().language());
		assertEquals("pl", Settings.getInstance().json.getString("language"));

		save("{\"language\":\"\"}");
		assertEquals("en", GameText.getInstance().language());
	}

	@Test
	public void anotherGameFolderIsReadAfresh () throws IOException {
		Settings.getInstance().json.put("language", "pl");
		GameText.reset();
		assertEquals("pl", GameText.getInstance().language());

		// An install with no Polish in it.
		final File other = EKUtils.createTempDir(PREFIX).get();
		assertTrue(new File(other, "PillarsOfEternity_Data/data/localized/en/text/game").mkdirs());
		save(new JSONObject().put("gameLocation", other.getAbsolutePath()).toString());

		assertEquals("en", GameText.getInstance().language());
	}

	@Test
	public void aSettingThatIsNotAboutNamesLeavesThemAlone () {
		GameText.reset();
		final GameText before = GameText.getInstance();

		save("{\"width\":1280,\"savesLocation\":\"somewhere\"}");
		save(new JSONObject().put("gameLocation", game.getAbsolutePath()).toString());

		assertSame(before, GameText.getInstance());
	}
}
