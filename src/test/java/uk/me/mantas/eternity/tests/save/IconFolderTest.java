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
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.save.IconFolder;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Icons read off disk as base64, cached for the life of the process.
 *
 * <p>ItemCatalog and StrongholdCatalog each had this, cached in a plain
 * {@code HashMap}, and the item catalog's is reached from {@code BrowseItems},
 * {@code BrowseAbilities} and {@code SavedGameOpener} — all of which run on
 * the shared worker pool, and routinely at the same moment when a save opens
 * and a browser starts filling in art. Concurrent puts on a HashMap can drop
 * entries or corrupt its buckets.
 */
public class IconFolderTest extends TestHarness {
	private File folderWith (final int icons) throws Exception {
		final File folder = EKUtils.createTempDir(PREFIX).get();
		for (int i = 0; i < icons; i++) {
			FileUtils.writeStringToFile(
				new File(folder, "icon" + i + ".png"), "png-" + i, StandardCharsets.UTF_8);
		}

		return folder;
	}

	private static String encoded (final int i) {
		return Base64.getEncoder().encodeToString(("png-" + i).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void anIconComesBackAsBase64 () throws Exception {
		final IconFolder icons = new IconFolder(folderWith(2));

		assertEquals(encoded(0), icons.data("icon0.png"));
		assertEquals(encoded(1), icons.data("icon1.png"));
	}

	@Test
	public void aMissingIconOrFolderIsEmptyRatherThanAnError () throws Exception {
		assertEquals("", new IconFolder(folderWith(1)).data("nope.png"));
		assertEquals("", new IconFolder(folderWith(1)).data(""));
		assertEquals("", new IconFolder(folderWith(1)).data(null));
		assertEquals("", new IconFolder(null).data("icon0.png"));
	}

	/** Cached: the file can go away and the answer does not change. */
	@Test
	public void anIconIsReadFromDiskOnce () throws Exception {
		final File folder = folderWith(1);
		final IconFolder icons = new IconFolder(folder);

		assertEquals(encoded(0), icons.data("icon0.png"));
		assertTrue(new File(folder, "icon0.png").delete());
		assertEquals(encoded(0), icons.data("icon0.png"));
	}

	/** A path in a request crosses the bridge from a page. */
	@Test
	public void aNameCannotReachOutsideItsFolder () throws Exception {
		final File folder = folderWith(1);
		FileUtils.writeStringToFile(
			new File(folder.getParentFile(), "secret.png"), "secret", StandardCharsets.UTF_8);

		assertEquals("", new IconFolder(folder).data("../secret.png"));
		assertEquals("", new IconFolder(folder).data("..\\secret.png"));
	}

	@Test
	public void manyThreadsReadingAtOnceAllGetTheRightIcon () throws Exception {
		final int count = 400;
		final IconFolder icons = new IconFolder(folderWith(count));
		final ExecutorService pool = Executors.newFixedThreadPool(20);

		try {
			final List<Future<Integer>> results = new ArrayList<>();
			for (int thread = 0; thread < 20; thread++) {
				final int offset = thread;
				results.add(pool.submit(() -> {
					int wrong = 0;
					for (int i = 0; i < count; i++) {
						final int icon = (i + offset * 37) % count;
						if (!encoded(icon).equals(icons.data("icon" + icon + ".png"))) {
							wrong++;
						}
					}

					return wrong;
				}));
			}

			for (final Future<Integer> result : results) {
				assertEquals(Integer.valueOf(0), result.get(60, TimeUnit.SECONDS));
			}
		} finally {
			pool.shutdownNow();
		}

		assertEquals("every icon cached exactly once", count, icons.cachedCount());
	}
}
