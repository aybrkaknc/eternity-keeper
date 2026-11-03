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
import uk.me.mantas.eternity.save.model.Component;
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.properties.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Edits the inventories carried by individual party members.
 *
 * <p>Every character owns their own 16-slot pack — the player's is a
 * {@code PlayerInventory}, a companion's a plain {@code Inventory} — plus a
 * four-slot {@code QuickbarInventory}. Only the {@code StashInventory} (and the
 * quest/crafting bags) is party-wide, and it hangs off the player alone. A
 * container is therefore addressed by the pair (owning character, component).
 *
 * <p>Each {@code InventoryItem} entry in a container's {@code ItemList} has a
 * same-index counterpart UUID in its {@code SerializedItemList}, and that UUID
 * is ALSO the ObjectID of a standalone top-level packet elsewhere in the save,
 * parented to the owning character — so removing an item means deleting all
 * three in sync, not just unlinking it from the list (the same "purge, don't
 * orphan" lesson as {@link Resurrector}), and moving one between characters
 * has to re-point that packet's {@code Parent} at its new owner.
 */
public class InventoryManager {
	private static final Logger logger = Logger.getLogger(InventoryManager.class);

	private final File saveDirectory;
	private String problem = null;

	// What one apply() touched, so the rules can be checked against where
	// everything ends up rather than at each step on the way: the editor parks
	// an item in its owner's pack between two slots, and two full packs trade
	// items one move at a time.
	private final Set<String> touchedContainers = new LinkedHashSet<>();
	private final Map<String, Set<String>> touchedItems = new HashMap<>();
	private final List<Placed> placed = new ArrayList<>();
	private final List<Requested> requested = new ArrayList<>();

	/** The tile an item asked to land on, honoured once everything has moved. */
	private static final class Requested {
		final String character;
		final String component;
		final String item;
		final int slot;

		Requested (final String character, final String component, final String item, final int slot) {
			this.character = character;
			this.component = component;
			this.item = item;
			this.slot = slot;
		}
	}

	/** An item put into a character's equipment slot or weapon set. */
	private static final class Placed {
		final String wearer;
		final boolean weaponSet;
		final int index;
		final String item;

		Placed (final String wearer, final boolean weaponSet, final int index, final String item) {
			this.wearer = wearer;
			this.weaponSet = weaponSet;
			this.index = index;
			this.item = item;
		}
	}

	public InventoryManager (final File saveDirectory) {
		this.saveDirectory = saveDirectory;
	}

	/** Why the last {@link #apply} refused, in words the user can act on. */
	public Optional<String> problem () {
		return Optional.ofNullable(problem);
	}

	/** The equipment component's fixed slot array, addressed by index. */
	public static final String EQUIPMENT = "Equipment";
	private static final int EQUIPMENT_SLOTS = 11;
	private static final String EMPTY_GUID = "00000000-0000-0000-0000-000000000000";

	/** One item's desired final state, relative to where it is now. */
	public static final class Change {
		public final String character;       // ObjectID of the current owner
		public final String component;       // container the item is in now
		public final String itemGuid;
		public final int stackSize;          // <= 0 means remove, whatever else is set
		public final String destCharacter;   // ObjectID of the final owner
		public final String destComponent;   // final container
		public final int destSlot;           // uiSlot to land on; < 0 picks the first free one
		public final int fromEquipmentSlot;  // equipment index the item starts in, else -1
		public final int toEquipmentSlot;    // equipment index it ends up in, else -1
		/** Indexes WeaponSetsSerialized rather than EquipmentSetSerialized. */
		public boolean weaponSet = false;
		/** Set only when adding a brand new item from the catalog. */
		public String newItemPrefab = null;
		public String newItemPath = null;
		/**
		 * Set only when bringing in an item whole, with every component it
		 * has -- a loadout's copy. Its ID must already be {@link #itemGuid},
		 * in the tree and in its mirror; its parent and area become its new
		 * holder's. {@link #newItemPath}, when set, is its inventory entry's
		 * prefab path.
		 */
		public Property carried = null;

		public Change (
			final String character
			, final String component
			, final String itemGuid
			, final int stackSize
			, final String destCharacter
			, final String destComponent
			, final int destSlot) {

			this(character, component, itemGuid, stackSize, destCharacter, destComponent
				, destSlot, -1, -1);
		}

		public Change (
			final String character
			, final String component
			, final String itemGuid
			, final int stackSize
			, final String destCharacter
			, final String destComponent
			, final int destSlot
			, final int fromEquipmentSlot
			, final int toEquipmentSlot) {

			this.character = character;
			this.component = component;
			this.itemGuid = itemGuid;
			this.stackSize = stackSize;
			this.destCharacter = destCharacter;
			this.destComponent = destComponent;
			this.destSlot = destSlot;
			this.fromEquipmentSlot = fromEquipmentSlot;
			this.toEquipmentSlot = toEquipmentSlot;
		}

		/** Convenience for edits that stay inside one container. */
		public Change (
			final String character
			, final String component
			, final String itemGuid
			, final int stackSize) {

			this(character, component, itemGuid, stackSize, character, component, -1);
		}

		/** Move an item out of a pack and onto a character's equipment slot. */
		public static Change equip (
			final String character
			, final String component
			, final String itemGuid
			, final String destCharacter
			, final int equipmentSlot) {

			return new Change(character, component, itemGuid, 1, destCharacter
				, EQUIPMENT, -1, -1, equipmentSlot);
		}

		/** Put an item into one of the four weapon sets (index 0..7). */
		public static Change equipWeapon (
			final String character
			, final String component
			, final String itemGuid
			, final String destCharacter
			, final int weaponSlot) {

			final Change change = new Change(character, component, itemGuid, 1
				, destCharacter, EQUIPMENT, -1, -1, weaponSlot);

			change.weaponSet = true;
			return change;
		}

		/** Take a weapon out of a set and drop it into a pack. */
		public static Change unequipWeapon (
			final String character
			, final int weaponSlot
			, final String itemGuid
			, final String destCharacter
			, final String destComponent
			, final int destSlot) {

			final Change change = new Change(character, EQUIPMENT, itemGuid, 1
				, destCharacter, destComponent, destSlot, weaponSlot, -1);

			change.weaponSet = true;
			return change;
		}

		/** Take a worn item off and drop it into a pack. */
		public static Change unequip (
			final String character
			, final int equipmentSlot
			, final String itemGuid
			, final String destCharacter
			, final String destComponent
			, final int destSlot) {

			return new Change(character, EQUIPMENT, itemGuid, 1, destCharacter
				, destComponent, destSlot, equipmentSlot, -1);
		}

		boolean isMove () {
			return !character.equalsIgnoreCase(destCharacter)
				|| !component.equals(destComponent);
		}
	}

	public boolean apply (final List<Change> changes) throws IOException {
		problem = null;
		touchedContainers.clear();
		touchedItems.clear();
		placed.clear();
		requested.clear();

		final File mobileObjects = new File(saveDirectory, "MobileObjects.save");
		final Optional<DeserializedPackets> deserializedOpt =
			new PacketDeserializer(mobileObjects).deserialize();

		if (!deserializedOpt.isPresent()) {
			logger.error("Unable to deserialize MobileObjects.save.%n");
			return false;
		}

		final DeserializedPackets deserialized = deserializedOpt.get();
		final List<Property> packets = new ArrayList<>(deserialized.getPackets());

		boolean packetsChanged = false;
		for (final Change change : changes) {
			final Optional<Property> owner = EKUtils.findPacketById(packets, change.character);
			if (!owner.isPresent()) {
				logger.error("No character '%s' in target save.%n", change.character);
				return false;
			}

			// An item brought in whole, enchantments and all: a loadout's.
			if (change.carried != null) {
				if (!addCarriedItem(packets, owner.get(), change)) {
					return false;
				}

				packetsChanged = true;
				continue;
			}

			// Adding an item that isn't in the save yet: it needs its own
			// top-level packet before anything can reference it.
			if (change.newItemPrefab != null) {
				if (!addNewItem(packets, owner.get(), change)) {
					return false;
				}

				packetsChanged = true;
				continue;
			}

			// Worn gear lives in a fixed slot array rather than an item list,
			// so equipping and unequipping are their own operations.
			if (change.toEquipmentSlot >= 0 || change.fromEquipmentSlot >= 0) {
				if (!applyEquipmentChange(packets, owner.get(), change)) {
					return false;
				}

				continue;
			}

			final Optional<CollectionProperty> itemList =
				findList(owner.get(), change.component, "ItemList");
			final Optional<CollectionProperty> serializedList =
				findList(owner.get(), change.component, "SerializedItemList");

			if (!itemList.isPresent() || !serializedList.isPresent()) {
				logger.error(
					"Component '%s' has no ItemList/SerializedItemList.%n", change.component);
				return false;
			}

			final int index = indexOfGuid(serializedList.get(), change.itemGuid);
			if (index < 0) {
				logger.error(
					"Item '%s' not found in '%s'.%n", change.itemGuid, change.component);
				return false;
			}

			if (change.stackSize <= 0) {
				itemList.get().items.remove(index);
				serializedList.get().items.remove(index);

				final boolean removed =
					packets.removeIf(p -> p.obj instanceof ObjectPersistencePacket
						&& change.itemGuid.equalsIgnoreCase(
							((ObjectPersistencePacket) p.obj).ObjectID));

				if (!removed) {
					logger.error(
						"Item '%s' had no standalone packet to remove.%n", change.itemGuid);
					return false;
				}

				packetsChanged = true;
				continue;
			}

			if (!change.isMove()) {
				final Property item = itemList.get().items.get(index);
				if (!setStackSize(item, change.stackSize)) {
					return false;
				}

				touch(change.character, change.component, change.itemGuid);
				request(change.character, change.component, change.itemGuid, change.destSlot);

				if (change.destSlot >= 0
					&& !setSlot(item, freeSlot(itemList.get(), change.destSlot, index))) {

					return false;
				}

				continue;
			}

			final Optional<Property> destOwner = EKUtils.findPacketById(packets, change.destCharacter);
			if (!destOwner.isPresent()) {
				logger.error("No destination character '%s'.%n", change.destCharacter);
				return false;
			}

			final Optional<CollectionProperty> destItemList =
				findList(destOwner.get(), change.destComponent, "ItemList");
			final Optional<CollectionProperty> destSerializedList =
				findList(destOwner.get(), change.destComponent, "SerializedItemList");

			if (!destItemList.isPresent() || !destSerializedList.isPresent()
				|| !findMaxItems(destOwner.get(), change.destComponent).isPresent()) {

				logger.error("Destination component '%s' not found.%n", change.destComponent);
				return false;
			}

			// Whether it fits, and which tile it gets, is decided once
			// everything has moved.
			touch(change.destCharacter, change.destComponent, change.itemGuid);
			request(change.destCharacter, change.destComponent, change.itemGuid, change.destSlot);

			final Property item = itemList.get().items.remove(index);
			final Property guid = serializedList.get().items.remove(index);
			destItemList.get().items.add(item);
			destSerializedList.get().items.add(guid);

			if (!setStackSize(item, change.stackSize)
				|| !setSlot(item, freeSlot(destItemList.get(), change.destSlot, -1))) {

				return false;
			}

			// An item's own packet is parented to whoever carries it, so
			// handing it to another character has to re-point that link.
			if (!change.character.equalsIgnoreCase(change.destCharacter)
				&& !reparent(packets, change.itemGuid, destOwner.get())) {

				return false;
			}
		}

		settleSlots(packets);

		final Optional<String> refusal = refusal(packets);
		if (refusal.isPresent()) {
			problem = refusal.get();
			logger.error("Refused: %s%n", problem);
			return false;
		}

		if (packetsChanged) {
			deserialized.setPackets(packets);
		}

		deserialized.replace(mobileObjects);
		return true;
	}

	private void touch (final String character, final String component, final String item) {
		final String key = character.toLowerCase() + "|" + component;
		touchedContainers.add(key);
		touchedItems.computeIfAbsent(key, k -> new HashSet<>()).add(item.toLowerCase());
	}

	private void request (
		final String character, final String component, final String item, final int slot) {

		if (slot >= 0) {
			requested.add(new Requested(character, component, item, slot));
		}
	}

	/**
	 * Where each item asked to land, settled once everything has moved. The
	 * first pass had to put an item somewhere while its tile might still be
	 * taken -- trading two items' places puts the first one down while the
	 * second is still on its tile -- so it took the lowest free one, which on
	 * a full quick bar is a fifth slot the bar does not have. Now that every
	 * item is where it is going, the tile it asked for is free if the editor
	 * said it would be, and anything still beyond the container's last tile
	 * comes back inside it.
	 */
	private void settleSlots (final List<Property> packets) {
		for (final Requested want : requested) {
			final Optional<Property> owner = EKUtils.findPacketById(packets, want.character);
			if (!owner.isPresent()) {
				continue;
			}

			final Optional<CollectionProperty> items = findList(owner.get(), want.component, "ItemList");
			final Optional<CollectionProperty> guids =
				findList(owner.get(), want.component, "SerializedItemList");

			if (!items.isPresent() || !guids.isPresent()) {
				continue;
			}

			// Moved on again later in the same batch: that move settles it.
			final int index = indexOfGuid(guids.get(), want.item);
			if (index < 0) {
				continue;
			}

			if (freeSlot(items.get(), want.slot, index) == want.slot) {
				setSlot(items.get().items.get(index), want.slot);
			}
		}

		for (final String key : touchedContainers) {
			final String[] parts = key.split("\\|", 2);
			final Optional<Property> owner = EKUtils.findPacketById(packets, parts[0]);
			if (!owner.isPresent()) {
				continue;
			}

			final Optional<CollectionProperty> items = findList(owner.get(), parts[1], "ItemList");
			final Optional<Integer> maxItems = findMaxItems(owner.get(), parts[1]);
			if (!items.isPresent() || !maxItems.isPresent()) {
				continue;
			}

			for (int i = 0; i < items.get().items.size(); i++) {
				final Property item = items.get().items.get(i);
				if (slotOf(item).orElse(0) >= maxItems.get()) {
					setSlot(item, freeSlot(items.get(), -1, i));
				}
			}
		}
	}

	// The containers that stack without limit (BaseInventory.InfiniteStacking);
	// everything else holds an item's MaxStackSize at most.
	private static final Set<String> INFINITE_STACKING =
		new HashSet<>(java.util.Arrays.asList("StashInventory", "CraftingInventory"));

	private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI"};

	/**
	 * The first of the game's rules the edited save would break, judged on
	 * where everything has ended up. Each is one the game itself enforces
	 * before it lets an item go somewhere, so a save that broke one is a save
	 * the game could not have written:
	 *
	 * <ul>
	 * <li>{@code BaseInventory.CanPutItem}: a container holds {@code MaxItems}
	 *   entries, and a stack no bigger than the item's {@code MaxStackSize}
	 *   unless it stacks without limit (the stash, the crafting bag).</li>
	 * <li>{@code Equippable.CanUseSlot}: the main hand wants
	 *   {@code PrimaryWeaponSlot}; the off hand wants {@code SecondaryWeaponSlot}
	 *   and not {@code BothPrimaryAndSecondarySlot}, which is what makes a
	 *   weapon two-handed.</li>
	 * <li>{@code UIInventoryGridItem.ItemTransferValid}: a two-hander shares its
	 *   weapon set with nothing.</li>
	 * <li>{@code CharacterStats.MaxWeaponSets} = 2 + {@code BonusWeaponSets}.</li>
	 * <li>{@code Equippable.WhyCantEquip}: nobody but its owner can equip an
	 *   item soulbound to them.</li>
	 * </ul>
	 *
	 * <p>Slot flags and stack sizes come from the item catalog; without one
	 * those checks are skipped rather than guessed. Capacity, weapon sets and
	 * soulbinding are all in the save.
	 */
	private Optional<String> refusal (final List<Property> packets) {
		final ItemCatalog catalog = ItemCatalog.getInstance();

		for (final String key : touchedContainers) {
			final String[] parts = key.split("\\|", 2);
			final Optional<Property> owner = EKUtils.findPacketById(packets, parts[0]);
			if (!owner.isPresent()) {
				continue;
			}

			final Optional<CollectionProperty> items = findList(owner.get(), parts[1], "ItemList");
			final Optional<CollectionProperty> guids =
				findList(owner.get(), parts[1], "SerializedItemList");
			final Optional<Integer> maxItems = findMaxItems(owner.get(), parts[1]);

			if (!items.isPresent() || !guids.isPresent()) {
				continue;
			}

			if (maxItems.isPresent() && items.get().items.size() > maxItems.get()) {
				return Optional.of(String.format(
					"%s would hold %d items, but there is room for %d."
					, containerName(owner.get(), parts[1])
					, items.get().items.size(), maxItems.get()));
			}

			if (INFINITE_STACKING.contains(parts[1])) {
				continue;
			}

			for (final String item : touchedItems.getOrDefault(key, new HashSet<>())) {
				final int index = indexOfGuid(guids.get(), item);
				if (index < 0 || index >= items.get().items.size()) {
					continue;
				}

				final int stack = stackSizeOf(items.get().items.get(index));
				final Optional<ItemCatalog.Entry> entry = catalogEntryOf(packets, item);
				if (entry.isPresent() && stack > entry.get().maxStack) {
					return Optional.of(String.format(
						"%s stacks to %d, so a stack of %d only fits in the stash."
						, itemName(packets, item), entry.get().maxStack, stack));
				}
			}
		}

		for (final Placed put : placed) {
			final Optional<Property> wearer = EKUtils.findPacketById(packets, put.wearer);
			if (!wearer.isPresent()) {
				continue;
			}

			final List<Property> slots = (put.weaponSet
				? findEquipmentList(wearer.get(), "WeaponSetsSerialized")
				: findEquipmentSlots(wearer.get()))
				.map(list -> list.items)
				.orElse(new ArrayList<>());

			// Something later in the same batch may have taken it out again.
			if (put.index >= slots.size() || !put.item.equalsIgnoreCase(guidOf(slots.get(put.index)))) {
				continue;
			}

			final String name = itemName(packets, put.item);
			final Optional<String> bound = boundTo(packets, put.item);
			if (bound.isPresent() && !bound.get().equalsIgnoreCase(put.wearer)) {
				return Optional.of(String.format("%s is soulbound to %s."
					, name, EKUtils.findPacketById(packets, bound.get())
						.map(InventoryManager::characterName).orElse("someone else")));
			}

			final Optional<ItemCatalog.Entry> entry = catalogEntryOf(packets, put.item);

			if (!put.weaponSet) {
				final String flag = put.index < EQUIPMENT_SLOT_FLAGS.length
					? EQUIPMENT_SLOT_FLAGS[put.index] : "";

				if (entry.isPresent() && !flag.isEmpty() && !entry.get().slots.contains(flag)) {
					return Optional.of(name + " cannot be worn in that slot.");
				}

				continue;
			}

			final int set = put.index / 2;
			final int usable = 2 + intStat(wearer.get(), "BonusWeaponSets");
			if (set >= usable) {
				return Optional.of(String.format("%s has not unlocked weapon set %s."
					, characterName(wearer.get()), set < ROMAN.length ? ROMAN[set] : "" + (set + 1)));
			}

			final boolean offHand = put.index % 2 == 1;
			if (entry.isPresent()) {
				final List<String> flags = entry.get().slots;
				if (offHand && flags.contains("BothPrimaryAndSecondarySlot")) {
					return Optional.of(name + " takes both hands, so it only goes in the main hand.");
				}

				if (offHand && !flags.contains("SecondaryWeaponSlot")) {
					return Optional.of(name + " cannot go in the off-hand.");
				}

				if (!offHand && !flags.contains("PrimaryWeaponSlot")) {
					return Optional.of(name + " cannot go in the main hand.");
				}
			}

			final int partnerIndex = put.index ^ 1;
			final String partner = partnerIndex < slots.size() ? guidOf(slots.get(partnerIndex)) : "";
			if (!partner.isEmpty()) {
				final Optional<String> twoHander = isTwoHanded(entry)
					? Optional.of(name)
					: (isTwoHanded(catalogEntryOf(packets, partner))
						? Optional.of(itemName(packets, partner)) : Optional.empty());

				if (twoHander.isPresent()) {
					return Optional.of(
						"Two-handed weapon requires two slots: " + twoHander.get()
						+ " has to be alone in its weapon set.");
				}
			}
		}

		return Optional.empty();
	}

	// EquipmentSet.SerializedEquipment order, the flag each slot asks for.
	private static final String[] EQUIPMENT_SLOT_FLAGS = {
		"HeadSlot", "NeckSlot", "ArmorSlot", "HandSlot", "RingRightHandSlot"
		, "RingLeftHandSlot", "", "FeetSlot", "WaistSlot", "GrimoireSlot", "PetSlot"
	};

	private static boolean isTwoHanded (final Optional<ItemCatalog.Entry> entry) {
		return entry.isPresent() && entry.get().slots.contains("BothPrimaryAndSecondarySlot");
	}

	private static Optional<ObjectPersistencePacket> itemPacket (
		final List<Property> packets, final String item) {

		return EKUtils.findPacketById(packets, item)
			.filter(p -> p.obj instanceof ObjectPersistencePacket)
			.map(p -> (ObjectPersistencePacket) p.obj);
	}

	private static String prefabName (final List<Property> packets, final String item) {
		return itemPacket(packets, item)
			.map(p -> p.ObjectName == null ? "" : p.ObjectName.replace("(Clone)", "").trim())
			.orElse("");
	}

	private static Optional<ItemCatalog.Entry> catalogEntryOf (
		final List<Property> packets, final String item) {

		final String prefab = prefabName(packets, item);
		return prefab.isEmpty() ? Optional.empty() : ItemCatalog.getInstance().lookup(prefab);
	}

	private static String itemName (final List<Property> packets, final String item) {
		final Optional<ItemCatalog.Entry> entry = catalogEntryOf(packets, item);
		if (entry.isPresent() && !entry.get().name.isEmpty()) {
			return entry.get().name;
		}

		final String prefab = prefabName(packets, item);
		return prefab.isEmpty() ? "That item" : prefab.replace('_', ' ');
	}

	/**
	 * Who an item is soulbound to: {@code EquipmentSoulbind.BoundGuid} on the
	 * item's own packet, which is the owner's ObjectID. Empty for an item that
	 * is not soulbound, or not yet.
	 */
	private static Optional<String> boundTo (final List<Property> packets, final String item) {
		return EKUtils.findPacketById(packets, item)
			.flatMap(SaveObject::of)
			.flatMap(o -> o.component("EquipmentSoulbind"))
			.flatMap(c -> c.variable("BoundGuid"))
			.map(p -> p.obj == null ? "" : p.obj.toString())
			.filter(guid -> !guid.isEmpty() && !EMPTY_GUID.equals(guid));
	}

	private static int intStat (final Property character, final String stat) {
		return statsOf(character)
			.flatMap(c -> c.variable(stat))
			.map(p -> p.obj instanceof Integer ? (Integer) p.obj : 0)
			.orElse(0);
	}

	private static Optional<Component> statsOf (final Property character) {
		return SaveObject.of(character).flatMap(o -> o.component("CharacterStats"));
	}

	/**
	 * What the user calls a character: the name the save gives them (the
	 * player's own, or a companion's in the save's language), then the
	 * registry's, then the one inside the object name.
	 */
	static String characterName (final Property character) {
		final Optional<String> override = statsOf(character)
			.flatMap(c -> c.variable("OverrideName"))
			.map(p -> p.obj == null ? "" : p.obj.toString().trim())
			.filter(name -> !name.isEmpty());

		if (override.isPresent()) {
			return override.get();
		}

		final String objectName = ((ObjectPersistencePacket) character.obj).ObjectName;
		final Optional<String> companionName = CompanionRegistry.displayNameFor(objectName, "");
		if (companionName.isPresent()) {
			return companionName.get();
		}

		final String inside = EKUtils.extractCharacterName(objectName);
		return inside.isEmpty() ? "That character" : inside.replace('_', ' ');
	}

	private static String containerName (final Property owner, final String component) {
		final String who = characterName(owner);
		switch (component) {
			case "StashInventory":    return "The stash";
			case "QuickbarInventory": return who + "'s quick slots";
			case "CraftingInventory": return "The crafting bag";
			case "QuestInventory":    return "The quest bag";
			default:                  return who + "'s pack";
		}
	}

	private static int stackSizeOf (final Property itemProperty) {
		if (!(itemProperty instanceof ComplexProperty)) {
			return 1;
		}

		return findExact((ComplexProperty) itemProperty, "stackSize")
			.map(p -> p.obj instanceof Integer ? (Integer) p.obj : 1)
			.orElse(1);
	}

	/**
	 * Equipping, unequipping, and the swap that happens when you drop an item
	 * onto an occupied slot.
	 *
	 * <p>Worn gear is referenced only by UUID from the wearer's fixed 11-entry
	 * {@code EquipmentSetSerialized} array, so putting something on is really
	 * "unlink it from its pack and write its GUID into slot N" — the item's own
	 * top-level packet never moves, it just gets re-parented if it changed
	 * hands. Taking something off is the awkward direction: the pack has no
	 * entry for it, so one has to be manufactured.
	 */
	private boolean applyEquipmentChange (
		final List<Property> packets, final Property owner, final Change change) {

		final int slotIndex = change.toEquipmentSlot >= 0
			? change.toEquipmentSlot : change.fromEquipmentSlot;

		if (!change.weaponSet && slotIndex >= EQUIPMENT_SLOTS) {
			logger.error("Equipment slot %d is out of range.%n", slotIndex);
			return false;
		}

		final boolean equipping = change.toEquipmentSlot >= 0;
		final String wearerID = equipping ? change.destCharacter : change.character;
		final Optional<Property> wearer = EKUtils.findPacketById(packets, wearerID);

		if (!wearer.isPresent()) {
			logger.error("No character '%s' to wear the item.%n", wearerID);
			return false;
		}

		final Optional<CollectionProperty> equipment = change.weaponSet
			? findEquipmentList(wearer.get(), "WeaponSetsSerialized")
			: findEquipmentSlots(wearer.get());

		if (!equipment.isPresent()) {
			logger.error("Character '%s' has no Equipment component.%n", wearerID);
			return false;
		}

		final List<Property> slots = equipment.get().items;
		if (slotIndex >= slots.size()) {
			logger.error(
				"Equipment only has %d slots; %d requested.%n", slots.size(), slotIndex);

			return false;
		}

		// The pack the item comes from (equip) or goes to (unequip).
		final String packCharacter = equipping ? change.character : change.destCharacter;
		final String packComponent = equipping ? change.component : change.destComponent;
		final Optional<Property> packOwner = EKUtils.findPacketById(packets, packCharacter);

		if (!packOwner.isPresent()) {
			logger.error("No character '%s' for the pack side.%n", packCharacter);
			return false;
		}

		final Optional<CollectionProperty> itemList =
			findList(packOwner.get(), packComponent, "ItemList");
		final Optional<CollectionProperty> serializedList =
			findList(packOwner.get(), packComponent, "SerializedItemList");

		if (!itemList.isPresent() || !serializedList.isPresent()) {
			logger.error("Component '%s' has no item lists.%n", packComponent);
			return false;
		}

		final String occupant = guidOf(slots.get(slotIndex));

		if (!equipping) {
			if (!change.itemGuid.equalsIgnoreCase(occupant)) {
				logger.error(
					"Slot %d holds '%s', not '%s'.%n", slotIndex, occupant, change.itemGuid);

				return false;
			}

			// A full pack is only a problem if the item is still in it at the
			// end: moving between two slots parks it here on the way.
			touch(packCharacter, packComponent, change.itemGuid);
			request(packCharacter, packComponent, change.itemGuid, change.destSlot);

			final Optional<Property> entry = buildInventoryEntry(
				packets, change.itemGuid, freeSlot(itemList.get(), change.destSlot, -1));

			if (!entry.isPresent()) {
				return false;
			}

			itemList.get().items.add(entry.get());
			addGuid(serializedList.get(), change.itemGuid);
			setGuid(slots.get(slotIndex), EMPTY_GUID);

			return change.character.equalsIgnoreCase(change.destCharacter)
				|| reparent(packets, change.itemGuid, packOwner.get());
		}

		// Equipping: unlink from the pack, then hand the slot over.
		final int index = indexOfGuid(serializedList.get(), change.itemGuid);
		if (index < 0) {
			logger.error(
				"Item '%s' not found in '%s'.%n", change.itemGuid, packComponent);

			return false;
		}

		final Property displacedEntry = itemList.get().items.remove(index);
		serializedList.get().items.remove(index);
		setGuid(slots.get(slotIndex), change.itemGuid);
		placed.add(new Placed(wearerID, change.weaponSet, slotIndex, change.itemGuid));

		// Whatever was already worn falls back into the pack. Reusing the
		// entry we just freed keeps this allocation-free and type-exact.
		if (!occupant.isEmpty() && !occupant.equalsIgnoreCase(change.itemGuid)) {
			final Optional<String> baseItem = prefabPathOf(packets, occupant);
			if (!baseItem.isPresent()) {
				return false;
			}

			if (!setBaseItem(displacedEntry, baseItem.get())
				|| !setStackSize(displacedEntry, 1)
				|| !setSlot(displacedEntry, freeSlot(itemList.get(), -1, -1))) {

				return false;
			}

			itemList.get().items.add(displacedEntry);
			addGuid(serializedList.get(), occupant);
			touch(packCharacter, packComponent, occupant);

			if (!reparent(packets, occupant, packOwner.get())) {
				return false;
			}
		}

		return change.character.equalsIgnoreCase(wearerID)
			|| reparent(packets, change.itemGuid, wearer.get());
	}

	/**
	 * Creates an item that isn't in the save at all yet.
	 *
	 * <p>Three things have to appear together: a top-level
	 * {@code ObjectPersistencePacket} carrying a fresh GUID and the prefab to
	 * instantiate, an {@code ItemList} entry, and the matching UUID in
	 * {@code SerializedItemList} (which the game reads as a GUIDLink and
	 * resolves against the persistence manager — hence the packet). The packet
	 * is deliberately minimal: {@code CreateObject} loads the prefab from
	 * {@code PrefabResource}, so anything not overridden here comes back as the
	 * prefab's own defaults, which is exactly right for a brand new item.
	 */
	private boolean addNewItem (
		final List<Property> packets, final Property owner, final Change change) {

		final Optional<CollectionProperty> itemList =
			findList(owner, change.destComponent, "ItemList");
		final Optional<CollectionProperty> serializedList =
			findList(owner, change.destComponent, "SerializedItemList");

		if (!itemList.isPresent() || !serializedList.isPresent()) {
			logger.error("Component '%s' has no item lists.%n", change.destComponent);
			return false;
		}

		final String ownerID = ((ObjectPersistencePacket) owner.obj).ObjectID;
		touch(ownerID, change.destComponent, change.itemGuid);
		request(ownerID, change.destComponent, change.itemGuid, change.destSlot);

		final Optional<Property> template = findAnyItemPacket(packets);
		if (!template.isPresent()) {
			logger.error("This save has no item packet to model a new one on.%n");
			return false;
		}

		final String guid = change.itemGuid;
		final String ownerName = ((ObjectPersistencePacket) owner.obj).ObjectName;
		final Optional<Property> packet = buildItemPacket(
			template.get(), guid, change.newItemPrefab, change.newItemPath, ownerName);

		if (!packet.isPresent()) {
			return false;
		}

		final Optional<ComplexProperty> entry = newEntry(packets, change.newItemPath
			, freeSlot(itemList.get(), change.destSlot, -1), change.stackSize);
		if (!entry.isPresent()) {
			return false;
		}

		itemList.get().items.add(entry.get());
		addGuid(serializedList.get(), guid);
		packets.add(packet.get());

		return true;
	}

	/**
	 * Brings in an item whole -- a loadout's copy, with its enchantments, its
	 * soulbinding and a grimoire's spells -- rather than a bare prefab. It
	 * goes into the holder's container like any new item, and takes the
	 * holder's area and name as its parent, in the tree and in its mirror,
	 * since the rest of this class finds objects through the mirror.
	 */
	private boolean addCarriedItem (
		final List<Property> packets, final Property owner, final Change change) {

		final Optional<CollectionProperty> itemList =
			findList(owner, change.destComponent, "ItemList");
		final Optional<CollectionProperty> serializedList =
			findList(owner, change.destComponent, "SerializedItemList");

		if (!itemList.isPresent() || !serializedList.isPresent()
			|| !(change.carried instanceof ComplexProperty)
			|| !(change.carried.obj instanceof ObjectPersistencePacket)) {

			logger.error("No room in '%s' for an item brought in whole.%n", change.destComponent);
			return false;
		}

		final ObjectPersistencePacket holder = (ObjectPersistencePacket) owner.obj;
		final ObjectPersistencePacket mirror = (ObjectPersistencePacket) change.carried.obj;
		if (!change.itemGuid.equalsIgnoreCase(mirror.ObjectID)) {
			logger.error("An item brought in as '%s' is '%s'.%n", change.itemGuid, mirror.ObjectID);
			return false;
		}

		final ComplexProperty packet = (ComplexProperty) change.carried;
		final Optional<Property> parent = packet.findProperty("Parent");
		final Optional<Property> level = packet.findProperty("LevelName");
		if (!parent.isPresent() || !level.isPresent()
			|| !Property.update(parent.get(), holder.ObjectName)
			|| !Property.update(level.get(), holder.LevelName)) {

			logger.error("An item brought in whole has no Parent or LevelName to set.%n");
			return false;
		}

		mirror.Parent = holder.ObjectName;
		mirror.LevelName = holder.LevelName;

		touch(holder.ObjectID, change.destComponent, change.itemGuid);
		request(holder.ObjectID, change.destComponent, change.itemGuid, change.destSlot);
		packets.add(packet);

		final Optional<String> baseItem = change.newItemPath != null
			? Optional.of(change.newItemPath) : prefabPathOf(packets, change.itemGuid);
		final Optional<ComplexProperty> entry = baseItem.isPresent()
			? newEntry(packets, baseItem.get(), freeSlot(itemList.get(), change.destSlot, -1), change.stackSize)
			: Optional.empty();

		if (!entry.isPresent()) {
			return false;
		}

		itemList.get().items.add(entry.get());
		addGuid(serializedList.get(), change.itemGuid);
		return true;
	}

	/**
	 * A fresh {@code InventoryItem} entry, its type strings copied from one
	 * this save already holds, so it is written exactly as the game would.
	 */
	private static Optional<ComplexProperty> newEntry (
		final List<Property> packets, final String baseItem, final int uiSlot, final int stackSize) {

		final Optional<ComplexProperty> template = findAnyInventoryEntry(packets);
		if (!template.isPresent()) {
			logger.error("This save has no InventoryItem to model a new entry on.%n");
			return Optional.empty();
		}

		final ComplexProperty entry = new ComplexProperty(null, template.get().type);
		for (final Property field : template.get().properties) {
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

		return Optional.of(entry);
	}

	/** Any existing item packet, used purely for its type information. */
	private static Optional<Property> findAnyItemPacket (final List<Property> packets) {
		for (final Property packet : packets) {
			if (!(packet.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket item = (ObjectPersistencePacket) packet.obj;
			if (item.PrefabResource != null
				&& item.PrefabResource.toLowerCase().contains("/items/")
				&& item.ComponentPackets != null) {

				return Optional.of(packet);
			}
		}

		return Optional.empty();
	}

	/**
	 * A new item's own packet. It carries only InstanceID and Persistence: the
	 * prefab brings its Equippable, Weapon or Consumable components back with
	 * default state, which is what a newly created item should have.
	 */
	private static Optional<Property> buildItemPacket (
		final Property template
		, final String guid
		, final String prefabName
		, final String prefabPath
		, final String ownerName) {

		return PacketMint.mint(template
			, new PacketMint.Identity(
				prefabName + "(Clone)", guid, UUID.fromString(guid), prefabPath, ownerName, null)
			, (type, component) -> "InstanceID".equals(type) || "Persistence".equals(type)
			, (type, copy) -> {});
	}

	private static Optional<CollectionProperty> findEquipmentSlots (final Property character) {
		return findEquipmentList(character, "EquipmentSetSerialized");
	}

	private static Optional<CollectionProperty> findEquipmentList (
		final Property character, final String listName) {

		return ((ComplexProperty) character)
			.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
			.flatMap(c -> EKUtils.findSubComponent(c, EQUIPMENT))
			.<DictionaryProperty>flatMap(e -> e.findProperty("Variables"))
			.flatMap(v -> v.findEntry(listName));
	}

	private static String guidOf (final Property property) {
		if (!(property instanceof SimpleProperty)
			|| ((SimpleProperty) property).value == null) {

			return "";
		}

		final String guid = ((SimpleProperty) property).value.toString();
		return EMPTY_GUID.equals(guid) ? "" : guid;
	}

	// Equipment slots hold real UUID objects, not their text — writing a String
	// through Property.update leaves the serializer emitting the wrong type and
	// the save no longer reads back.
	private static boolean setGuid (final Property property, final String guid) {
		if (!(property instanceof SimpleProperty)) {
			logger.error("Equipment slot was not a simple property.%n");
			return false;
		}

		final UUID value = UUID.fromString(guid);
		((SimpleProperty) property).value = value;
		property.obj = value;
		return true;
	}

	private static void addGuid (final CollectionProperty list, final String guid) {
		final SimpleProperty item = new SimpleProperty(null, new TypePair(UUID.class, null));
		item.value = UUID.fromString(guid);
		item.obj = item.value;
		list.items.add(item);
	}

	/**
	 * An item's prefab path, rebuilt from its own packet plus the catalog.
	 * A save records something like
	 * {@code Assets/Data/Prefabs/Items/Rings/Ring_PREORDER_Gauns_Pledge.prefab},
	 * and the packet's ObjectName gives the exact-case file name. Only that
	 * file name actually matters — {@code GameResources.LoadPrefab} throws the
	 * directory away and lowercases the rest — but keeping the real directory
	 * makes edited saves look like the game wrote them.
	 */
	private static Optional<String> prefabPathOf (
		final List<Property> packets, final String itemGuid) {

		for (final Property packet : packets) {
			if (!(packet.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket item = (ObjectPersistencePacket) packet.obj;
			if (!itemGuid.equalsIgnoreCase(item.ObjectID) || item.ObjectName == null) {
				continue;
			}

			final String prefab = item.ObjectName.replace("(Clone)", "").trim();
			final Optional<ItemCatalog.Entry> entry =
				ItemCatalog.getInstance().lookup(prefab);

			final String directory = entry
				.map(e -> e.path)
				.filter(p -> !p.isEmpty() && p.lastIndexOf('/') > 0)
				.map(p -> p.substring(0, p.lastIndexOf('/') + 1))
				.orElse("Assets/Data/Prefabs/Items/");

			return Optional.of(directory + prefab + ".prefab");
		}

		logger.error("No packet for item '%s'; cannot rebuild its prefab path.%n", itemGuid);
		return Optional.empty();
	}

	/**
	 * Manufactures the {@code InventoryItem} entry an unequipped item needs.
	 * The type strings are copied from an entry that already exists in this
	 * save rather than hardcoded, so the result is byte-compatible with
	 * whatever the game wrote.
	 */
	private Optional<Property> buildInventoryEntry (
		final List<Property> packets, final String itemGuid, final int uiSlot) {

		final Optional<String> baseItem = prefabPathOf(packets, itemGuid);
		if (!baseItem.isPresent()) {
			return Optional.empty();
		}

		final Optional<ComplexProperty> template = findAnyInventoryEntry(packets);
		if (!template.isPresent()) {
			logger.error(
				"This save has no InventoryItem to model a new entry on.%n");

			return Optional.empty();
		}

		final ComplexProperty entry =
			new ComplexProperty(null, template.get().type);

		for (final Property field : template.get().properties) {
			if (!(field instanceof SimpleProperty) || field.name == null) {
				continue;
			}

			final Object value;
			switch (field.name) {
				case "BaseItem":        value = baseItem.get(); break;
				case "uiSlot":          value = uiSlot; break;
				case "StackSize":       value = 1; break;
				case "stackSize":       value = 1; break;
				case "Original":        value = false; break;
				case "AreaLootSource":  value = false; break;
				default:                value = ((SimpleProperty) field).value; break;
			}

			final SimpleProperty copy = new SimpleProperty(field.name, field.type);
			copy.value = value;
			copy.obj = value;
			entry.properties.add(copy);
		}

		return Optional.of(entry);
	}

	private static Optional<ComplexProperty> findAnyInventoryEntry (
		final List<Property> packets) {

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

				final Optional<CollectionProperty> list = variables.get().findEntry("ItemList");
				if (!list.isPresent()) {
					continue;
				}

				for (final Property entry : list.get().items) {
					if (entry instanceof ComplexProperty
						&& !((ComplexProperty) entry).properties.isEmpty()) {

						return Optional.of((ComplexProperty) entry);
					}
				}
			}
		}

		return Optional.empty();
	}

	private static boolean setBaseItem (final Property itemProperty, final String baseItem) {
		if (!(itemProperty instanceof ComplexProperty)) {
			return false;
		}

		final Optional<Property> field = findExact((ComplexProperty) itemProperty, "BaseItem");
		if (!field.isPresent()) {
			logger.error("Inventory item is missing BaseItem.%n");
			return false;
		}

		return Property.update(field.get(), baseItem);
	}

	// The game keeps one item per uiSlot and fills gaps with the lowest free
	// index (BaseInventory.FirstFreeSlot), so mirror that rather than letting
	// two items claim the same tile.
	private static int freeSlot (
		final CollectionProperty itemList, final int preferred, final int ignoreIndex) {

		final Set<Integer> taken = new HashSet<>();
		for (int i = 0; i < itemList.items.size(); i++) {
			if (i == ignoreIndex) {
				continue;
			}

			slotOf(itemList.items.get(i)).ifPresent(taken::add);
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

	private static Optional<Integer> slotOf (final Property itemProperty) {
		if (!(itemProperty instanceof ComplexProperty)) {
			return Optional.empty();
		}

		return findExact((ComplexProperty) itemProperty, "uiSlot")
			.filter(p -> p instanceof SimpleProperty)
			.map(p -> ((SimpleProperty) p).value)
			.filter(v -> v instanceof Integer)
			.map(v -> (Integer) v);
	}

	private static boolean setSlot (final Property itemProperty, final int slot) {
		if (!(itemProperty instanceof ComplexProperty)) {
			logger.error("Inventory item entry was not a ComplexProperty.%n");
			return false;
		}

		final Optional<Property> uiSlot = findExact((ComplexProperty) itemProperty, "uiSlot");
		if (!uiSlot.isPresent()) {
			logger.error("Inventory item is missing uiSlot.%n");
			return false;
		}

		return Property.update(uiSlot.get(), slot);
	}

	private static boolean reparent (
		final List<Property> packets, final String itemGuid, final Property newOwner) {

		final String ownerName = ((ObjectPersistencePacket) newOwner.obj).ObjectName;
		for (final Property packet : packets) {
			if (!(packet.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			if (!itemGuid.equalsIgnoreCase(((ObjectPersistencePacket) packet.obj).ObjectID)) {
				continue;
			}

			final Optional<Property> parent = ((ComplexProperty) packet).findProperty("Parent");
			if (!parent.isPresent()) {
				logger.error("Item packet '%s' has no Parent property.%n", itemGuid);
				return false;
			}

			return Property.update(parent.get(), ownerName);
		}

		logger.error("Item '%s' had no standalone packet to reparent.%n", itemGuid);
		return false;
	}

	// InventoryItem is the one C# type in the whole format with both a
	// backing field ("stackSize") and a same-named property ("StackSize")
	// independently serialized (Polenter.Serialization doesn't know the
	// property just wraps the field). ComplexProperty.findProperty() matches
	// case-insensitively — exactly right for bridging C#/Java naming
	// elsewhere, but here it would resolve BOTH names to whichever of the
	// two appears first, silently leaving the other one stale. Match exact
	// case instead so both are actually found and updated; the real game
	// always writes them in lockstep, so both must agree on write-back too.
	private static boolean setStackSize (final Property itemProperty, final int stackSize) {
		if (!(itemProperty instanceof ComplexProperty)) {
			logger.error("Inventory item entry was not a ComplexProperty.%n");
			return false;
		}

		final ComplexProperty item = (ComplexProperty) itemProperty;
		final Optional<Property> lower = findExact(item, "stackSize");
		final Optional<Property> upper = findExact(item, "StackSize");

		if (!lower.isPresent() || !upper.isPresent()) {
			logger.error("Inventory item is missing stackSize/StackSize.%n");
			return false;
		}

		return Property.update(lower.get(), stackSize) && Property.update(upper.get(), stackSize);
	}

	private static Optional<Property> findExact (final ComplexProperty complex, final String name) {
		for (final Property p : complex.properties) {
			if (p != null && name.equals(p.name)) {
				return Optional.of(p);
			}
		}

		return Optional.empty();
	}

	private static int indexOfGuid (final CollectionProperty serializedList, final String guid) {
		for (int i = 0; i < serializedList.items.size(); i++) {
			final Property item = serializedList.items.get(i);
			if (item instanceof SimpleProperty
				&& ((SimpleProperty) item).value != null
				&& guid.equalsIgnoreCase(((SimpleProperty) item).value.toString())) {

				return i;
			}
		}

		return -1;
	}

	private static Optional<CollectionProperty> findList (
		final Property character, final String component, final String listName) {

		return ((ComplexProperty) character)
			.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
			.flatMap(c -> EKUtils.findSubComponent(c, component))
			.<DictionaryProperty>flatMap(inv -> inv.findProperty("Variables"))
			.flatMap(v -> v.findEntry(listName));
	}

	private static Optional<Integer> findMaxItems (
		final Property character, final String component) {

		final Optional<Property> maxItems = ((ComplexProperty) character)
			.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
			.flatMap(c -> EKUtils.findSubComponent(c, component))
			.<DictionaryProperty>flatMap(inv -> inv.findProperty("Variables"))
			.flatMap(v -> v.findEntry("MaxItems"));

		return maxItems.filter(p -> p instanceof SimpleProperty)
			.map(p -> (Integer) ((SimpleProperty) p).value);
	}

}
