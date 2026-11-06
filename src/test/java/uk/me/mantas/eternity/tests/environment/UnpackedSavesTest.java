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


package uk.me.mantas.eternity.tests.environment;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import org.apache.commons.io.FileUtils;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.environment.UnpackedSaves;
import uk.me.mantas.eternity.handlers.ListSavedGames.SaveInfoLister;
import uk.me.mantas.eternity.handlers.OpenSavedGame;
import uk.me.mantas.eternity.save.SaveGameExtractor;
import uk.me.mantas.eternity.save.SaveGameInfo;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * How much of a save the list unpacks, and when the rest follows.
 *
 * <p>A save's tile in the list is drawn from eight small files: saveinfo.xml,
 * the screenshot and the party's portraits. Every search used to unpack every
 * save whole all the same -- measured on twelve real saves, 5.8 s and 767 MB of
 * temp space each time, which a player with a hundred saves would have waited
 * a minute for at every start. Now a search unpacks what it draws, and the
 * rest of a save follows when something needs it: opening it, comparing it.
 */
public class UnpackedSavesTest extends TestHarness {
	private static final String NAME = "guid systemname.savegame";

	/** A save as the game writes one: what a tile needs, a world state, an area. */
	private static File wholeSave (final File saves) throws Exception {
		final File resources = new File(UnpackedSavesTest.class.getResource("/").toURI());
		final File staging = EKUtils.createTempDir(PREFIX).get();
		new ZipFile(new File(resources, "SaveGameExtractorTest/" + NAME)).extractAll(staging.getAbsolutePath());
		FileUtils.copyFile(new File(resources, "MobileObjects.save"), new File(staging, "MobileObjects.save"));
		FileUtils.writeStringToFile(new File(staging, "AR_0001_Somewhere.lvl"), "an area", "UTF-8");

		final File save = new File(saves, NAME);
		new ZipFile(save).addFiles(new ArrayList<>(Arrays.asList(staging.listFiles())), new ZipParameters());
		return save;
	}

	private static TreeSet<String> names (final File folder) {
		final String[] names = folder.list();
		return new TreeSet<>(Arrays.asList(names == null ? new String[0] : names));
	}

	private static UnpackedSaves unpacked () {
		return Environment.getInstance().state().unpacked();
	}

	private static File listed (final File saves, final File working) {
		final Optional<SaveGameInfo[]> found =
			new SaveGameExtractor(saves.getAbsolutePath(), working).unpackAllSaves();

		assertTrue(found.isPresent());
		assertEquals(1, found.get().length);
		return new File(found.get()[0].absolutePath);
	}

	@Test
	public void aSearchUnpacksOnlyWhatATileIsDrawnFrom () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		wholeSave(saves);

		final File folder = listed(saves, EKUtils.createTempDir(PREFIX).get());
		assertEquals(new TreeSet<>(Arrays.asList(
				"0.png", "1.png", "2.png", "3.png", "4.png", "5.png", "saveinfo.xml", "screenshot.png"))
			, names(folder));
	}

	@Test
	public void theRestOfASaveFollowsWhenItIsNeeded () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		wholeSave(saves);
		final File folder = listed(saves, EKUtils.createTempDir(PREFIX).get());

		unpacked().complete(folder);
		assertTrue(names(folder).containsAll(Arrays.asList(
			"MobileObjects.save", "AR_0001_Somewhere.lvl", "saveinfo.xml", "0.png")));
		assertEquals(10, names(folder).size());
	}

	// Asked for again, a save that is whole is not unpacked again: what an
	// edit has since done to the folder must not be put back.
	@Test
	public void aSaveIsCompletedOnce () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		wholeSave(saves);
		final File folder = listed(saves, EKUtils.createTempDir(PREFIX).get());

		unpacked().complete(folder);
		FileUtils.writeStringToFile(new File(folder, "AR_0001_Somewhere.lvl"), "edited", "UTF-8");
		unpacked().complete(folder);

		assertEquals("edited"
			, FileUtils.readFileToString(new File(folder, "AR_0001_Somewhere.lvl"), "UTF-8"));
	}

	// A folder the list did not unpack -- one a Save wrote, one a test made --
	// has no archive behind it and is whole as it stands.
	@Test
	public void aFolderTheListDidNotUnpackIsLeftAsItIs () throws Exception {
		final File folder = new File(EKUtils.createTempDir(PREFIX).get(), "written 1 Scene.savegame");
		assertTrue(folder.mkdir());
		FileUtils.writeStringToFile(new File(folder, "MobileObjects.save"), "a world state", "UTF-8");

		unpacked().complete(folder);
		assertEquals(new TreeSet<>(Arrays.asList("MobileObjects.save")), names(folder));
	}

	@Test
	public void aSaveWhoseArchiveHasGoneCannotBeCompleted () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		final File archive = wholeSave(saves);
		final File folder = listed(saves, EKUtils.createTempDir(PREFIX).get());
		assertTrue(archive.delete());

		try {
			unpacked().complete(folder);
			fail("there is nothing to unpack the rest from");
		} catch (final IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains(NAME));
		}

		assertFalse("and it is not taken for whole afterwards", new File(folder, "MobileObjects.save").exists());
	}

	// A search empties the folder everything was unpacked into, so nothing in
	// it is whole any more.
	@Test
	public void aNewSearchStartsAgain () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		wholeSave(saves);
		final File working = EKUtils.createTempDir(PREFIX).get();
		Environment.getInstance().directory().working(working);

		final CefQueryCallback first = mock(CefQueryCallback.class);
		new SaveInfoLister(saves.getAbsolutePath(), first).run();
		final File folder = new File(working, NAME);
		unpacked().complete(folder);
		assertTrue(new File(folder, "MobileObjects.save").exists());

		new SaveInfoLister(saves.getAbsolutePath(), mock(CefQueryCallback.class)).run();
		assertFalse("the search left only what it draws", new File(folder, "MobileObjects.save").exists());

		unpacked().complete(folder);
		assertTrue("and the save can be completed again", new File(folder, "MobileObjects.save").exists());
	}

	// Opening is what needs a save whole: the world state is not there until
	// then.
	@Test
	public void openingASaveUnpacksTheRestOfIt () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		wholeSave(saves);
		final File folder = listed(saves, EKUtils.createTempDir(PREFIX).get());
		assertFalse(new File(folder, "MobileObjects.save").exists());

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new OpenSavedGame().onQuery(mock(CefBrowser.class), 0, folder.getAbsolutePath(), false, callback);

		final ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
		verify(callback, timeout(60000)).success(reply.capture());
		final JSONObject opened = new JSONObject(reply.getValue());
		assertFalse(reply.getValue().substring(0, Math.min(200, reply.getValue().length())), opened.has("error"));
		assertTrue(opened.getJSONArray("characters").length() > 0);
	}

	@Test
	public void aSaveThatCannotBeUnpackedSaysSoInsteadOfOpening () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		final File archive = wholeSave(saves);
		final File folder = listed(saves, EKUtils.createTempDir(PREFIX).get());
		assertTrue(archive.delete());

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new OpenSavedGame().onQuery(mock(CefBrowser.class), 0, folder.getAbsolutePath(), false, callback);

		final ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
		verify(callback, timeout(60000)).success(reply.capture());
		final JSONObject refused = new JSONObject(reply.getValue());
		assertEquals("UNPACK_ERR", refused.getString("error"));
		assertTrue(refused.getString("msg"), refused.getString("msg").contains(NAME));
	}

	// Whatever edits or reads the open save asks WorkingSave where it is, and
	// the page says which save that is. It has always named the one it opened
	// -- but a working copy made of a save the list only drew would be a save
	// of eight pictures, so the rest is fetched there too.
	@Test
	public void anEditNeverStartsFromWhatTheListDrew () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		wholeSave(saves);
		final File folder = listed(saves, EKUtils.createTempDir(PREFIX).get());

		final File editing = Environment.getInstance().state().workingSave().forEditing(folder, false);
		assertNotEquals(folder.getAbsoluteFile(), editing.getAbsoluteFile());
		assertTrue(new File(editing, "MobileObjects.save").isFile());
		assertTrue(new File(editing, "AR_0001_Somewhere.lvl").isFile());
	}

	@Test
	public void aReadNeverStartsFromWhatTheListDrew () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		wholeSave(saves);
		final File folder = listed(saves, EKUtils.createTempDir(PREFIX).get());

		final File reading = Environment.getInstance().state().workingSave().forReading(folder, false);
		assertEquals(folder.getAbsoluteFile(), reading.getAbsoluteFile());
		assertTrue(new File(reading, "MobileObjects.save").isFile());
	}

	// And where the rest cannot be fetched, no copy is made of what there is.
	@Test
	public void aSaveThatCannotBeCompletedIsNotCopiedForEditing () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		final File archive = wholeSave(saves);
		final File folder = listed(saves, EKUtils.createTempDir(PREFIX).get());
		assertTrue(archive.delete());

		try {
			Environment.getInstance().state().workingSave().forEditing(folder, false);
			fail("a copy of eight pictures is not a save to edit");
		} catch (final IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains(NAME));
		}
	}

	@Test
	public void everySaveOfASearchIsRemembered () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		wholeSave(saves);
		final File resources = new File(getClass().getResource("/").toURI());
		FileUtils.copyFile(new File(resources, "SaveGameExtractorTest/guid2 systemname.savegame")
			, new File(saves, "guid2 systemname.savegame"));

		final File working = EKUtils.createTempDir(PREFIX).get();
		final Optional<SaveGameInfo[]> found =
			new SaveGameExtractor(saves.getAbsolutePath(), working).unpackAllSaves();

		assertEquals(2, found.get().length);
		final List<String> before = new ArrayList<>(names(new File(working, "guid2 systemname.savegame")));
		unpacked().complete(new File(working, "guid2 systemname.savegame"));
		assertEquals("a save with nothing more in it is whole after a look"
			, before, new ArrayList<>(names(new File(working, "guid2 systemname.savegame"))));
	}
}
