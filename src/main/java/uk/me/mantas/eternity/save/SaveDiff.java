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

import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.MultiDimensionalArrayProperty;
import uk.me.mantas.eternity.serializer.properties.MultiDimensionalArrayProperty.MultiDimensionalArrayItem;
import uk.me.mantas.eternity.serializer.properties.NullProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Every value that differs between two packet files, object by object: the
 * raw half of comparing two saves, which {@link SaveComparison} puts into
 * words.
 *
 * <p>Objects are matched by ObjectID, the one thing about an object that
 * stays put from save to save: companions have fixed IDs, and an item or an
 * ability keeps its own for as long as it exists. Within a matched pair each
 * of the object's own fields and each variable of each component is compared
 * as the property tree the file holds, not as the mirror objects the
 * deserializer builds, so a value with no mirror class is compared as surely
 * as one with. Two things a byte comparison would get wrong are allowed for:
 * a dictionary is compared by key, since the game's Hashtables list their
 * entries in the table's own order, and binary blobs (quest progress, the
 * conversations read) by what they hold.
 *
 * <p>Measured on two real saves fifty minutes of play apart (6,953 objects
 * each): 1,235 objects differ, and nearly all of that is the party's
 * belongings riding along into another area (their {@code LevelName},
 * {@code Location} and {@code Rotation}) and timers ticking. What the player
 * did is a few dozen values. Telling those apart is the presenter's job; this
 * reports everything.
 */
public final class SaveDiff {
	/** One value that differs between the two files. */
	public static final class Change {
		/** The object's ID, in lower case. */
		public final String id;
		/** The component's TypeString, or empty for one of the object's own fields. */
		public final String component;
		/** Which of the object's components of that type, from 0; nearly always 0. */
		public final int occurrence;
		public final String variable;
		/** What the first file holds, or null when it holds nothing there. */
		public final Property before;
		/** What the second file holds, or null when it holds nothing there. */
		public final Property after;

		Change (
			final String id, final String component, final int occurrence, final String variable
			, final Property before, final Property after) {

			this.id = id;
			this.component = component;
			this.occurrence = occurrence;
			this.variable = variable;
			this.before = before;
			this.after = after;
		}

		@Override
		public String toString () {
			return id + " " + (component.isEmpty() ? "" : component + ".") + variable;
		}
	}

	/** One place inside two values where they differ. */
	public static final class Leaf {
		/**
		 * How to reach it from the value: {@code ".field"} for a field,
		 * {@code "[3]"} for a place in a list, {@code "[key]"} for an entry,
		 * joined; empty for the value itself.
		 */
		public final String path;
		/** Null when the first value has nothing there. */
		public final Property before;
		/** Null when the second value has nothing there. */
		public final Property after;

		Leaf (final String path, final Property before, final Property after) {
			this.path = path;
			this.before = before;
			this.after = after;
		}

		@Override
		public String toString () {
			return path;
		}
	}

	/** Every object of the first file, by lower-case ID, in file order. */
	public final Map<String, ComplexProperty> before;
	/** Every object of the second file, likewise. */
	public final Map<String, ComplexProperty> after;
	/** IDs only the second file holds, in its order. */
	public final List<String> added;
	/** IDs only the first file holds, in its order. */
	public final List<String> removed;
	public final List<Change> changes;

	private SaveDiff (
		final Map<String, ComplexProperty> before, final Map<String, ComplexProperty> after
		, final List<String> added, final List<String> removed, final List<Change> changes) {

		this.before = before;
		this.after = after;
		this.added = added;
		this.removed = removed;
		this.changes = changes;
	}

	private static final String COMPONENTS = "ComponentPackets";
	private static final String ID = "ObjectID";

	/** What differs between two files' packets, {@code before} first. */
	public static SaveDiff between (final List<Property> before, final List<Property> after) {
		final Map<String, ComplexProperty> first = byId(before);
		final Map<String, ComplexProperty> second = byId(after);

		final List<String> added = new ArrayList<>();
		final List<String> removed = new ArrayList<>();
		final List<Change> changes = new ArrayList<>();

		for (final Map.Entry<String, ComplexProperty> object : first.entrySet()) {
			final ComplexProperty other = second.get(object.getKey());
			if (other == null) {
				removed.add(object.getKey());
			} else {
				compare(object.getKey(), object.getValue(), other, changes);
			}
		}

		for (final String id : second.keySet()) {
			if (!first.containsKey(id)) {
				added.add(id);
			}
		}

		return new SaveDiff(first, second, added, removed, changes);
	}

	/**
	 * Each object under its ID. An ID a file holds twice -- the signature of
	 * the old item-minting bug, which the game answers by dropping both -- keeps
	 * the second under {@code id#2}, so neither copy goes uncompared.
	 */
	private static Map<String, ComplexProperty> byId (final List<Property> packets) {
		final Map<String, ComplexProperty> objects = new LinkedHashMap<>();
		if (packets == null) {
			return objects;
		}

		for (final Property property : packets) {
			if (!(property instanceof ComplexProperty)) {
				continue;
			}

			final ComplexProperty packet = (ComplexProperty) property;
			final String id = text(packet, ID);
			String key = id.isEmpty() ? "(no id) " + text(packet, "ObjectName") : id.toLowerCase();

			for (int n = 2; objects.containsKey(key); n++) {
				key = (id.isEmpty() ? "(no id) " + text(packet, "ObjectName") : id.toLowerCase()) + "#" + n;
			}

			objects.put(key, packet);
		}

		return objects;
	}

	private static String text (final ComplexProperty packet, final String field) {
		return packet.findProperty(field)
			.map(property -> ((Property) property).obj)
			.map(String::valueOf)
			.orElse("");
	}

	private static void compare (
		final String id, final ComplexProperty first, final ComplexProperty second
		, final List<Change> changes) {

		final Map<String, Property> ownFirst = ownFields(first);
		final Map<String, Property> ownSecond = ownFields(second);
		for (final String field : union(ownFirst.keySet(), ownSecond.keySet())) {
			final Property a = ownFirst.get(field);
			final Property b = ownSecond.get(field);
			if (!same(a, b)) {
				changes.add(new Change(id, "", 0, field, a, b));
			}
		}

		final Map<String, Map<String, Property>> componentsFirst = components(first);
		final Map<String, Map<String, Property>> componentsSecond = components(second);
		for (final String key : union(componentsFirst.keySet(), componentsSecond.keySet())) {
			final Map<String, Property> a = componentsFirst.getOrDefault(key, Collections.emptyMap());
			final Map<String, Property> b = componentsSecond.getOrDefault(key, Collections.emptyMap());
			final int hash = key.lastIndexOf('#');
			final String type = key.substring(0, hash);
			final int occurrence = Integer.parseInt(key.substring(hash + 1));

			for (final String variable : union(a.keySet(), b.keySet())) {
				if (!same(a.get(variable), b.get(variable))) {
					changes.add(new Change(id, type, occurrence, variable, a.get(variable), b.get(variable)));
				}
			}
		}
	}

	private static <T> Set<T> union (final Set<T> first, final Set<T> second) {
		final Set<T> all = new LinkedHashSet<>(first);
		all.addAll(second);
		return all;
	}

	/** The object's own fields: its name, area, place, parent and the rest. */
	private static Map<String, Property> ownFields (final ComplexProperty packet) {
		final Map<String, Property> fields = new LinkedHashMap<>();
		for (final Property field : packet.properties) {
			if (field != null && field.name != null
				&& !COMPONENTS.equals(field.name) && !ID.equals(field.name)) {

				fields.put(field.name, field);
			}
		}

		return fields;
	}

	/** Each component's variables, under {@code TypeString#occurrence}. */
	private static Map<String, Map<String, Property>> components (final ComplexProperty packet) {
		final Map<String, Map<String, Property>> components = new LinkedHashMap<>();
		final Property list = packet.findProperty(COMPONENTS).orElse(null);
		if (!(list instanceof SingleDimensionalArrayProperty)) {
			return components;
		}

		final Map<String, Integer> seen = new LinkedHashMap<>();
		for (final Object item : ((SingleDimensionalArrayProperty) list).items) {
			if (!(item instanceof ComplexProperty)) {
				continue;
			}

			final ComplexProperty component = (ComplexProperty) item;
			final String type = text(component, "TypeString");
			final int occurrence = seen.merge(type, 1, Integer::sum) - 1;

			final Map<String, Property> variables = new LinkedHashMap<>();
			final Property dictionary = component.findProperty("Variables").orElse(null);
			if (dictionary instanceof DictionaryProperty) {
				for (final Map.Entry<Property, Property> entry : ((DictionaryProperty) dictionary).items) {
					if (entry != null && entry.getKey() != null) {
						variables.put(String.valueOf(entry.getKey().obj), entry.getValue());
					}
				}
			}

			components.put(type + "#" + occurrence, variables);
		}

		return components;
	}

	/**
	 * Whether two values are the same, however the file happened to order a
	 * dictionary's entries. Nothing and a null are the same.
	 */
	public static boolean same (final Property a, final Property b) {
		return same(a, b, new ArrayList<>());
	}

	private static boolean same (final Property a, final Property b, final List<Property[]> path) {
		if (a == b) {
			return true;
		}

		if (isNull(a) || isNull(b)) {
			return isNull(a) && isNull(b);
		}

		if (a instanceof SimpleProperty || b instanceof SimpleProperty) {
			return a instanceof SimpleProperty && b instanceof SimpleProperty
				&& sameValue(((SimpleProperty) a).value, ((SimpleProperty) b).value);
		}

		if (a.getClass() != b.getClass()) {
			return false;
		}

		// A reference back up the tree: these two are already being compared.
		for (final Property[] pair : path) {
			if (pair[0] == a && pair[1] == b) {
				return true;
			}
		}

		path.add(new Property[] {a, b});
		try {
			return sameShape(a, b, path);
		} finally {
			path.remove(path.size() - 1);
		}
	}

	private static boolean sameShape (final Property a, final Property b, final List<Property[]> path) {
		if (a instanceof DictionaryProperty) {
			return sameEntries((DictionaryProperty) a, (DictionaryProperty) b, path)
				&& sameFields((ComplexProperty) a, (ComplexProperty) b, path);
		}

		if (a instanceof CollectionProperty) {
			return sameItems(((CollectionProperty) a).items, ((CollectionProperty) b).items, path)
				&& sameFields((ComplexProperty) a, (ComplexProperty) b, path);
		}

		if (a instanceof SingleDimensionalArrayProperty) {
			return sameItems(
				((SingleDimensionalArrayProperty) a).items
				, ((SingleDimensionalArrayProperty) b).items, path);
		}

		if (a instanceof MultiDimensionalArrayProperty) {
			final List<MultiDimensionalArrayItem> first = ((MultiDimensionalArrayProperty) a).items;
			final List<MultiDimensionalArrayItem> second = ((MultiDimensionalArrayProperty) b).items;
			if (first.size() != second.size()) {
				return false;
			}

			for (int i = 0; i < first.size(); i++) {
				if (!Arrays.equals(first.get(i).indexes, second.get(i).indexes)
					|| !same(first.get(i).value, second.get(i).value, path)) {

					return false;
				}
			}

			return true;
		}

		if (a instanceof ComplexProperty) {
			return sameFields((ComplexProperty) a, (ComplexProperty) b, path);
		}

		return false;
	}

	private static boolean sameFields (
		final ComplexProperty a, final ComplexProperty b, final List<Property[]> path) {

		if (a.properties.size() != b.properties.size()) {
			return false;
		}

		for (int i = 0; i < a.properties.size(); i++) {
			final Property first = a.properties.get(i);
			Property second = b.properties.get(i);

			// Same type, same order, nearly always; look the field up otherwise.
			if (first != null && second != null && !Objects.equals(first.name, second.name)) {
				second = field(b, first.name);
			}

			if (!same(first, second, path)) {
				return false;
			}
		}

		return true;
	}

	private static Property field (final ComplexProperty owner, final String name) {
		for (final Property field : owner.properties) {
			if (field != null && Objects.equals(field.name, name)) {
				return field;
			}
		}

		return null;
	}

	private static boolean sameItems (final List<?> a, final List<?> b, final List<Property[]> path) {
		if (a.size() != b.size()) {
			return false;
		}

		for (int i = 0; i < a.size(); i++) {
			if (!same(asProperty(a.get(i)), asProperty(b.get(i)), path)) {
				return false;
			}
		}

		return true;
	}

	private static boolean sameEntries (
		final DictionaryProperty a, final DictionaryProperty b, final List<Property[]> path) {

		if (a.items.size() != b.items.size()) {
			return false;
		}

		// The same order is the usual case, and the cheap one.
		boolean ordered = true;
		for (int i = 0; i < a.items.size() && ordered; i++) {
			ordered = same(key(a.items.get(i)), key(b.items.get(i)), path);
		}

		if (ordered) {
			for (int i = 0; i < a.items.size(); i++) {
				if (!same(a.items.get(i).getValue(), b.items.get(i).getValue(), path)) {
					return false;
				}
			}

			return true;
		}

		final Map<Object, Property> second = entries(b);
		if (second.size() != b.items.size()) {
			return false;
		}

		for (final Map.Entry<Object, Property> entry : entries(a).entrySet()) {
			if (!second.containsKey(entry.getKey())
				|| !same(entry.getValue(), second.get(entry.getKey()), path)) {

				return false;
			}
		}

		return true;
	}

	private static Property key (final Map.Entry<Property, Property> entry) {
		return entry == null ? null : entry.getKey();
	}

	/** A dictionary's values by what their keys hold. */
	private static Map<Object, Property> entries (final DictionaryProperty dictionary) {
		final Map<Object, Property> entries = new LinkedHashMap<>();
		for (final Map.Entry<Property, Property> entry : dictionary.items) {
			if (entry != null) {
				entries.put(keyOf(entry.getKey()), entry.getValue());
			}
		}

		return entries;
	}

	/**
	 * What a dictionary key holds, as something a map can look up. Keys are
	 * strings and numbers in every save seen so far; anything else is spelled
	 * out.
	 */
	static Object keyOf (final Property key) {
		if (key instanceof SimpleProperty) {
			final Object value = ((SimpleProperty) key).value;
			return value != null && value.getClass().isArray() ? Arrays.deepToString(new Object[] {value}) : value;
		}

		final StringBuilder spelled = new StringBuilder();
		spell(key, spelled, 0);
		return spelled.toString();
	}

	private static void spell (final Property property, final StringBuilder out, final int depth) {
		if (depth > 8 || isNull(property)) {
			out.append("null");
		} else if (property instanceof SimpleProperty) {
			out.append(((SimpleProperty) property).value);
		} else if (property instanceof ComplexProperty) {
			out.append('{');
			for (final Property field : ((ComplexProperty) property).properties) {
				out.append(field == null ? "?" : field.name).append('=');
				spell(field, out, depth + 1);
				out.append(';');
			}

			out.append('}');
		} else {
			out.append(property.getClass().getSimpleName());
		}
	}

	private static boolean sameValue (final Object a, final Object b) {
		if (a == b) {
			return true;
		}

		if (a == null || b == null) {
			return false;
		}

		if (a instanceof Object[] && b instanceof Object[]) {
			return Arrays.deepEquals((Object[]) a, (Object[]) b);
		}

		if (a.getClass().isArray() && b.getClass().isArray()) {
			final int length = Array.getLength(a);
			if (length != Array.getLength(b)) {
				return false;
			}

			for (int i = 0; i < length; i++) {
				if (!Objects.equals(Array.get(a, i), Array.get(b, i))) {
					return false;
				}
			}

			return true;
		}

		return a.equals(b);
	}

	static boolean isNull (final Property property) {
		return property == null
			|| property instanceof NullProperty
			|| (property instanceof SimpleProperty && ((SimpleProperty) property).value == null);
	}

	/** A list item that is a plain value rather than a property, wrapped as one. */
	private static Property asProperty (final Object item) {
		if (item == null || item instanceof Property) {
			return (Property) item;
		}

		final SimpleProperty wrapped = new SimpleProperty(null, null);
		wrapped.value = item;
		wrapped.obj = item;
		return wrapped;
	}

	/** Every place inside two values where they differ, by the path to it. */
	public static List<Leaf> leaves (final Property before, final Property after) {
		final List<Leaf> leaves = new ArrayList<>();
		leaves(before, after, "", leaves, new ArrayList<>());
		return leaves;
	}

	private static void leaves (
		final Property a, final Property b, final String at, final List<Leaf> out
		, final List<Property[]> path) {

		if (same(a, b)) {
			return;
		}

		if (isNull(a) || isNull(b) || a instanceof SimpleProperty || b instanceof SimpleProperty
			|| a.getClass() != b.getClass()) {

			out.add(new Leaf(at, isNull(a) ? null : a, isNull(b) ? null : b));
			return;
		}

		for (final Property[] pair : path) {
			if (pair[0] == a && pair[1] == b) {
				return;
			}
		}

		path.add(new Property[] {a, b});
		try {
			// A list or a dictionary can carry fields of its own beside its
			// items, and same() compares those too.
			if (a instanceof CollectionProperty || a instanceof DictionaryProperty) {
				final Map<String, Property> first = fields((ComplexProperty) a);
				final Map<String, Property> second = fields((ComplexProperty) b);
				for (final String field : union(first.keySet(), second.keySet())) {
					leaves(first.get(field), second.get(field), at + "." + field, out, path);
				}
			}

			if (a instanceof DictionaryProperty) {
				final Map<Object, Property> first = entries((DictionaryProperty) a);
				final Map<Object, Property> second = entries((DictionaryProperty) b);
				for (final Object key : union(first.keySet(), second.keySet())) {
					leaves(first.get(key), second.get(key), at + "[" + key + "]", out, path);
				}

				return;
			}

			if (a instanceof CollectionProperty || a instanceof SingleDimensionalArrayProperty) {
				final List<?> first = a instanceof CollectionProperty
					? ((CollectionProperty) a).items : ((SingleDimensionalArrayProperty) a).items;
				final List<?> second = b instanceof CollectionProperty
					? ((CollectionProperty) b).items : ((SingleDimensionalArrayProperty) b).items;

				for (int i = 0; i < Math.max(first.size(), second.size()); i++) {
					leaves(
						i < first.size() ? asProperty(first.get(i)) : null
						, i < second.size() ? asProperty(second.get(i)) : null
						, at + "[" + i + "]", out, path);
				}

				return;
			}

			if (a instanceof MultiDimensionalArrayProperty) {
				final Map<String, Property> first = cells((MultiDimensionalArrayProperty) a);
				final Map<String, Property> second = cells((MultiDimensionalArrayProperty) b);
				for (final String cell : union(first.keySet(), second.keySet())) {
					leaves(first.get(cell), second.get(cell), at + cell, out, path);
				}

				return;
			}

			final Map<String, Property> first = fields((ComplexProperty) a);
			final Map<String, Property> second = fields((ComplexProperty) b);
			for (final String field : union(first.keySet(), second.keySet())) {
				leaves(first.get(field), second.get(field), at + "." + field, out, path);
			}
		} finally {
			path.remove(path.size() - 1);
		}
	}

	private static Map<String, Property> fields (final ComplexProperty owner) {
		final Map<String, Property> fields = new LinkedHashMap<>();
		for (final Property field : owner.properties) {
			if (field != null) {
				fields.put(field.name, field);
			}
		}

		return fields;
	}

	private static Map<String, Property> cells (final MultiDimensionalArrayProperty array) {
		final Map<String, Property> cells = new LinkedHashMap<>();
		for (final MultiDimensionalArrayItem item : array.items) {
			cells.put(Arrays.toString(item.indexes), item.value);
		}

		return cells;
	}
}
