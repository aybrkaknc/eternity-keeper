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


package uk.me.mantas.eternity.save;

import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.save.model.Component;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.WriteRefusedException;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * The in-game console's IRoll20s marks a save as cheated: it flips
 * GameState.CheatsEnabled AND sets AchievementTracker.m_disableAchievements,
 * which disables Steam achievements for the playthrough. Decompiling the game
 * shows m_disableAchievements is the ONLY flag achievements are gated on, and
 * nothing re-derives it from CheatsEnabled on load — while CheatsEnabled
 * itself keeps runtime cheat effects (god mode's death immunity, cheat
 * commands) working. So this toggles ONLY m_disableAchievements: achievements
 * come back without switching off the cheats the player had active.
 */
public class AchievementsEnabler {
	private static final Logger logger = Logger.getLogger(AchievementsEnabler.class);

	private final File saveDirectory;

	public AchievementsEnabler (final File saveDirectory) {
		this.saveDirectory = saveDirectory;
	}

	public boolean enable () throws IOException {
		return set(true);
	}

	/**
	 * @param achievementsEnabled true clears m_disableAchievements so Steam
	 *                            achievements unlock again; false sets it.
	 *                            GameState.CheatsEnabled is deliberately left
	 *                            alone either way.
	 * @throws WriteRefusedException when the save holds the flag as anything
	 *         but a boolean; nothing is written
	 */
	public boolean set (final boolean achievementsEnabled) throws IOException {
		final File mobileObjects = new File(saveDirectory, "MobileObjects.save");
		final Optional<DeserializedPackets> deserializedOpt =
			new PacketDeserializer(mobileObjects).deserialize();

		if (!deserializedOpt.isPresent()) {
			logger.error("Unable to deserialize MobileObjects.save.%n");
			return false;
		}

		final DeserializedPackets deserialized = deserializedOpt.get();
		final Optional<Component> tracker = SaveModel.of(deserialized).objects().stream()
			.map(object -> object.component("AchievementTracker"))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.filter(component -> component.variable("m_disableAchievements").isPresent())
			.findFirst();

		if (!tracker.isPresent()) {
			logger.error("Could not find AchievementTracker.m_disableAchievements.%n");
			return false;
		}

		tracker.get().set("m_disableAchievements", !achievementsEnabled);
		deserialized.replace(mobileObjects);
		return true;
	}
}
