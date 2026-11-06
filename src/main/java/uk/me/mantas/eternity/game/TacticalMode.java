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

package uk.me.mantas.eternity.game;

/**
 * Combat mode, added by the Turn Based Patch (3.9.20).
 *
 * <p>These are the game's own names, and the order is what matters: the
 * serializer stores an enum by its ordinal, so a mirror that merely counts the
 * same still round-trips. The names leak out, though — saveinfo.xml carries
 * TacticalMode as text for the load screen to read — so calling these
 * RealTime/TurnBased, as an earlier version did, wrote a value the game has no
 * name for.
 */
public enum TacticalMode {
	Disabled,
	RoundBased
}
