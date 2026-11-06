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

package uk.me.mantas.eternity.tests;

import org.junit.Test;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.AppPaths;
import uk.me.mantas.eternity.environment.Environment;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertTrue;

/**
 * The tests must never touch the user's own editor. Since settings moved to
 * %APPDATA%\Eternity Keeper, any test that reached {@code Settings} without
 * pointing it elsewhere created that folder for real, and a settings file a
 * test saved would be what the user's editor opened next.
 */
public class TestIsolationTest extends TestHarness {
	private static boolean inTemp (final File file) throws IOException {
		final String temp = new File(System.getProperty("java.io.tmpdir")).getCanonicalPath();
		return file.getCanonicalPath().startsWith(temp);
	}

	@Test
	public void settingsAreWrittenToTempNotTheUsersDataFolder () throws IOException {
		Settings.getInstance().save();

		final File settings = Environment.getInstance().directory().settingsFile();
		assertTrue(settings.getPath(), settings.isFile());
		assertTrue(settings.getPath(), inTemp(settings));
	}

	@Test
	public void everyPathTheAppWouldWriteIsInTemp () throws IOException {
		final AppPaths paths = AppPaths.forThisProcess();
		assertTrue(paths.data().getPath(), inTemp(paths.data()));
		assertTrue(paths.logFile().getPath(), inTemp(paths.logFile()));
		assertTrue(paths.gameData().getPath(), inTemp(paths.gameData()));
		assertTrue(paths.backups().getPath(), inTemp(paths.backups()));
	}
}
