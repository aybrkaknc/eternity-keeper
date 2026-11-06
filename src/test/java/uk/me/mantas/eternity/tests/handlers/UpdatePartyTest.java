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
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.handlers.UpdateParty;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Optional;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

public class UpdatePartyTest extends TestHarness {
	private static final String COMPANION_GUID = "b1a7e809-0000-0000-0000-000000000000";

	@Test
	public void dismissesCompanionAndReturnsReopenedSave ()
		throws IOException
		, URISyntaxException {

		final File resources = new File(getClass().getResource("/").toURI());
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		final File saveDir = new File(workingDir.get(), "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), saveDir);

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		doThrow(new JSONException("")).when(mockJSON).getString(anyString());

		final JSONObject changes = new JSONObject();
		changes.put(COMPANION_GUID, false);

		final JSONObject request = new JSONObject();
		request.put("oldSave", saveDir.getAbsolutePath());
		request.put("savedYet", false);
		request.put("changes", changes);

		final CefBrowser mockBrowser = mock(CefBrowser.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		new UpdateParty().onQuery(mockBrowser, 0, request.toString(), false, mockCallback);

		// The response is the re-opened save; Calisca must now be reported as
		// out of the party.
		verify(mockCallback, timeout(60000)).success(argThat(response -> {
			final JSONObject json = new JSONObject(response);
			if (!json.has("characters")) {
				return false;
			}

			for (int i = 0; i < json.getJSONArray("characters").length(); i++) {
				final JSONObject character = json.getJSONArray("characters").getJSONObject(i);
				if (character.getString("GUID").equals(COMPANION_GUID)) {
					return !character.getBoolean("inParty");
				}
			}

			return false;
		}));
	}
}
