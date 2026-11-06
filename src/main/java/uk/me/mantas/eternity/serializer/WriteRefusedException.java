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

import java.io.IOException;

/**
 * A write the editor would not make, and why. Nothing was written: the file is
 * exactly as it was.
 *
 * <p>The message is meant for the user as it stands, which is why the places
 * that report a failed write pass it on unchanged rather than prefixing it
 * with "could not write" -- the write did not fail, it was declined.
 */
public class WriteRefusedException extends IOException {
	public WriteRefusedException (final String message) {
		super(message);
	}
}
