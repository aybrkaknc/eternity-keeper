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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.MultiDimensionalArrayProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gives objects new IDs, everywhere a set of packets names them.
 *
 * <p>Objects brought into a save from another -- a companion transplanted from
 * a donor, a character imported from a file -- keep the IDs they had there. If
 * the target already holds an object under one of those, both exist twice over
 * and the game drops both, so the newcomers get fresh ones. An ID is written in
 * two forms, both rewritten: as a UUID (an {@code InstanceID}, the item lists,
 * the equipment slots, a GUIDLink) and as text (an {@code ObjectID}). IDs are
 * matched ignoring case, since the game writes them in whatever case it
 * formatted them in.
 */
public final class GuidRemap {
	private GuidRemap () {}

	private static final Pattern ID = Pattern.compile(
		"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	/**
	 * Which of {@code ids} an area file of the save in {@code saveDirectory}
	 * already holds an object under, in lower case.
	 *
	 * <p>The world state is not the only file with objects in it. What a
	 * companion held in their quick slots when they died is left in the area
	 * they died in, as objects of that area under the IDs it always had; what
	 * the party sold is in the store's area. The same objects brought back
	 * from another save under those IDs are in the save twice, and the game
	 * says so when that area loads ("Packet is in both Mobile and Persistence
	 * object lists!"). So what comes in is checked against the areas as well.
	 *
	 * <p>An object's ID is written as text, so the files are searched for the
	 * text rather than read: a couple of hundred of them, 110 MB in a late
	 * save. Anything else spelt like one of the IDs counts too, which costs an
	 * incoming object a fresh ID it did not strictly need.
	 */
	public static Set<String> heldByAreas (final File saveDirectory, final Collection<String> ids)
		throws IOException {

		final Set<String> wanted = new HashSet<>();
		for (final String id : ids) {
			if (id != null) {
				wanted.add(id.toLowerCase());
			}
		}

		final File[] areas = saveDirectory.listFiles(
			(folder, name) -> name.toLowerCase().endsWith(".lvl"));

		if (wanted.isEmpty() || areas == null || areas.length < 1) {
			return Collections.emptySet();
		}

		final Set<String> held = ConcurrentHashMap.newKeySet();
		try {
			Arrays.stream(areas).parallel().forEach(area -> {
				try {
					final Matcher found = ID.matcher(new String(
						Files.readAllBytes(area.toPath()), StandardCharsets.ISO_8859_1));

					while (found.find()) {
						final String id = found.group().toLowerCase();
						if (wanted.contains(id)) {
							held.add(id);
						}
					}
				} catch (final IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (final UncheckedIOException e) {
			throw e.getCause();
		}

		return held;
	}

	/**
	 * Rewrites every occurrence of the IDs {@code remap} names, in the given
	 * packets only.
	 *
	 * @return how many values were rewritten
	 */
	public static int rewrite (final Collection<Property> packets, final Map<String, UUID> remap) {
		final Map<String, UUID> byKey = new HashMap<>();
		remap.forEach((id, fresh) -> byKey.put(id.toLowerCase(), fresh));

		// Flat-copied reference stubs share child lists, so an identity set
		// guards against rewriting (or counting) one twice.
		final Set<Property> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		int rewrites = 0;
		for (final Property packet : packets) {
			rewrites += rewrite(packet, byKey, visited);
		}

		return rewrites;
	}

	private static int rewrite (
		final Property property, final Map<String, UUID> remap, final Set<Property> visited) {

		if (property == null || !visited.add(property)) {
			return 0;
		}

		if (property instanceof SimpleProperty) {
			final Object value = ((SimpleProperty) property).obj;
			if (value instanceof UUID && remap.containsKey(value.toString().toLowerCase())) {
				Property.update(property, remap.get(value.toString().toLowerCase()));
				return 1;
			}

			if (value instanceof String && remap.containsKey(((String) value).toLowerCase())) {
				Property.update(property, remap.get(((String) value).toLowerCase()).toString());
				return 1;
			}

			return 0;
		}

		int rewrites = 0;
		if (property instanceof SingleDimensionalArrayProperty) {
			for (final Object item : ((SingleDimensionalArrayProperty) property).items) {
				if (item instanceof Property) {
					rewrites += rewrite((Property) item, remap, visited);
				}
			}
		}

		if (property instanceof MultiDimensionalArrayProperty) {
			for (final MultiDimensionalArrayProperty.MultiDimensionalArrayItem item
				: ((MultiDimensionalArrayProperty) property).items) {

				if (item != null) {
					rewrites += rewrite(item.value, remap, visited);
				}
			}
		}

		if (property instanceof CollectionProperty) {
			for (final Property item : ((CollectionProperty) property).items) {
				rewrites += rewrite(item, remap, visited);
			}
		}

		if (property instanceof DictionaryProperty) {
			for (final Map.Entry<Property, Property> entry : ((DictionaryProperty) property).items) {
				if (entry != null) {
					rewrites += rewrite(entry.getKey(), remap, visited);
					rewrites += rewrite(entry.getValue(), remap, visited);
				}
			}
		}

		if (property instanceof ComplexProperty) {
			for (final Property sub : ((ComplexProperty) property).properties) {
				rewrites += rewrite(sub, remap, visited);
			}
		}

		return rewrites;
	}
}
