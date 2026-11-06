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
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.handlers.ConvertSave;
import uk.me.mantas.eternity.save.SaveConverter;
import uk.me.mantas.eternity.save.SaveConverter.Format;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

public class ConvertSaveTest extends TestHarness {
	private File archive (final String fixturePath, final String name) throws Exception {
		final File staging = EKUtils.createTempDir(PREFIX).get();
		final File contents = new File(staging, "extracted");
		FileUtils.copyDirectory(
			new File(getClass().getResource(fixturePath).toURI()), contents);

		final File archive = new File(staging, name);
		new ZipFile(archive).addFiles(
			new ArrayList<>(Arrays.asList(contents.listFiles())), new ZipParameters());

		return archive;
	}

	private JSONObject request (final File save, final boolean convert) {
		final JSONObject request = new JSONObject();
		request.put("savePath", save.getAbsolutePath());
		request.put("convert", convert);
		return request;
	}

	@Test
	public void reportsTheFormatWithoutTouchingTheSave () throws Exception {
		final File save = archive("/SerializerTest/windowStoreSave", "id 0 Raedric.savegame");
		final long before = save.length();

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new ConvertSave().onQuery(
			mock(CefBrowser.class), 0, request(save, false).toString(), false, callback);

		verify(callback, timeout(60000)).success(argThat(response -> {
			final JSONObject json = new JSONObject(response);
			assertEquals("MODERN", json.getString("format"));
			assertTrue(json.getBoolean("convertible"));
			assertTrue(json.getString("destination").endsWith("id 0 Raedric.savegame"));
			assertFalse(json.getBoolean("converted"));
			return true;
		}));

		assertEquals(before, save.length());
	}

	@Test
	public void convertsTheSaveIntoAFolderOfItsOwn () throws Exception {
		final File save = archive("/SerializerTest/windowStoreSave", "id 0 Raedric.savegame");
		final byte[] before = FileUtils.readFileToByteArray(save);

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new ConvertSave().onQuery(
			mock(CefBrowser.class), 0, request(save, true).toString(), false, callback);

		verify(callback, timeout(120000)).success(argThat(response -> {
			final JSONObject json = new JSONObject(response);
			assertTrue(json.getBoolean("converted"));
			assertEquals(88, json.getInt("replacements"));
			assertEquals(2, json.getInt("files"));
			assertEquals(0, json.getInt("fellBack"));

			final File output = new File(json.getString("destination"));
			assertTrue(output.isFile());
			assertEquals("converted", output.getParentFile().getName());

			try {
				assertEquals(Format.LEGACY, SaveConverter.detectSave(output));
			} catch (final Exception e) {
				fail(e.getMessage());
			}

			return true;
		}));

		// Invariant 1: the save it was given is exactly as it was found.
		assertArrayEquals(before, FileUtils.readFileToByteArray(save));
	}

	@Test
	public void saysWhenThereIsNothingToConvert () throws Exception {
		final File save =
			archive("/SerializerTest/windowStoreSaveConverted", "id 0 Raedric.savegame");

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new ConvertSave().onQuery(
			mock(CefBrowser.class), 0, request(save, false).toString(), false, callback);

		verify(callback, timeout(60000)).success(argThat(response -> {
			final JSONObject json = new JSONObject(response);
			assertEquals("LEGACY", json.getString("format"));
			assertFalse(json.getBoolean("convertible"));
			return true;
		}));
	}

	@Test
	public void failsWithAMessageRatherThanThrowing () throws Exception {
		final File missing = new File(EKUtils.createTempDir(PREFIX).get(), "nowhere.savegame");

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new ConvertSave().onQuery(
			mock(CefBrowser.class), 0, request(missing, false).toString(), false, callback);

		verify(callback, timeout(60000)).failure(anyInt(), anyString());
		verify(callback, never()).success(anyString());
	}

	@Test
	public void willNotConvertTheSameSaveTwice () throws Exception {
		final File save = archive("/SerializerTest/windowStoreSave", "id 0 Raedric.savegame");

		final CefQueryCallback first = mock(CefQueryCallback.class);
		new ConvertSave().onQuery(
			mock(CefBrowser.class), 0, request(save, true).toString(), false, first);
		verify(first, timeout(120000)).success(anyString());

		final CefQueryCallback second = mock(CefQueryCallback.class);
		new ConvertSave().onQuery(
			mock(CefBrowser.class), 0, request(save, true).toString(), false, second);
		verify(second, timeout(120000)).failure(anyInt(), argThat(
			message -> message.contains("already")));
	}
}
