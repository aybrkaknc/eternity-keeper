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
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.handlers.ResurrectCharacter;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Optional;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// The success path (donor scan + transplant) is covered end-to-end by
// ResurrectorTest; these tests cover the handler's request wiring and its
// user-facing failure messages.
public class ResurrectCharacterTest extends TestHarness {

	private File setupSave () throws URISyntaxException, IOException {
		final File resources = new File(getClass().getResource("/").toURI());
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		final File saveDir = new File(workingDir.get(), "cadena 0 Dead.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), saveDir);
		return saveDir;
	}

	private void mockSavesLocation (final File dir) {
		final Settings settings = mockSettings();
		settings.json = new JSONObject();
		settings.json.put("savesLocation", dir.getAbsolutePath());
	}

	private JSONObject request (final File saveDir, final String companion) {
		final JSONObject request = new JSONObject();
		request.put("oldSave", saveDir.getAbsolutePath());
		request.put("savedYet", false);
		request.put("companion", companion);
		return request;
	}

	@Test
	public void failsWhenNoDonorExists () throws IOException, URISyntaxException {
		final File saveDir = setupSave();
		final Optional<File> emptySaves = EKUtils.createTempDir(PREFIX);
		assertTrue(emptySaves.isPresent());
		mockSavesLocation(emptySaves.get());

		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		new ResurrectCharacter().onQuery(
			mockBrowser, 0, request(saveDir, "Eder").toString(), false, mockCallback);

		verify(mockCallback, timeout(60000))
			.failure(eq(-1), contains("No other save from this playthrough contains Eder"));
	}

	@Test
	public void failsForUnknownCompanion () throws IOException, URISyntaxException {
		final File saveDir = setupSave();

		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		new ResurrectCharacter().onQuery(
			mockBrowser, 0, request(saveDir, "Calisca").toString(), false, mockCallback);

		verify(mockCallback, timeout(60000))
			.failure(eq(-1), contains("Unknown companion"));
	}

	@Test
	public void failsForMissingSave () {
		final JSONObject request = new JSONObject();
		request.put("oldSave", "Z:\\does\\not\\exist");
		request.put("savedYet", false);
		request.put("companion", "Eder");

		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		new ResurrectCharacter().onQuery(mockBrowser, 0, request.toString(), false, mockCallback);
		verify(mockCallback, timeout(60000)).failure(eq(-1), contains("save file"));
	}
}
