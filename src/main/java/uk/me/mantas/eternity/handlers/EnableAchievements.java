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

package uk.me.mantas.eternity.handlers;

import org.json.JSONObject;
import uk.me.mantas.eternity.save.AchievementsEnabler;

import java.io.File;
import java.io.IOException;

// Turns Steam achievements back on in a save, or off again. The request carries
// {oldSave, savedYet, enable}; enable defaults to true for older UIs.
//
// Only AchievementTracker.m_disableAchievements is touched. GameState's
// CheatsEnabled is deliberately left alone: achievements are gated on the
// tracker flag alone, and CheatsEnabled keeps live cheat effects working (god
// mode's death immunity checks it), so clearing it would change the game
// rather than just the achievements.
public class EnableAchievements extends SaveMutationHandler {
	@Override
	protected String mutate (final File save, final JSONObject request) throws IOException {
		final boolean enable = request.optBoolean("enable", true);

		return new AchievementsEnabler(save).set(enable)
			? null : "Could not update the cheat flags. Details are in eternity.log; Settings shows where it is.";
	}
}
