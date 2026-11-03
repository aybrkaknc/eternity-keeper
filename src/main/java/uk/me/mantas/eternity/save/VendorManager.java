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

import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.ShortReadException;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Takes items out of vendors' stock.
 *
 * <p>An item in a store lives in three places, as one in a pack does: the
 * store's {@code ItemList} entry, the parallel {@code SerializedItemList} GUID
 * at the same index, and the item's own packet, in the same area file and
 * parented to the store. All three go. The remaining entries are numbered
 * 0..n-1 in list order again, which is how {@code BaseInventory.Sort} and
 * {@code CompressSlots} leave a store and how every store in the real saves
 * reads.
 *
 * <p>An entry is named by its place in the lists, with the GUID that place
 * should hold as the check that nothing moved. The GUID alone will not do: one
 * with no packet can repeat, and in a real save nine are shared by eighteen of
 * the stronghold merchant's entries -- different items under one GUID.
 *
 * <p>Everything is planned before anything is written: a request naming one
 * entry that is no longer where it was is refused whole, with no file touched.
 * Files are then replaced one at a time, each atomically.
 */
public class VendorManager {
	private static final Logger logger = Logger.getLogger(VendorManager.class);

	private static final String STORE = "Store";

	private final File saveDirectory;
	private String problem = null;

	public VendorManager (final File saveDirectory) {
		this.saveDirectory = saveDirectory;
	}

	/** One entry of a store's stock: its place, and the GUID it should hold there. */
	public static final class Entry {
		public final int index;
		public final String guid;

		public Entry (final int index, final String guid) {
			this.index = index;
			this.guid = guid;
		}
	}

	/** Entries to take out of one vendor's stock. */
	public static final class Removal {
		/** The packet file the vendor is in, as {@link VendorStock.Vendor#file} names it. */
		public final String file;
		/** The store object's ObjectID. */
		public final String vendor;
		public final List<Entry> items;

		public Removal (final String file, final String vendor, final List<Entry> items) {
			this.file = file;
			this.vendor = vendor;
			this.items = items == null ? Collections.emptyList() : items;
		}
	}

	/** Why the last {@link #apply} refused, in words for the user. */
	public Optional<String> problem () {
		return Optional.ofNullable(problem);
	}

	public boolean apply (final List<Removal> removals) throws IOException {
		problem = null;
		if (removals == null || removals.isEmpty()) {
			return true;
		}

		final Map<String, List<Removal>> byFile = new LinkedHashMap<>();
		for (final Removal removal : removals) {
			if (!VendorStock.isPacketFile(removal.file)) {
				return refuse("'" + removal.file + "' is not one of this save's area files.");
			}

			byFile.computeIfAbsent(removal.file, file -> new ArrayList<>()).add(removal);
		}

		final Map<File, DeserializedPackets> planned = new LinkedHashMap<>();
		for (final Map.Entry<String, List<Removal>> entry : byFile.entrySet()) {
			final File file = new File(saveDirectory, entry.getKey());
			if (!file.isFile()) {
				return refuse("This save has no " + entry.getKey() + ".");
			}

			final Optional<DeserializedPackets> read;
			try {
				read = new PacketDeserializer(file).deserialize();
			} catch (final ShortReadException e) {
				return refuse(e.getMessage());
			}

			if (!read.isPresent()) {
				return refuse(entry.getKey() + " could not be read, so nothing was changed.");
			}

			final List<Property> packets = new ArrayList<>(read.get().getPackets());
			final Set<String> taken = new HashSet<>();
			for (final Removal removal : entry.getValue()) {
				if (!take(packets, entry.getKey(), removal, taken)) {
					return false;
				}
			}

			final Set<String> stillListed = listedGuids(packets);
			final int before = packets.size();
			packets.removeIf(p -> p.obj instanceof ObjectPersistencePacket
				&& EKUtils.unwrapPacket(p).ObjectID != null
				&& taken.contains(EKUtils.unwrapPacket(p).ObjectID.toLowerCase())
				&& !stillListed.contains(EKUtils.unwrapPacket(p).ObjectID.toLowerCase()));

			logger.info("%s: %d stock entries out, %d item packets with them.%n"
				, entry.getKey(), taken.size(), before - packets.size());

			read.get().setPackets(packets);

			planned.put(file, read.get());
		}

		// Every file checked before any is written, so a refusal leaves them
		// all as they were rather than half the edit in place.
		for (final DeserializedPackets packets : planned.values()) {
			try {
				packets.checkWritable();
			} catch (final WriteRefusedException e) {
				return refuse(e.getMessage());
			}
		}

		for (final Map.Entry<File, DeserializedPackets> entry : planned.entrySet()) {
			entry.getValue().replace(entry.getKey());
		}

		return true;
	}

	/** Takes one vendor's items out of its two lists, remembering their GUIDs. */
	private boolean take (
		final List<Property> packets, final String file, final Removal removal
		, final Set<String> taken) {

		final Optional<Property> vendor = removal.vendor == null
			? Optional.empty()
			: EKUtils.findPacketById(packets, removal.vendor);

		if (!vendor.isPresent()) {
			return refuse("There is no vendor " + removal.vendor + " in " + file
				+ ". The list was out of date; nothing was changed.");
		}

		final String name = VendorStock.vendorName(EKUtils.unwrapPacket(vendor.get()).ObjectName);
		final Optional<CollectionProperty> itemList = storeList(vendor.get(), "ItemList");
		final Optional<CollectionProperty> guids = storeList(vendor.get(), "SerializedItemList");

		if (!itemList.isPresent() || !guids.isPresent()) {
			return refuse(name + " in " + file + " has no stock list.");
		}

		if (itemList.get().items.size() != guids.get().items.size()) {
			return refuse(name + "'s stock lists disagree in length already, so it was left alone.");
		}

		// Highest place first, so the places still to come do not move.
		final TreeMap<Integer, String> places = new TreeMap<>(Collections.reverseOrder());
		for (final Entry entry : removal.items) {
			if (entry == null || !holds(guids.get(), entry)) {
				return refuse("Something picked from " + name + "'s stock is no longer there. "
					+ "The list was out of date; nothing was changed.");
			}

			places.put(entry.index, entry.guid);
		}

		for (final Map.Entry<Integer, String> place : places.entrySet()) {
			itemList.get().items.remove((int) place.getKey());
			guids.get().items.remove((int) place.getKey());
			taken.add(place.getValue().toLowerCase());
		}

		for (int slot = 0; slot < itemList.get().items.size(); slot++) {
			if (!setSlot(itemList.get().items.get(slot), slot)) {
				return refuse(name + "'s stock has an entry in an unexpected shape.");
			}
		}

		return true;
	}

	/**
	 * Every GUID any list in the file still holds. An item's packet only goes
	 * when nothing names it any more; otherwise that list would be left
	 * pointing at nothing.
	 */
	private static Set<String> listedGuids (final List<Property> packets) {
		final Set<String> listed = new HashSet<>();
		for (final Property packet : packets) {
			if (!(packet instanceof ComplexProperty)) {
				continue;
			}

			final Optional<SingleDimensionalArrayProperty> components =
				((ComplexProperty) packet).findProperty("ComponentPackets");

			if (!components.isPresent()) {
				continue;
			}

			for (final Object component : components.get().items) {
				if (!(component instanceof ComplexProperty)) {
					continue;
				}

				final Optional<DictionaryProperty> variables =
					((ComplexProperty) component).findProperty("Variables");

				if (!variables.isPresent()) {
					continue;
				}

				for (final Map.Entry<Property, Property> variable : variables.get().items) {
					if (variable == null || !(variable.getValue() instanceof CollectionProperty)) {
						continue;
					}

					for (final Property item : ((CollectionProperty) variable.getValue()).items) {
						if (item instanceof SimpleProperty && ((SimpleProperty) item).value != null) {
							listed.add(((SimpleProperty) item).value.toString().toLowerCase());
						}
					}
				}
			}
		}

		return listed;
	}

	private static Optional<CollectionProperty> storeList (final Property vendor, final String name) {
		return ((ComplexProperty) vendor)
			.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
			.flatMap(components -> EKUtils.findSubComponent(components, STORE))
			.<DictionaryProperty>flatMap(store -> store.findProperty("Variables"))
			.flatMap(variables -> variables.<Property>findEntry(name))
			.filter(list -> list instanceof CollectionProperty)
			.map(CollectionProperty.class::cast);
	}

	/** Whether the list still holds the entry's GUID at the entry's place. */
	private static boolean holds (final CollectionProperty guids, final Entry entry) {
		if (entry.guid == null || entry.index < 0 || entry.index >= guids.items.size()) {
			return false;
		}

		final Property item = guids.items.get(entry.index);
		return item instanceof SimpleProperty
			&& ((SimpleProperty) item).value != null
			&& entry.guid.equalsIgnoreCase(((SimpleProperty) item).value.toString());
	}

	private static boolean setSlot (final Property entry, final int slot) {
		if (!(entry instanceof ComplexProperty)) {
			return false;
		}

		for (final Property field : ((ComplexProperty) entry).properties) {
			if (field != null && "uiSlot".equals(field.name)) {
				return Property.update(field, slot);
			}
		}

		return false;
	}

	private boolean refuse (final String reason) {
		problem = reason;
		logger.error("Refused: %s%n", reason);
		return false;
	}
}
