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


package uk.me.mantas.eternity.tests.save;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.cef.callback.CefQueryCallback;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.Directories;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.handlers.ListSavedGames.SaveInfoLister;
import uk.me.mantas.eternity.save.SaveGameExtractor;
import uk.me.mantas.eternity.save.SaveGameInfo;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The save list beside saves that are damaged, or made to do damage.
 *
 * <p>The saves folder is the one place the editor reads files it did not write
 * and the user may not have written either: a download cut short, a disk
 * error, a save somebody shared. One of them must cost that save's tile and
 * nothing more -- not the list, not a file outside the folder a save is
 * unpacked into, not a request to somebody's server.
 */
public class DamagedSavesTest extends TestHarness {
	private static final String GOOD = "good 1 Encampment.savegame";

	/** What a save's tile is drawn from, as the list fixture holds it. */
	private static Map<String, byte[]> tileFiles () throws Exception {
		final File fixture = new File(DamagedSavesTest.class
			.getResource("/SaveGameExtractorTest/guid systemname.savegame").toURI());

		final Map<String, byte[]> files = new LinkedHashMap<>();
		try (final ZipFile zip = new ZipFile(fixture)) {
			final Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				final ZipEntry entry = entries.nextElement();
				try (final InputStream in = zip.getInputStream(entry)) {
					files.put(entry.getName(), IOUtils.toByteArray(in));
				}
			}
		}

		return files;
	}

	private static File save (final File saves, final String name, final Map<String, byte[]> files)
		throws IOException {

		final File save = new File(saves, name);
		try (final ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(save))) {
			for (final Map.Entry<String, byte[]> file : files.entrySet()) {
				zip.putNextEntry(new ZipEntry(file.getKey()));
				zip.write(file.getValue());
				zip.closeEntry();
			}
		}

		return save;
	}

	private static File saveWithInfo (final File saves, final String name, final String saveinfo)
		throws Exception {

		final Map<String, byte[]> files = tileFiles();
		files.put("saveinfo.xml", saveinfo.getBytes(StandardCharsets.UTF_8));
		return save(saves, name, files);
	}

	private static String saveinfo () throws Exception {
		return new String(tileFiles().get("saveinfo.xml"), StandardCharsets.UTF_8);
	}

	private static TreeSet<String> listed (final File saves) {
		final File working = EKUtils.createTempDir(PREFIX).get();
		final Optional<SaveGameInfo[]> found =
			new SaveGameExtractor(saves.getAbsolutePath(), working).unpackAllSaves();

		assertTrue(found.isPresent());
		return Arrays.stream(found.get())
			.map(info -> new File(info.absolutePath).getName())
			.collect(Collectors.toCollection(TreeSet::new));
	}

	private static TreeSet<String> only (final String... names) {
		return new TreeSet<>(Arrays.asList(names));
	}

	@Test
	public void aFileThatIsNotASaveCostsOnlyItsOwnTile () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		final File good = save(saves, GOOD, tileFiles());
		final byte[] whole = FileUtils.readFileToByteArray(good);

		final byte[] noise = new byte[4096];
		new java.util.Random(7).nextBytes(noise);
		FileUtils.writeByteArrayToFile(new File(saves, "noise 2 Noise.savegame"), noise);
		FileUtils.writeByteArrayToFile(new File(saves, "empty 3 Empty.savegame"), new byte[0]);

		// A download or a copy cut short: a zip's directory is at its end.
		FileUtils.writeByteArrayToFile(new File(saves, "cut 4 Short.savegame")
			, Arrays.copyOf(whole, whole.length / 2));

		// And one whose directory survived while what it lists did not.
		final byte[] holed = whole.clone();
		Arrays.fill(holed, 40, holed.length / 2, (byte) 0);
		FileUtils.writeByteArrayToFile(new File(saves, "holed 5 Holed.savegame"), holed);

		assertEquals(only(GOOD), listed(saves));
	}

	@Test
	public void aSaveWithoutWhatATileNeedsCostsOnlyItsOwnTile () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		save(saves, GOOD, tileFiles());

		final Map<String, byte[]> noInfo = tileFiles();
		noInfo.remove("saveinfo.xml");
		save(saves, "noinfo 2 NoInfo.savegame", noInfo);

		final Map<String, byte[]> noScreenshot = tileFiles();
		noScreenshot.remove("screenshot.png");
		save(saves, "noshot 3 NoShot.savegame", noScreenshot);

		save(saves, "nothing 4 Nothing.savegame", new LinkedHashMap<>());

		assertEquals(only(GOOD), listed(saves));
	}

	@Test
	public void aSummaryThatCannotBeReadCostsOnlyItsOwnTile () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		save(saves, GOOD, tileFiles());

		saveWithInfo(saves, "notxml 2 NotXml.savegame", "<Complex name=\"Root\"");
		saveWithInfo(saves, "blank 3 Blank.savegame", "");
		saveWithInfo(saves, "bare 4 Bare.savegame", "<Complex name=\"Root\"/>");
		saveWithInfo(saves, "chapter 5 Chapter.savegame"
			, saveinfo().replace("name=\"Chapter\" type=\"System.Int32, mscorlib\" value=\"1\""
				, "name=\"Chapter\" type=\"System.Int32, mscorlib\" value=\"the first\""));
		saveWithInfo(saves, "date 6 Date.savegame"
			, saveinfo().replace("value=\"05/10/2015 15:44:52\"", "value=\"yesterday\""));
		saveWithInfo(saves, "binary 7 Binary.savegame", "\u0000\u0001\u0002￿<<<>>>&&&");

		assertEquals(only(GOOD), listed(saves));
	}

	// A save left out is not left out in silence: the list says which file and
	// why, or its owner is left wondering where the save went.
	@Test
	public void whatIsLeftOutIsNamedWithTheReason () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		save(saves, GOOD, tileFiles());

		FileUtils.writeByteArrayToFile(new File(saves, "noise 2 Noise.savegame"), new byte[4096]);
		final Map<String, byte[]> noInfo = tileFiles();
		noInfo.remove("saveinfo.xml");
		save(saves, "noinfo 3 NoInfo.savegame", noInfo);
		saveWithInfo(saves, "bare 4 Bare.savegame", "<Complex name=\"Root\"/>");
		save(saves, "renamed.savegame", tileFiles());

		final SaveGameExtractor extractor =
			new SaveGameExtractor(saves.getAbsolutePath(), EKUtils.createTempDir(PREFIX).get());

		assertEquals(1, extractor.unpackAllSaves().get().length);

		final Map<String, String> reasons = extractor.unreadable().stream()
			.collect(Collectors.toMap(file -> file.name, file -> file.reason));

		assertEquals(only("noise 2 Noise.savegame", "noinfo 3 NoInfo.savegame"
			, "bare 4 Bare.savegame", "renamed.savegame"), new TreeSet<>(reasons.keySet()));

		assertTrue(reasons.get("noise 2 Noise.savegame")
			, reasons.get("noise 2 Noise.savegame").contains("could not be unpacked"));
		assertTrue(reasons.get("noinfo 3 NoInfo.savegame")
			, reasons.get("noinfo 3 NoInfo.savegame").contains("saveinfo.xml"));
		assertTrue(reasons.get("bare 4 Bare.savegame")
			, reasons.get("bare 4 Bare.savegame").contains("saveinfo.xml"));
		assertTrue(reasons.get("renamed.savegame")
			, reasons.get("renamed.savegame").contains("name"));
	}

	@Test
	public void theListSaysWhatItLeftOut () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		save(saves, GOOD, tileFiles());
		FileUtils.writeByteArrayToFile(new File(saves, "noise 2 Noise.savegame"), new byte[4096]);
		Environment.getInstance().directory().working(EKUtils.createTempDir(PREFIX).get());

		final JSONObject listed = searched(saves.getAbsolutePath());
		assertEquals(1, listed.getJSONArray("saves").length());
		assertEquals(1, listed.getJSONArray("unreadable").length());
		assertEquals("noise 2 Noise.savegame"
			, listed.getJSONArray("unreadable").getJSONObject(0).getString("name"));
		assertFalse(listed.getJSONArray("unreadable").getJSONObject(0).getString("reason").isEmpty());
	}

	@Test
	public void aFolderOfNothingTheListCanReadStillSaysWhatWasThere () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		FileUtils.writeByteArrayToFile(new File(saves, "noise 2 Noise.savegame"), new byte[4096]);
		Environment.getInstance().directory().working(EKUtils.createTempDir(PREFIX).get());

		final JSONObject listed = searched(saves.getAbsolutePath());
		assertEquals("NO_RESULTS", listed.getString("error"));
		assertEquals(1, listed.getJSONArray("unreadable").length());
	}

	// A search that fails must still answer: the page waits for the reply
	// with its Search button disabled, and waited for ever.
	@Test
	public void aSearchThatFailsStillAnswers () {
		final Directories directories = mockEnvironment().directory();
		doThrow(new IllegalStateException("the disk is on fire")).when(directories).emptyWorking();

		final JSONObject listed = searched("anywhere");
		assertEquals("LIST_ERR", listed.getString("error"));
		assertTrue(listed.getString("msg"), listed.getString("msg").contains("the disk is on fire"));
	}

	private static JSONObject searched (final String saves) {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new SaveInfoLister(saves, callback).run();

		final ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
		verify(callback).success(reply.capture());
		return new JSONObject(reply.getValue());
	}

	// saveinfo.xml is the game's own and never carries a document type. One
	// that does is asking the parser to go and read something: a file on this
	// disk, or an address that then knows the save was opened and by whom.
	@Test
	public void aSummaryCannotMakeTheEditorReadAnotherFile () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		final File outside = new File(EKUtils.createTempDir(PREFIX).get(), "outside.dtd");
		FileUtils.writeStringToFile(outside, "<!ENTITY name \"read from outside the save\">", "UTF-8");

		final String doctype = "<!DOCTYPE Complex [<!ENTITY % outside SYSTEM \""
			+ outside.toURI() + "\"> %outside;]>\n";

		saveWithInfo(saves, "reads 1 Reads.savegame"
			, doctype + saveinfo().replace("value=\"Start\"", "value=\"&name;\""));

		final File working = EKUtils.createTempDir(PREFIX).get();
		final SaveGameInfo[] found =
			new SaveGameExtractor(saves.getAbsolutePath(), working).unpackAllSaves().get();

		for (final SaveGameInfo info : found) {
			assertNotEquals("read from outside the save", info.userSaveName);
		}
	}

	@Test
	public void aSummaryCannotMakeTheEditorCallAnyone () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();

		try (final ServerSocket listening = new ServerSocket(0)) {
			listening.setSoTimeout(1500);
			final String address = "http://127.0.0.1:" + listening.getLocalPort() + "/who-opened-this.dtd";

			saveWithInfo(saves, "calls 1 Calls.savegame"
				, "<!DOCTYPE Complex SYSTEM \"" + address + "\">\n" + saveinfo());
			saveWithInfo(saves, "calls 2 Calls.savegame"
				, "<!DOCTYPE Complex [<!ENTITY % out SYSTEM \"" + address + "\"> %out;]>\n" + saveinfo());

			final Thread listing = new Thread(() -> listed(saves));
			listing.setDaemon(true);
			listing.start();

			try {
				listening.accept().close();
				fail("listing the save made the editor connect to " + address);
			} catch (final SocketTimeoutException nobodyCalled) {
				// As it should be.
			}
		}
	}

	// A zip names its files by path, and a path can climb out of the folder
	// it is unpacked into.
	@Test
	public void aSaveCannotUnpackOutsideItsOwnFolder () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		final File elsewhere = EKUtils.createTempDir(PREFIX).get();

		final Map<String, byte[]> files = tileFiles();
		files.put("../climbed.txt", "outside".getBytes(StandardCharsets.UTF_8));
		files.put("../../climbed.txt", "outside".getBytes(StandardCharsets.UTF_8));
		files.put(new File(elsewhere, "planted.txt").getAbsolutePath()
			, "outside".getBytes(StandardCharsets.UTF_8));
		files.put(new File(elsewhere, "planted2.txt").getAbsolutePath().replace('\\', '/')
			, "outside".getBytes(StandardCharsets.UTF_8));
		save(saves, "climbs 1 Climbs.savegame", files);

		final File working = EKUtils.createTempDir(PREFIX).get();
		final SaveGameInfo[] found =
			new SaveGameExtractor(saves.getAbsolutePath(), working).unpackAllSaves().get();

		assertEquals(1, found.length);
		final File folder = new File(found[0].absolutePath);

		try {
			Environment.getInstance().state().unpacked().complete(folder);
		} catch (final IOException refused) {
			// Refusing the whole save is as good as leaving the climbers out.
		}

		assertFalse(new File(working, "climbed.txt").exists());
		assertFalse(new File(working.getParentFile(), "climbed.txt").exists());
		assertFalse(new File(elsewhere, "planted.txt").exists());
		assertFalse(new File(elsewhere, "planted2.txt").exists());
		assertEquals("nothing but the save's own folder in the list's", only(folder.getName())
			, new TreeSet<>(Arrays.asList(working.list())));
	}
}
