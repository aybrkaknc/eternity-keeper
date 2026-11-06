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
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.handlers.UpdateStronghold;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Optional;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

public class UpdateStrongholdTest extends TestHarness {
	/** A save with a taken Caed Nua: four hirelings and Kestorik in the cells. */
	private File keep () throws IOException, URISyntaxException {
		final File resources = new File(getClass().getResource("/").toURI());
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		final File saveDir = new File(workingDir.get(), "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(
			new File(resources, "StrongholdManagerTest/MobileObjects.save"), saveDir);

		final Settings mockSettings = mockSettings();
		mockSettings.json = new JSONObject();
		return saveDir;
	}

	private static CefQueryCallback apply (final File saveDir, final JSONArray changes) {
		final JSONObject request = new JSONObject()
			.put("oldSave", saveDir.getAbsolutePath())
			.put("savedYet", false)
			.put("changes", changes);

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new UpdateStronghold().onQuery(mock(CefBrowser.class), 0, request.toString(), false, callback);
		return callback;
	}

	// One change the save could not take was logged and skipped, the rest
	// went in, and the page was told the Apply had worked (found by review,
	// 2026-10-27). The page is told which change, and nothing goes in.
	@Test
	public void aBatchWithARefusedChangeIsRefusedInTheManagersWords ()
		throws IOException, URISyntaxException {

		final File saveDir = keep();
		final CefQueryCallback callback = apply(saveDir, new JSONArray()
			.put(new JSONObject().put("kind", "dismissHireling").put("name", "b_crucible_hireling"))
			.put(new JSONObject().put("kind", "releasePrisoner").put("name", "b_nobody_prisoner")));

		verify(callback, timeout(60000)).failure(anyInt(), argThat(message ->
			message.contains("b_nobody_prisoner") && message.contains("Nothing was changed")));
		verify(callback, never()).success(anyString());
	}

	@Test
	public void aChangeOfAKindNobodyKnowsIsRefused () throws IOException, URISyntaxException {
		final File saveDir = keep();
		final CefQueryCallback callback = apply(saveDir, new JSONArray()
			.put(new JSONObject().put("kind", "dismissHireling").put("name", "b_crucible_hireling"))
			.put(new JSONObject().put("kind", "razeTheKeep").put("name", "")));

		verify(callback, timeout(60000)).failure(anyInt(), argThat(message ->
			message.contains("razeTheKeep") && message.contains("Nothing was changed")));
		verify(callback, never()).success(anyString());
	}

	@Test
	public void dismissAndReleaseComeBackAsTheReopenedKeep ()
		throws IOException, URISyntaxException {

		final File saveDir = keep();
		final JSONObject request = new JSONObject()
			.put("oldSave", saveDir.getAbsolutePath())
			.put("savedYet", false)
			.put("changes", new JSONArray()
				.put(new JSONObject()
					.put("kind", "dismissHireling").put("name", "b_crucible_hireling"))
				.put(new JSONObject()
					.put("kind", "releasePrisoner").put("name", "b_kestorik_prisoner")));

		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		new UpdateStronghold().onQuery(
			mock(CefBrowser.class), 0, request.toString(), false, mockCallback);

		// Three hirelings left, the dungeon empty, and the Crucible Knight's
		// +4 Prestige gone with him.
		verify(mockCallback, timeout(60000)).success(argThat(response -> {
			final JSONObject keep = new JSONObject(response).getJSONObject("stronghold");
			return keep.getJSONArray("hirelings").length() == 3
				&& keep.getJSONArray("prisoners").length() == 0
				&& keep.getInt("prestige") == 34;
		}));
	}
}
