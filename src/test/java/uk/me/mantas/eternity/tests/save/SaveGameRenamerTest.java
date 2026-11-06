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

import net.lingala.zip4j.ZipFile;
import org.apache.commons.io.FileUtils;
import org.joox.Match;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.save.SaveBackups;
import uk.me.mantas.eternity.save.SaveBackups.Backup;
import uk.me.mantas.eternity.save.SaveBackups.Reason;
import uk.me.mantas.eternity.save.SaveGameRenamer;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.joox.JOOX.$;
import static org.junit.Assert.*;

public class SaveGameRenamerTest extends TestHarness {
	// Includes Polish characters to make sure the encoding survives.
	private static final String NEW_NAME = "Zapis Wielka Sala ĄĘŁŻÓćń";

	private String userSaveName (final File saveinfo) throws IOException {
		final Match xml = $(new String(
			EKUtils.removeBOM(FileUtils.readFileToByteArray(saveinfo)), "UTF-8"));

		return xml.find("Simple[name='UserSaveName']").attr("value");
	}

	@Test
	public void renamesArchiveAndWorkingCopy ()
		throws URISyntaxException
		, IOException {

		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		// Build an extracted working copy and a .savegame archive from the
		// test fixture.
		final File fixture = new File(
			getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI());
		final File extracted = new File(workingDir.get(), "extracted.savegame");
		FileUtils.copyDirectory(fixture, extracted);

		final File archive = new File(workingDir.get(), "original.savegame");
		final ZipFile zip = new ZipFile(archive);
		zip.addFiles(new ArrayList<>(Arrays.asList(extracted.listFiles())));

		final SaveGameRenamer renamer =
			new SaveGameRenamer(archive.getAbsolutePath(), extracted.getAbsolutePath());
		renamer.rename(NEW_NAME);

		// The working copy is updated...
		assertEquals(NEW_NAME, userSaveName(new File(extracted, "saveinfo.xml")));

		// ...and so is the saveinfo.xml inside the archive.
		final File reExtracted = new File(workingDir.get(), "reextracted");
		new ZipFile(archive).extractAll(reExtracted.getAbsolutePath());
		assertEquals(NEW_NAME, userSaveName(new File(reExtracted, "saveinfo.xml")));

		// The rest of the archive is intact.
		assertTrue(new File(reExtracted, "MobileObjects.save").exists());
	}

	/** Renaming rewrites the player's own file, so a copy of it comes first. */
	@Test
	public void theSaveIsBackedUpAsItWasBeforeRenaming () throws URISyntaxException, IOException {
		final File workingDir = EKUtils.createTempDir(PREFIX).get();
		final File fixture = new File(
			getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI());
		final File extracted = new File(workingDir, "extracted.savegame");
		FileUtils.copyDirectory(fixture, extracted);

		final File archive = new File(workingDir, "abc 7 Encampment.savegame");
		new ZipFile(archive).addFiles(new ArrayList<>(Arrays.asList(extracted.listFiles())));
		final byte[] before = FileUtils.readFileToByteArray(archive);

		new SaveGameRenamer(archive.getAbsolutePath(), extracted.getAbsolutePath()).rename(NEW_NAME);

		final List<Backup> backups = SaveBackups.forThisProcess().list();
		assertEquals(1, backups.size());
		assertEquals(Reason.RENAME, backups.get(0).reason);
		assertEquals("Start", backups.get(0).userSaveName);
		assertEquals(archive.getCanonicalFile(), backups.get(0).original);
		assertArrayEquals(before, FileUtils.readFileToByteArray(backups.get(0).file));
	}

	// Renaming took saveinfo.xml out of the player's own file and then put the
	// new one in: a failure in between left a save with no saveinfo.xml
	// (found by review, 2026-10-27). The new file is made beside it and moved
	// over it whole. A hard link still to the old file shows which happened:
	// an edit in place changes what it sees, a replacement does not.
	@Test
	public void theOriginalIsReplacedWholeNotEditedInPlace () throws URISyntaxException, IOException {
		final File workingDir = EKUtils.createTempDir(PREFIX).get();
		final File fixture = new File(
			getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI());
		final File extracted = new File(workingDir, "extracted.savegame");
		FileUtils.copyDirectory(fixture, extracted);

		final File archive = new File(workingDir, "abc 7 Encampment.savegame");
		new ZipFile(archive).addFiles(new ArrayList<>(Arrays.asList(extracted.listFiles())));
		final byte[] before = FileUtils.readFileToByteArray(archive);
		final File link = new File(workingDir, "link to the old file");
		Files.createLink(link.toPath(), archive.toPath());

		new SaveGameRenamer(archive.getAbsolutePath(), extracted.getAbsolutePath()).rename(NEW_NAME);

		assertArrayEquals("the old file was never written to", before, FileUtils.readFileToByteArray(link));
		assertEquals("the same entries, saveinfo.xml once", entries(link), entries(archive));
		final File reExtracted = new File(workingDir, "reextracted");
		new ZipFile(archive).extractAll(reExtracted.getAbsolutePath());
		assertEquals(NEW_NAME, userSaveName(new File(reExtracted, "saveinfo.xml")));
		assertEquals("nothing is left beside it"
			, new ArrayList<>(Arrays.asList("abc 7 Encampment.savegame", "extracted.savegame"
				, "link to the old file", "reextracted"))
			, sortedNames(workingDir));
	}

	// The new name went into the working copy first and the player's file
	// second, so a rename that failed on the file left the two disagreeing:
	// the page went on with a name the save on disk never had. A rename that
	// fails changes neither.
	@Test
	public void aRenameThatFailsChangesNeitherCopy () throws URISyntaxException, IOException {
		final File workingDir = EKUtils.createTempDir(PREFIX).get();
		final File fixture = new File(
			getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI());
		final File extracted = new File(workingDir, "extracted.savegame");
		FileUtils.copyDirectory(fixture, extracted);
		final byte[] saveinfo = FileUtils.readFileToByteArray(new File(extracted, "saveinfo.xml"));

		final File archive = new File(workingDir, "abc 7 Encampment.savegame");
		FileUtils.writeStringToFile(archive, "damaged: not a zip at all", "UTF-8");
		final byte[] before = FileUtils.readFileToByteArray(archive);

		try {
			new SaveGameRenamer(archive.getAbsolutePath(), extracted.getAbsolutePath()).rename(NEW_NAME);
			fail("a save that is not a zip was renamed");
		} catch (final IOException expected) {
			// Refused; what matters is what it left.
		}

		assertArrayEquals(before, FileUtils.readFileToByteArray(archive));
		assertArrayEquals("the working copy keeps its name"
			, saveinfo, FileUtils.readFileToByteArray(new File(extracted, "saveinfo.xml")));
		assertEquals("nothing is left beside it"
			, new ArrayList<>(Arrays.asList("abc 7 Encampment.savegame", "extracted.savegame"))
			, sortedNames(workingDir));
	}

	private static List<String> entries (final File zip) throws IOException {
		final List<String> names = new ArrayList<>();
		for (final net.lingala.zip4j.model.FileHeader header : new ZipFile(zip).getFileHeaders()) {
			names.add(header.getFileName());
		}

		java.util.Collections.sort(names);
		return names;
	}

	private static List<String> sortedNames (final File directory) {
		final List<String> names = new ArrayList<>(Arrays.asList(directory.list()));
		java.util.Collections.sort(names);
		return names;
	}

	@Test(expected = FileNotFoundException.class)
	public void throwsWhenArchiveMissing () throws IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		new SaveGameRenamer(
			new File(workingDir.get(), "404.savegame").getAbsolutePath()
			, workingDir.get().getAbsolutePath());
	}
}
