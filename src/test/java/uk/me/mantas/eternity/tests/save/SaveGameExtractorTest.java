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
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import org.junit.Test;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.SaveGameExtractor;
import uk.me.mantas.eternity.save.SaveGameInfo;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class SaveGameExtractorTest extends TestHarness {
	@Test
	public void savesLocationNotExists () {
		File mockWorkingDirectory = mock(File.class);
		SaveGameExtractor saveGameExtractor =
			new SaveGameExtractor("404", mockWorkingDirectory);

		assertFalse(saveGameExtractor.unpackAllSaves().isPresent());
	}

	@Test
	public void saveGamesExtractedSuccessfully ()
		throws IOException, URISyntaxException {

		String savesLocation = new File(
			this.getClass().getResource("/SaveGameExtractorTest").toURI())
			.getAbsolutePath();

		File workingDirectory = Files.createTempDirectory(PREFIX).toFile();

		SaveGameExtractor saveGameExtractor =
			new SaveGameExtractor(savesLocation, workingDirectory);

		Optional<SaveGameInfo[]> saveGameInfo =
			saveGameExtractor.unpackAllSaves();

		assertTrue(saveGameInfo.isPresent());
		assertEquals(2, saveGameInfo.get().length);
		assertEquals(6, saveGameInfo.get()[0].portraits.size());

		// The folder also holds two files that are not saves; neither is
		// counted towards progress or unzipped.
		assertEquals(2, saveGameExtractor.totalFiles.get());
		assertEquals(2, saveGameExtractor.currentCount.get());
	}

	/**
	 * The saves folder is where the game writes, and other things end up in it
	 * too -- eternity.log did, on this machine. Every one of them was handed to
	 * the unzipper on every search and logged as an error, and counted towards
	 * a progress bar that could then never read what it was doing.
	 */
	@Test
	public void onlySaveGamesAreUnzipped () throws Exception {
		final File fixtures = new File(getClass().getResource("/SaveGameExtractorTest").toURI());
		final File saves = Files.createTempDirectory(PREFIX).toFile();

		FileUtils.copyFile(new File(fixtures, "guid systemname.savegame")
			, new File(saves, "guid systemname.savegame"));
		FileUtils.writeStringToFile(new File(saves, "eternity.log"), "not a save", "UTF-8");
		FileUtils.writeStringToFile(new File(saves, "notes.txt"), "not a save", "UTF-8");
		assertTrue(new File(saves, "converted").mkdir());

		final Logger logger = interceptLogging(SaveGameExtractor.class);
		final SaveGameExtractor extractor =
			new SaveGameExtractor(saves.getAbsolutePath(), Files.createTempDirectory(PREFIX).toFile());

		final Optional<SaveGameInfo[]> found = extractor.unpackAllSaves();

		assertTrue(found.isPresent());
		assertEquals(1, found.get().length);
		assertEquals(1, extractor.totalFiles.get());
		verify(logger, never()).error(anyString(), (Object[]) any());
	}

	/** A file named like a save that is not one still fails politely. */
	@Test
	public void aSaveGameThatIsNotAZipIsSkipped () throws Exception {
		final File fixtures = new File(getClass().getResource("/SaveGameExtractorTest").toURI());
		final File saves = Files.createTempDirectory(PREFIX).toFile();

		FileUtils.copyFile(new File(fixtures, "guid systemname.savegame")
			, new File(saves, "guid systemname.savegame"));
		FileUtils.copyFile(new File(fixtures, "bad"), new File(saves, "broken.savegame"));
		FileUtils.copyFile(new File(fixtures, "no.required.files")
			, new File(saves, "empty.savegame"));

		final SaveGameExtractor extractor =
			new SaveGameExtractor(saves.getAbsolutePath(), Files.createTempDirectory(PREFIX).toFile());

		final Optional<SaveGameInfo[]> found = extractor.unpackAllSaves();

		assertTrue(found.isPresent());
		assertEquals(1, found.get().length);
		assertEquals(3, extractor.totalFiles.get());
	}

	@Test
	public void unpackSingleSaveExtractsValidArchiveAndRejectsInvalid () throws Exception {
		final File fixtures = new File(getClass().getResource("/SaveGameExtractorTest").toURI());
		final File workingDirectory = Files.createTempDirectory(PREFIX).toFile();
		final SaveGameExtractor extractor =
			new SaveGameExtractor(fixtures.getAbsolutePath(), workingDirectory);

		assertFalse(extractor.unpackSingleSave(null).isPresent());
		assertFalse(extractor.unpackSingleSave(new File(workingDirectory, "missing.savegame")).isPresent());

		final Optional<SaveGameInfo> valid = extractor.unpackSingleSave(
			new File(fixtures, "guid systemname.savegame"));
		assertTrue(valid.isPresent());
		assertEquals("Elenor", valid.get().playerName);
		assertEquals("Encampment", valid.get().sceneTitle);
		assertEquals(6, valid.get().portraits.size());
		assertTrue(extractor.unreadable().isEmpty());

		final File broken = new File(workingDirectory, "broken.savegame");
		FileUtils.copyFile(new File(fixtures, "bad"), broken);
		assertFalse(extractor.unpackSingleSave(broken).isPresent());
		assertEquals(1, extractor.unreadable().size());
		assertEquals("broken.savegame", extractor.unreadable().get(0).name);
	}

	@Test
	public void droppedArchivesWithSameNameUseIsolatedDirectories () throws Exception {
		final File fixtures = new File(getClass().getResource("/SaveGameExtractorTest").toURI());
		final File root = Files.createTempDirectory(PREFIX).toFile();
		final File firstSource = new File(root, "first");
		final File secondSource = new File(root, "second");
		assertTrue(firstSource.mkdir());
		assertTrue(secondSource.mkdir());
		final File firstArchive = new File(firstSource, "guid systemname.savegame");
		final File secondArchive = new File(secondSource, "guid systemname.savegame");
		FileUtils.copyFile(new File(fixtures, "guid systemname.savegame"), firstArchive);
		FileUtils.copyFile(new File(fixtures, "guid systemname.savegame"), secondArchive);
		final File stale = new File(root, "stale-area.lvl");
		FileUtils.writeStringToFile(stale, "first archive only", "UTF-8");
		new ZipFile(firstArchive).addFile(stale, new ZipParameters());

		final File working = Files.createTempDirectory(PREFIX).toFile();
		final SaveGameExtractor extractor = new SaveGameExtractor(root.getAbsolutePath(), working);
		final SaveGameInfo first = extractor.unpackSingleSave(firstArchive).get();
		Environment.getInstance().state().unpacked().complete(new File(first.absolutePath));
		assertTrue(new File(first.absolutePath, "stale-area.lvl").isFile());

		final SaveGameInfo second = extractor.unpackSingleSave(secondArchive).get();
		assertNotEquals(first.absolutePath, second.absolutePath);
		Environment.getInstance().state().unpacked().complete(new File(second.absolutePath));
		assertFalse(new File(second.absolutePath, "stale-area.lvl").exists());
	}

	@Test
	public void reopeningChangedDroppedArchiveRemovesFilesNoLongerPresent () throws Exception {
		final File fixtures = new File(getClass().getResource("/SaveGameExtractorTest").toURI());
		final File root = Files.createTempDirectory(PREFIX).toFile();
		final File archive = new File(root, "guid systemname.savegame");
		FileUtils.copyFile(new File(fixtures, "guid systemname.savegame"), archive);
		final File extra = new File(root, "removed-on-replacement.lvl");
		FileUtils.writeStringToFile(extra, "old entry", "UTF-8");
		new ZipFile(archive).addFile(extra, new ZipParameters());

		final File working = Files.createTempDirectory(PREFIX).toFile();
		final SaveGameExtractor extractor = new SaveGameExtractor(root.getAbsolutePath(), working);
		final SaveGameInfo first = extractor.unpackSingleSave(archive).get();
		Environment.getInstance().state().unpacked().complete(new File(first.absolutePath));
		assertTrue(new File(first.absolutePath, extra.getName()).isFile());

		FileUtils.copyFile(new File(fixtures, "guid systemname.savegame"), archive, true);
		final SaveGameInfo second = extractor.unpackSingleSave(archive).get();
		Environment.getInstance().state().unpacked().complete(new File(second.absolutePath));
		assertFalse(new File(second.absolutePath, extra.getName()).exists());
	}
}
