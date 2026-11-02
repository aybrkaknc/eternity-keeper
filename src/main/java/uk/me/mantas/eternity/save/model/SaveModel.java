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

import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.properties.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The objects of one save file, read and written through the property tree
 * the serializer writes back (invariant 26): never through the mirror objects
 * the deserializer also builds, which an edit does not change.
 *
 * <p>It knows how the game names what an editor looks for -- the main
 * character, the two global objects -- and how an object holds its components
 * and a component its variables, so that a writer says what it wants instead
 * of walking the tree to it. The names are the ones every save measured has
 * (docs/save-format.md).
 */
public final class SaveModel {
	private final List<SaveObject> objects;

	private SaveModel (final List<SaveObject> objects) {
		this.objects = Collections.unmodifiableList(objects);
	}

	/** The objects {@code packets} holds now; one added or removed afterwards is not seen. */
	public static SaveModel of (final DeserializedPackets packets) {
		return of(packets.getPackets());
	}

	public static SaveModel of (final List<Property> packets) {
		final List<SaveObject> objects = new ArrayList<>();
		for (final Property packet : packets) {
			SaveObject.of(packet).ifPresent(objects::add);
		}

		return new SaveModel(objects);
	}

	/** Every game object, in the file's order. */
	public List<SaveObject> objects () {
		return objects;
	}

	/** The main character: the one {@code Player_} object a save has. */
	public Optional<SaveObject> player () {
		return first(SaveObject::isPlayer);
	}

	/** {@code InGameGlobal(Clone)}: the global variables, the quests, the stronghold. */
	public Optional<SaveObject> inGameGlobal () {
		return first(object -> named(object, "InGameGlobal("));
	}

	/** {@code Global(Clone)}: the world's own state, among it the game's settings. */
	public Optional<SaveObject> worldGlobal () {
		return first(object -> named(object, "Global(Clone)") || "Global".equals(object.name()));
	}

	/** The object with this ObjectID, in whatever case either is written. */
	public Optional<SaveObject> object (final String id) {
		return first(object -> id != null && id.equalsIgnoreCase(object.id()));
	}

	private Optional<SaveObject> first (final Predicate<SaveObject> wanted) {
		return objects.stream().filter(wanted).findFirst();
	}

	private static boolean named (final SaveObject object, final String prefix) {
		return object.name() != null && object.name().startsWith(prefix);
	}
}
