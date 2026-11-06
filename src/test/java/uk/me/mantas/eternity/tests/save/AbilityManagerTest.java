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
import uk.me.mantas.eternity.game.GenericAbility.AbilityType;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.AbilityManager;
import uk.me.mantas.eternity.save.AbilityManager.Change;
import uk.me.mantas.eternity.save.AbilityManager.NewAbility;
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

// The fixture's Player_Elwyn owns five ability objects (KnockDown,
// ConstantRecovery, WeaponSpecPreReq, FightingSpirit and Second_Wind), each a
// standalone packet parented to him, and an empty m_serializedTalents — enough
// to exercise both directions of both kinds of change without a live save.
public class AbilityManagerTest extends TestHarness {
	private static final String ELWYN = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String CALISCA = "b1a7e809-0000-0000-0000-000000000000";
	private static final String KNOCKDOWN_GUID = "3ffdbced-4efb-4a6a-afcc-686fed65381b";

	private static final NewAbility CAUTIOUS_ATTACK = new NewAbility(
		"Cautious_Attack"
		, "Assets/Data/Prefabs/RPG/Talents/Talent_Abilities/Cautious_Attack.prefab"
		, "GenericAbility"
		, AbilityType.Talent.ordinal());

	private static final NewAbility FIREBALL = new NewAbility(
		"Fireball"
		, "Assets/Data/Prefabs/RPG/Spells/Wizard/L_03/Fireball.prefab"
		, "GenericSpell"
		, AbilityType.Spell.ordinal()
		, true
		, "Wizard");

	private File setupSave () throws URISyntaxException, IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());
		final File saveDir = new File(workingDir.get(), "cadena 0 Test.savegame");
		assertTrue(saveDir.mkdir());

		final File resources = new File(getClass().getResource("/").toURI());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), saveDir);
		return saveDir;
	}

	private DeserializedPackets deserialize (final File saveDir) throws IOException {
		final Optional<DeserializedPackets> deserialized =
			new PacketDeserializer(new File(saveDir, "MobileObjects.save")).deserialize();
		assertTrue(deserialized.isPresent());
		return deserialized.get();
	}

	private List<ObjectPersistencePacket> abilitiesOf (
		final DeserializedPackets packets, final String ownerName) {

		final List<ObjectPersistencePacket> owned = new ArrayList<>();
		for (final Property property : packets.getPackets()) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			if (!ownerName.equals(packet.Parent) || packet.ComponentPackets == null) {
				continue;
			}

			for (final ComponentPersistencePacket component : packet.ComponentPackets) {
				if (component != null && component.Variables != null
					&& component.Variables.containsKey("EffectType")) {

					owned.add(packet);
					break;
				}
			}
		}

		return owned;
	}

	private List<String> talentsOf (
		final DeserializedPackets packets, final String objectID) {

		final List<String> talents = new ArrayList<>();
		for (final Property property : packets.getPackets()) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			if (!objectID.equalsIgnoreCase(packet.ObjectID)
				|| packet.ComponentPackets == null) {

				continue;
			}

			for (final ComponentPersistencePacket component : packet.ComponentPackets) {
				if (component == null || component.Variables == null
					|| !"CharacterStats".equals(component.TypeString)) {

					continue;
				}

				final Object list = component.Variables.get("m_serializedTalents");
				if (list instanceof CSharpCollection) {
					final Iterator iterator = ((CSharpCollection) list).iterator();
					while (iterator.hasNext()) {
						talents.add(String.valueOf(iterator.next()));
					}
				}
			}
		}

		return talents;
	}

	private Optional<ObjectPersistencePacket> named (
		final DeserializedPackets packets, final String objectName) {

		for (final Property property : packets.getPackets()) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			if (objectName.equals(packet.ObjectName)) {
				return Optional.of(packet);
			}
		}

		return Optional.empty();
	}

	private static Object variable (
		final ObjectPersistencePacket packet, final String type, final String name) {

		for (final ComponentPersistencePacket component : packet.ComponentPackets) {
			if (component != null && type.equals(component.TypeString)
				&& component.Variables != null) {

				return component.Variables.get(name);
			}
		}

		return null;
	}

	@Test
	public void removesAnAbilityObject () throws Exception {
		final File saveDir = setupSave();
		final DeserializedPackets before = deserialize(saveDir);
		final int total = before.getPackets().size();
		assertEquals(5, abilitiesOf(before, "Player_Elwyn").size());

		assertTrue(new AbilityManager(saveDir).apply(Collections.singletonList(
			Change.removeAbility(ELWYN, KNOCKDOWN_GUID))));

		final DeserializedPackets after = deserialize(saveDir);
		assertEquals(total - 1, after.getPackets().size());

		final List<ObjectPersistencePacket> remaining = abilitiesOf(after, "Player_Elwyn");
		assertEquals(4, remaining.size());
		for (final ObjectPersistencePacket packet : remaining) {
			assertNotEquals(KNOCKDOWN_GUID, packet.ObjectID);
		}

		// Calisca has a KnockDown of her own; removing his must not touch hers.
		assertEquals(4, abilitiesOf(after, "Companion_Calisca(Clone)_1").size());
	}

	@Test
	public void refusesToRemoveAnAbilityAnotherCharacterOwns () throws Exception {
		final File saveDir = setupSave();
		// KnockDown belongs to Elwyn, not to Calisca.
		assertFalse(new AbilityManager(saveDir).apply(Collections.singletonList(
			Change.removeAbility(CALISCA, KNOCKDOWN_GUID))));
	}

	@Test
	public void addsAnAbilityAsItsOwnPacket () throws Exception {
		final File saveDir = setupSave();
		final int total = deserialize(saveDir).getPackets().size();

		assertTrue(new AbilityManager(saveDir).apply(Collections.singletonList(
			Change.addAbility(ELWYN, FIREBALL))));

		final DeserializedPackets after = deserialize(saveDir);
		assertEquals(total + 1, after.getPackets().size());
		assertEquals(6, abilitiesOf(after, "Player_Elwyn").size());

		final Optional<ObjectPersistencePacket> minted = named(after, "Fireball(Clone)");
		assertTrue(minted.isPresent());

		final ObjectPersistencePacket packet = minted.get();
		assertEquals("Player_Elwyn", packet.Parent);
		assertEquals(FIREBALL.path, packet.PrefabResource);
		assertEquals(packet.ObjectID, packet.GUID.toString());

		// The component class is not interchangeable: a spell has to be written
		// as a GenericSpell or the game applies none of its state.
		assertEquals(ELWYN, String.valueOf(variable(packet, "GenericSpell", "Owner")));
		assertEquals(AbilityType.Spell, variable(packet, "GenericSpell", "EffectType"));
		assertEquals(packet.GUID, variable(packet, "InstanceID", "Guid"));
	}

	@Test
	public void mintingNeverAliasesAnExistingPacket () throws Exception {
		final File saveDir = setupSave();
		assertTrue(new AbilityManager(saveDir).apply(Arrays.asList(
			Change.addAbility(ELWYN, FIREBALL)
			, Change.addAbility(CALISCA, CAUTIOUS_ATTACK))));

		// Every object identifies itself by its own ObjectID. Sharing a
		// template's Property objects instead of copying them silently rewrote
		// the template's GUID, and the game then dropped both objects.
		for (final Property property : deserialize(saveDir).getPackets()) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			if (packet.ComponentPackets == null || packet.ObjectID == null) {
				continue;
			}

			final Object guid = variable(packet, "InstanceID", "Guid");
			if (guid != null) {
				assertEquals(
					"InstanceID of " + packet.ObjectName
					, packet.ObjectID.toLowerCase(), String.valueOf(guid).toLowerCase());
			}
		}
	}

	@Test
	public void addsATalentAndTheAbilityItGrants () throws Exception {
		final File saveDir = setupSave();
		assertTrue(talentsOf(deserialize(saveDir), ELWYN).isEmpty());

		assertTrue(new AbilityManager(saveDir).apply(Collections.singletonList(
			Change.addTalent(ELWYN, "TLN_Cautious_Attack"
				, Collections.singletonList(CAUTIOUS_ATTACK)
				, Collections.emptyMap()))));

		final DeserializedPackets after = deserialize(saveDir);
		assertEquals(
			Collections.singletonList("TLN_Cautious_Attack"), talentsOf(after, ELWYN));

		// CharacterStats.Restored rebuilds a character's abilities from the
		// objects already in the save and never re-runs GenericTalent.Purchase,
		// so the granted ability has to be minted here or it never exists.
		final Optional<ObjectPersistencePacket> granted =
			named(after, "Cautious_Attack(Clone)");

		assertTrue(granted.isPresent());
		assertEquals("Player_Elwyn", granted.get().Parent);
		assertEquals(
			AbilityType.Talent, variable(granted.get(), "GenericAbility", "EffectType"));
	}

	@Test
	public void removesATalentAndItsGrantedAbility () throws Exception {
		final File saveDir = setupSave();
		final AbilityManager manager = new AbilityManager(saveDir);

		assertTrue(manager.apply(Collections.singletonList(
			Change.addTalent(ELWYN, "TLN_Cautious_Attack"
				, Collections.singletonList(CAUTIOUS_ATTACK)
				, Collections.emptyMap()))));

		assertTrue(new AbilityManager(saveDir).apply(Collections.singletonList(
			Change.removeTalent(ELWYN, "TLN_Cautious_Attack"
				, Collections.singletonList("Cautious_Attack")
				, Collections.emptyMap()))));

		final DeserializedPackets after = deserialize(saveDir);
		assertTrue(talentsOf(after, ELWYN).isEmpty());
		assertFalse(named(after, "Cautious_Attack(Clone)").isPresent());
		assertEquals(5, abilitiesOf(after, "Player_Elwyn").size());
	}

	@Test
	public void addingATalentTwiceGrantsItsAbilityOnce () throws Exception {
		final File saveDir = setupSave();
		final Change add = Change.addTalent(ELWYN, "TLN_Cautious_Attack"
			, Collections.singletonList(CAUTIOUS_ATTACK), Collections.emptyMap());

		assertTrue(new AbilityManager(saveDir).apply(Collections.singletonList(add)));
		assertTrue(new AbilityManager(saveDir).apply(Collections.singletonList(add)));

		final DeserializedPackets after = deserialize(saveDir);
		assertEquals(
			Collections.singletonList("TLN_Cautious_Attack"), talentsOf(after, ELWYN));

		// Six, not seven: the second add is a no-op rather than a second copy
		// of the ability the talent grants.
		assertEquals(6, abilitiesOf(after, "Player_Elwyn").size());
	}

	@Test
	public void modelsASpellOnASpellRatherThanOnAnyAbility () throws Exception {
		final File saveDir = setupSave();
		assertTrue(new AbilityManager(saveDir).apply(Collections.singletonList(
			Change.addAbility(ELWYN, FIREBALL))));

		final Optional<ObjectPersistencePacket> minted =
			named(deserialize(saveDir), "Fireball(Clone)");

		assertTrue(minted.isPresent());
		// The fixture only has plain GenericAbility objects to copy, so the
		// spell-only fields cannot come from a template — they are written
		// because a spell needs them, matching InstantiateAbility.
		assertEquals(false, variable(minted.get(), "GenericSpell", "IsFree"));
		assertEquals(true, variable(minted.get(), "GenericSpell", "NeedsGrimoire"));
	}

	@Test
	public void appliesTheSkillBonusATalentCarries () throws Exception {
		final File saveDir = setupSave();

		assertTrue(new AbilityManager(saveDir).apply(Collections.singletonList(
			Change.addTalent(ELWYN, "TLN_Field_Triage"
				, Collections.emptyList()
				, Collections.singletonMap("Athletics", 2)))));

		final Optional<ObjectPersistencePacket> elwyn =
			named(deserialize(saveDir), "Player_Elwyn");

		assertTrue(elwyn.isPresent());
		// Purchase() bakes the bonus into <Skill>Bonus and nothing recomputes it
		// on load, so the editor has to add it too.
		assertEquals(2, variable(elwyn.get(), "CharacterStats", "AthleticsBonus"));
	}
}
