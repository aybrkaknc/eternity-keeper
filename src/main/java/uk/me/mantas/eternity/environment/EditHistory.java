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
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each Apply wrote into the working save, so it can be undone and redone.
 *
 * <p>Every manager writes the working save through
 * {@code DeserializedPackets.replace}, which writes a sibling file and moves it
 * over the old one (invariant 13). While a step is open, each file about to be
 * replaced is kept first -- a hard link to it, which costs nothing because the
 * move gives the live name a new file and leaves the old one to the link, or a
 * copy where the disk cannot link. Undoing a step swaps the kept files with
 * the live ones, which leaves the step holding its own versions: redoing it is
 * the same swap again.
 *
 * <p>A step is what one Apply wrote, however many files that was. Only the
 * last step can be undone and only the last one undone can be redone; a new
 * step ends what could be redone. The history belongs to one working
 * directory and starts again when another is edited, when a save is opened,
 * and when one is written: past a Save the scalar edits it wrote are in the
 * same files, so taking a file back would take them back too, and the save
 * that was opened is still in the list anyway.
 *
 * <p>Changes typed into the page -- stats, globals, money -- are not files yet
 * and are the page's own history; this is the half the page cannot undo
 * itself.
 */
public class EditHistory {
	private static final Logger logger = Logger.getLogger(EditHistory.class);

	/** One Apply's worth of kept files. */
	private static final class Step {
		final long id;
		final File directory;
		File folder = null;
		/** File name to whether the kept version exists (false: the step made the file). */
		final Map<String, Boolean> kept = new LinkedHashMap<>();

		Step (final long id, final File directory) {
			this.id = id;
			this.directory = directory;
		}
	}

	private final boolean linking;
	private final Deque<Step> undo = new ArrayDeque<>();
	private final Deque<Step> redo = new ArrayDeque<>();
	private Step open = null;
	private long next = 1;

	public EditHistory () {
		this(true);
	}

	/** @param linking false to always copy, as on a disk that cannot link */
	public EditHistory (final boolean linking) {
		this.linking = linking;
	}

	/**
	 * Starts recording an edit of {@code directory}. Steps recorded against
	 * another directory are forgotten, since they could not be put back where
	 * they came from; so is anything that could be redone, since this edit
	 * starts from what is there now.
	 */
	public synchronized void begin (final File directory) {
		if (open != null) {
			logger.error("A step was still open; it is abandoned.%n");
			abandon(open);
		}

		final Step last = undo.peekLast() != null ? undo.peekLast() : redo.peekLast();
		if (last != null && !same(last.directory, directory)) {
			clear();
		}

		discard(redo);
		open = new Step(next++, directory.getAbsoluteFile());
	}

	/**
	 * Keeps {@code target} as it is before it is replaced, if a step is open
	 * and the file is one of the directory the step is editing. Only its first
	 * version in a step is kept: that is what undoing the step goes back to.
	 *
	 * @throws IOException when it cannot be kept; the write then must not
	 *         happen, or the step could not be undone
	 */
	public synchronized void keep (final File target) throws IOException {
		if (open == null) {
			return;
		}

		final File parent = target.getAbsoluteFile().getParentFile();
		if (parent == null || !same(parent, open.directory) || open.kept.containsKey(target.getName())) {
			return;
		}

		if (open.folder == null) {
			open.folder = Files.createTempDirectory("EK-history-").toFile();
		}

		final boolean exists = target.isFile();
		if (exists) {
			preserve(target, new File(open.folder, target.getName()));
		}

		open.kept.put(target.getName(), exists);
	}

	/**
	 * Ends the open step.
	 *
	 * @return its id, or 0 when it wrote nothing, which makes it no step
	 */
	public synchronized long commit () {
		final Step step = open;
		open = null;

		if (step == null || step.kept.isEmpty()) {
			if (step != null) {
				abandon(step);
			}

			return 0;
		}

		undo.addLast(step);
		return step.id;
	}

	/**
	 * Ends the open step without keeping it: the edit was refused or failed.
	 * Whatever it had already replaced is put back, so a failure half way
	 * through a several-file edit leaves the save as it was.
	 */
	public synchronized void abort () {
		final Step step = open;
		open = null;

		if (step == null) {
			return;
		}

		for (final Map.Entry<String, Boolean> entry : step.kept.entrySet()) {
			final File live = new File(step.directory, entry.getKey());
			try {
				if (entry.getValue()) {
					moveOver(new File(step.folder, entry.getKey()), live);
				} else {
					Files.deleteIfExists(live.toPath());
				}
			} catch (final IOException e) {
				logger.error("Could not put back %s: %s%n", live.getAbsolutePath(), e.getMessage());
			}
		}

		abandon(step);
	}

	/**
	 * Puts back the files step {@code id} replaced. It must be the last step
	 * of {@code directory}.
	 *
	 * @return false when it is not, and nothing was touched
	 */
	public synchronized boolean undo (final File directory, final long id) throws IOException {
		return move(undo, redo, directory, id);
	}

	/**
	 * Puts back the files step {@code id} wrote. It must be the last step of
	 * {@code directory} undone.
	 *
	 * @return false when it is not, and nothing was touched
	 */
	public synchronized boolean redo (final File directory, final long id) throws IOException {
		return move(redo, undo, directory, id);
	}

	/** Forgets every step and deletes the files they kept. */
	public synchronized void clear () {
		if (open != null) {
			abandon(open);
			open = null;
		}

		discard(undo);
		discard(redo);
	}

	/** The steps that can be undone, oldest first. */
	public synchronized List<Long> undoable () {
		return ids(undo);
	}

	/** The steps that can be redone, the next to redo last. */
	public synchronized List<Long> redoable () {
		return ids(redo);
	}

	/** Where a step keeps its files, or null; for tests. */
	public synchronized File folderOf (final long id) {
		for (final Deque<Step> steps : Arrays.asList(undo, redo)) {
			for (final Step step : steps) {
				if (step.id == id) {
					return step.folder;
				}
			}
		}

		return null;
	}

	// ------------------------------------------------------------------------

	private boolean move (
		final Deque<Step> from, final Deque<Step> to, final File directory, final long id)
		throws IOException {

		final Step step = from.peekLast();
		if (open != null || step == null || step.id != id || !same(step.directory, directory)) {
			return false;
		}

		swap(step);
		from.removeLast();
		to.addLast(step);
		return true;
	}

	/**
	 * Exchanges a step's kept files with the live ones. All or nothing: a
	 * swap that fails part of the way swaps back what it had done.
	 */
	private void swap (final Step step) throws IOException {
		final List<String> done = new ArrayList<>();
		try {
			for (final String name : step.kept.keySet()) {
				swapOne(step, name);
				done.add(name);
			}
		} catch (final IOException e) {
			for (int i = done.size() - 1; i >= 0; i--) {
				try {
					swapOne(step, done.get(i));
				} catch (final IOException again) {
					logger.error("Could not swap %s back: %s%n", done.get(i), again.getMessage());
				}
			}

			throw e;
		}
	}

	private void swapOne (final Step step, final String name) throws IOException {
		final File live = new File(step.directory, name);
		final File kept = new File(step.folder, name);
		final File other = new File(step.folder, name + ".other");
		final boolean liveExists = live.isFile();

		if (liveExists) {
			preserve(live, other);
		}

		if (step.kept.get(name)) {
			moveOver(kept, live);
		} else {
			Files.deleteIfExists(live.toPath());
		}

		if (liveExists) {
			moveOver(other, kept);
		}

		step.kept.put(name, liveExists);
	}

	/** A second name for {@code from}'s contents, or a copy of them. */
	private void preserve (final File from, final File to) throws IOException {
		Files.deleteIfExists(to.toPath());
		if (linking) {
			try {
				Files.createLink(to.toPath(), from.toPath());
				return;
			} catch (final IOException | UnsupportedOperationException e) {
				logger.info("Copying %s rather than linking it: %s%n", from.getName(), e.toString());
			}
		}

		Files.copy(from.toPath(), to.toPath());
	}

	private static void moveOver (final File from, final File to) throws IOException {
		try {
			Files.move(from.toPath(), to.toPath()
				, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (final AtomicMoveNotSupportedException e) {
			Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void discard (final Deque<Step> steps) {
		for (final Iterator<Step> each = steps.iterator(); each.hasNext(); ) {
			abandon(each.next());
			each.remove();
		}
	}

	private static void abandon (final Step step) {
		if (step.folder != null) {
			FileUtils.deleteQuietly(step.folder);
		}
	}

	private static List<Long> ids (final Deque<Step> steps) {
		final List<Long> ids = new ArrayList<>();
		steps.forEach(step -> ids.add(step.id));
		return ids;
	}

	private static boolean same (final File a, final File b) {
		try {
			return a.getCanonicalFile().equals(b.getCanonicalFile());
		} catch (final IOException e) {
			return a.getAbsoluteFile().equals(b.getAbsoluteFile());
		}
	}
}
