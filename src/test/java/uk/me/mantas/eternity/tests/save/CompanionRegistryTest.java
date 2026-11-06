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


package uk.me.mantas.eternity.tests.save;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;
import uk.me.mantas.eternity.save.CompanionRegistry;
import uk.me.mantas.eternity.save.CompanionRegistry.Companion;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

// Every fact here was verified against a matrix of real saves, one per dead
// companion, diffed against a baseline where everyone is alive.
public class CompanionRegistryTest {
	@Test
	public void mapsVerifiedCompanions () {
		final Companion eder = CompanionRegistry.byKey("Eder").get();
		assertEquals("b_Eder_Dead", eder.deathFlag);
		assertEquals("Companion_Eder", eder.objectNamePrefix);
		assertEquals("eder", eder.portraitName());

		// Durance's mobile object is Companion_GGP and his flag breaks the
		// b_X_Dead convention.
		final Companion durance = CompanionRegistry.byKey("Durance").get();
		assertEquals("bDuranceDead", durance.deathFlag);
		assertEquals("Companion_GGP", durance.objectNamePrefix);

		// The Devil of Caroc's mobile object is Companion_Caroc.
		final Companion devil = CompanionRegistry.byKey("DevilOfCaroc").get();
		assertEquals("Companion_Caroc", devil.objectNamePrefix);
		assertEquals("b_devil_dead", devil.deathFlag);
		assertEquals("devil_of_caroc", devil.portraitName());

		// Grieving Mother's death sets a second flag that must be cleared.
		final Companion gm = CompanionRegistry.byKey("GrievingMother").get();
		assertEquals("b_companion_gm_dead", gm.deathFlag);
		assertTrue(gm.extraFlagsToClear.contains("b_companion_gm_unavailable"));

		// Zahua's death sets no global at all.
		final Companion zahua = CompanionRegistry.byKey("Zahua").get();
		assertNull(zahua.deathFlag);

		// Itumaak dies with Sagani and must be replaced from the donor.
		final Companion sagani = CompanionRegistry.byKey("Sagani").get();
		assertEquals("CRE_ArcticFox_Animal_Companion", sagani.linkedCreaturePrefix);

		// Quest paths as keyed in the QuestTrackers dictionary; the oddballs
		// were verified against real saves.
		assertEquals("data/quests/companions/companion_qst_eder.quest", eder.questPath);
		assertEquals(
			"data/quests/13_twin_elms_elms_reach/13_qst_true_to_form.quest"
			, CompanionRegistry.byKey("Hiravias").get().questPath);
		assertEquals(
			"data/quests/px1_companions/px2_companion_qst_zahua.quest", zahua.questPath);
	}

	@Test
	public void detectsFlaggedDeath () {
		final Companion eder = CompanionRegistry.byKey("Eder").get();
		final Set<String> noOrphans = ImmutableSet.of();

		assertTrue(eder.isDeadIn(
			ImmutableMap.of("b_Eder_Dead", 1), ImmutableSet.of("player_x(clone)_0"), noOrphans));
		assertFalse(eder.isDeadIn(
			ImmutableMap.of("b_Eder_Dead", 0), ImmutableSet.of("player_x(clone)_0"), noOrphans));

		// Present in the save trumps the flag.
		assertFalse(eder.isDeadIn(
			ImmutableMap.of("b_Eder_Dead", 1)
			, ImmutableSet.of("companion_eder(clone)_1")
			, noOrphans));
	}

	@Test
	public void detectsFlaglessDeathViaOrphans () {
		final Companion zahua = CompanionRegistry.byKey("Zahua").get();
		final Map<String, Object> globals = ImmutableMap.of();

		// Absent + orphaned remnants pointing at him = dead.
		assertTrue(zahua.isDeadIn(
			globals
			, ImmutableSet.of("player_x(clone)_0")
			, ImmutableSet.of("companion_zahua(clone)_5")));

		// Absent with no remnants = never recruited, not dead.
		assertFalse(zahua.isDeadIn(
			globals, ImmutableSet.of("player_x(clone)_0"), ImmutableSet.of()));

		// Alive = alive, whatever the orphans of older incarnations say.
		assertFalse(zahua.isDeadIn(
			globals
			, ImmutableSet.of("companion_zahua(clone)_5")
			, ImmutableSet.of("companion_zahua(clone)_2")));
	}

	@Test
	public void allEntriesAreWellFormedAndUnique () {
		final Set<String> keys = new HashSet<>();
		final Set<String> prefixes = new HashSet<>();

		for (final Companion c : CompanionRegistry.all()) {
			assertFalse(c.key.isEmpty());
			assertFalse(c.displayName.isEmpty());
			assertTrue(c.objectNamePrefix.startsWith("Companion_"));
			assertNotNull(c.extraFlagsToClear);
			assertNotNull(c.questPath);
			assertTrue(c.questPath.endsWith(".quest"));
			assertTrue("duplicate key " + c.key, keys.add(c.key));
			assertTrue("duplicate prefix " + c.objectNamePrefix, prefixes.add(c.objectNamePrefix));
		}

		assertFalse(CompanionRegistry.byKey("NotACompanion").isPresent());
	}
}
