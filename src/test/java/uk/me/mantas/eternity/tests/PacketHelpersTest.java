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

package uk.me.mantas.eternity.tests;

import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * The two helpers every object-minting manager needs: find a packet by its
 * ObjectID, and copy a component without sharing anything with it.
 *
 * <p>AbilityManager and InventoryManager each had their own identical copies.
 * The copy is the one that matters. Minting an item used to insert the
 * template's own {@code Property} objects into the new packet, so writing the
 * new GUID into the copied {@code InstanceID} rewrote the template's GUID too,
 * and the game dropped both items on load with no error. One implementation,
 * tested for exactly that, is the fix that cannot drift.
 */
public class PacketHelpersTest extends TestHarness {
	private List<Property> fixture () throws Exception {
		final File save = new File(getClass().getResource("/MobileObjects.save").toURI());
		return new PacketDeserializer(save).deserialize().get().getPackets();
	}

	private static ComplexProperty instanceIdOf (final Property packet) {
		return SaveObject.of(packet).get().component("InstanceID").get().property();
	}

	private static SimpleProperty guidOf (final ComplexProperty instanceId) {
		return (SimpleProperty) instanceId.<DictionaryProperty>findProperty("Variables")
			.flatMap(v -> v.findEntry("Guid")).get();
	}

	private Property someCharacter (final List<Property> packets) {
		return packets.stream()
			.filter(p -> p.obj instanceof ObjectPersistencePacket)
			.filter(p -> ((ObjectPersistencePacket) p.obj).ObjectName.startsWith("Player_"))
			.findFirst().get();
	}

	@Test
	public void aCopiedComponentSharesNothingWithItsSource () throws Exception {
		final ComplexProperty source = instanceIdOf(someCharacter(fixture()));
		final Object sourceGuid = guidOf(source).value;

		final ComplexProperty copy = EKUtils.copyComponent(source);
		final UUID minted = UUID.randomUUID();
		guidOf(copy).value = minted;
		guidOf(copy).obj = minted;

		assertEquals("rewriting the copy must leave the template alone"
			, sourceGuid, guidOf(source).value);
		assertNotSame(source, copy);
		assertNotSame(guidOf(source), guidOf(copy));
	}

	@Test
	public void aCopiedComponentKeepsEveryTypeItWasSerializedWith () throws Exception {
		final ComplexProperty source = instanceIdOf(someCharacter(fixture()));
		final ComplexProperty copy = EKUtils.copyComponent(source);

		assertEquals(source.type, copy.type);

		final DictionaryProperty from = source.<DictionaryProperty>findProperty("Variables").get();
		final DictionaryProperty to = copy.<DictionaryProperty>findProperty("Variables").get();

		// A hand-made entry serializes with an empty C# type name and the save
		// no longer reads back, so the types have to come across exactly.
		assertEquals(from.keyType, to.keyType);
		assertEquals(from.valueType, to.valueType);
		assertEquals(from.items.size(), to.items.size());
		assertEquals(guidOf(source).type, guidOf(copy).type);
		assertEquals(guidOf(source).value, guidOf(copy).value);
	}

	@Test
	public void aPacketIsFoundByItsObjectIdInAnyCase () throws Exception {
		final List<Property> packets = fixture();
		final Property character = someCharacter(packets);
		final String id = SaveObject.of(character).get().id();
		final SaveModel save = SaveModel.of(packets);

		assertSame(character, save.object(id).get().property());
		assertSame(character, save.object(id.toUpperCase()).get().property());
		assertSame(character, save.object(id.toLowerCase()).get().property());
	}

	@Test
	public void anUnknownObjectIdFindsNothing () throws Exception {
		final Optional<SaveObject> found =
			SaveModel.of(fixture()).object(UUID.randomUUID().toString());

		assertFalse(found.isPresent());
	}
}
