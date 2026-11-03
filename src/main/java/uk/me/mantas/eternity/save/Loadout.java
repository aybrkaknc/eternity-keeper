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

import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.ShortReadException;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One character's gear as a file, and putting it on someone else -- in the
 * same save, or in another playthrough altogether.
 *
 * <p>A loadout is what a character wears, the weapons in their sets and what
 * they keep in their quick slots: not their pack, not what they know. The file
 * is a packet file like a {@code .chr} -- the character's own object first,
 * whose slot lists say which item sat where, then each of those items' own
 * objects, enchantments, soulbinding and a grimoire's spells included -- so
 * nothing about an item is lost and nothing new had to be written to read it.
 *
 * <p>What goes on is always a copy with an ID of its own, so the same file can
 * go on twice, or into the save it came from; what it replaces goes to the
 * stash. Each item is judged on its own against the game's rules, so a
 * paladin's shield left behind for a wizard does not stop the rest:
 * {@code Equipment.HasEquipmentSlot} (no head slot for a godlike, a grimoire
 * slot for a wizard only, a pet slot for the main character only),
 * {@code CharacterStats.MaxWeaponSets} and {@code MaxQuickSlots},
 * {@code Equippable.RestrictedToClass} and soulbinding. {@link InventoryManager}
 * then makes the moves in one go and checks the end state against everything
 * it already checks, so nothing it would refuse can slip through.
 *
 * <p>A copy names none of the other objects its original did. An item whose
 * enchantment grants an ability lists that ability's object by ID
 * ({@code Equippable.AbilityModGuids}), and a pet its summoned creature
 * ({@code Summon.m_summons}); when the game finds none by those IDs it makes
 * its own -- {@code ItemModComponent.Initialize} for an enchantment,
 * {@code Summon.PerformSummoning} for a worn pet, whose creature takes the
 * prefab's own fixed ID -- where a copy in the save it came from would
 * otherwise have tied two items to one object.
 *
 * <p>The game knows the main character and every companion by the same ID in
 * every playthrough, so gear soulbound to the Watcher goes on the Watcher of
 * another playthrough, and a refusal names the owner as the save the gear is
 * going into knows them.
 */
public final class Loadout {
	private static final Logger logger = Logger.getLogger(Loadout.class);

	/** What a loadout file is called. */
	public static final String EXTENSION = "loadout";

	private static final String WORLD = "MobileObjects.save";
	private static final String STASH = "StashInventory";
	private static final String QUICK = "QuickbarInventory";
	private static final String EMPTY_GUID = "00000000-0000-0000-0000-000000000000";

	// EquipmentSet.SerializedEquipment's order, as the Inventory tab names it.
	static final String[] SLOT_NAMES = {
		"head", "neck", "chest", "hands", "right-hand ring", "left-hand ring"
		, "cape", "feet", "waist", "grimoire", "pet"};
	private static final int HEAD = 0;
	private static final int CAPE = 6;
	private static final int GRIMOIRE = 9;
	private static final int PET = 10;
	static final String[] SETS = {"I", "II", "III", "IV"};

	/** Where an item goes: a worn slot, a hand of a weapon set, a quick slot. */
	public enum Kind { WORN, WEAPON, QUICK }

	/** One item of the loadout, and the slot it came from. */
	public static final class Slot {
		public final Kind kind;
		/** The worn slot (0-10), the hand (0-7, two to a set) or the quick slot's tile. */
		public final int index;
		/** Its ID in the file. */
		public final String guid;
		/** "Ring_PREORDER_Gauns_Pledge". */
		public final String prefab;
		/** The prefab's path as an inventory entry records it, where the file had one. */
		public final String baseItem;
		public final int stack;
		/** Whom it is soulbound to, or empty. */
		public final String boundTo;

		Slot (
			final Kind kind, final int index, final String guid, final String prefab
			, final String baseItem, final int stack, final String boundTo) {

			this.kind = kind;
			this.index = index;
			this.guid = guid;
			this.prefab = prefab;
			this.baseItem = baseItem;
			this.stack = stack;
			this.boundTo = boundTo;
		}

		/** "chest", "weapon set II, off hand", "quick slot 3". */
		public String place () {
			switch (kind) {
				case WORN:
					return index < SLOT_NAMES.length ? SLOT_NAMES[index] : "slot " + (index + 1);
				case WEAPON:
					return "weapon set " + (index / 2 < SETS.length ? SETS[index / 2] : index / 2 + 1)
						+ (index % 2 == 0 ? ", main hand" : ", off hand");
				default:
					return "quick slot " + (index + 1);
			}
		}
	}

	/** One item against one character: whether it goes on, and what it replaces. */
	public static final class Fit {
		public final Slot slot;
		/** Why it cannot go on, or null when it can. */
		public final String reason;
		/** What it takes the place of, which goes to the stash, or null. */
		public final String replaces;

		Fit (final Slot slot, final String reason, final String replaces) {
			this.slot = slot;
			this.reason = reason;
			this.replaces = replaces;
		}
	}

	/** Putting a loadout on one character, slot by slot. */
	public static final class Plan {
		public final List<Fit> fits;
		/**
		 * What comes off a weapon set the loadout puts on whole, to the stash:
		 * a hand the loadout leaves empty, so the set ends up as it was saved.
		 */
		public final List<String> cleared;

		Plan (final List<Fit> fits, final List<String> cleared) {
			this.fits = Collections.unmodifiableList(fits);
			this.cleared = Collections.unmodifiableList(cleared);
		}

		/** How many items go on. */
		public int fitting () {
			int fitting = 0;
			for (final Fit fit : fits) {
				if (fit.reason == null) {
					fitting++;
				}
			}

			return fitting;
		}
	}

	private final File file;
	public final String characterId;
	public final String characterName;
	public final String characterClass;
	public final List<Slot> slots;

	private Loadout (
		final File file, final String characterId, final String characterName
		, final String characterClass, final List<Slot> slots) {

		this.file = file;
		this.characterId = characterId;
		this.characterName = characterName;
		this.characterClass = characterClass;
		this.slots = Collections.unmodifiableList(slots);
	}

	// ---------------------------------------------------------------- saving

	/**
	 * Writes {@code characterId}'s gear in the save to {@code file}.
	 *
	 * @return how many items it holds; 0 when they wear and hold nothing, and
	 *         then no file is written
	 * @throws ShortReadException when the save could be read only in part
	 */
	public static int save (final File saveDirectory, final String characterId, final File file)
		throws IOException {

		final DeserializedPackets read = worldOf(saveDirectory);
		final SaveModel save = SaveModel.of(read);
		final SaveObject character = save.object(characterId)
			.orElseThrow(() -> new IOException("There is no such character in this save."));

		final List<Property> objects = new ArrayList<>();
		objects.add(character.property());
		for (final String guid : gearOf(character.property())) {
			save.object(guid)
				.map(SaveObject::property)
				.filter(item -> !objects.contains(item))
				.ifPresent(objects::add);
		}

		if (objects.size() < 2) {
			return 0;
		}

		// Into a sibling first: the serializer appends (invariant 13), and a
		// write that fails half-way must not leave something that reads as a
		// loadout, nor take an older file of the same name with it.
		final File directory = file.getAbsoluteFile().getParentFile();
		final File partial = File.createTempFile("EK-loadout", ".partial", directory);
		try {
			// The save's own leading count, which the write sets to what the
			// file holds; the save is never written from this reading.
			new DeserializedPackets(objects, read.getCount()).reserialize(partial);

			Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} finally {
			if (partial.exists() && !partial.delete()) {
				logger.error("Unable to remove %s.%n", partial.getAbsolutePath());
			}
		}

		return objects.size() - 1;
	}

	/** Every item ID a character wears, holds or keeps in a quick slot, in that order. */
	private static List<String> gearOf (final Property character) {
		final List<String> gear = new ArrayList<>();
		gear.addAll(guids(variable(character, "Equipment", "EquipmentSetSerialized")));
		gear.addAll(guids(variable(character, "Equipment", "WeaponSetsSerialized")));
		gear.addAll(guids(variable(character, QUICK, "SerializedItemList")));
		gear.removeIf(String::isEmpty);
		return gear;
	}

	// --------------------------------------------------------------- reading

	/** Reads a loadout file; anything else is refused in words. */
	public static Loadout read (final File file) throws IOException {
		if (!file.isFile()) {
			throw new FileNotFoundException(file.getAbsolutePath());
		}

		final Optional<DeserializedPackets> read;
		try {
			read = new PacketDeserializer(file).deserialize();
		} catch (final ShortReadException e) {
			throw new IOException(file.getName() + " is damaged: only part of it could be read.");
		} catch (final RuntimeException e) {
			throw notALoadout(file);
		}

		if (!read.isPresent() || read.get().getPackets().size() < 2) {
			throw notALoadout(file);
		}

		final SaveModel packets = SaveModel.of(read.get());
		final Property character = read.get().getPackets().get(0);
		final Optional<SaveObject> object = SaveObject.of(character);
		if (!object.isPresent()
			|| !object.get().component("Equipment").isPresent()
			|| !object.get().component("CharacterStats").isPresent()) {

			throw notALoadout(file);
		}

		final List<Slot> slots = new ArrayList<>();
		final List<String> worn = guids(variable(character, "Equipment", "EquipmentSetSerialized"));
		for (int i = 0; i < worn.size(); i++) {
			slot(packets, Kind.WORN, i, worn.get(i), null, 1).ifPresent(slots::add);
		}

		final List<String> held = guids(variable(character, "Equipment", "WeaponSetsSerialized"));
		for (int i = 0; i < held.size(); i++) {
			slot(packets, Kind.WEAPON, i, held.get(i), null, 1).ifPresent(slots::add);
		}

		final List<String> quick = guids(variable(character, QUICK, "SerializedItemList"));
		final List<Property> entries = items(variable(character, QUICK, "ItemList"));
		for (int i = 0; i < quick.size() && i < entries.size(); i++) {
			final Property entry = entries.get(i);
			slot(packets, Kind.QUICK, intField(entry, "uiSlot", i), quick.get(i)
				, textField(entry, "BaseItem"), intField(entry, "stackSize", 1)).ifPresent(slots::add);
		}

		slots.sort((a, b) -> a.kind != b.kind ? a.kind.compareTo(b.kind) : Integer.compare(a.index, b.index));
		return new Loadout(file, key(object.get().id()), InventoryManager.characterName(object.get())
			, enumName(variable(character, "CharacterStats", "CharacterClass")), slots);
	}

	private static IOException notALoadout (final File file) {
		return new IOException(file.getName() + " is not a loadout: it holds no character's gear.");
	}

	private static Optional<Slot> slot (
		final SaveModel packets, final Kind kind, final int index, final String guid
		, final String baseItem, final int stack) {

		if (guid.isEmpty()) {
			return Optional.empty();
		}

		final Optional<SaveObject> item = packets.object(guid);
		if (!item.isPresent()) {
			return Optional.empty();
		}

		final String prefab = item.get().name() == null ? "" : item.get().name().replace("(Clone)", "").trim();
		final String bound = item.get().component("EquipmentSoulbind")
			.flatMap(soulbind -> soulbind.get("BoundGuid"))
			.map(String::valueOf)
			.filter(value -> !EMPTY_GUID.equals(value))
			.orElse("");

		return Optional.of(new Slot(kind, index, key(guid), prefab
			, baseItem == null || baseItem.isEmpty() ? null : baseItem, stack, key(bound)));
	}

	// --------------------------------------------------------------- planning

	/** What putting this on {@code characterId} would do, slot by slot. */
	public Plan plan (final List<Property> packets, final String targetId) {
		final SaveModel save = SaveModel.of(packets);
		final SaveObject wearer = save.object(targetId)
			.orElseThrow(() -> new IllegalArgumentException("There is no such character in this save."));

		final Property target = wearer.property();
		final String id = key(wearer.id());
		final boolean player = wearer.isPlayer();
		final String race = enumName(variable(target, "CharacterStats", "CharacterRace"));
		final String characterClass = enumName(variable(target, "CharacterStats", "CharacterClass"));
		final int weaponSets = 2 + intValue(variable(target, "CharacterStats", "BonusWeaponSets"));
		final int quickSlots = 4 + intValue(variable(target, "CharacterStats", "BonusQuickSlots"));

		final List<String> worn = guids(variable(target, "Equipment", "EquipmentSetSerialized"));
		final List<String> held = guids(variable(target, "Equipment", "WeaponSetsSerialized"));
		final Map<Integer, String> quick = tiles(target);

		final List<Fit> fits = new ArrayList<>();
		for (final Slot slot : slots) {
			String reason = null;
			switch (slot.kind) {
				case WORN:
					if (slot.index == HEAD && "Godlike".equals(race)) {
						reason = "no head slot (godlike)";
					} else if (slot.index == GRIMOIRE && !"Wizard".equals(characterClass)) {
						reason = "only wizards have a grimoire slot";
					} else if (slot.index == PET && !player) {
						reason = "only the main character has a pet slot";
					} else if (slot.index >= worn.size()) {
						reason = "no such slot";
					} else if (slot.index == CAPE) {
						reason = "the game no longer uses the cape slot";
					}
					break;

				case WEAPON:
					if (slot.index / 2 >= weaponSets || slot.index >= held.size()) {
						reason = "weapon set " + (slot.index / 2 < SETS.length ? SETS[slot.index / 2]
							: String.valueOf(slot.index / 2 + 1)) + " is locked";
					}
					break;

				default:
					if (slot.index >= quickSlots) {
						reason = "quick slot " + (slot.index + 1) + " is locked";
					}
			}

			if (reason == null) {
				reason = classReason(slot, characterClass);
			}

			// The main character and every companion have the same ID in every
			// playthrough, so the owner is named as this save knows them.
			if (reason == null && !slot.boundTo.isEmpty() && !slot.boundTo.equals(id)) {
				reason = "soulbound to " + save.object(slot.boundTo)
					.map(InventoryManager::characterName)
					.orElse(slot.boundTo.equals(this.characterId) ? characterName : "someone else");
			}

			String replaces = null;
			if (reason == null) {
				final String occupant = slot.kind == Kind.WORN ? worn.get(slot.index)
					: slot.kind == Kind.WEAPON ? held.get(slot.index)
					: quick.getOrDefault(slot.index, "");
				replaces = occupant.isEmpty() ? null : occupant;
			}

			fits.add(new Fit(slot, reason, replaces));
		}

		// A set goes on whole: a hand the loadout leaves empty comes empty, so
		// a two-hander never ends up beside someone else's shield.
		final List<String> cleared = new ArrayList<>();
		for (int set = 0; set * 2 + 1 < held.size(); set++) {
			final boolean[] filled = new boolean[2];
			boolean goesOn = false;
			for (final Fit fit : fits) {
				if (fit.slot.kind == Kind.WEAPON && fit.slot.index / 2 == set && fit.reason == null) {
					filled[fit.slot.index % 2] = true;
					goesOn = true;
				}
			}

			for (int hand = 0; goesOn && hand < 2; hand++) {
				if (!filled[hand] && !held.get(set * 2 + hand).isEmpty()) {
					cleared.add(held.get(set * 2 + hand));
				}
			}
		}

		return new Plan(fits, cleared);
	}

	/** {@code Equippable.RestrictedToClass}, from the item catalog. */
	private static String classReason (final Slot slot, final String characterClass) {
		final List<String> classes = ItemCatalog.getInstance().lookup(slot.prefab)
			.map(entry -> entry.classes)
			.orElse(Collections.emptyList());

		if (classes.isEmpty() || classes.contains(characterClass)) {
			return null;
		}

		final List<String> plural = new ArrayList<>();
		classes.forEach(name -> plural.add(name.toLowerCase(Locale.ROOT) + "s"));
		final String list = plural.size() == 1 ? plural.get(0)
			: String.join(", ", plural.subList(0, plural.size() - 1)) + " and " + plural.get(plural.size() - 1);
		return "for " + list + " only";
	}

	// ----------------------------------------------------------- putting it on

	/**
	 * Puts on {@code characterId} every item {@link #plan} says can go, as
	 * copies: in through the stash, then on, with whatever they replace
	 * left in the stash.
	 *
	 * @return what stopped it, in words, or nothing when it went on
	 */
	public Optional<String> putOn (final File saveDirectory, final String targetId) throws IOException {
		final List<Property> packets = worldOf(saveDirectory).getPackets();
		final Plan plan = plan(packets, targetId);
		if (plan.fitting() == 0) {
			return Optional.of("None of this loadout can go on that character.");
		}

		final SaveModel save = SaveModel.of(packets);
		final Optional<SaveObject> player = save.player();
		if (!player.isPresent()) {
			return Optional.of("This save has no main character, so no stash to put things in.");
		}

		final String stashHolder = player.get().id();
		final Property wearer = save.object(targetId).get().property();
		final String target = save.object(targetId).get().id();

		// The items come from the file afresh each time: what goes on is a
		// copy, and two calls must not hand the save the same objects twice.
		final List<Property> fromFile = new PacketDeserializer(file).deserialize()
			.orElseThrow(() -> notALoadout(file)).getPackets();

		final List<InventoryManager.Change> carryIn = new ArrayList<>();
		final List<InventoryManager.Change> takeOff = new ArrayList<>();
		final List<InventoryManager.Change> putOn = new ArrayList<>();

		for (final Fit fit : plan.fits) {
			if (fit.reason != null) {
				continue;
			}

			final Slot slot = fit.slot;
			final String fresh = UUID.randomUUID().toString();
			final Property copy = copyOf(fromFile, slot.guid, fresh)
				.orElseThrow(() -> new IOException(file.getName() + " has lost one of its items."));

			final InventoryManager.Change in = new InventoryManager.Change(
				stashHolder, STASH, fresh, slot.stack, stashHolder, STASH, -1);
			in.carried = copy;
			in.newItemPath = slot.baseItem;
			carryIn.add(in);

			switch (slot.kind) {
				case WORN:
					putOn.add(InventoryManager.Change.equip(stashHolder, STASH, fresh, target, slot.index));
					break;

				case WEAPON:
					putOn.add(InventoryManager.Change.equipWeapon(stashHolder, STASH, fresh, target, slot.index));
					break;

				default:
					// A quick slot takes no swap: what is there goes first.
					if (fit.replaces != null) {
						takeOff.add(new InventoryManager.Change(target, QUICK, fit.replaces
							, stackOn(wearer, fit.replaces), stashHolder, STASH, -1));
					}

					putOn.add(new InventoryManager.Change(
						stashHolder, STASH, fresh, slot.stack, target, QUICK, slot.index));
			}
		}

		final List<String> held = guids(variable(wearer, "Equipment", "WeaponSetsSerialized"));
		for (final String cleared : plan.cleared) {
			takeOff.add(InventoryManager.Change.unequipWeapon(
				target, held.indexOf(cleared), cleared, stashHolder, STASH, -1));
		}

		final List<InventoryManager.Change> changes = new ArrayList<>(carryIn);
		changes.addAll(takeOff);
		changes.addAll(putOn);

		final InventoryManager manager = new InventoryManager(saveDirectory);
		if (manager.apply(changes)) {
			logger.info("Put %d of %s's items on %s.%n", plan.fitting(), characterName, target);
			return Optional.empty();
		}

		return Optional.of(manager.problem()
			.orElse("The loadout could not be put on. Details are in eternity.log; Settings shows where it is."));
	}

	/**
	 * One of the file's items as a new object: its ID rewritten wherever the
	 * object names it, and the other objects it named forgotten (see the
	 * class comment).
	 */
	private static Optional<Property> copyOf (final List<Property> fromFile, final String guid, final String fresh) {
		final Optional<Property> item = SaveModel.of(fromFile).object(guid).map(SaveObject::property);
		if (!item.isPresent()) {
			return Optional.empty();
		}

		final Map<String, UUID> remap = new HashMap<>();
		remap.put(guid, UUID.fromString(fresh));
		GuidRemap.rewrite(Collections.singletonList(item.get()), remap);

		// Equippable, Weapon, Shield and the rest each name their mod
		// abilities, and a Summon its creatures.
		for (final Object component : components(item.get())) {
			for (final String other : new String[] {"AbilityModGuids", "m_summons"}) {
				final Optional<Property> list = ((ComplexProperty) component)
					.<DictionaryProperty>findProperty("Variables")
					.flatMap(variables -> variables.<Property>findEntry(other));

				if (list.isPresent() && list.get() instanceof CollectionProperty) {
					((CollectionProperty) list.get()).items.clear();
				}
			}
		}

		return item;
	}

	// ------------------------------------------------------------------ reading

	/** A save's world state, whole, or a refusal saying why not. */
	private static DeserializedPackets worldOf (final File saveDirectory) throws IOException {
		final File world = new File(saveDirectory, WORLD);
		if (!world.isFile()) {
			throw new FileNotFoundException(world.getAbsolutePath());
		}

		return new PacketDeserializer(world).deserialize()
			.orElseThrow(() -> new IOException("The save's world state could not be read."));
	}

	private static List<?> components (final Property packet) {
		final Optional<Property> list = ((ComplexProperty) packet).findProperty("ComponentPackets");
		return list.isPresent() && list.get() instanceof SingleDimensionalArrayProperty
			? ((SingleDimensionalArrayProperty) list.get()).items : Collections.emptyList();
	}

	/** A component's variable, or null. */
	static Property variable (final Property packet, final String component, final String name) {
		return SaveObject.of(packet)
			.flatMap(o -> o.component(component))
			.flatMap(c -> c.variable(name))
			.orElse(null);
	}

	private static List<Property> items (final Property list) {
		return list instanceof CollectionProperty ? ((CollectionProperty) list).items : Collections.emptyList();
	}

	/** A list of IDs, lower case, empty for an empty slot. */
	private static List<String> guids (final Property list) {
		final List<String> guids = new ArrayList<>();
		for (final Property item : items(list)) {
			final String guid = item == null || item.obj == null ? "" : key(item.obj.toString());
			guids.add(EMPTY_GUID.equals(guid) ? "" : guid);
		}

		return guids;
	}

	/** A character's quick items by their tile. */
	private static Map<Integer, String> tiles (final Property character) {
		final List<String> ids = guids(variable(character, QUICK, "SerializedItemList"));
		final List<Property> entries = items(variable(character, QUICK, "ItemList"));
		final Map<Integer, String> tiles = new LinkedHashMap<>();
		for (int i = 0; i < ids.size() && i < entries.size(); i++) {
			tiles.put(intField(entries.get(i), "uiSlot", i), ids.get(i));
		}

		return tiles;
	}

	private static int stackOn (final Property character, final String guid) {
		final List<String> ids = guids(variable(character, QUICK, "SerializedItemList"));
		final List<Property> entries = items(variable(character, QUICK, "ItemList"));
		final int index = ids.indexOf(key(guid));
		return index < 0 || index >= entries.size() ? 1 : intField(entries.get(index), "stackSize", 1);
	}

	// stackSize, not StackSize: the two are serialized separately (invariant 14).
	private static int intField (final Property entry, final String name, final int otherwise) {
		if (!(entry instanceof ComplexProperty)) {
			return otherwise;
		}

		for (final Property field : ((ComplexProperty) entry).properties) {
			if (field != null && name.equals(field.name) && field.obj instanceof Integer) {
				return (Integer) field.obj;
			}
		}

		return otherwise;
	}

	private static String textField (final Property entry, final String name) {
		if (entry instanceof ComplexProperty) {
			for (final Property field : ((ComplexProperty) entry).properties) {
				if (field != null && name.equals(field.name) && field.obj != null) {
					return field.obj.toString();
				}
			}
		}

		return null;
	}

	private static int intValue (final Property property) {
		return property != null && property.obj instanceof Integer ? (Integer) property.obj : 0;
	}

	private static String enumName (final Property property) {
		return property == null || property.obj == null ? ""
			: property.obj instanceof Enum ? ((Enum<?>) property.obj).name() : property.obj.toString();
	}

	private static String key (final String guid) {
		return guid == null ? "" : guid.toLowerCase(Locale.ROOT);
	}
}
