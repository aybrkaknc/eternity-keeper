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

package uk.me.mantas.eternity.save.model;

import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.util.function.BiConsumer;

/**
 * A new object made out of the shape of one already in the save (invariant
 * 7), for {@link SaveModel#mint}.
 *
 * <p>The header fields are copied with their exact types and the object's own
 * values written in, and a few of the template's components copied across --
 * everything else comes back from the prefab when the game restores the
 * object, which is exactly right for a new one. {@code InstanceID.Guid} is
 * always the new object's own GUID, and every copied component is a copy:
 * reusing the template's own properties once made rewriting the new GUID
 * rewrite the template's too, and the game dropped both objects without a
 * word.
 */
final class Mint {
	private Mint () {}

	static ComplexProperty build (
		final SaveObject template
		, final SaveModel.Identity identity
		, final SaveModel.Keep keep
		, final BiConsumer<String, ComplexProperty> adjust) throws WriteRefusedException {

		final ComplexProperty source = template.property();
		final ComplexProperty packet = new ComplexProperty(source.name, source.type);
		String levelName = identity.levelName;

		for (final Property field : source.properties) {
			if (field.name == null) {
				continue;
			}

			if ("ComponentPackets".equals(field.name)) {
				packet.properties.add(components(template, field, identity, keep, adjust));
				continue;
			}

			if (!(field instanceof SimpleProperty)) {
				// Location and Rotation ride along unchanged: an item in a pack
				// is never placed, and an ability is moved onto its owner the
				// moment the game restores it.
				packet.properties.add(field);
				continue;
			}

			Object value = ((SimpleProperty) field).value;
			switch (field.name) {
				case "ObjectName":      value = identity.objectName; break;
				case "ObjectID":        value = identity.objectID; break;
				case "GUID":            value = identity.guid; break;
				case "PrefabResource":  value = identity.prefabResource; break;
				case "Parent":          value = identity.parent; break;
				case "LevelName":
					if (levelName == null) {
						levelName = value == null ? null : value.toString();
					} else {
						value = levelName;
					}
					break;
				default:                break;
			}

			final SimpleProperty copy = new SimpleProperty(field.name, field.type);
			copy.value = value;
			copy.obj = value;
			packet.properties.add(copy);
		}

		// Only the header: the components have no mirror of their own, and
		// nothing that writes reads one (invariant 26).
		final ObjectPersistencePacket materialised = new ObjectPersistencePacket();
		materialised.ObjectName = identity.objectName;
		materialised.ObjectID = identity.objectID;
		materialised.GUID = identity.guid;
		materialised.PrefabResource = identity.prefabResource;
		materialised.Parent = identity.parent;
		materialised.LevelName = levelName;
		packet.obj = materialised;

		return packet;
	}

	private static Property components (
		final SaveObject template
		, final Property field
		, final SaveModel.Identity identity
		, final SaveModel.Keep keep
		, final BiConsumer<String, ComplexProperty> adjust) throws WriteRefusedException {

		if (!(field instanceof SingleDimensionalArrayProperty)) {
			throw new WriteRefusedException(template.describe()
				+ "'s components are not a list, so nothing can be made from it. Nothing was changed.");
		}

		final SingleDimensionalArrayProperty source = (SingleDimensionalArrayProperty) field;
		final SingleDimensionalArrayProperty components =
			new SingleDimensionalArrayProperty(source.name, source.type);

		components.elementType = source.elementType;

		for (final Component component : template.components()) {
			final String type = component.type();
			if (type == null || !keep.test(type, component.property())) {
				continue;
			}

			final ComplexProperty copy = EKUtils.copyComponent(component.property());
			components.items.add(copy);

			if ("InstanceID".equals(type)) {
				copy.<DictionaryProperty>findProperty("Variables")
					.flatMap(variables -> variables.findEntry("Guid"))
					.ifPresent(entry -> {
						((SimpleProperty) entry).value = identity.guid;
						entry.obj = identity.guid;
					});
			}

			adjust.accept(type, copy);
		}

		if (components.items.isEmpty()) {
			throw new WriteRefusedException(template.describe()
				+ " has no component worth copying, so nothing can be made from it. Nothing was changed.");
		}

		return components;
	}
}
