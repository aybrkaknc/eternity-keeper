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
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.handlers.ChangeHistory;
import uk.me.mantas.eternity.handlers.UpdateInventory;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;

import static org.junit.Assert.*;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.*;

// Undo and Redo for what an Apply wrote: the page asks for a step by the id
// the Apply's reply gave it, and gets the reopened save back, like any Apply.
public class ChangeHistoryTest extends TestHarness {
	private static final String ELWYN = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String MAIL = "5ea3dc07-93bc-46a2-ba3c-d7c14660a127";
	private static final String SWORD = "5b1a11ca-ddcd-452f-84b3-bc651af00fad";
	private static final int CHEST = 2;

	@Before
	public void noSettings () {
		mockSettings().json = new JSONObject();
	}

	private static File save () throws Exception {
		final File save = new File(EKUtils.createTempDir(PREFIX).get(), "target.savegame");
		assertTrue(save.mkdir());
		FileUtils.copyFileToDirectory(new File(new File(ChangeHistoryTest.class.getResource("/").toURI())
			, "InventoryManagerTest/MobileObjects.save"), save);

		return save;
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

	private static JSONObject unequip (final String item, final int slot, final boolean weapon) {
		return new JSONObject().put("character", ELWYN).put("component", "Equipment")
			.put("itemGuid", item).put("stackSize", 1).put("destCharacter", ELWYN)
			.put("destComponent", "StashInventory").put("destSlot", -1)
			.put("fromEquipmentSlot", slot).put("weaponSet", weapon);
	}

	private static CefQueryCallback apply (final File save, final JSONObject... changes) {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		final JSONArray list = new JSONArray();
		for (final JSONObject change : changes) {
			list.put(change);
		}

		new UpdateInventory().onQuery(mock(CefBrowser.class), 0, new JSONObject()
			.put("oldSave", save.getAbsolutePath()).put("savedYet", false)
			.put("changes", list).toString(), false, callback);

		return callback;
	}

	private static CefQueryCallback history (final File save, final String action, final long step) {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new ChangeHistory().onQuery(mock(CefBrowser.class), 0, new JSONObject()
			.put("oldSave", save.getAbsolutePath()).put("savedYet", false)
			.put("action", action).put("step", step).toString(), false, callback);

		return callback;
	}

	private static String worn (final JSONObject save, final int slot) {
		final JSONArray characters = save.getJSONObject("inventory").getJSONArray("characters");
		for (int i = 0; i < characters.length(); i++) {
			final JSONObject character = characters.getJSONObject(i);
			if (ELWYN.equalsIgnoreCase(character.getString("guid"))) {
				final JSONObject entry = character.getJSONObject("equipment").getJSONArray("slots")
					.getJSONObject(slot);
				return entry.has("item") && !entry.isNull("item")
					? entry.getJSONObject("item").getString("guid") : "";
			}
		}

		return null;
	}

	@Test
	public void anApplyTellsThePageWhichStepItWas () throws Exception {
		final JSONObject reply = new JSONObject(succeeded(apply(save(), unequip(MAIL, CHEST, false))));
		assertTrue(reply.getLong("historyStep") > 0);
	}

	@Test
	public void undoingAnApplyPutsTheSaveBackAndRedoingItAgain () throws Exception {
		final File save = save();
		final JSONObject applied = new JSONObject(succeeded(apply(save, unequip(MAIL, CHEST, false))));
		final long step = applied.getLong("historyStep");
		assertEquals("", worn(applied, CHEST));

		final JSONObject undone = new JSONObject(succeeded(history(save, "undo", step)));
		assertEquals(MAIL, worn(undone, CHEST));
		assertFalse("an undo is no new step", undone.has("historyStep") && undone.getLong("historyStep") > 0);

		final JSONObject redone = new JSONObject(succeeded(history(save, "redo", step)));
		assertEquals("", worn(redone, CHEST));
	}

	@Test
	public void onlyTheLastChangeIsUndone () throws Exception {
		final File save = save();
		final long first = new JSONObject(succeeded(apply(save, unequip(MAIL, CHEST, false))))
			.getLong("historyStep");
		succeeded(apply(save, unequip(SWORD, 0, true)));

		assertEquals("That change is no longer the last one in the editor's history, so nothing was"
			+ " undone. Reopening the save starts the history again from the file."
			, failed(history(save, "undo", first)));
	}

	@Test
	public void aRefusedApplyIsNoStep () throws Exception {
		final File save = save();
		failed(apply(save, unequip("00000000-0000-0000-0000-00000000beef", CHEST, false)));
		assertTrue(Environment.getInstance().state().workingSave().history().undoable().isEmpty());
	}

	@Test
	public void nothingIsRedoneThatWasNotUndone () throws Exception {
		final File save = save();
		final long step = new JSONObject(succeeded(apply(save, unequip(MAIL, CHEST, false))))
			.getLong("historyStep");

		assertEquals("That change is no longer the last one undone, so nothing was redone."
			, failed(history(save, "redo", step)));
	}
}
