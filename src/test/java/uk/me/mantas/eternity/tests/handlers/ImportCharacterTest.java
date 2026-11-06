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
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.handlers.ChangeHistory;
import uk.me.mantas.eternity.handlers.ImportCharacter;
import uk.me.mantas.eternity.save.CharacterExporter;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

public class ImportCharacterTest extends TestHarness {
	private static final String COMPANION_GUID = "b1a7e809-0000-0000-0000-000000000000";

	@Test
	public void confirmedOverwriteImportsWithoutFileDialog ()
		throws IOException
		, URISyntaxException {

		final File resources = new File(getClass().getResource("/").toURI());
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		final File saveDir = new File(workingDir.get(), "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), saveDir);

		final File chrFile = new File(workingDir.get(), "companion.chr");
		final CharacterExporter exporter = new CharacterExporter(
			resources.getAbsolutePath()
			, COMPANION_GUID
			, chrFile.getAbsolutePath());
		assertTrue(exporter.export());

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		doThrow(new JSONException("")).when(mockJSON).getString(anyString());

		final JSONObject request = new JSONObject();
		request.put("oldSave", saveDir.getAbsolutePath());
		request.put("savedYet", false);
		request.put("chrPath", chrFile.getAbsolutePath());
		request.put("overwrite", true);

		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		new ImportCharacter().onQuery(mockBrowser, 0, request.toString(), false, mockCallback);

		// The handler must import and respond with the re-opened save, and
		// must never open a file dialog for a confirmed request.
		verify(mockCallback, timeout(60000)).success(contains("\"characters\""));
		verifyZeroInteractions(mockBrowser);
	}

	// An import is a step of the save's history like any Apply.
	@Test
	public void anImportCanBeUndone () throws Exception {
		final File resources = new File(getClass().getResource("/").toURI());
		final File workingDir = EKUtils.createTempDir(PREFIX).get();
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), saveDir);
		final byte[] before = Files.readAllBytes(new File(saveDir, "MobileObjects.save").toPath());

		final File chrFile = new File(workingDir, "companion.chr");
		assertTrue(new CharacterExporter(
			resources.getAbsolutePath(), COMPANION_GUID, chrFile.getAbsolutePath()).export());
		mockSettings().json = new JSONObject();

		final CefQueryCallback imported = mock(CefQueryCallback.class);
		new ImportCharacter().onQuery(mock(CefBrowser.class), 0, new JSONObject()
			.put("oldSave", saveDir.getAbsolutePath()).put("savedYet", false)
			.put("chrPath", chrFile.getAbsolutePath()).put("overwrite", true).toString()
			, false, imported);

		final ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
		verify(imported, timeout(60000)).success(reply.capture());
		final long step = new JSONObject(reply.getValue()).getLong("historyStep");
		assertTrue(step > 0);

		final File working = Environment.getInstance().state().workingSave().forReading(saveDir, false);
		final File world = new File(working, "MobileObjects.save");
		assertFalse(Arrays.equals(before, Files.readAllBytes(world.toPath())));

		final CefQueryCallback undone = mock(CefQueryCallback.class);
		new ChangeHistory().onQuery(mock(CefBrowser.class), 0, new JSONObject()
			.put("oldSave", saveDir.getAbsolutePath()).put("savedYet", false)
			.put("action", "undo").put("step", step).toString(), false, undone);

		verify(undone, timeout(60000)).success(contains("\"characters\""));
		assertArrayEquals(before, Files.readAllBytes(world.toPath()));
	}

	// Written, and then the save could not be handed back: the page is told
	// the import failed and never learns of the step, so the import is taken
	// back, as an Apply's is. It stayed, and the next Save wrote a character
	// the page had never shown (2026-10-27).
	@Test
	public void anImportThatCannotBeHandedBackIsTakenBack () throws Exception {
		final File resources = new File(getClass().getResource("/").toURI());
		final File workingDir = EKUtils.createTempDir(PREFIX).get();
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), saveDir);
		final byte[] before = Files.readAllBytes(new File(saveDir, "MobileObjects.save").toPath());

		final File chrFile = new File(workingDir, "companion.chr");
		assertTrue(new CharacterExporter(
			resources.getAbsolutePath(), COMPANION_GUID, chrFile.getAbsolutePath()).export());
		mockSettings().json = new JSONObject();

		final CefQueryCallback failing = mock(CefQueryCallback.class);
		doThrow(new IllegalStateException("the reply could not be built"))
			.when(failing).success(anyString());

		new ImportCharacter().onQuery(mock(CefBrowser.class), 0, new JSONObject()
			.put("oldSave", saveDir.getAbsolutePath()).put("savedYet", false)
			.put("chrPath", chrFile.getAbsolutePath()).put("overwrite", true).toString()
			, false, failing);

		verify(failing, timeout(60000)).failure(anyInt(), anyString());
		final File working = Environment.getInstance().state().workingSave().forReading(saveDir, false);
		assertArrayEquals(before, Files.readAllBytes(new File(working, "MobileObjects.save").toPath()));
	}
}
