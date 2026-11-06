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

import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.util.Optional;

/**
 * One component of a game object (a ComponentPersistencePacket): its
 * {@code TypeString} and its {@code Variables}.
 */
public final class Component {
	private final SaveObject owner;
	private final ComplexProperty component;

	Component (final SaveObject owner, final ComplexProperty component) {
		this.owner = owner;
		this.component = component;
	}

	/** The component in the tree, for what the model does not cover yet. */
	public ComplexProperty property () {
		return component;
	}

	public SaveObject owner () {
		return owner;
	}

	public String type () {
		return Property.find(component, "TypeString")
			.filter(type -> type instanceof SimpleProperty)
			.map(type -> String.valueOf(((SimpleProperty) type).value))
			.orElse(null);
	}

	/** A variable as it is in the tree: a plain value, or something to read further. */
	public Optional<Property> variable (final String name) {
		return variables().flatMap(variables -> variables.findEntry(name));
	}

	/** A variable that is a plain value. */
	public Optional<Object> get (final String name) {
		return variable(name)
			.filter(value -> value instanceof SimpleProperty)
			.map(value -> ((SimpleProperty) value).value);
	}

	/**
	 * Writes a variable the component holds, as the type it holds.
	 *
	 * @throws WriteRefusedException when there is no such variable, it is not
	 *         a plain value, or the value cannot be its type; nothing is
	 *         changed
	 */
	public void set (final String name, final Object value) throws WriteRefusedException {
		final Optional<Property> found = variable(name);
		if (!found.isPresent() || !(found.get() instanceof SimpleProperty)) {
			throw new WriteRefusedException(
				describe() + " has no '" + name + "' to set. Nothing was changed.");
		}

		TypedValue.write((SimpleProperty) found.get(), value, describe() + "'s " + name);
	}

	/** A list of GUIDs the component holds: its SerializedItemList, its equipment slots. */
	public Optional<GuidList> guidList (final String name) {
		return GuidList.of(describe() + "'s " + name, variable(name));
	}

	/** What the component holds, when it is a container: a pack, the stash, a store. */
	public Optional<ItemContainer> itemContainer () {
		return ItemContainer.of(this);
	}

	Optional<DictionaryProperty> variables () {
		return Property.find(component, "Variables")
			.filter(variables -> variables instanceof DictionaryProperty)
			.map(variables -> (DictionaryProperty) variables);
	}

	private String describe () {
		return owner.describe() + "'s " + type();
	}
}
