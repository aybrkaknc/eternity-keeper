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


package uk.me.mantas.eternity.serializer;

import com.google.common.primitives.UnsignedInteger;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.MultiDimensionalArrayProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a packet file must not contradict about itself, read off the property
 * tree the serializer writes.
 *
 * <p>{@code save/SaveValidator} checks the same things on an opened save, but
 * it reads the mirror objects the deserializer builds, and a write changes the
 * tree without touching those ({@code Property.update} sets the tree's value
 * and nothing else). So checking what is about to be written means reading the
 * tree itself: the leading count, each object's ID and InstanceID, the item
 * lists that must pair up, the GUIDs that must name an object in the file, and
 * the value every simple property holds.
 *
 * <p>That last one is invariant 2 made mechanical. The serializer writes a
 * value by the class it holds, not by the type the file declares, so a GUID
 * held as text is written as a string where sixteen bytes belong, a
 * {@code Long} as eight bytes where an int's four belong, and nothing after it
 * reads again. An enum may hold its plain number: it is written as that number
 * either way, which is how a constant the mirror enum does not know survives.
 *
 * <p>A breach carries a signature, the same for the same problem whether it is
 * found in the file as read or as about to be written, so a writer can refuse
 * only what an edit newly got wrong. Real saves have their own: every mid-game
 * save has stores whose lists name items with no object of their own.
 */
public final class PacketInvariants {
	/** One thing a file contradicts about itself. */
	public static final class Breach {
		/** The same for the same problem, found before an edit or after it. */
		public final String signature;
		/** In words for the user. */
		public final String detail;

		Breach (final String signature, final String detail) {
			this.signature = signature;
			this.detail = detail;
		}

		@Override
		public String toString () {
			return detail;
		}
	}

	private static final String[] WORN = {"EquipmentSetSerialized", "WeaponSetsSerialized"};

	private PacketInvariants () {}

	/** Everything {@code packets}, with {@code count} leading them, contradicts. */
	public static List<Breach> check (final List<Property> packets, final SimpleProperty count) {
		final List<Breach> breaches = new ArrayList<>();
		if (packets == null) {
			return breaches;
		}

		if (count != null && count.obj instanceof Number
			&& ((Number) count.obj).intValue() != packets.size()) {

			breaches.add(new Breach("COUNT", String.format(
				"the file would say it holds %d objects and carry %d, so every read would stop early"
				, ((Number) count.obj).intValue(), packets.size())));
		}

		final List<ComplexProperty> objects = new ArrayList<>();
		final Map<String, Integer> ids = new LinkedHashMap<>();
		for (final Property property : packets) {
			if (!(property instanceof ComplexProperty)) {
				continue;
			}

			final ComplexProperty packet = (ComplexProperty) property;
			objects.add(packet);
			final String id = text(packet, "ObjectID");
			if (!id.isEmpty()) {
				ids.merge(key(id), 1, Integer::sum);
			}
		}

		for (final Map.Entry<String, Integer> id : ids.entrySet()) {
			if (id.getValue() > 1) {
				breaches.add(new Breach("DUPLICATE|" + id.getKey(), String.format(
					"%d objects would share the ID %s, and the game drops all of them"
					, id.getValue(), id.getKey())));
			}
		}

		for (final ComplexProperty packet : objects) {
			checkComponents(packet, ids, breaches);
			checkTypes(packet, breaches);
		}

		return breaches;
	}

	private static void checkComponents (
		final ComplexProperty packet, final Map<String, Integer> ids, final List<Breach> breaches) {

		final String id = key(text(packet, "ObjectID"));
		final String who = who(packet);
		final Optional<SingleDimensionalArrayProperty> components =
			packet.findProperty("ComponentPackets");

		if (!components.isPresent()) {
			return;
		}

		for (final Object item : components.get().items) {
			if (!(item instanceof ComplexProperty)) {
				continue;
			}

			final ComplexProperty component = (ComplexProperty) item;
			final String type = text(component, "TypeString");
			final Optional<DictionaryProperty> variables = component.findProperty("Variables");
			if (!variables.isPresent()) {
				continue;
			}

			if ("InstanceID".equalsIgnoreCase(type)) {
				final Optional<Property> guid = variables.get().findEntry("Guid");
				if (guid.isPresent() && guid.get() instanceof SimpleProperty
					&& ((SimpleProperty) guid.get()).obj != null) {

					final String value = String.valueOf(((SimpleProperty) guid.get()).obj);
					if (!key(value).equals(id)) {
						breaches.add(new Breach("INSTANCE|" + id, String.format(
							"%s's InstanceID would be %s rather than its own ID, and the game "
								+ "loads it by that and loses it", who, value)));
					}
				}
			}

			final Optional<Property> items = variables.get().findEntry("ItemList");
			final Optional<Property> guids = variables.get().findEntry("SerializedItemList");
			if (items.isPresent() && guids.isPresent()
				&& size(items.get()) >= 0 && size(guids.get()) >= 0
				&& size(items.get()) != size(guids.get())) {

				breaches.add(new Breach("LENGTH|" + id + "|" + type, String.format(
					"%s's %s would hold %d items but %d item IDs, and the two lists pair up by place"
					, who, type, size(items.get()), size(guids.get()))));
			}

			if (guids.isPresent()) {
				for (final String guid : guids(guids.get())) {
					if (!ids.containsKey(key(guid))) {
						breaches.add(new Breach("ITEM|" + id + "|" + type + "|" + key(guid)
							, String.format("%s's %s would carry item %s, which has no object "
								+ "of its own, so the game drops it", who, type, key(guid))));
					}
				}
			}

			for (final String slots : WORN) {
				final Optional<Property> worn = variables.get().findEntry(slots);
				if (!worn.isPresent()) {
					continue;
				}

				for (final String guid : guids(worn.get())) {
					if (!ids.containsKey(key(guid))) {
						breaches.add(new Breach("WORN|" + id + "|" + slots + "|" + key(guid)
							, String.format("%s would %s item %s, which has no object of its own"
								, who, slots.startsWith("Weapon") ? "hold" : "wear", key(guid))));
					}
				}
			}
		}
	}

	// Every simple value one object carries, against the type it is declared
	// as. Each object is walked on its own: parts of the tree are shared, and
	// counting a shared value against whichever object reached it first would
	// move it between objects when the list changes.
	private static void checkTypes (final ComplexProperty packet, final List<Breach> breaches) {
		final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		final Deque<Property> pending = new ArrayDeque<>();
		pending.push(packet);

		int wrong = 0;
		String example = null;
		while (!pending.isEmpty()) {
			final Property property = pending.pop();
			if (property == null || !visited.add(property)) {
				continue;
			}

			if (property instanceof SimpleProperty) {
				final SimpleProperty simple = (SimpleProperty) property;
				final Class<?> declared = simple.type == null ? null : simple.type.type;
				if (simple.obj != null && declared != null && !fits(declared, simple.obj)) {
					wrong++;
					if (example == null) {
						example = String.format("%s held as %s where %s belongs"
							, simple.name == null ? "a value" : simple.name
							, simple.obj.getClass().getSimpleName(), declared.getSimpleName());
					}
				}

				continue;
			}

			if (property instanceof SingleDimensionalArrayProperty) {
				for (final Object item : ((SingleDimensionalArrayProperty) property).items) {
					if (item instanceof Property) {
						pending.push((Property) item);
					}
				}
			}

			if (property instanceof MultiDimensionalArrayProperty) {
				for (final MultiDimensionalArrayProperty.MultiDimensionalArrayItem item
					: ((MultiDimensionalArrayProperty) property).items) {

					if (item != null) {
						pending.push(item.value);
					}
				}
			}

			if (property instanceof CollectionProperty) {
				for (final Property item : ((CollectionProperty) property).items) {
					pending.push(item);
				}
			}

			if (property instanceof DictionaryProperty) {
				for (final Map.Entry<Property, Property> entry : ((DictionaryProperty) property).items) {
					if (entry != null) {
						pending.push(entry.getKey());
						pending.push(entry.getValue());
					}
				}
			}

			if (property instanceof ComplexProperty) {
				for (final Property sub : ((ComplexProperty) property).properties) {
					pending.push(sub);
				}
			}
		}

		if (wrong > 0) {
			breaches.add(new Breach("TYPE|" + key(text(packet, "ObjectID")) + "|" + wrong
				, String.format("%s would hold %d value%s of the wrong kind (%s), and the file "
					+ "would stop reading there", who(packet), wrong, wrong == 1 ? "" : "s", example)));
		}
	}

	private static final Map<Class<?>, Class<?>> BOXED = new HashMap<Class<?>, Class<?>>() {{
		put(boolean.class, Boolean.class);
		put(byte.class, Byte.class);
		put(char.class, Character.class);
		put(short.class, Short.class);
		put(int.class, Integer.class);
		put(long.class, Long.class);
		put(float.class, Float.class);
		put(double.class, Double.class);
	}};

	/** Whether the serializer would write {@code value} as a {@code declared}. */
	static boolean fits (final Class<?> declared, final Object value) {
		final Class<?> boxed = BOXED.containsKey(declared) ? BOXED.get(declared) : declared;
		if (BOXED.containsValue(boxed) || boxed == String.class || boxed == UUID.class
			|| boxed == UnsignedInteger.class) {

			return boxed.isInstance(value);
		}

		if (declared.isEnum()) {
			return declared.isInstance(value) || value instanceof Integer;
		}

		// Anything else is written by its own shape; nothing here to judge.
		return true;
	}

	private static int size (final Property list) {
		if (list instanceof CollectionProperty) {
			return ((CollectionProperty) list).items.size();
		}

		if (list instanceof SingleDimensionalArrayProperty) {
			return ((SingleDimensionalArrayProperty) list).items.size();
		}

		return -1;
	}

	/** The GUIDs a list names, leaving out empty slots. */
	private static List<String> guids (final Property list) {
		final List<Object> items = new ArrayList<>();
		if (list instanceof CollectionProperty) {
			items.addAll(((CollectionProperty) list).items);
		} else if (list instanceof SingleDimensionalArrayProperty) {
			items.addAll(((SingleDimensionalArrayProperty) list).items);
		}

		final List<String> guids = new ArrayList<>();
		for (final Object item : items) {
			if (!(item instanceof SimpleProperty) || ((SimpleProperty) item).obj == null) {
				continue;
			}

			final Object value = ((SimpleProperty) item).obj;
			if (value instanceof UUID
				&& ((UUID) value).getMostSignificantBits() == 0
				&& ((UUID) value).getLeastSignificantBits() == 0) {

				continue;
			}

			final String text = String.valueOf(value);
			if (!text.isEmpty() && !"00000000-0000-0000-0000-000000000000".equals(text)) {
				guids.add(text);
			}
		}

		return guids;
	}

	private static String text (final ComplexProperty complex, final String name) {
		for (final Property property : complex.properties) {
			if (property instanceof SimpleProperty && name.equals(property.name)) {
				final Object value = ((SimpleProperty) property).obj;
				return value == null ? "" : String.valueOf(value);
			}
		}

		return "";
	}

	private static String who (final ComplexProperty packet) {
		final String name = text(packet, "ObjectName");
		return name.isEmpty() ? text(packet, "ObjectID") : name;
	}

	private static String key (final String guid) {
		return guid == null ? "" : guid.toLowerCase();
	}
}
