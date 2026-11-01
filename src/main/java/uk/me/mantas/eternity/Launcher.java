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

package uk.me.mantas.eternity;

/**
 * The jar's entry point: what has to be settled before any AWT class loads,
 * then {@link EternityKeeper#main}.
 *
 * <p>It has to be a class of its own. {@code EternityKeeper} is a Frame, so
 * calling its {@code main} first initialises Component, Window and Toolkit,
 * and Toolkit reads the assistive technologies as it initialises: a property
 * set in that {@code main} comes too late.
 */
public final class Launcher {
	private Launcher () {}

	public static void main (final String[] args) {
		withoutJavaAccessBridge();
		EternityKeeper.main(args);
	}

	// With the Java Access Bridge switched on in ~/.accessibility.properties,
	// AWT loads the bridge, the bridge loads jawt.dll in its own class loader,
	// and CefApp cannot load it again: the editor died at start with nothing
	// on screen (found 2026-11-01, when the bridge had been switched on on the
	// machine it is built on). The page is Chromium, which does its own
	// accessibility; the bridge only serves Java's widgets. An empty list
	// replaces the file's, unless one was given on the command line.
	private static void withoutJavaAccessBridge () {
		if (System.getProperty("javax.accessibility.assistive_technologies") == null) {
			System.setProperty("javax.accessibility.assistive_technologies", "");
		}
	}
}
