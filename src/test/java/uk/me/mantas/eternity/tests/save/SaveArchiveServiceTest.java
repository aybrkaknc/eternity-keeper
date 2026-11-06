/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2026 the authors.
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

import org.junit.Test;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.SaveArchiveService;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class SaveArchiveServiceTest extends TestHarness {
	@Test
	public void listingReturnsImmutableTypedSaveResults () throws Exception {
		final File fixtures = new File(getClass().getResource("/SaveGameExtractorTest").toURI());
		final File working = Files.createTempDirectory(PREFIX).toFile();
		final SaveArchiveService service = new SaveArchiveService(
			working, Environment.getInstance().state().unpacked());

		final SaveArchiveService.SaveListResult result = service.list(fixtures);
		assertTrue(result.directoryFound());
		assertEquals(2, result.saves().size());
		assertEquals("Elenor", result.saves().get(0).playerName);
		try {
			result.saves().clear();
			fail("save list result must be immutable");
		} catch (final UnsupportedOperationException expected) {
			// expected
		}
	}

	@Test
	public void unsupportedFileProducesTypedArchiveFailure () throws Exception {
		final File working = Files.createTempDirectory(PREFIX).toFile();
		final File invalid = new File(working, "notes.txt");
		assertTrue(invalid.createNewFile());
		final SaveArchiveService service = new SaveArchiveService(
			working, Environment.getInstance().state().unpacked());
		try {
			service.stageArchive(invalid);
			fail("expected archive validation to fail");
		} catch (final SaveArchiveService.SaveArchiveException e) {
			assertEquals(SaveArchiveService.Failure.INVALID_ARCHIVE, e.failure());
		}
	}
}
