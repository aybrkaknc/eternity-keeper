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
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.handlers.CompareSaves;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;

import static org.junit.Assert.*;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.*;

// The Compare dialog's question: what differs between two saves of the list.
// The page names them by the folders the save list unpacked them into, so
// those are the only folders it may name.
public class CompareSavesTest extends TestHarness {
	private static File save (final File folder, final String name) throws Exception {
		final File resources = new File(CompareSavesTest.class.getResource("/").toURI());
		final File save = new File(folder, name);
		assertTrue(save.mkdirs());
		FileUtils.copyFile(new File(resources, "MobileObjects.save"), new File(save, "MobileObjects.save"));
		FileUtils.copyFile(new File(resources, "SaveGameInfoTest.saveinfo.xml"), new File(save, "saveinfo.xml"));
		return save;
	}

	private static CefQueryCallback ask (final File before, final File after) {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new CompareSaves().onQuery(mock(CefBrowser.class), 0, new JSONObject()
			.put("before", before.getAbsolutePath())
			.put("after", after.getAbsolutePath())
			.toString(), false, callback);

		return callback;
	}

	@Test
	public void twoSavesOfTheListAreCompared () throws Exception {
		final File unpacked = EKUtils.createTempDir(PREFIX).get();
		Environment.getInstance().directory().working(unpacked);
		final File before = save(unpacked, "cadena 0 Test.savegame");
		final File after = save(unpacked, "cadena 1 Test.savegame");
		FileUtils.writeStringToFile(new File(after, "saveinfo.xml"), FileUtils.readFileToString(
			new File(after, "saveinfo.xml"), "UTF-8").replace("value=\"Start\"", "value=\"Later\""), "UTF-8");

		final CefQueryCallback callback = ask(before, after);
		final ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
		verify(callback, timeout(60000)).success(reply.capture());

		final JSONObject comparison = new JSONObject(reply.getValue());
		assertEquals("Later", comparison.getJSONObject("after").getString("userSaveName"));
		assertEquals("save", comparison.getJSONArray("sections").getJSONObject(0).getString("id"));
	}

	// A path the page sends is never followed anywhere the save list did not
	// unpack: not a save folder elsewhere, not the unpacked folder itself.
	@Test
	public void onlyTheSavesTheListUnpackedCanBeCompared () throws Exception {
		final File unpacked = EKUtils.createTempDir(PREFIX).get();
		Environment.getInstance().directory().working(unpacked);
		final File listed = save(unpacked, "cadena 0 Test.savegame");
		final File elsewhere = save(EKUtils.createTempDir(PREFIX).get(), "cadena 1 Test.savegame");

		for (final File[] pair : new File[][] {
			{listed, elsewhere}, {elsewhere, listed}, {listed, unpacked}
			, {listed, new File(unpacked, "cadena 0 Test.savegame/../cadena 0 Test.savegame/..")}}) {

			final CefQueryCallback callback = ask(pair[0], pair[1]);
			final ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
			verify(callback, timeout(60000)).failure(anyInt(), message.capture());
			verify(callback, never()).success(anyString());
			assertTrue(message.getValue(), message.getValue().contains("Only saves in the list can be compared"));
		}
	}
}
