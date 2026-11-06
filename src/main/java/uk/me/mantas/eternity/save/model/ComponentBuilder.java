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

import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.NullProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.util.AbstractMap.SimpleEntry;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A component made from nothing, written as the game writes one: its
 * variables in order, each with the exact C# type the game's own saves give
 * it (harvested from saves). For a component the game adds itself -- a
 * party member's AI, a stored roster record's -- when there is none in the
 * save to copy.
 */
public final class ComponentBuilder {
	public static final String TYPE_OPP = "ObjectPersistencePacket, Assembly-CSharp";
	public static final String TYPE_CPP = "ComponentPersistencePacket, Assembly-CSharp";
	private static final String TYPE_STRING = "System.String, mscorlib";
	private static final String TYPE_BOOL = "System.Boolean, mscorlib";
	private static final String TYPE_INT = "System.Int32, mscorlib";
	private static final String TYPE_GUID = "System.Guid, mscorlib";
	private static final String TYPE_OBJECT = "System.Object, mscorlib";
	private static final String TYPE_GUID_LIST =
		"System.Collections.Generic.List`1[[System.Guid, mscorlib]], mscorlib";

	private final String typeString;
	private final LinkedHashMap<String, Property> variables = new LinkedHashMap<>();

	private ComponentBuilder (final String typeString) {
		this.typeString = typeString;
	}

	/** A component of this {@code TypeString}. */
	public static ComponentBuilder of (final String typeString) {
		return new ComponentBuilder(typeString);
	}

	public ComponentBuilder integer (final String name, final int value) {
		return put(name, value(int.class, TYPE_INT, value));
	}

	public ComponentBuilder bool (final String name, final boolean value) {
		return put(name, value(boolean.class, TYPE_BOOL, value));
	}

	public ComponentBuilder text (final String name, final String value) {
		return put(name, value(String.class, TYPE_STRING, value));
	}

	/** A GUID; none, for {@code null}. */
	public ComponentBuilder guid (final String name, final UUID value) {
		return value == null ? nothing(name) : put(name, value(UUID.class, TYPE_GUID, value));
	}

	/** A constant of one of the game's enums, whose C# type is {@code cSharpType}. */
	public ComponentBuilder constant (final String name, final Enum<?> value, final String cSharpType) {
		return put(name, value(value.getDeclaringClass(), cSharpType, value));
	}

	/** A variable that holds nothing (a null reference). */
	public ComponentBuilder nothing (final String name) {
		return put(name, new NullProperty(null));
	}

	/** An empty {@code List<Guid>}. */
	public ComponentBuilder emptyGuidList (final String name) {
		final CollectionProperty list =
			new CollectionProperty(null, new TypePair(CSharpCollection.class, TYPE_GUID_LIST));
		list.elementType = new TypePair(UUID.class, TYPE_GUID);
		list.properties.add(field("Capacity", int.class, 0));
		return put(name, list);
	}

	private ComponentBuilder put (final String name, final Property value) {
		variables.put(name, value);
		return this;
	}

	public ComplexProperty build () {
		final ComplexProperty component =
			new ComplexProperty(null, new TypePair(ComponentPersistencePacket.class, null));

		component.properties.add(field("TypeString", String.class, typeString));

		final DictionaryProperty dict =
			new DictionaryProperty("Variables", new TypePair(HashMap.class, null));
		dict.keyType = new TypePair(String.class, TYPE_STRING);
		dict.valueType = new TypePair(Object.class, TYPE_OBJECT);

		for (final Map.Entry<String, Property> variable : variables.entrySet()) {
			final SimpleProperty key = new SimpleProperty(null, new TypePair(String.class, null));
			key.value = variable.getKey();
			key.obj = variable.getKey();
			dict.items.add(new SimpleEntry<>(key, variable.getValue()));
		}

		component.properties.add(dict);
		return component;
	}

	private static SimpleProperty value (final Class javaType, final String cSharpType, final Object value) {
		final SimpleProperty property = new SimpleProperty(null, new TypePair(javaType, cSharpType));
		property.value = value;
		property.obj = value;
		return property;
	}

	/** A field of an object or a component, named, with no C# type of its own written. */
	public static SimpleProperty field (final String name, final Class javaType, final Object value) {
		final SimpleProperty property = new SimpleProperty(name, new TypePair(javaType, null));
		property.value = value;
		property.obj = value;
		return property;
	}
}
