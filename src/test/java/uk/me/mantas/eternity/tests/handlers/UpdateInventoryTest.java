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
import uk.me.mantas.eternity.handlers.UpdateInventory;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Optional;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

public class UpdateInventoryTest extends TestHarness {
	private static final String ELWYN = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String CALISCA = "b1a7e809-0000-0000-0000-000000000000";
	private static final String SCEPTRE = "9a493248-f2d7-42bf-9eb1-9c7475efbd04";

	@Test
	public void aRefusalReachesTheUserInTheGamesOwnTerms ()
		throws IOException, URISyntaxException {

		// Calisca's sceptre is soulbound to her; handing it to Elwyn's weapon
		// set is refused, and the reason is what the user should read, rather
		// than "details are in the log".
		final File resources = new File(getClass().getResource("/").toURI());
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		final File saveDir = new File(workingDir.get(), "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(
			new File(resources, "InventoryManagerTest/MobileObjects.save"), saveDir);

		final Settings mockSettings = mockSettings();
		mockSettings.json = new JSONObject();

		final JSONObject request = new JSONObject()
			.put("oldSave", saveDir.getAbsolutePath())
			.put("savedYet", false)
			.put("changes", new JSONArray()
				.put(new JSONObject()
					.put("character", CALISCA).put("component", "Equipment")
					.put("itemGuid", SCEPTRE).put("stackSize", 1)
					.put("destCharacter", CALISCA).put("destComponent", "Inventory")
					.put("fromEquipmentSlot", 2).put("weaponSet", true))
				.put(new JSONObject()
					.put("character", CALISCA).put("component", "Inventory")
					.put("itemGuid", SCEPTRE).put("stackSize", 1)
					.put("destCharacter", ELWYN).put("destComponent", "Equipment")
					.put("toEquipmentSlot", 0).put("weaponSet", true)));

		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		new UpdateInventory().onQuery(
			mock(CefBrowser.class), 0, request.toString(), false, mockCallback);

		verify(mockCallback, timeout(60000)).failure(anyInt(), argThat(message ->
			message.contains("soulbound to Calisca")));
	}
}
