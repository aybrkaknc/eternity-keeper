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
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a container component holds -- a pack, the stash, the quick slots, a
 * store's stock: its {@code ItemList} of entries and its
 * {@code SerializedItemList} of the items' GUIDs, which pair up by place
 * (invariant 6). Everything here changes the two together.
 */
public final class ItemContainer {
	private final Component component;
	private final CollectionProperty items;
	private final GuidList guids;

	private ItemContainer (final Component component, final CollectionProperty items, final GuidList guids) {
		this.component = component;
		this.items = items;
		this.guids = guids;
	}

	/** The container a component is, when it holds both lists. */
	static Optional<ItemContainer> of (final Component component) {
		final Optional<Property> items = component.variable("ItemList")
			.filter(list -> list instanceof CollectionProperty);
		final Optional<GuidList> guids = component.guidList("SerializedItemList");

		if (!items.isPresent() || !guids.isPresent()) {
			return Optional.empty();
		}

		return Optional.of(new ItemContainer(component, (CollectionProperty) items.get(), guids.get()));
	}

	public Component component () {
		return component;
	}

	/** The object whose container this is. */
	public SaveObject owner () {
		return component.owner();
	}

	public int size () {
		return items.items.size();
	}

	/** How many entries the container takes, when it says. */
	public Optional<Integer> maxItems () {
		return component.get("MaxItems")
			.filter(value -> value instanceof Integer)
			.map(value -> (Integer) value);
	}

	/** Where the container holds {@code guid}, in any case; -1 when it does not. */
	public int indexOf (final String guid) {
		return guids.indexOf(guid);
	}

	/** The GUID of the item at {@code index}. */
	public String guid (final int index) {
		return guids.get(index);
	}

	public Entry entry (final int index) {
		return new Entry(items.items.get(index));
	}

	/** Whether the two lists are the same length, as the game writes them. */
	public boolean paired () {
		return items.items.size() == guids.size();
	}

	/**
	 * Puts an item in, after the ones the container holds.
	 *
	 * @throws WriteRefusedException when {@code guid} is not a GUID; nothing
	 *         is changed
	 */
	public void add (final Entry entry, final String guid) throws WriteRefusedException {
		guids.add(guid);
		items.items.add(entry.property());
		GuidList.fitCapacity(items);
	}

	/**
	 * Takes the item at {@code index} out of both lists. Its object stays:
	 * {@link SaveModel#removeUnlisted} takes that when nothing names it.
	 *
	 * @throws WriteRefusedException when there is no such item; nothing is changed
	 */
	public Entry remove (final int index) throws WriteRefusedException {
		check(index);
		guids.remove(index);
		return new Entry(items.items.remove(index));
	}

	/**
	 * Hands the item at {@code index} to {@code other}, entry and GUID as they
	 * are. When {@code other} is another object's, the item's own object
	 * names that one as its Parent, as the game's does.
	 *
	 * @return the entry, now {@code other}'s last
	 * @throws WriteRefusedException when there is no such item, or it has no
	 *         object of its own in {@code save} to re-parent; nothing is changed
	 */
	public Entry moveTo (final ItemContainer other, final int index, final SaveModel save)
		throws WriteRefusedException {

		check(index);
		final boolean handedOver = !sameOwner(other);
		final Optional<SaveObject> item = save.object(guid(index));
		if (handedOver && !item.isPresent()) {
			throw new WriteRefusedException("Item " + guid(index)
				+ " has no object of its own to hand to " + other.owner().describe() + ". Nothing was changed.");
		}

		if (handedOver) {
			item.get().set("Parent", other.owner().name());
		}

		final Property guid = guids.remove(index);
		final Property entry = items.items.remove(index);
		other.guids.append(guid);
		other.items.items.add(entry);
		GuidList.fitCapacity(other.items);
		return new Entry(entry);
	}

	private boolean sameOwner (final ItemContainer other) {
		final String mine = owner().id();
		return mine != null && mine.equalsIgnoreCase(other.owner().id());
	}

	/**
	 * The lowest tile no other entry holds, or {@code preferred} when it is
	 * free: the game keeps one item per tile and fills gaps from the lowest
	 * (BaseInventory.FirstFreeSlot).
	 *
	 * @param ignoreIndex an entry whose tile counts as free, or -1
	 */
	public int freeSlot (final int preferred, final int ignoreIndex) {
		final Set<Integer> taken = new HashSet<>();
		for (int i = 0; i < items.items.size(); i++) {
			if (i != ignoreIndex) {
				new Entry(items.items.get(i)).slot().ifPresent(taken::add);
			}
		}

		if (preferred >= 0 && !taken.contains(preferred)) {
			return preferred;
		}

		int slot = 0;
		while (taken.contains(slot)) {
			slot++;
		}

		return slot;
	}

	/**
	 * Gives every entry the tile of its place, as a store's stock is laid out.
	 *
	 * @throws WriteRefusedException when an entry has no tile; the entries
	 *         before it are renumbered
	 */
	public void renumberSlots () throws WriteRefusedException {
		for (int slot = 0; slot < items.items.size(); slot++) {
			new Entry(items.items.get(slot)).setSlot(slot);
		}
	}

	private void check (final int index) throws WriteRefusedException {
		if (index < 0 || index >= items.items.size() || index >= guids.size()) {
			throw new WriteRefusedException(
				owner().describe() + "'s " + component.type() + " has no item " + index + ". Nothing was changed.");
		}
	}

	/**
	 * A new entry for an item of {@code baseItem}, its fields and their types
	 * copied from an entry the save already holds, so it is written exactly
	 * as the game would.
	 *
	 * @throws WriteRefusedException when the save holds no entry to copy
	 */
	public static Entry newEntry (
		final SaveModel save, final String baseItem, final int uiSlot, final int stackSize)
		throws WriteRefusedException {

		final ComplexProperty template = anyEntry(save).orElseThrow(() -> new WriteRefusedException(
			"This save has no InventoryItem to model a new entry on. Nothing was changed."));

		final ComplexProperty entry = new ComplexProperty(null, template.type);
		for (final Property field : template.properties) {
			if (!(field instanceof SimpleProperty) || field.name == null) {
				continue;
			}

			final Object value;
			switch (field.name) {
				case "BaseItem":        value = baseItem; break;
				case "uiSlot":          value = uiSlot; break;
				case "StackSize":       value = Math.max(1, stackSize); break;
				case "stackSize":       value = Math.max(1, stackSize); break;
				case "Original":        value = false; break;
				case "AreaLootSource":  value = false; break;
				default:                value = ((SimpleProperty) field).value; break;
			}

			final SimpleProperty copy = new SimpleProperty(field.name, field.type);
			copy.value = value;
			copy.obj = value;
			entry.properties.add(copy);
		}

		return new Entry(entry);
	}

	private static Optional<ComplexProperty> anyEntry (final SaveModel save) {
		for (final SaveObject object : save.objects()) {
			for (final Component component : object.components()) {
				final Optional<Property> list = component.variable("ItemList")
					.filter(found -> found instanceof CollectionProperty);

				if (!list.isPresent()) {
					continue;
				}

				for (final Property entry : ((CollectionProperty) list.get()).items) {
					if (entry instanceof ComplexProperty && !((ComplexProperty) entry).properties.isEmpty()) {
						return Optional.of((ComplexProperty) entry);
					}
				}
			}
		}

		return Optional.empty();
	}

	/**
	 * One entry of an ItemList (an InventoryItem): which item, how many, and
	 * the tile it is on.
	 *
	 * <p>InventoryItem is the one type in the format with a field and a
	 * property of one name in two cases, {@code stackSize} and
	 * {@code StackSize}, both written; a match ignoring case would find only
	 * one of them (invariant 14), so these match exactly, and the two are
	 * kept in step as the game keeps them.
	 */
	public static final class Entry {
		private final Property entry;

		Entry (final Property entry) {
			this.entry = entry;
		}

		/** The entry in the tree, for what the model does not cover yet. */
		public Property property () {
			return entry;
		}

		public Optional<Integer> slot () {
			return field("uiSlot")
				.map(found -> ((SimpleProperty) found).value)
				.filter(value -> value instanceof Integer)
				.map(value -> (Integer) value);
		}

		/** How many the stack holds; 1 for an entry that does not say. */
		public int stackSize () {
			return field("stackSize")
				.map(found -> ((SimpleProperty) found).value)
				.filter(value -> value instanceof Integer)
				.map(value -> (Integer) value)
				.orElse(1);
		}

		public Optional<String> baseItem () {
			return field("BaseItem")
				.map(found -> ((SimpleProperty) found).value)
				.map(String::valueOf);
		}

		/** @throws WriteRefusedException when the entry has no tile; nothing is changed */
		public void setSlot (final int slot) throws WriteRefusedException {
			write("uiSlot", slot);
		}

		/** @throws WriteRefusedException when the entry has not both fields; nothing is changed */
		public void setStackSize (final int stackSize) throws WriteRefusedException {
			final Optional<Property> lower = field("stackSize");
			final Optional<Property> upper = field("StackSize");
			if (!lower.isPresent() || !upper.isPresent()) {
				throw new WriteRefusedException(
					"An inventory entry has no stackSize and StackSize to set. Nothing was changed.");
			}

			TypedValue.write((SimpleProperty) lower.get(), stackSize, "An inventory entry's stackSize");
			TypedValue.write((SimpleProperty) upper.get(), stackSize, "An inventory entry's StackSize");
		}

		/** @throws WriteRefusedException when the entry has no BaseItem; nothing is changed */
		public void setBaseItem (final String baseItem) throws WriteRefusedException {
			write("BaseItem", baseItem);
		}

		private void write (final String name, final Object value) throws WriteRefusedException {
			final Optional<Property> found = field(name);
			if (!found.isPresent()) {
				throw new WriteRefusedException("An inventory entry has no " + name + " to set. Nothing was changed.");
			}

			TypedValue.write((SimpleProperty) found.get(), value, "An inventory entry's " + name);
		}

		private Optional<Property> field (final String name) {
			if (!(entry instanceof ComplexProperty)) {
				return Optional.empty();
			}

			final List<Property> fields = ((ComplexProperty) entry).properties;
			for (final Property field : fields) {
				if (field instanceof SimpleProperty && name.equals(field.name)) {
					return Optional.of(field);
				}
			}

			return Optional.empty();
		}
	}
}
