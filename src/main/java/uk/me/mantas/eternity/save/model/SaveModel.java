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
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
 *
 * <p>It is the file's own list, not a copy of it: what it adds and removes is
 * what the file holds, and every lookup sees the file as it is now.
 */
public final class SaveModel {
	private final Supplier<List<Property>> packets;

	private SaveModel (final Supplier<List<Property>> packets) {
		this.packets = packets;
	}

	/** The objects {@code packets} holds, whenever they are asked for. */
	public static SaveModel of (final DeserializedPackets packets) {
		return new SaveModel(packets::getPackets);
	}

	/** The objects of {@code packets}, a list the model changes in place. */
	public static SaveModel of (final List<Property> packets) {
		return new SaveModel(() -> packets);
	}

	/** Every game object, in the file's order. */
	public List<SaveObject> objects () {
		final List<SaveObject> objects = new ArrayList<>();
		for (final Property packet : packets.get()) {
			SaveObject.of(packet).ifPresent(objects::add);
		}

		return Collections.unmodifiableList(objects);
	}

	/**
	 * Puts an object in the file, after the ones it holds.
	 *
	 * @throws WriteRefusedException when the file already holds an object with
	 *         its ID -- the game would drop both; nothing is changed
	 */
	public void add (final SaveObject object) throws WriteRefusedException {
		if (object(object.id()).isPresent()) {
			throw new WriteRefusedException("The save already holds an object with the ID "
				+ object.id() + ", so " + object.describe() + " cannot be added. Nothing was changed.");
		}

		packets.get().add(object.property());
	}

	/**
	 * Takes an object out of the file.
	 *
	 * @throws WriteRefusedException when it is not in the file; nothing is changed
	 */
	public void remove (final SaveObject object) throws WriteRefusedException {
		final List<Property> list = packets.get();
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i) == object.property()) {
				list.remove(i);
				return;
			}
		}

		throw new WriteRefusedException(
			"The save does not hold " + object.describe() + " to remove. Nothing was changed.");
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

	/**
	 * One of the game's global variables (GlobalVariables' {@code m_data} on
	 * InGameGlobal). One that is not in the save reads as 0 in the game.
	 */
	public Optional<Object> globalVariable (final String name) {
		return globalVariableEntry(name)
			.filter(entry -> entry instanceof SimpleProperty)
			.map(entry -> ((SimpleProperty) entry).value);
	}

	/**
	 * Writes a global variable the save holds, as the type it holds.
	 *
	 * @throws WriteRefusedException when the save does not hold it, or the
	 *         value cannot be its type; nothing is changed
	 */
	public void setGlobalVariable (final String name, final Object value) throws WriteRefusedException {
		final Optional<Property> entry = globalVariableEntry(name);
		if (!entry.isPresent() || !(entry.get() instanceof SimpleProperty)) {
			throw new WriteRefusedException(
				"The save has no global variable '" + name + "' to set. Nothing was changed.");
		}

		TypedValue.write((SimpleProperty) entry.get(), value, "The global variable " + name);
	}

	private Optional<Property> globalVariableEntry (final String name) {
		return inGameGlobal()
			.flatMap(global -> global.component("GlobalVariables"))
			.flatMap(globals -> globals.variable("m_data"))
			.filter(data -> data instanceof DictionaryProperty)
			.flatMap(data -> ((DictionaryProperty) data).findEntry(name));
	}

	/** The object with this ObjectID, in whatever case either is written. */
	public Optional<SaveObject> object (final String id) {
		return first(object -> id != null && id.equalsIgnoreCase(object.id()));
	}

	private Optional<SaveObject> first (final Predicate<SaveObject> wanted) {
		for (final Property packet : packets.get()) {
			final Optional<SaveObject> object = SaveObject.of(packet);
			if (object.isPresent() && wanted.test(object.get())) {
				return object;
			}
		}

		return Optional.empty();
	}

	private static boolean named (final SaveObject object, final String prefix) {
		return object.name() != null && object.name().startsWith(prefix);
	}
}
