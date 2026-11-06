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

var CloneFactory = {};
CloneFactory.clone = function (element) {
	if (element == null || !element.clone) {
		console.error('Tried to clone something which was not an HTML element.');
		return;
	}

	var spawn = element.clone();
	spawn.attr('id', null);

	// Clearing the data cache is not enough: the attribute rides along in the
	// clone, so every tile on screen still answers $('[data-bound]') with no
	// id to bind to. Nothing re-runs bindDOM today, but leaving the markup
	// saying something untrue is how the next person gets misled.
	spawn.data('bound', null);
	spawn.removeAttr('data-bound');

	return spawn;
};
