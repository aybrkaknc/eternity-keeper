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

/**
 * A packet file that gave back fewer objects than its leading count promised.
 *
 * <p>The file was cut short or damaged part of the way through, or holds
 * something the reader cannot build. Either way what came back is only the
 * start of it, and writing that back would lose the rest without a word, so
 * nothing that could only be read in part is written. The message is meant for
 * the user as it stands: it says what happened and why nothing was written.
 */
public class ShortReadException extends WriteRefusedException {
	/** The file's name, as the user knows it. */
	public final String file;
	/** How many objects its leading count promised. */
	public final int declared;
	/** How many could be read. */
	public final int read;

	public ShortReadException (final String file, final int declared, final int read) {
		super(String.format(
			"Only %d of the %d objects in %s could be read, so nothing was written: "
				+ "the rest would have been lost."
			, read, declared, file));

		this.file = file;
		this.declared = declared;
		this.read = read;
	}
}
