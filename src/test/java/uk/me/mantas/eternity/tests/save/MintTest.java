/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2016 the authors.
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

import org.junit.Test;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.model.Component;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.*;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Making a new top-level object out of the shape of one already in the save
 * ({@link SaveModel#mint}), which is how both a new item and a new ability
 * come to exist.
 *
 * <p>The two managers each had their own copy of this once, and the lesson
 * both copies carry is the one that only ever surfaced in-game: never reuse
 * the template's own properties, or rewriting the new object's GUID rewrites
 * the template's too and the game silently drops both.
 */
public class MintTest extends TestHarness {
	private static final UUID GUID = UUID.fromString("0a0a0a0a-1111-2222-3333-444444444444");

	private static SaveModel save () throws Exception {
		final File save = new File(MintTest.class.getResource("/MobileObjects.save").toURI());
		final Optional<DeserializedPackets> read = new PacketDeserializer(save).deserialize();
		assertTrue(read.isPresent());
		return SaveModel.of(read.get());
	}

	/** An object with an InstanceID and at least one other component to leave out. */
	private static SaveObject template (final SaveModel save) {
		for (final SaveObject object : save.objects()) {
			final Set<String> types = new HashSet<>();
			for (final Component component : object.components()) {
				types.add(component.type());
			}

			if (types.contains("InstanceID") && types.contains("Persistence") && types.size() > 2) {
				return object;
			}
		}

		throw new AssertionError("the fixture has no object to model on");
	}

	private static SaveModel.Identity identity (final String levelName) {
		return new SaveModel.Identity(
			"Ring_Of_Tests(Clone)", GUID.toString(), GUID
			, "Assets/Prefabs/Items/Ring_Of_Tests.prefab", "Player_Elwyn(Clone)", levelName);
	}

	private static Object header (final SaveObject object, final String name) {
		return object.get(name).orElse(null);
	}

	private static Set<String> componentTypes (final SaveObject object) {
		final Set<String> types = new HashSet<>();
		object.components().forEach(component -> types.add(component.type()));
		return types;
	}

	private static Object instanceGuid (final SaveObject object) {
		return object.component("InstanceID").flatMap(component -> component.get("Guid")).orElse(null);
	}

	private static final SaveModel.Keep IDENTITY_ONLY =
		(type, component) -> "InstanceID".equals(type) || "Persistence".equals(type);

	@Test
	public void theHeaderIsTheNewObjectsOwn () throws Exception {
		final SaveModel save = save();
		final SaveObject minted = save.mint(
			template(save), identity("AR_0001_Test"), IDENTITY_ONLY, (type, copy) -> {});

		assertEquals("Ring_Of_Tests(Clone)", header(minted, "ObjectName"));
		assertEquals(GUID.toString(), header(minted, "ObjectID"));
		assertEquals("the GUID is a real UUID, not its text", GUID, header(minted, "GUID"));
		assertEquals("Assets/Prefabs/Items/Ring_Of_Tests.prefab", header(minted, "PrefabResource"));
		assertEquals("Player_Elwyn(Clone)", header(minted, "Parent"));
		assertEquals("AR_0001_Test", header(minted, "LevelName"));

		// The header-only mirror a minted object keeps.
		final ObjectPersistencePacket object = (ObjectPersistencePacket) minted.property().obj;
		assertEquals("Ring_Of_Tests(Clone)", object.ObjectName);
		assertEquals(GUID.toString(), object.ObjectID);
		assertEquals("Player_Elwyn(Clone)", object.Parent);
	}

	@Test
	public void noLevelNameKeepsTheTemplates () throws Exception {
		final SaveModel save = save();
		final SaveObject template = template(save);
		final SaveObject minted = save.mint(template, identity(null), IDENTITY_ONLY, (t, c) -> {});

		assertEquals(template.levelName(), minted.levelName());
	}

	@Test
	public void onlyTheKeptComponentsComeAcrossAndInstanceIdNamesTheNewObject () throws Exception {
		final SaveModel save = save();
		final SaveObject minted = save.mint(template(save), identity(null), IDENTITY_ONLY, (t, c) -> {});

		assertEquals(new HashSet<>(Arrays.asList("InstanceID", "Persistence")), componentTypes(minted));
		assertEquals("InstanceID.Guid must be the object's own ObjectID", GUID, instanceGuid(minted));
	}

	@Test
	public void theTemplateIsNeverTouched () throws Exception {
		final SaveModel save = save();
		final SaveObject template = template(save);
		final Object guidBefore = instanceGuid(template);
		final Object nameBefore = header(template, "ObjectName");

		final SaveObject minted = save.mint(template, identity(null), IDENTITY_ONLY
			, (type, copy) -> copy.<DictionaryProperty>findProperty("Variables")
				.ifPresent(variables -> variables.items.forEach(entry -> {
					if (entry.getValue() instanceof SimpleProperty) {
						((SimpleProperty) entry.getValue()).value = "changed";
					}
				})));

		assertNotNull(minted);
		assertEquals(guidBefore, instanceGuid(template));
		assertEquals(nameBefore, header(template, "ObjectName"));
	}

	@Test
	public void theAdjustmentSeesEachKeptCopyByType () throws Exception {
		final SaveModel save = save();
		final List<String> seen = new ArrayList<>();
		save.mint(template(save), identity(null), IDENTITY_ONLY, (type, copy) -> seen.add(type));

		assertEquals(new HashSet<>(Arrays.asList("InstanceID", "Persistence")), new HashSet<>(seen));
	}

	@Test
	public void keepingNothingMintsNothing () throws Exception {
		final SaveModel save = save();
		final int before = save.objects().size();
		try {
			save.mint(template(save), identity(null), (type, component) -> false, (t, c) -> {});
			fail("a new object with no components was made");
		} catch (final WriteRefusedException e) {
			assertEquals(before, save.objects().size());
		}
	}
}
