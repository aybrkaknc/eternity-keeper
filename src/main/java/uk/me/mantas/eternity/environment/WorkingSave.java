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

package uk.me.mantas.eternity.environment;

import org.apache.commons.io.FileUtils;
import uk.me.mantas.eternity.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Which directory holds the current state of the save the editor has open.
 *
 * <p>The save list unpacks every save once and the editor opens that unpacked
 * directory. Managers used to edit it in place, so an Apply the user then
 * discarded was still there the next time the same save was opened from the
 * list, and a Save from that point wrote it into a file. So:
 *
 * <ul>
 * <li>until the first Save, the first edit copies the opened save somewhere
 *     private and every edit and read after it uses that copy;</li>
 * <li>after a Save, the directory that was written is the live one, exactly as
 *     before;</li>
 * <li>opening a save from the list forgets both and deletes the copy.</li>
 * </ul>
 *
 * <p>{@code savedYet} is still the client's to say, because the client is what
 * knows whether this open session has saved; the server only refuses to
 * believe it when nothing was written.
 */
public class WorkingSave {
	private static final Logger logger = Logger.getLogger(WorkingSave.class);

	private File opened = null;
	private File copy = null;
	private File written = null;
	private final EditHistory history = new EditHistory();
	private final UnpackedSaves unpacked;

	public WorkingSave () {
		this(new UnpackedSaves());
	}

	/** @param unpacked which of the folders the save list made hold a whole save */
	public WorkingSave (final UnpackedSaves unpacked) {
		this.unpacked = unpacked;
	}

	/** What the Applies since the save was opened or last written changed. */
	public EditHistory history () {
		return history;
	}

	/** The directory to read the open save's current state from. */
	public synchronized File forReading (final File opened, final boolean savedYet) {
		if (savedYet && written != null) {
			return written;
		}

		if (isCopyOf(opened)) {
			return copy;
		}

		// Opening a save unpacks the rest of it, and the page names the save it
		// opened; this is for the day something names another. One that cannot
		// be completed is handed back as it is, and the reader reports the
		// file it does not find.
		try {
			unpacked.complete(opened);
		} catch (final IOException e) {
			logger.error("%s%n", e.getMessage());
		}

		return opened;
	}

	/**
	 * The directory to edit. Makes the private copy the first time an unsaved
	 * session asks; a save that does not exist is handed back as it is, for the
	 * caller to report.
	 */
	public synchronized File forEditing (final File opened, final boolean savedYet)
		throws IOException {

		if (savedYet && written != null) {
			return written;
		}

		if (savedYet) {
			logger.error("Client reported we had already saved but nothing was written.%n");
		}

		if (isCopyOf(opened)) {
			return copy;
		}

		if (!opened.isDirectory()) {
			return opened;
		}

		// A copy of a save the list only drew would be a save of eight pictures.
		unpacked.complete(opened);
		discardCopy();
		final File made = new File(
			Files.createTempDirectory("EK-editing-").toFile(), opened.getName());

		try {
			FileUtils.copyDirectory(opened, made);
		} catch (final IOException e) {
			FileUtils.deleteQuietly(made.getParentFile());
			throw e;
		}

		this.opened = opened.getAbsoluteFile();
		this.copy = made;
		return made;
	}

	/** Where the last Save wrote to, or null before one. */
	public synchronized File written () {
		return written;
	}

	/** A Save wrote the open save's state here. */
	public synchronized void written (final File directory) {
		written = directory;
	}

	/** A save is being opened from the list: nothing of the last one carries over. */
	public synchronized void opening () {
		history.clear();
		discardCopy();
		written = null;
	}

	private boolean isCopyOf (final File candidate) {
		return copy != null && opened != null && opened.equals(candidate.getAbsoluteFile());
	}

	private void discardCopy () {
		if (copy != null) {
			FileUtils.deleteQuietly(copy.getParentFile());
		}

		copy = null;
		opened = null;
	}
}
