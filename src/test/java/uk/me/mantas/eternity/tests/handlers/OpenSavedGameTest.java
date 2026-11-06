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

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import org.apache.commons.io.FileUtils;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.handlers.OpenSavedGame;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.*;

public class OpenSavedGameTest extends TestHarness {
	@Test
	public void requestFileNotExists () {
		CefBrowser mockBrowser = mock(CefBrowser.class);
		CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		OpenSavedGame cls = new OpenSavedGame();

		cls.onQuery(mockBrowser, 0, "404", false, mockCallback);
		verify(mockCallback).success("{\"error\":\"NOT_EXISTS\"}");
	}

	@Test
	public void requestFileExists () throws IOException {
		Environment mockEnvironment = mockEnvironment();
		CefBrowser mockBrowser = mock(CefBrowser.class);
		CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		ExecutorService mockWorkers = mock(ExecutorService.class);
		OpenSavedGame cls = new OpenSavedGame();

		when(mockEnvironment.workers()).thenReturn(mockWorkers);

		File tmp = File.createTempFile(PREFIX, "OpenSavedGameTest");
		cls.onQuery(mockBrowser, 0, tmp.getAbsolutePath(), false, mockCallback);

		verify(mockWorkers).execute(anyObject());
	}

	@Test
	public void openDirectSavegameArchiveAttachesOpenedSaveInfo () throws Exception {
		final Environment environment = Environment.getInstance();
		final File workingDirectory = EKUtils.createTempDir(PREFIX).get();
		environment.directory().working(workingDirectory);

		final File settingsFile = new File(workingDirectory, "settings.json");
		FileUtils.writeStringToFile(settingsFile, "{}", "UTF-8");
		Settings.initialise();

		final File templateArchive = new File(
			getClass().getResource("/SaveGameExtractorTest/guid systemname.savegame").toURI());
		final File mobileObjects = new File(
			getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame/MobileObjects.save").toURI());

		final File archiveDir = EKUtils.createTempDir(PREFIX).get();
		final File archiveFile = new File(archiveDir, "guid 0 Encampment.savegame");
		FileUtils.copyFile(templateArchive, archiveFile);
		new ZipFile(archiveFile).addFile(mobileObjects, new ZipParameters());

		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final OpenSavedGame handler = new OpenSavedGame();

		handler.onQuery(mockBrowser, 0, archiveFile.getAbsolutePath(), false, mockCallback);
		Environment.joinAllWorkers();

		final ArgumentCaptor<String> replyCaptor = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(replyCaptor.capture());

		final JSONObject reply = new JSONObject(replyCaptor.getValue());
		assertTrue(reply.has("openedSaveInfo"));
		assertTrue(reply.has("characters"));
		assertTrue(reply.getJSONArray("characters").length() > 0);

		final JSONObject saveInfo = reply.getJSONObject("openedSaveInfo");
		assertEquals("Elenor", saveInfo.getString("playerName"));
		assertEquals("Encampment", saveInfo.getString("sceneTitle"));
	}
}
