/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2016 the authors.
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

import org.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.GameDataExtraction;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Reading the game's data from inside the editor. It used to be three Python
 * scripts with the developer's own paths typed into them, run by hand; now the
 * editor runs one program with the install it already found and shows its
 * progress, so a user who has never heard of UnityPy gets item names and icons.
 */
public class GameDataExtractionTest extends TestHarness {
	private File root;
	private File game;
	private File out;
	private final AtomicInteger finished = new AtomicInteger();
	private GameDataExtraction extraction;

	@Before
	public void makeRoot () {
		root = EKUtils.createTempDir(PREFIX).get();
		game = new File(root, "Pillars of Eternity");
		out = new File(root, "data/gamedata");
		finished.set(0);
	}

	@After
	public void stop () {
		if (extraction != null) {
			extraction.cancel();
		}
	}

	private GameDataExtraction fake (final String mode) throws Exception {
		// The fake's own classes folder rather than java.class.path: surefire
		// runs the tests from a jar whose manifest holds the real class path.
		final String classes = new File(
			FakeExtractor.class.getProtectionDomain().getCodeSource().getLocation().toURI())
			.getAbsolutePath();

		final List<String> command = Arrays.asList(
			new File(System.getProperty("java.home"), "bin/java").getAbsolutePath()
			, "-cp", classes, FakeExtractor.class.getName(), mode);

		extraction = new GameDataExtraction(
			() -> Optional.of(command), out, finished::incrementAndGet);
		return extraction;
	}

	@Test
	public void aSuccessfulRunReportsItsProgressAndReloadsTheCatalogs () throws Exception {
		final GameDataExtraction extraction = fake("succeed");
		assertTrue(extraction.start(game));
		extraction.await(30000);

		final JSONObject status = extraction.status();
		assertFalse(status.getBoolean("running"));
		assertEquals(100, status.getInt("percent"));
		assertEquals("Done", status.getString("text"));
		assertFalse(status.has("error"));
		assertTrue(new File(out, "catalog.json").isFile());
		assertEquals("the catalogs are re-read exactly once", 1, finished.get());
	}

	@Test
	public void progressLinesAreReadAndChatterIsNot () {
		final GameDataExtraction.Progress progress = new GameDataExtraction.Progress();
		progress.read("PROGRESS 42 Reading item and ability bundles (1933 of 4604)");
		progress.read("  250/4604  (97 catalogued)");
		assertEquals(42, progress.percent);
		assertEquals("Reading item and ability bundles (1933 of 4604)", progress.text);

		progress.read("PROGRESS garbage");
		assertEquals("a line that does not parse changes nothing", 42, progress.percent);
	}

	/** The extractor's own reason is what the user needs to see. */
	@Test
	public void aRefusalIsReportedInTheExtractorsWords () throws Exception {
		final GameDataExtraction extraction = fake("refuse");
		assertTrue(extraction.start(game));
		extraction.await(30000);

		final JSONObject status = extraction.status();
		assertFalse(status.getBoolean("running"));
		assertEquals(game + " is not a Pillars of Eternity install.", status.getString("error"));
		assertEquals("nothing new to read", 0, finished.get());
	}

	/** A crash says nothing on its own, so the last thing it printed is the clue. */
	@Test
	public void aCrashIsReportedWithItsLastWords () throws Exception {
		final GameDataExtraction extraction = fake("crash");
		assertTrue(extraction.start(game));
		extraction.await(30000);

		final String error = extraction.status().getString("error");
		assertTrue(error, error.contains("exit code 3"));
		assertTrue(error, error.contains("MemoryError"));
		assertEquals(0, finished.get());
	}

	// The reader clears its own half-built folder when it fails by itself, and
	// at the start of its next run. Stopped, it is killed where it stands, so
	// the folder stayed in the data folder until the next read, if there was
	// one.
	@Test
	public void aStoppedRunLeavesNothingHalfReadBehind () throws Exception {
		final File staging = new File(out.getPath() + ".new");
		FileUtils.writeStringToFile(new File(out, "catalog.json"), "what was read before", "UTF-8");

		final GameDataExtraction extraction = fake("hang");
		assertTrue(extraction.start(game));
		final long deadline = System.currentTimeMillis() + 30000;
		while (!new File(staging, "catalog.json").exists() && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}

		assertTrue("the fake has read half of it", new File(staging, "catalog.json").exists());
		extraction.cancel();
		extraction.await(30000);

		assertFalse("nothing half-read is left", staging.exists());
		assertEquals("and what was read before is untouched", "what was read before"
			, FileUtils.readFileToString(new File(out, "catalog.json"), "UTF-8"));
		assertTrue(extraction.status().getString("error").startsWith("Stopped before it finished"));
	}

	@Test
	public void oneRunAtATime () throws Exception {
		final GameDataExtraction extraction = fake("hang");
		assertTrue(extraction.start(game));
		assertFalse("a second run would write into the same folder", extraction.start(game));
		assertTrue(extraction.status().getBoolean("running"));

		extraction.cancel();
		extraction.await(30000);
		assertFalse(extraction.status().getBoolean("running"));
		assertEquals(0, finished.get());
	}

	@Test
	public void withNoExtractorNothingStartsAndTheStatusSaysSo () {
		extraction = new GameDataExtraction(Optional::empty, out, finished::incrementAndGet);
		assertFalse(extraction.available());
		assertFalse(extraction.start(game));
		assertFalse(extraction.status().getBoolean("available"));
	}

	/** What is already there: what the Settings dialog shows. */
	@Test
	public void theStatusDescribesTheDataAlreadyRead () throws Exception {
		fake("succeed").start(game);
		extraction.await(30000);

		final JSONObject data = fake("succeed").status().getJSONObject("data");
		assertEquals(out.getCanonicalPath(), data.getString("folder"));
		assertEquals(2156, data.getInt("items"));
		assertEquals("2026-09-19T10:00:00", data.getString("written"));
		assertEquals(game.getPath(), data.getString("game"));
	}

	@Test
	public void noDataYetIsNoData () throws Exception {
		assertFalse(fake("succeed").status().has("data"));
	}
}
