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

import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A list of GUIDs a component holds: the item IDs beside an ItemList, the
 * slots of the worn equipment and the weapon sets, the stronghold's roster.
 * The save writes a GUID in whatever case the game formatted it in, so every
 * match ignores case; every GUID written is a real {@code System.Guid}, never
 * its text (invariant 2).
 *
 * <p>A list carries a {@code Capacity} of its own, which the game never
 * writes below the count (every packet file of the test saves and the real
 * ones, 2026-11-03); an add that takes the count past it raises it.
 */
public final class GuidList {
	/** The GUID an empty equipment slot holds. */
	public static final String EMPTY = "00000000-0000-0000-0000-000000000000";

	private final String describe;
	private final CollectionProperty list;

	GuidList (final String describe, final CollectionProperty list) {
		this.describe = describe;
		this.list = list;
	}

	/** The list in the tree, for what the model does not cover yet. */
	public CollectionProperty property () {
		return list;
	}

	public int size () {
		return list.items.size();
	}

	/** The GUID at {@code index} as text, or "" for one that holds nothing. */
	public String get (final int index) {
		return textOf(list.items.get(index));
	}

	/** Every GUID, as {@link #get} gives each. */
	public List<String> guids () {
		final List<String> guids = new ArrayList<>();
		for (final Property item : list.items) {
			guids.add(textOf(item));
		}

		return guids;
	}

	/** Where the list holds {@code guid}, in any case; -1 when it does not. */
	public int indexOf (final String guid) {
		for (int i = 0; i < list.items.size(); i++) {
			if (guid != null && guid.equalsIgnoreCase(textOf(list.items.get(i)))) {
				return i;
			}
		}

		return -1;
	}

	public boolean contains (final String guid) {
		return indexOf(guid) >= 0;
	}

	/**
	 * Puts {@code guid} after the ones the list holds.
	 *
	 * @throws WriteRefusedException when it is not a GUID; nothing is changed
	 */
	public void add (final String guid) throws WriteRefusedException {
		list.items.add(guidProperty(guid));
		fitCapacity(list);
	}

	/**
	 * Writes {@code guid} into the slot at {@code index}.
	 *
	 * @throws WriteRefusedException when there is no such slot, or it is not a
	 *         GUID; nothing is changed
	 */
	public void set (final int index, final String guid) throws WriteRefusedException {
		if (index < 0 || index >= list.items.size()) {
			throw new WriteRefusedException(describe + " has no slot " + index + ". Nothing was changed.");
		}

		final Property slot = list.items.get(index);
		if (!(slot instanceof SimpleProperty)) {
			throw new WriteRefusedException(
				describe + "'s slot " + index + " holds no GUID to set. Nothing was changed.");
		}

		final UUID value = uuid(guid);
		((SimpleProperty) slot).value = value;
		slot.obj = value;
	}

	/** Takes out every entry that is {@code guid}, in any case; whether there was one. */
	public boolean remove (final String guid) {
		return list.items.removeIf(item -> guid != null && guid.equalsIgnoreCase(textOf(item)));
	}

	/**
	 * Takes out the entry at {@code index}, and gives it back.
	 *
	 * @throws WriteRefusedException when there is none; nothing is changed
	 */
	public Property remove (final int index) throws WriteRefusedException {
		if (index < 0 || index >= list.items.size()) {
			throw new WriteRefusedException(describe + " has no entry " + index + ". Nothing was changed.");
		}

		return list.items.remove(index);
	}

	/** An entry another list gave up, moved here as it is. */
	void append (final Property entry) {
		list.items.add(entry);
		fitCapacity(list);
	}

	private UUID uuid (final String guid) throws WriteRefusedException {
		try {
			return UUID.fromString(guid);
		} catch (final IllegalArgumentException | NullPointerException e) {
			throw new WriteRefusedException(
				"'" + guid + "' is not a GUID, so " + describe + " was not changed.");
		}
	}

	private SimpleProperty guidProperty (final String guid) throws WriteRefusedException {
		final SimpleProperty item = new SimpleProperty(null, new TypePair(UUID.class, null));
		item.value = uuid(guid);
		item.obj = item.value;
		return item;
	}

	static String textOf (final Property item) {
		if (!(item instanceof SimpleProperty) || ((SimpleProperty) item).value == null) {
			return "";
		}

		final String guid = ((SimpleProperty) item).value.toString();
		return EMPTY.equals(guid) ? "" : guid;
	}

	/** Raises a list's Capacity to its count, when the count has passed it. */
	static void fitCapacity (final CollectionProperty list) {
		for (final Property field : list.properties) {
			if (field != null && "Capacity".equals(field.name) && field instanceof SimpleProperty
				&& ((SimpleProperty) field).value instanceof Integer
				&& (Integer) ((SimpleProperty) field).value < list.items.size()) {

				Property.update(field, list.items.size());
			}
		}
	}

	/** The list a variable holds, if it is one. */
	static Optional<GuidList> of (final String describe, final Optional<Property> variable) {
		return variable
			.filter(list -> list instanceof CollectionProperty)
			.map(list -> new GuidList(describe, (CollectionProperty) list));
	}
}
