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

import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static uk.me.mantas.eternity.game.UnityEngine.Vector3;

/**
 * One game object of a save (an ObjectPersistencePacket): its own fields --
 * ObjectName, ObjectID, Parent, LevelName -- and its components.
 */
public final class SaveObject {
	private final ComplexProperty packet;

	private SaveObject (final ComplexProperty packet) {
		this.packet = packet;
	}

	/** The game object this entry of a file is, unless it is not one. */
	public static Optional<SaveObject> of (final Property packet) {
		// The odd entry of a real save is not a game object at all.
		if (packet instanceof ComplexProperty && packet.obj instanceof ObjectPersistencePacket) {
			return Optional.of(new SaveObject((ComplexProperty) packet));
		}

		return Optional.empty();
	}

	/** The object in the tree, for what the model does not cover yet. */
	public ComplexProperty property () {
		return packet;
	}

	public String name () {
		return text("ObjectName");
	}

	public String id () {
		return text("ObjectID");
	}

	/** The ObjectName of the object that owns this one, or {@code null}. */
	public String parent () {
		return text("Parent");
	}

	public boolean isPlayer () {
		return name() != null && name().startsWith("Player_");
	}

	public boolean isCompanion () {
		return name() != null && name().startsWith("Companion_");
	}

	/** One of the object's own fields, as a plain value. */
	public Optional<Object> get (final String field) {
		return Property.find(packet, field)
			.filter(value -> value instanceof SimpleProperty)
			.map(value -> ((SimpleProperty) value).value);
	}

	/**
	 * Writes one of the object's own fields, as the type it holds.
	 *
	 * @throws WriteRefusedException when the object has no such field, or the
	 *         value cannot be that field's type; nothing is changed
	 */
	public void set (final String field, final Object value) throws WriteRefusedException {
		final Optional<Property> found = Property.find(packet, field);
		if (!found.isPresent() || !(found.get() instanceof SimpleProperty)) {
			throw new WriteRefusedException(describe() + " has no field '" + field + "' to set.");
		}

		TypedValue.write((SimpleProperty) found.get(), value, describe() + "'s " + field);
	}

	/** Where in its area the object stands; 0, 0, 0 for one that has no Location. */
	public Vector3 location () {
		final Vector3 location = new Vector3();
		final Optional<Property> found = Property.find(packet, "Location");
		if (found.isPresent()) {
			location.x = coordinate(found.get(), "x");
			location.y = coordinate(found.get(), "y");
			location.z = coordinate(found.get(), "z");
		}

		return location;
	}

	/**
	 * Puts the object in that area at that place.
	 *
	 * @throws WriteRefusedException when the object has no LevelName or
	 *         Location to set; nothing is changed
	 */
	public void moveTo (final String levelName, final Vector3 location) throws WriteRefusedException {
		final Optional<Property> found = Property.find(packet, "Location");
		if (!found.isPresent()
			|| !Property.find(found.get(), "x").isPresent()
			|| !Property.find(found.get(), "y").isPresent()
			|| !Property.find(found.get(), "z").isPresent()) {

			throw new WriteRefusedException(describe() + " has no Location to move. Nothing was changed.");
		}

		set("LevelName", levelName);
		Property.update(found.get(), "x", location.x);
		Property.update(found.get(), "y", location.y);
		Property.update(found.get(), "z", location.z);
	}

	private static float coordinate (final Property location, final String axis) {
		return Property.find(location, axis)
			.filter(value -> value instanceof SimpleProperty && ((SimpleProperty) value).value instanceof Float)
			.map(value -> (Float) ((SimpleProperty) value).value)
			.orElse(0f);
	}

	/** The component of this type ({@code TypeString}, in any case). */
	public Optional<Component> component (final String type) {
		return components().stream()
			.filter(component -> type.equalsIgnoreCase(component.type()))
			.findFirst();
	}

	public List<Component> components () {
		final List<Component> components = new ArrayList<>();
		for (final Object item : componentArray()) {
			if (item instanceof ComplexProperty) {
				components.add(new Component(this, (ComplexProperty) item));
			}
		}

		return components;
	}

	/**
	 * Puts {@code now} -- another object's component, say -- where this
	 * object's {@code was} stands.
	 *
	 * @throws WriteRefusedException when {@code was} is not one of this
	 *         object's components; nothing is changed
	 */
	@SuppressWarnings("unchecked")
	public void replace (final Component was, final Component now) throws WriteRefusedException {
		final List<Object> items = componentArray();
		final int at = items.indexOf(was.property());
		if (at < 0) {
			throw new WriteRefusedException(
				describe() + " has no " + was.type() + " of its own to replace. Nothing was changed.");
		}

		items.set(at, now.property());
	}

	@SuppressWarnings("unchecked")
	private List<Object> componentArray () {
		return Property.find(packet, "ComponentPackets")
			.filter(array -> array instanceof SingleDimensionalArrayProperty)
			.map(array -> (List<Object>) ((SingleDimensionalArrayProperty) array).items)
			.orElse(new ArrayList<>());
	}

	String describe () {
		return name() == null ? "An object with no name" : name();
	}

	private String text (final String field) {
		return get(field).map(value -> value == null ? null : value.toString()).orElse(null);
	}
}
