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
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.handlers.ApplyLoadout;
import uk.me.mantas.eternity.handlers.ExportLoadout;
import uk.me.mantas.eternity.handlers.ReadLoadout;
import uk.me.mantas.eternity.save.InventoryManager;
import uk.me.mantas.eternity.save.InventoryManager.Change;
import uk.me.mantas.eternity.save.Loadout;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.*;

// The page's three questions about loadouts: write this character's gear to a
// file, say what a file would do to someone, and put it on. The native file
// dialogs are CEF's, so these ask past them.
public class LoadoutHandlersTest extends TestHarness {
	private static final String ELWYN = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String CALISCA = "b1a7e809-0000-0000-0000-000000000000";
	private static final String MAIL = "5ea3dc07-93bc-46a2-ba3c-d7c14660a127";
	private static final String SWORD = "5b1a11ca-ddcd-452f-84b3-bc651af00fad";
	private static final String SHIELD = "eff2c4bb-1a24-47d7-b71f-430292d3a6ca";

	private static File save () throws Exception {
		final File folder = EKUtils.createTempDir(PREFIX).get();
		final File save = new File(folder, "target.savegame");
		assertTrue(save.mkdir());
		FileUtils.copyFileToDirectory(new File(new File(LoadoutHandlersTest.class.getResource("/").toURI())
			, "InventoryManagerTest/MobileObjects.save"), save);

		return save;
	}

	private static File loadoutOf (final File save, final String who) throws Exception {
		final File file = new File(EKUtils.createTempDir(PREFIX).get(), "gear.loadout");
		assertTrue(Loadout.save(save, who, file) > 0);
		return file;
	}

	private static String succeeded (final CefQueryCallback callback) {
		final ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
		verify(callback, timeout(60000)).success(reply.capture());
		return reply.getValue();
	}

	private static String failed (final CefQueryCallback callback) {
		final ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
		verify(callback, timeout(60000)).failure(anyInt(), reason.capture());
		verify(callback, never()).success(anyString());
		return reason.getValue();
	}

	@Test
	public void aCharactersGearIsWrittenToTheFileChosen () throws Exception {
		final File save = save();
		final File target = new File(EKUtils.createTempDir(PREFIX).get(), "Elwyn");
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		ExportLoadout.export(new JSONObject().put("absolutePath", save.getAbsolutePath())
			.put("savedYet", false).put("GUID", ELWYN).put("name", "Elwyn"), target.getAbsolutePath(), callback);

		final JSONObject reply = new JSONObject(succeeded(callback));
		assertEquals(3, reply.getInt("items"));
		assertTrue("the extension is added", reply.getString("file").endsWith("Elwyn.loadout"));
		assertEquals(3, Loadout.read(new File(reply.getString("file"))).slots.size());
	}

	@Test
	public void someoneWithNothingOnIsToldSo () throws Exception {
		final File save = save();
		assertTrue(new InventoryManager(save).apply(Arrays.asList(
			Change.unequip(ELWYN, 2, MAIL, ELWYN, "StashInventory", -1)
			, Change.unequipWeapon(ELWYN, 0, SWORD, ELWYN, "StashInventory", -1)
			, Change.unequipWeapon(ELWYN, 1, SHIELD, ELWYN, "StashInventory", -1))));

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		ExportLoadout.export(new JSONObject().put("absolutePath", save.getAbsolutePath())
			.put("savedYet", false).put("GUID", ELWYN).put("name", "Elwyn")
			, new File(EKUtils.createTempDir(PREFIX).get(), "Elwyn.loadout").getAbsolutePath(), callback);

		assertEquals("Elwyn wears and holds nothing, so there is no loadout to save.", failed(callback));
	}

	@Test
	public void aPlanIsSaidInWords () throws Exception {
		final File save = save();
		final File file = loadoutOf(save, ELWYN);
		final Loadout loadout = Loadout.read(file);
		final List<Property> packets = new PacketDeserializer(new File(save, "MobileObjects.save"))
			.deserialize().get().getPackets();

		final JSONObject plan = ReadLoadout.describe(
			file.getAbsolutePath(), loadout, loadout.plan(packets, CALISCA), packets);

		assertEquals("Elwyn", plan.getString("from"));
		assertEquals("Fighter", plan.getString("className"));
		assertEquals(3, plan.getInt("fitting"));

		final JSONArray items = plan.getJSONArray("items");
		final JSONObject chest = items.getJSONObject(0);
		assertEquals("chest", chest.getString("place"));
		assertEquals("Mail Armor", chest.getString("name"));
		assertTrue(chest.getBoolean("fits"));
		assertEquals("Scale Armor", chest.getString("replaces"));

		final JSONObject sword = items.getJSONObject(1);
		assertEquals("weapon set I, main hand", sword.getString("place"));
		assertEquals("Battle Axe", sword.getString("replaces"));
		assertEquals("weapon set I, off hand", items.getJSONObject(2).getString("place"));
	}

	@Test
	public void theFileChosenIsReadAndPlanned () throws Exception {
		final File save = save();
		final File file = loadoutOf(save, CALISCA);
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		ReadLoadout.answer(new JSONObject().put("absolutePath", save.getAbsolutePath())
			.put("savedYet", false).put("GUID", ELWYN), file.getAbsolutePath(), callback);

		final JSONObject plan = new JSONObject(succeeded(callback));
		assertEquals("Calisca", plan.getString("from"));
		assertEquals("all but the soulbound sceptre", 3, plan.getInt("fitting"));
		assertEquals("soulbound to Calisca", plan.getJSONArray("items").getJSONObject(3).getString("reason"));
	}

	// A .chr reads like a loadout (a character, then their things), but only
	// a .loadout goes on, so the plan says so before anyone confirms it.
	@Test
	public void anotherKindOfFileIsRefusedBeforeThePlan () throws Exception {
		final File save = save();
		final File chr = new File(EKUtils.createTempDir(PREFIX).get(), "Elwyn.chr");
		FileUtils.copyFile(loadoutOf(save, ELWYN), chr);

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		ReadLoadout.answer(new JSONObject().put("absolutePath", save.getAbsolutePath())
			.put("savedYet", false).put("GUID", CALISCA), chr.getAbsolutePath(), callback);

		assertEquals("Elwyn.chr is not a loadout: only a .loadout file can be put on.", failed(callback));
	}

	@Test
	public void puttingItOnIsAnApply () throws Exception {
		mockSettings().json = new JSONObject();
		final File save = save();
		final File file = loadoutOf(save, ELWYN);

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new ApplyLoadout().onQuery(mock(CefBrowser.class), 0, new JSONObject()
			.put("oldSave", save.getAbsolutePath()).put("savedYet", false)
			.put("GUID", CALISCA).put("file", file.getAbsolutePath()).toString(), false, callback);

		final JSONObject reopened = new JSONObject(succeeded(callback));
		final JSONArray characters = reopened.getJSONObject("inventory").getJSONArray("characters");
		JSONObject calisca = null;
		for (int i = 0; i < characters.length(); i++) {
			if (CALISCA.equalsIgnoreCase(characters.getJSONObject(i).getString("guid"))) {
				calisca = characters.getJSONObject(i);
			}
		}

		assertNotNull(calisca);
		final JSONObject chest = calisca.getJSONObject("equipment").getJSONArray("slots").getJSONObject(2);
		assertTrue(chest.toString(), chest.getJSONObject("item").getString("baseItem").contains("Mail_Armor"));
	}

	@Test
	public void onlyALoadoutFileGoesOn () throws Exception {
		final File save = save();
		final File chr = new File(EKUtils.createTempDir(PREFIX).get(), "Elwyn.chr");
		FileUtils.copyFile(loadoutOf(save, ELWYN), chr);

		for (final File file : Arrays.asList(chr, new File(chr.getParentFile(), "gone.loadout"))) {
			final CefQueryCallback callback = mock(CefQueryCallback.class);
			new ApplyLoadout().onQuery(mock(CefBrowser.class), 0, new JSONObject()
				.put("oldSave", save.getAbsolutePath()).put("savedYet", false)
				.put("GUID", CALISCA).put("file", file.getAbsolutePath()).toString(), false, callback);

			final String reason = failed(callback);
			assertTrue(reason, reason.equals("Only a .loadout file can be put on.")
				|| reason.equals("That loadout file is not there any more."));
		}
	}
}
