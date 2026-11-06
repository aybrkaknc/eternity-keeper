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


package uk.me.mantas.eternity.serializer;

import java.util.Collections;
import java.util.List;

/**
 * An edit that would have left a packet file contradicting itself in a way it
 * did not when it was read ({@link PacketInvariants}).
 *
 * <p>The file is the user's and the contradiction is the editor's: an edit
 * that breaks the save's own invariants is a bug in whatever made it, and the
 * game would have dropped what it could not resolve without a word. So it is
 * not written, and the message says so, naming the first thing wrong. The
 * rest are in the log.
 */
public class InconsistentWriteException extends WriteRefusedException {
	/** The file's name, as the user knows it. */
	public final String file;
	/** Everything the edit got wrong, each in words for the user. */
	public final List<String> problems;

	public InconsistentWriteException (final String file, final List<String> problems) {
		super(message(file, problems));
		this.file = file;
		this.problems = problems == null
			? Collections.emptyList() : Collections.unmodifiableList(problems);
	}

	private static String message (final String file, final List<String> problems) {
		final int count = problems == null ? 0 : problems.size();
		return String.format(
			"The change would have left %s contradicting itself, so nothing was written: %s%s. "
				+ "That is a fault in the editor; eternity.log lists everything it found."
			, file == null || file.isEmpty() ? "the save" : file
			, count == 0 ? "it no longer agrees with itself" : problems.get(0)
			, count > 1 ? String.format(" (and %d more)", count - 1) : "");
	}
}
