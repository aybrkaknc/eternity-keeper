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
import uk.me.mantas.eternity.save.model.Component;
import uk.me.mantas.eternity.save.model.GuidList;
import uk.me.mantas.eternity.save.model.ItemContainer;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.Property;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
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
		final SaveModel save = SaveModel.of(deserialized);

		for (final Change change : changes) {
			final Optional<SaveObject> owner = save.object(change.character);
			if (!owner.isPresent()) {
				logger.error("No character '%s' in target save.%n", change.character);
				return false;
			}

			// An item brought in whole, enchantments and all: a loadout's.
			if (change.carried != null) {
				if (!addCarriedItem(save, owner.get(), change)) {
					return false;
				}

				continue;
			}

			// Adding an item that isn't in the save yet: it needs its own
			// top-level object before anything can reference it.
			if (change.newItemPrefab != null) {
				if (!addNewItem(save, owner.get(), change)) {
					return false;
				}

				continue;
			}

			// Worn gear lives in a fixed slot array rather than an item list,
			// so equipping and unequipping are their own operations.
			if (change.toEquipmentSlot >= 0 || change.fromEquipmentSlot >= 0) {
				if (!applyEquipmentChange(save, owner.get(), change)) {
					return false;
				}

				continue;
			}

			final Optional<ItemContainer> container = container(owner.get(), change.component);
			if (!container.isPresent()) {
				logger.error(
					"Component '%s' has no ItemList/SerializedItemList.%n", change.component);
				return false;
			}

			final int index = container.get().indexOf(change.itemGuid);
			if (index < 0) {
				logger.error(
					"Item '%s' not found in '%s'.%n", change.itemGuid, change.component);
				return false;
			}

			if (change.stackSize <= 0) {
				if (!save.object(change.itemGuid).isPresent()) {
					logger.error(
						"Item '%s' had no standalone packet to remove.%n", change.itemGuid);
					return false;
				}

				container.get().remove(index);
				save.removeUnlisted(Collections.singleton(change.itemGuid));
				continue;
			}

			if (!change.isMove()) {
				final ItemContainer.Entry item = container.get().entry(index);
				item.setStackSize(change.stackSize);

				touch(change.character, change.component, change.itemGuid);
				request(change.character, change.component, change.itemGuid, change.destSlot);

				if (change.destSlot >= 0) {
					item.setSlot(container.get().freeSlot(change.destSlot, index));
				}

				continue;
			}

			final Optional<SaveObject> destOwner = save.object(change.destCharacter);
			if (!destOwner.isPresent()) {
				logger.error("No destination character '%s'.%n", change.destCharacter);
				return false;
			}

			final Optional<ItemContainer> destination = container(destOwner.get(), change.destComponent);
			if (!destination.isPresent() || !destination.get().maxItems().isPresent()) {
				logger.error("Destination component '%s' not found.%n", change.destComponent);
				return false;
			}

			// Whether it fits, and which tile it gets, is decided once
			// everything has moved.
			touch(change.destCharacter, change.destComponent, change.itemGuid);
			request(change.destCharacter, change.destComponent, change.itemGuid, change.destSlot);

			// An item's own object is parented to whoever carries it, so
			// handing it to another character re-points that link.
			final ItemContainer.Entry item = container.get().moveTo(destination.get(), index, save);
			item.setStackSize(change.stackSize);
			item.setSlot(destination.get().freeSlot(change.destSlot, -1));
		}

		settleSlots(save);

		final Optional<String> refusal = refusal(save);
		if (refusal.isPresent()) {
			problem = refusal.get();
			logger.error("Refused: %s%n", problem);
			return false;
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
	private void settleSlots (final SaveModel save) throws WriteRefusedException {
		for (final Requested want : requested) {
			final Optional<ItemContainer> container =
				save.object(want.character).flatMap(owner -> container(owner, want.component));

			if (!container.isPresent()) {
				continue;
			}

			// Moved on again later in the same batch: that move settles it.
			final int index = container.get().indexOf(want.item);
			if (index < 0) {
				continue;
			}

			if (container.get().freeSlot(want.slot, index) == want.slot) {
				container.get().entry(index).setSlot(want.slot);
			}
		}

		for (final String key : touchedContainers) {
			final String[] parts = key.split("\\|", 2);
			final Optional<ItemContainer> container =
				save.object(parts[0]).flatMap(owner -> container(owner, parts[1]));

			if (!container.isPresent() || !container.get().maxItems().isPresent()) {
				continue;
			}

			final int maxItems = container.get().maxItems().get();
			for (int i = 0; i < container.get().size(); i++) {
				final ItemContainer.Entry item = container.get().entry(i);
				if (item.slot().orElse(0) >= maxItems) {
					item.setSlot(container.get().freeSlot(-1, i));
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
	private Optional<String> refusal (final SaveModel save) {
		for (final String key : touchedContainers) {
			final String[] parts = key.split("\\|", 2);
			final Optional<SaveObject> owner = save.object(parts[0]);
			if (!owner.isPresent()) {
				continue;
			}

			final Optional<ItemContainer> container = container(owner.get(), parts[1]);
			if (!container.isPresent()) {
				continue;
			}

			final Optional<Integer> maxItems = container.get().maxItems();
			if (maxItems.isPresent() && container.get().size() > maxItems.get()) {
				return Optional.of(String.format(
					"%s would hold %d items, but there is room for %d."
					, containerName(owner.get(), parts[1])
					, container.get().size(), maxItems.get()));
			}

			if (INFINITE_STACKING.contains(parts[1])) {
				continue;
			}

			for (final String item : touchedItems.getOrDefault(key, new HashSet<>())) {
				final int index = container.get().indexOf(item);
				if (index < 0 || index >= container.get().size()) {
					continue;
				}

				final int stack = container.get().entry(index).stackSize();
				final Optional<ItemCatalog.Entry> entry = catalogEntryOf(save, item);
				if (entry.isPresent() && stack > entry.get().maxStack) {
					return Optional.of(String.format(
						"%s stacks to %d, so a stack of %d only fits in the stash."
						, itemName(save, item), entry.get().maxStack, stack));
				}
			}
		}

		for (final Placed put : placed) {
			final Optional<SaveObject> wearer = save.object(put.wearer);
			if (!wearer.isPresent()) {
				continue;
			}

			final Optional<GuidList> slots = equipment(wearer.get(), put.weaponSet);

			// Something later in the same batch may have taken it out again.
			if (!slots.isPresent() || put.index >= slots.get().size()
				|| !put.item.equalsIgnoreCase(slots.get().get(put.index))) {

				continue;
			}

			final String name = itemName(save, put.item);
			final Optional<String> bound = boundTo(save, put.item);
			if (bound.isPresent() && !bound.get().equalsIgnoreCase(put.wearer)) {
				return Optional.of(String.format("%s is soulbound to %s."
					, name, save.object(bound.get())
						.map(InventoryManager::characterName).orElse("someone else")));
			}

			final Optional<ItemCatalog.Entry> entry = catalogEntryOf(save, put.item);

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
			final String partner = partnerIndex < slots.get().size() ? slots.get().get(partnerIndex) : "";
			if (!partner.isEmpty()) {
				final Optional<String> twoHander = isTwoHanded(entry)
					? Optional.of(name)
					: (isTwoHanded(catalogEntryOf(save, partner))
						? Optional.of(itemName(save, partner)) : Optional.empty());

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

	private static String prefabName (final SaveModel save, final String item) {
		return save.object(item)
			.map(object -> object.name() == null ? "" : object.name().replace("(Clone)", "").trim())
			.orElse("");
	}

	private static Optional<ItemCatalog.Entry> catalogEntryOf (final SaveModel save, final String item) {
		final String prefab = prefabName(save, item);
		return prefab.isEmpty() ? Optional.empty() : ItemCatalog.getInstance().lookup(prefab);
	}

	private static String itemName (final SaveModel save, final String item) {
		final Optional<ItemCatalog.Entry> entry = catalogEntryOf(save, item);
		if (entry.isPresent() && !entry.get().name.isEmpty()) {
			return entry.get().name;
		}

		final String prefab = prefabName(save, item);
		return prefab.isEmpty() ? "That item" : prefab.replace('_', ' ');
	}

	/**
	 * Who an item is soulbound to: {@code EquipmentSoulbind.BoundGuid} on the
	 * item's own object, which is the owner's ObjectID. Empty for an item that
	 * is not soulbound, or not yet.
	 */
	private static Optional<String> boundTo (final SaveModel save, final String item) {
		return save.object(item)
			.flatMap(o -> o.component("EquipmentSoulbind"))
			.flatMap(c -> c.get("BoundGuid"))
			.map(String::valueOf)
			.filter(guid -> !guid.isEmpty() && !GuidList.EMPTY.equals(guid));
	}

	private static int intStat (final SaveObject character, final String stat) {
		return character.component("CharacterStats")
			.flatMap(c -> c.get(stat))
			.map(value -> value instanceof Integer ? (Integer) value : 0)
			.orElse(0);
	}

	/**
	 * What the user calls a character: the name the save gives them (the
	 * player's own, or a companion's in the save's language), then the
	 * registry's, then the one inside the object name.
	 */
	static String characterName (final Property character) {
		return SaveObject.of(character).map(InventoryManager::characterName).orElse("That character");
	}

	static String characterName (final SaveObject character) {
		final Optional<String> override = character.component("CharacterStats")
			.flatMap(c -> c.get("OverrideName"))
			.map(value -> value == null ? "" : value.toString().trim())
			.filter(name -> !name.isEmpty());

		if (override.isPresent()) {
			return override.get();
		}

		final String objectName = character.name();
		final Optional<String> companionName = CompanionRegistry.displayNameFor(objectName, "");
		if (companionName.isPresent()) {
			return companionName.get();
		}

		final String inside = EKUtils.extractCharacterName(objectName);
		return inside.isEmpty() ? "That character" : inside.replace('_', ' ');
	}

	private static String containerName (final SaveObject owner, final String component) {
		final String who = characterName(owner);
		switch (component) {
			case "StashInventory":    return "The stash";
			case "QuickbarInventory": return who + "'s quick slots";
			case "CraftingInventory": return "The crafting bag";
			case "QuestInventory":    return "The quest bag";
			default:                  return who + "'s pack";
		}
	}

	/**
	 * Equipping, unequipping, and the swap that happens when you drop an item
	 * onto an occupied slot.
	 *
	 * <p>Worn gear is referenced only by UUID from the wearer's fixed 11-entry
	 * {@code EquipmentSetSerialized} array, so putting something on is really
	 * "unlink it from its pack and write its GUID into slot N" — the item's own
	 * top-level object never moves, it just gets re-parented if it changed
	 * hands. Taking something off is the awkward direction: the pack has no
	 * entry for it, so one has to be manufactured.
	 */
	private boolean applyEquipmentChange (
		final SaveModel save, final SaveObject owner, final Change change)
		throws WriteRefusedException {

		final int slotIndex = change.toEquipmentSlot >= 0
			? change.toEquipmentSlot : change.fromEquipmentSlot;

		if (!change.weaponSet && slotIndex >= EQUIPMENT_SLOTS) {
			logger.error("Equipment slot %d is out of range.%n", slotIndex);
			return false;
		}

		final boolean equipping = change.toEquipmentSlot >= 0;
		final String wearerID = equipping ? change.destCharacter : change.character;
		final Optional<SaveObject> wearer = save.object(wearerID);

		if (!wearer.isPresent()) {
			logger.error("No character '%s' to wear the item.%n", wearerID);
			return false;
		}

		final Optional<GuidList> equipment = equipment(wearer.get(), change.weaponSet);
		if (!equipment.isPresent()) {
			logger.error("Character '%s' has no Equipment component.%n", wearerID);
			return false;
		}

		final GuidList slots = equipment.get();
		if (slotIndex >= slots.size()) {
			logger.error(
				"Equipment only has %d slots; %d requested.%n", slots.size(), slotIndex);

			return false;
		}

		// The pack the item comes from (equip) or goes to (unequip).
		final String packCharacter = equipping ? change.character : change.destCharacter;
		final String packComponent = equipping ? change.component : change.destComponent;
		final Optional<SaveObject> packOwner = save.object(packCharacter);

		if (!packOwner.isPresent()) {
			logger.error("No character '%s' for the pack side.%n", packCharacter);
			return false;
		}

		final Optional<ItemContainer> pack = container(packOwner.get(), packComponent);
		if (!pack.isPresent()) {
			logger.error("Component '%s' has no item lists.%n", packComponent);
			return false;
		}

		final String occupant = slots.get(slotIndex);

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

			final Optional<String> baseItem = prefabPathOf(save, change.itemGuid);
			if (!baseItem.isPresent()) {
				return false;
			}

			pack.get().add(ItemContainer.newEntry(
				save, baseItem.get(), pack.get().freeSlot(change.destSlot, -1), 1), change.itemGuid);
			slots.set(slotIndex, GuidList.EMPTY);

			return change.character.equalsIgnoreCase(change.destCharacter)
				|| reparent(save, change.itemGuid, packOwner.get());
		}

		// Equipping: unlink from the pack, then hand the slot over.
		final int index = pack.get().indexOf(change.itemGuid);
		if (index < 0) {
			logger.error(
				"Item '%s' not found in '%s'.%n", change.itemGuid, packComponent);

			return false;
		}

		final ItemContainer.Entry displacedEntry = pack.get().remove(index);
		slots.set(slotIndex, change.itemGuid);
		placed.add(new Placed(wearerID, change.weaponSet, slotIndex, change.itemGuid));

		// Whatever was already worn falls back into the pack. Reusing the
		// entry we just freed keeps this allocation-free and type-exact.
		if (!occupant.isEmpty() && !occupant.equalsIgnoreCase(change.itemGuid)) {
			final Optional<String> baseItem = prefabPathOf(save, occupant);
			if (!baseItem.isPresent()) {
				return false;
			}

			displacedEntry.setBaseItem(baseItem.get());
			displacedEntry.setStackSize(1);
			displacedEntry.setSlot(pack.get().freeSlot(-1, -1));
			pack.get().add(displacedEntry, occupant);
			touch(packCharacter, packComponent, occupant);

			if (!reparent(save, occupant, packOwner.get())) {
				return false;
			}
		}

		return change.character.equalsIgnoreCase(wearerID)
			|| reparent(save, change.itemGuid, wearer.get());
	}

	/**
	 * Creates an item that isn't in the save at all yet.
	 *
	 * <p>Three things have to appear together: a top-level object carrying a
	 * fresh GUID and the prefab to instantiate, an {@code ItemList} entry, and
	 * the matching UUID in {@code SerializedItemList} (which the game reads as
	 * a GUIDLink and resolves against the persistence manager — hence the
	 * object). The object is deliberately minimal: {@code CreateObject} loads
	 * the prefab from {@code PrefabResource}, so anything not overridden here
	 * comes back as the prefab's own defaults, which is exactly right for a
	 * brand new item. It carries only InstanceID and Persistence: the prefab
	 * brings its Equippable, Weapon or Consumable components back with default
	 * state, which is what a newly created item should have.
	 */
	private boolean addNewItem (
		final SaveModel save, final SaveObject owner, final Change change)
		throws WriteRefusedException {

		final Optional<ItemContainer> container = container(owner, change.destComponent);
		if (!container.isPresent()) {
			logger.error("Component '%s' has no item lists.%n", change.destComponent);
			return false;
		}

		touch(owner.id(), change.destComponent, change.itemGuid);
		request(owner.id(), change.destComponent, change.itemGuid, change.destSlot);

		final Optional<SaveObject> template = findAnyItem(save);
		if (!template.isPresent()) {
			logger.error("This save has no item packet to model a new one on.%n");
			return false;
		}

		final String guid = change.itemGuid;
		final ItemContainer.Entry entry = ItemContainer.newEntry(save, change.newItemPath
			, container.get().freeSlot(change.destSlot, -1), change.stackSize);

		save.mint(template.get()
			, new SaveModel.Identity(change.newItemPrefab + "(Clone)", guid, UUID.fromString(guid)
				, change.newItemPath, owner.name(), null)
			, (type, component) -> "InstanceID".equals(type) || "Persistence".equals(type)
			, (type, copy) -> {});

		container.get().add(entry, guid);
		return true;
	}

	/**
	 * Brings in an item whole -- a loadout's copy, with its enchantments, its
	 * soulbinding and a grimoire's spells -- rather than a bare prefab. It
	 * goes into the holder's container like any new item, and takes the
	 * holder's area and name as its parent.
	 */
	private boolean addCarriedItem (
		final SaveModel save, final SaveObject owner, final Change change)
		throws WriteRefusedException {

		final Optional<ItemContainer> container = container(owner, change.destComponent);
		final Optional<SaveObject> item = SaveObject.of(change.carried);

		if (!container.isPresent() || !item.isPresent()) {
			logger.error("No room in '%s' for an item brought in whole.%n", change.destComponent);
			return false;
		}

		if (!change.itemGuid.equalsIgnoreCase(item.get().id())) {
			logger.error("An item brought in as '%s' is '%s'.%n", change.itemGuid, item.get().id());
			return false;
		}

		item.get().set("Parent", owner.name());
		item.get().set("LevelName", owner.levelName());

		touch(owner.id(), change.destComponent, change.itemGuid);
		request(owner.id(), change.destComponent, change.itemGuid, change.destSlot);
		save.add(item.get());

		final Optional<String> baseItem = change.newItemPath != null
			? Optional.of(change.newItemPath) : prefabPathOf(save, change.itemGuid);

		if (!baseItem.isPresent()) {
			return false;
		}

		container.get().add(ItemContainer.newEntry(save, baseItem.get()
			, container.get().freeSlot(change.destSlot, -1), change.stackSize), change.itemGuid);
		return true;
	}

	/** Any item object the game wrote, used purely for its shape. */
	private static Optional<SaveObject> findAnyItem (final SaveModel save) {
		return save.objects().stream()
			.filter(object -> object.get("PrefabResource")
				.map(prefab -> String.valueOf(prefab).toLowerCase().contains("/items/"))
				.orElse(false))
			.filter(object -> !object.components().isEmpty())
			.findFirst();
	}

	private static Optional<ItemContainer> container (final SaveObject owner, final String component) {
		return owner.component(component).flatMap(Component::itemContainer);
	}

	/** The worn equipment's slots, or the weapon sets'. */
	private static Optional<GuidList> equipment (final SaveObject wearer, final boolean weaponSet) {
		return wearer.component(EQUIPMENT)
			.flatMap(c -> c.guidList(weaponSet ? "WeaponSetsSerialized" : "EquipmentSetSerialized"));
	}

	/**
	 * An item's prefab path, rebuilt from its own object plus the catalog.
	 * A save records something like
	 * {@code Assets/Data/Prefabs/Items/Rings/Ring_PREORDER_Gauns_Pledge.prefab},
	 * and the object's ObjectName gives the exact-case file name. Only that
	 * file name actually matters — {@code GameResources.LoadPrefab} throws the
	 * directory away and lowercases the rest — but keeping the real directory
	 * makes edited saves look like the game wrote them.
	 */
	private static Optional<String> prefabPathOf (final SaveModel save, final String itemGuid) {
		final Optional<SaveObject> item = save.object(itemGuid).filter(object -> object.name() != null);
		if (!item.isPresent()) {
			logger.error("No packet for item '%s'; cannot rebuild its prefab path.%n", itemGuid);
			return Optional.empty();
		}

		final String prefab = item.get().name().replace("(Clone)", "").trim();
		final Optional<ItemCatalog.Entry> entry = ItemCatalog.getInstance().lookup(prefab);

		final String directory = entry
			.map(e -> e.path)
			.filter(p -> !p.isEmpty() && p.lastIndexOf('/') > 0)
			.map(p -> p.substring(0, p.lastIndexOf('/') + 1))
			.orElse("Assets/Data/Prefabs/Items/");

		return Optional.of(directory + prefab + ".prefab");
	}

	private static boolean reparent (
		final SaveModel save, final String itemGuid, final SaveObject newOwner)
		throws WriteRefusedException {

		final Optional<SaveObject> item = save.object(itemGuid);
		if (!item.isPresent()) {
			logger.error("Item '%s' had no standalone packet to reparent.%n", itemGuid);
			return false;
		}

		item.get().set("Parent", newOwner.name());
		return true;
	}
}
