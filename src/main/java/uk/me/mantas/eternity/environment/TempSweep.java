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


package uk.me.mantas.eternity.environment;

import org.apache.commons.io.FileUtils;
import uk.me.mantas.eternity.Logger;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Clears out what earlier runs of the editor left in the temp folder.
 *
 * <p>An editor closed normally deletes its working copy of the open save
 * ({@code EK-editing-*}) and the files Undo keeps ({@code EK-history-*}); a
 * resurrection and a conversion delete their own ({@code EK-donor*},
 * {@code EK-convert*}). One that crashes, is killed or loses power cannot, and
 * each of those folders is a copy of a save: 43 of them, 1.7 GB, had collected
 * on the machine this was written on.
 *
 * <p>So they are swept at the next start, by name and by age. Only the names
 * the editor makes go -- one of its prefixes and the number
 * {@code Files.createTempDirectory} adds -- because the temp folder is shared
 * and "EK-" is three letters anyone might use. And nothing touched in the last
 * day goes, since a second editor or a test run that is still going owns
 * folders of the same names. A folder's date is the newer of its own and its
 * immediate children's, because an edit replaces a file inside the copy of
 * the save, which moves that folder's date and not its parent's.
 */
public final class TempSweep {
	private static final Logger logger = Logger.getLogger(TempSweep.class);
	private static final Pattern OURS = Pattern.compile("EK-(editing-|history-|donor|convert)?[0-9]+");
	private static final long OLD_ENOUGH = TimeUnit.DAYS.toMillis(1);

	private TempSweep () {}

	/** Sweeps the temp folder on a thread of its own: a gigabyte takes seconds to delete. */
	public static void inBackground (final File keep) {
		final Thread sweeper = new Thread(() -> {
			final int swept = sweep(
				new File(System.getProperty("java.io.tmpdir")), keep, System.currentTimeMillis());

			if (swept > 0) {
				logger.info("Removed %d folders an earlier run left in the temp folder.%n", swept);
			}
		}, "temp-sweep");

		sweeper.setDaemon(true);
		sweeper.setPriority(Thread.MIN_PRIORITY);
		sweeper.start();
	}

	/**
	 * Deletes every folder of the editor's directly in {@code temp} that
	 * nothing has touched for a day, except {@code keep}: the folder the save
	 * list unpacks into, which the editor empties itself.
	 *
	 * @return how many folders went
	 */
	public static int sweep (final File temp, final File keep, final long now) {
		final File[] folders = temp.listFiles(
			file -> file.isDirectory() && OURS.matcher(file.getName()).matches());

		if (folders == null) {
			return 0;
		}

		int swept = 0;
		for (final File folder : folders) {
			if (folder.getName().equals(keep.getName()) || now - lastTouched(folder) < OLD_ENOUGH) {
				continue;
			}

			if (FileUtils.deleteQuietly(folder)) {
				swept++;
			} else {
				logger.error("Unable to remove %s.%n", folder.getAbsolutePath());
			}
		}

		return swept;
	}

	private static long lastTouched (final File folder) {
		long newest = folder.lastModified();
		final File[] children = folder.listFiles();
		if (children != null) {
			for (final File child : children) {
				newest = Math.max(newest, child.lastModified());
			}
		}

		return newest;
	}
}
