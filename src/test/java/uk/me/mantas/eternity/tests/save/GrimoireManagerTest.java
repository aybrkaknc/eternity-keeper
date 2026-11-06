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

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.GrimoireManager;
import uk.me.mantas.eternity.save.GrimoireManager.Change;
import uk.me.mantas.eternity.save.GrimoireManager.Spell;
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static uk.me.mantas.eternity.EKUtils.findComponent;

// Which spells sit in a grimoire.
//
// A grimoire is an item, and the Grimoire component hangs off the item's own
// packet -- Grimoire.Find() reads it out of the wearer's Grimoire equipment
// slot. What it holds is SerializedSpellNames, a flat List<string> of spell
// prefab names, and that is the only half of the class that survives a save:
// SerializedSpells is a SpellChapter[8] of object references and every chapter
// comes back all-null. The names setter rebuilds the chapters from the list,
// filing each spell under its own SpellLevel and silently dropping anything
// past the fourth at a level, so the manager has to do that arithmetic itself
// or the editor will show spells the game will quietly discard.
//
// The fixture is the ordinary prologue save with a real grimoire spliced onto
// Player_Elwyn, since the prologue party contains no wizard.
public class GrimoireManagerTest extends TestHarness {
	private static final String GRIMOIRE = "11111111-2222-3333-4444-555555555555";

	private File setupSave () throws URISyntaxException, IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		final File saveDir = new File(workingDir.get(), "target.savegame");
		assertTrue(saveDir.mkdir());

		final File resources = new File(getClass().getResource("/").toURI());
		FileUtils.copyFileToDirectory(
			new File(resources, "GrimoireManagerTest/MobileObjects.save"), saveDir);

		return saveDir;
	}

	private static Spell spell (final String prefab, final int level) {
		return new Spell(prefab, level);
	}

	/** The spell names a save's one grimoire holds, in stored order. */
	private List<String> spellsIn (final File saveDir) throws IOException {
		final Optional<DeserializedPackets> packets =
			new PacketDeserializer(new File(saveDir, "MobileObjects.save")).deserialize();

		assertTrue(packets.isPresent());

		for (final Property property : packets.get().getPackets()) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			if (packet.ComponentPackets == null) {
				continue;
			}

			final Optional<ComponentPersistencePacket> component =
				findComponent(packet.ComponentPackets, "Grimoire");

			if (!component.isPresent()) {
				continue;
			}

			final Object names = component.get().Variables.get("SerializedSpellNames");
			final List<String> result = new ArrayList<>();
			if (names instanceof CSharpCollection) {
				final Iterator iterator = ((CSharpCollection) names).iterator();
				while (iterator.hasNext()) {
					result.add(String.valueOf(iterator.next()));
				}
			}

			return result;
		}

		fail("the fixture has no grimoire");
		return null;
	}

	private int packetCount (final File saveDir) throws IOException {
		final Optional<DeserializedPackets> packets =
			new PacketDeserializer(new File(saveDir, "MobileObjects.save")).deserialize();

		assertTrue(packets.isPresent());
		return packets.get().getPackets().size();
	}

	// ---- the planning half, which is where the game's rules live -----------

	@Test
	public void aChapterHoldsFourSpellsAndNoMore () {
		final List<Spell> requested = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			requested.add(spell("Level_One_Spell_" + i, 1));
		}

		final List<Spell> kept = GrimoireManager.plan(requested);
		assertEquals(4, kept.size());
		assertEquals("Level_One_Spell_1", kept.get(0).prefab);
		assertEquals("Level_One_Spell_4", kept.get(3).prefab);
	}

	@Test
	public void spellsComeOutInChapterOrder () {
		final List<Spell> kept = GrimoireManager.plan(Arrays.asList(
			spell("Fireball", 3)
			, spell("Chill_Fog", 1)
			, spell("Necrotic_Lance", 2)
			, spell("Slicken", 1)));

		assertEquals(4, kept.size());
		assertEquals("Chill_Fog", kept.get(0).prefab);
		assertEquals("Slicken", kept.get(1).prefab);
		assertEquals("Necrotic_Lance", kept.get(2).prefab);
		assertEquals("Fireball", kept.get(3).prefab);
	}

	@Test
	public void thereAreOnlyEightChapters () {
		final List<Spell> kept = GrimoireManager.plan(Arrays.asList(
			spell("Chill_Fog", 1)
			, spell("Nonsense_Nine", 9)
			, spell("Nonsense_Zero", 0)
			, spell("Nonsense_Negative", -1)
			, spell("Wall_of_Many_Colors", 8)));

		assertEquals(2, kept.size());
		assertEquals("Chill_Fog", kept.get(0).prefab);
		assertEquals("Wall_of_Many_Colors", kept.get(1).prefab);
	}

	@Test
	public void theSameSpellIsNotWrittenTwice () {
		// GameResources.LoadPrefab resolves a name case-insensitively, so two
		// spellings of one spell would come back as the same prefab twice.
		final List<Spell> kept = GrimoireManager.plan(Arrays.asList(
			spell("Chill_Fog", 1)
			, spell("chill_fog", 1)
			, spell("Slicken", 1)));

		assertEquals(2, kept.size());
		assertEquals("Chill_Fog", kept.get(0).prefab);
		assertEquals("Slicken", kept.get(1).prefab);
	}

	@Test
	public void nothingIsAlsoAPlan () {
		assertTrue(GrimoireManager.plan(Collections.emptyList()).isEmpty());
		assertTrue(GrimoireManager.plan(null).isEmpty());
	}

	// ---- and the half that touches the save --------------------------------

	@Test
	public void theFixtureHasARealGrimoire () throws Exception {
		final File saveDir = setupSave();
		final List<String> spells = spellsIn(saveDir);

		assertEquals(30, spells.size());
		assertEquals("Chill_Fog", spells.get(0));
		assertTrue(spells.contains("Fireball"));
	}

	@Test
	public void writingReplacesWhatTheGrimoireHolds () throws Exception {
		final File saveDir = setupSave();
		final int before = packetCount(saveDir);

		assertTrue(new GrimoireManager(saveDir).apply(Collections.singletonList(
			new Change(GRIMOIRE, Arrays.asList(
				spell("Fireball", 3)
				, spell("Chill_Fog", 1)
				, spell("Slicken", 1))))));

		assertEquals(
			Arrays.asList("Chill_Fog", "Slicken", "Fireball"), spellsIn(saveDir));

		// Nothing else in the save moved.
		assertEquals(before, packetCount(saveDir));
	}

	@Test
	public void aGrimoireCanBeEmptied () throws Exception {
		final File saveDir = setupSave();

		assertTrue(new GrimoireManager(saveDir).apply(Collections.singletonList(
			new Change(GRIMOIRE, Collections.emptyList()))));

		assertTrue(spellsIn(saveDir).isEmpty());
	}

	@Test
	public void theSaveIsRewrittenNotAppendedTo () throws Exception {
		// serializeAll() opens the file for append and seeks to the end, so an
		// in-place edit that does not clear the file first silently doubles it
		// and every later read returns the stale copy (invariant 13).
		final File saveDir = setupSave();
		final long before = new File(saveDir, "MobileObjects.save").length();

		assertTrue(new GrimoireManager(saveDir).apply(Collections.singletonList(
			new Change(GRIMOIRE, Arrays.asList(spell("Chill_Fog", 1))))));

		final long after = new File(saveDir, "MobileObjects.save").length();
		assertTrue("save grew from " + before + " to " + after, after < before);
		assertEquals(Arrays.asList("Chill_Fog"), spellsIn(saveDir));
	}

	@Test
	public void anUnknownGrimoireIsRefused () throws Exception {
		final File saveDir = setupSave();

		assertFalse(new GrimoireManager(saveDir).apply(Collections.singletonList(
			new Change("00000000-0000-0000-0000-000000000000"
				, Arrays.asList(spell("Chill_Fog", 1))))));

		// A refused change leaves the save exactly as it was.
		assertEquals(30, spellsIn(saveDir).size());
	}

	@Test
	public void theGameRulesApplyOnWriteToo () throws Exception {
		final File saveDir = setupSave();
		final List<Spell> tooMany = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			tooMany.add(spell("First_Level_" + i, 1));
		}

		tooMany.add(spell("Fireball", 3));

		assertTrue(new GrimoireManager(saveDir).apply(
			Collections.singletonList(new Change(GRIMOIRE, tooMany))));

		assertEquals(
			Arrays.asList("First_Level_1", "First_Level_2", "First_Level_3"
				, "First_Level_4", "Fireball")
			, spellsIn(saveDir));
	}
}
