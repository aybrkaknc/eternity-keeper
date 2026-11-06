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
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.InventoryItem;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.Property;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What every vendor in a save holds.
 *
 * <p>A store is any object with a {@code Store} component, and its stock is
 * that component's {@code ItemList} with the parallel
 * {@code SerializedItemList} of GUIDs -- the same shape as a character's pack.
 * Each GUID is also the ObjectID of the item's own packet, parented to the
 * store. What makes vendors their own problem is where they are: the world
 * state holds none in a mid-game save (Heodan's, in the prologue, is the
 * exception). Every other one lives in the area file of the level it stands
 * in, which the rest of the editor never opens.
 *
 * <p>{@code InventoryItem.Original} is set only for a store's initial stock
 * ({@code BaseInventory.Restored} adds it with {@code original: true}).
 * Anything the player sold there is not original, and neither is what
 * {@code Store.RegenerateItems} restocks -- which it also destroys again, every
 * copy of each prefab in its table, when it next restocks. So "not original"
 * is what the player sold plus restock that would be re-rolled anyway, and
 * original stock includes the uniques nobody has bought yet.
 */
public class VendorStock {
	private static final Logger logger = Logger.getLogger(VendorStock.class);

	/** The world state; every other packet file in a save is an area. */
	public static final String WORLD = "MobileObjects.save";
	public static final String AREA_SUFFIX = ".lvl";

	private static final String STORE = "Store";

	// "Store" the way the serializer writes a string value: a 7-bit length
	// and the bytes (BinaryWriter.writeString). A Store component's
	// TypeString is written exactly so, so a file without this sequence
	// holds no store and need not be read. Measured on a mid-game save, it
	// picks out exactly the 40 of 190 packet files that have one.
	private static final byte[] STORE_TYPE_STRING = {5, 'S', 't', 'o', 'r', 'e'};

	private static final Pattern PLAIN_NAME = Pattern.compile("[^/\\\\:]+");

	/**
	 * One entry of a store's stock. An entry is its place in the store's two
	 * lists: its GUID usually names the item's packet, but a GUID with no
	 * packet can repeat -- in a real save nine of them are shared by eighteen
	 * of the stronghold merchant's entries, each a different item.
	 */
	public static final class Item {
		/** The entry's place in ItemList and SerializedItemList. */
		public final int index;
		/** The SerializedItemList GUID, which is also the ObjectID of the item's packet. */
		public final String guid;
		/** Prefab file name without its extension, or null when nothing names it. */
		public final String prefab;
		public final int stack;
		/** Part of the store's initial stock, rather than sold there or restocked. */
		public final boolean original;
		/** Whether the item's own packet is in the same file. */
		public final boolean hasPacket;

		Item (final int index, final String guid, final String prefab, final int stack
			, final boolean original, final boolean hasPacket) {

			this.index = index;
			this.guid = guid;
			this.prefab = prefab;
			this.stack = stack;
			this.original = original;
			this.hasPacket = hasPacket;
		}
	}

	/** One store and what it holds, in its own order. */
	public static final class Vendor {
		/** The packet file it is in: an area file, or the world state. */
		public final String file;
		public final String id;
		public final String objectName;
		public final String level;
		public final String name;
		public final String area;
		/** Whether the player has ever opened it: {@code Store.m_firstTime} is cleared then. */
		public final boolean opened;
		/** What it charges, as a multiple of an item's value. */
		public final float sellMultiplier;
		/** What it pays. */
		public final float buyMultiplier;
		public final List<Item> items;

		Vendor (final String file, final String id, final String objectName, final String level
			, final boolean opened, final float sellMultiplier, final float buyMultiplier
			, final List<Item> items) {

			this.file = file;
			this.id = id;
			this.objectName = objectName;
			this.level = level;
			this.name = vendorName(objectName);
			this.area = areaName(level);
			this.opened = opened;
			this.sellMultiplier = sellMultiplier;
			this.buyMultiplier = buyMultiplier;
			this.items = Collections.unmodifiableList(items);
		}

		@Override
		public String toString () {
			return objectName + " in " + file;
		}
	}

	/** The vendors, and the files that name a store but could not be read. */
	public static final class Result {
		public final List<Vendor> vendors;
		public final List<String> unreadable;

		Result (final List<Vendor> vendors, final List<String> unreadable) {
			this.vendors = Collections.unmodifiableList(vendors);
			this.unreadable = Collections.unmodifiableList(unreadable);
		}
	}

	private VendorStock () {
	}

	/**
	 * Every vendor in an unpacked save: the world state first, then the areas
	 * by name. The files are independent, so they are read side by side.
	 */
	public static Result read (final File saveDirectory) {
		final List<File> files = packetFiles(saveDirectory);
		final List<Optional<List<Vendor>>> read = files.parallelStream()
			.map(file -> mayHoldStore(file)
				? readWhole(file).map(packets -> vendorsIn(file.getName(), packets.getPackets()))
				: Optional.of(Collections.<Vendor>emptyList()))
			.collect(Collectors.toList());

		final List<Vendor> vendors = new ArrayList<>();
		final List<String> unreadable = new ArrayList<>();
		for (int i = 0; i < files.size(); i++) {
			if (read.get(i).isPresent()) {
				vendors.addAll(read.get(i).get());
			} else {
				unreadable.add(files.get(i).getName());
			}
		}

		return new Result(vendors, unreadable);
	}

	/** One vendor, read from the one file it is in. */
	public static Optional<Vendor> read (
		final File saveDirectory, final String file, final String id) {

		if (!isPacketFile(file) || id == null) {
			return Optional.empty();
		}

		final File packetFile = new File(saveDirectory, file);
		if (!packetFile.isFile()) {
			return Optional.empty();
		}

		return readWhole(packetFile)
			.flatMap(packets -> vendorsIn(file, packets.getPackets()).stream()
				.filter(vendor -> vendor.id.equalsIgnoreCase(id))
				.findFirst());
	}

	/**
	 * The packets of one file, or nothing when any of them could not be read.
	 * {@code PacketDeserializer} refuses a read that comes up short itself now,
	 * for every writer in the editor; this only turns that, and any other
	 * failure, into "nothing", so the list can name a file it cannot read
	 * instead of stopping at it.
	 */
	public static Optional<DeserializedPackets> readWhole (final File file) {
		try {
			return new PacketDeserializer(file).deserialize();
		} catch (final IOException | RuntimeException e) {
			logger.error(e, "Unable to read %s: %s%n", file.getName(), e.toString());
			return Optional.empty();
		}
	}

	/** Whether a file can hold a store at all, from its bytes alone. */
	public static boolean mayHoldStore (final File file) {
		final byte[] needle = STORE_TYPE_STRING;
		try (final InputStream in = new BufferedInputStream(new FileInputStream(file), 1 << 16)) {
			// A running match over the stream. The needle's first byte never
			// recurs in it, so a mismatch can restart from the current byte.
			int matched = 0;
			int b;
			while ((b = in.read()) >= 0) {
				if (b == (needle[matched] & 0xff)) {
					if (++matched == needle.length) {
						return true;
					}
				} else {
					matched = b == (needle[0] & 0xff) ? 1 : 0;
				}
			}

			return false;
		} catch (final IOException e) {
			logger.error("Unable to scan %s: %s%n", file.getName(), e.getMessage());
			return false;
		}
	}

	/**
	 * Whether a name, as a page sends it, is one of a save's packet files: the
	 * world state or an area file, named plainly, never a path.
	 */
	public static boolean isPacketFile (final String name) {
		if (name == null || !PLAIN_NAME.matcher(name).matches()
			|| name.equals(".") || name.equals("..")) {

			return false;
		}

		return name.equals(WORLD)
			|| (name.endsWith(AREA_SUFFIX) && name.length() > AREA_SUFFIX.length());
	}

	/** The packet files of an unpacked save, world state first. */
	public static List<File> packetFiles (final File saveDirectory) {
		final File[] areas = saveDirectory.listFiles((dir, name) ->
			name.endsWith(AREA_SUFFIX) && new File(dir, name).isFile());

		final List<File> files = new ArrayList<>();
		final File world = new File(saveDirectory, WORLD);
		if (world.isFile()) {
			files.add(world);
		}

		if (areas != null) {
			Arrays.sort(areas);
			files.addAll(Arrays.asList(areas));
		}

		return files;
	}

	/** The stores among one file's packets, in the file's order. */
	public static List<Vendor> vendorsIn (final String file, final List<Property> packets) {
		final Map<String, ObjectPersistencePacket> byId = new HashMap<>();
		final List<ObjectPersistencePacket> stores = new ArrayList<>();

		for (final Property property : packets) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = EKUtils.unwrapPacket(property);
			if (packet.ObjectID != null) {
				byId.put(packet.ObjectID.toLowerCase(), packet);
			}

			if (packet.ComponentPackets != null
				&& EKUtils.findComponent(packet.ComponentPackets, STORE).isPresent()) {

				stores.add(packet);
			}
		}

		final List<Vendor> vendors = new ArrayList<>();
		for (final ObjectPersistencePacket packet : stores) {
			final ComponentPersistencePacket store =
				EKUtils.findComponent(packet.ComponentPackets, STORE).get();

			final List<Object> entries = list(store.Variables.get("ItemList"));
			final List<Object> guids = list(store.Variables.get("SerializedItemList"));
			final List<Item> items = new ArrayList<>();

			for (int i = 0; i < guids.size(); i++) {
				final String guid = String.valueOf(guids.get(i));
				final ObjectPersistencePacket itemPacket = byId.get(guid.toLowerCase());
				final InventoryItem entry = i < entries.size() && entries.get(i) instanceof InventoryItem
					? (InventoryItem) entries.get(i)
					: null;

				final String prefab = entry != null && entry.BaseItem != null
					? fileName(entry.BaseItem)
					: itemPacket != null ? objectPrefab(itemPacket.ObjectName) : null;

				items.add(new Item(
					i
					, guid
					, prefab
					, entry == null ? 1 : entry.stackSize
					, entry != null && entry.Original
					, itemPacket != null));
			}

			final String level = packet.LevelName == null || packet.LevelName.isEmpty()
				? EKUtils.removeExtension(file)
				: packet.LevelName;

			vendors.add(new Vendor(
				file
				, packet.ObjectID
				, packet.ObjectName
				, level
				, !Boolean.TRUE.equals(store.Variables.get("m_firstTime"))
				, sellMultiplier(number(store.Variables.get("sellMultiplier"), 1.5f))
				, buyMultiplier(number(store.Variables.get("buyMultiplier"), 0.2f))
				, items));
		}

		return vendors;
	}

	/**
	 * A store's name from its object's: "Store_Inn_Black_Hound" is "Black
	 * Hound (inn)". The name the game shows is the Vendor's StoreName, which
	 * lives on the scene object in the level itself and is not in the save.
	 */
	public static String vendorName (final String objectName) {
		if (objectName == null) {
			return "";
		}

		String name = objectName.split("\\(")[0];
		name = name.replaceFirst("(?i)^PX\\d+_", "");
		name = name.replaceFirst("(?i)^(Store|NPC|CRE|Companion)_", "");
		name = name.replaceFirst("_\\d+$", "");

		final boolean inn = name.regionMatches(true, 0, "Inn_", 0, 4);
		if (inn) {
			name = name.substring(4);
		}

		name = name.replace('_', ' ').trim();
		return inn ? name + " (inn)" : name;
	}

	/** An area's name from its scene's: "AR_0003_Dyrford_Store" is "Dyrford Store". */
	public static String areaName (final String level) {
		if (level == null) {
			return "";
		}

		return level.replaceFirst("(?i)^(AR|PX\\d+)_\\d+_", "").replace('_', ' ').trim();
	}

	/** {@code Store.Restored}: a multiplier stored ten times over is divided back. */
	public static float sellMultiplier (final float stored) {
		return stored >= 10f ? stored / 10f : stored;
	}

	/** {@code Store.Restored}, for what the store pays. */
	public static float buyMultiplier (final float stored) {
		return stored >= 1f ? stored / 10f : stored;
	}

	private static float number (final Object value, final float fallback) {
		return value instanceof Number ? ((Number) value).floatValue() : fallback;
	}

	private static String fileName (final String path) {
		final String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
		return EKUtils.removeExtension(name);
	}

	private static String objectPrefab (final String objectName) {
		return objectName == null ? null : objectName.split("\\(")[0];
	}

	private static List<Object> list (final Object collection) {
		final List<Object> out = new ArrayList<>();
		if (collection instanceof CSharpCollection) {
			for (final Iterator<?> it = ((CSharpCollection) collection).iterator(); it.hasNext();) {
				out.add(it.next());
			}
		}

		return out;
	}
}
