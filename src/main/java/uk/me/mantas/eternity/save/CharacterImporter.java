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

import org.json.JSONObject;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.factory.PacketDeserializerFactory;
import uk.me.mantas.eternity.save.model.Component;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.MultiDimensionalArrayProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.ReferenceTargetProperty;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static uk.me.mantas.eternity.game.UnityEngine.Vector3;

public class CharacterImporter {
	private static final Logger logger = Logger.getLogger(CharacterImporter.class);
	private final File opened;
	private final boolean savedYet;
	private final File chrFile;
	private final PacketDeserializerFactory packetDeserializer;

	public CharacterImporter (final String request, final String chrFilePath)
		throws FileNotFoundException {

		final Environment environment = Environment.getInstance();
		final JSONObject json = new JSONObject(request);
		opened = new File(json.getString("oldSave"));
		savedYet = json.getBoolean("savedYet");

		if (!saveFile().exists()) {
			throw new FileNotFoundException(saveFile().getAbsolutePath());
		}

		final File chrFile = new File(chrFilePath);
		if (!chrFile.exists()) {
			throw new FileNotFoundException(chrFilePath);
		}

		this.chrFile = chrFile;
		packetDeserializer = environment.factory().packetDeserializer();
	}

	/**
	 * The save as it stands now: the one the list opened, the private copy an
	 * edit made of it, or the one last written. Asking for a conflict only
	 * reads; the import itself is what makes the copy.
	 */
	public File saveFile () {
		return Environment.getInstance().state().workingSave().forReading(opened, savedYet);
	}

	// Describes a character in the CHR file that already exists in the target
	// save and would be duplicated by a plain import.
	public static final class ImportConflict {
		public final String characterName;
		public final boolean mainCharacter;

		ImportConflict (final String characterName, final boolean mainCharacter) {
			this.characterName = characterName;
			this.mainCharacter = mainCharacter;
		}
	}

	public Optional<ImportConflict> detectConflict () throws IOException {
		final Optional<DeserializedPackets> chrDeserialized =
			packetDeserializer.forFile(chrFile).deserialize();

		if (!chrDeserialized.isPresent()) {
			return Optional.empty();
		}

		final Optional<SaveObject> character = findCharacter(SaveModel.of(chrDeserialized.get()));
		if (!character.isPresent()) {
			return Optional.empty();
		}

		final File mobileObjectsFile = new File(saveFile(), "MobileObjects.save");
		final Optional<DeserializedPackets> deserialized =
			packetDeserializer.forFile(mobileObjectsFile).deserialize();

		if (!deserialized.isPresent()) {
			return Optional.empty();
		}

		return findExistingMatch(character.get(), SaveModel.of(deserialized.get()))
			.map(existing -> new ImportConflict(
				EKUtils.extractCharacterName(character.get().name())
				, character.get().isPlayer()));
	}

	public boolean importCharacter () throws IOException {
		return doImport(false);
	}

	public boolean overwriteCharacter () throws IOException {
		return doImport(true);
	}

	private boolean doImport (final boolean overwrite) throws IOException {
		final PacketDeserializer chrDeserializer = packetDeserializer.forFile(chrFile);
		final Optional<DeserializedPackets> chrDeserialized = chrDeserializer.deserialize();
		if (!chrDeserialized.isPresent()) {
			return false;
		}

		List<Property> chrObjects = chrDeserialized.get().getPackets();
		if (chrObjects.size() < 1) {
			return false;
		}

		final File editing = Environment.getInstance().state().workingSave()
			.forEditing(opened, savedYet);
		final File mobileObjectsFile = new File(editing, "MobileObjects.save");
		final PacketDeserializer deserializer = packetDeserializer.forFile(mobileObjectsFile);
		final Optional<DeserializedPackets> deserialized = deserializer.deserialize();
		if (!deserialized.isPresent()) {
			logger.error("Unable to deserialize MobileObjects.save.%n");
			return false;
		}

		final List<Property> mobileObjects = deserialized.get().getPackets();
		if (mobileObjects.size() < 1) {
			logger.error("No objects in MobileObjects.save.%n");
			return false;
		}

		final SaveModel save = SaveModel.of(mobileObjects);
		final Optional<SaveObject> found = findCharacter(SaveModel.of(chrObjects));
		if (!found.isPresent()) {
			logger.error("Unable to find character in CHR file.%n");
			return false;
		}

		final SaveObject character = found.get();
		final Optional<SaveObject> existingMatch = overwrite
			? findExistingMatch(character, save)
			: Optional.empty();

		List<Property> retainedObjects = mobileObjects;
		boolean keepsPartysBelongings = false;
		final String levelName;
		final Vector3 location;

		if (existingMatch.isPresent()) {
			// Overwrite: the imported character replaces the existing one
			// (and everything it owns) and takes its exact position. Its
			// identity is kept so no GUID regeneration happens.
			final SaveObject existing = existingMatch.get();

			// The stash, the quest items and the crafting materials hang off
			// the main character alone, but they are the party's: the save
			// keeps its own, and what the file's Watcher had in them stays
			// behind in the playthrough it came from.
			final Set<String> partys = new HashSet<>();
			if (existing.isPlayer()) {
				if (!movable(existing) || !movable(character)) {
					return false;
				}

				partys.addAll(partysBelongings(existing));
				final Set<String> left = partysBelongings(character);
				final List<Property> brought = new ArrayList<>();
				for (final Property object : chrObjects) {
					final String id = idOf(object);
					if (id == null || !left.contains(id.toLowerCase())) {
						brought.add(object);
					}
				}

				chrObjects = brought;
				keepsPartysBelongings = true;

				// The game keeps the playthrough's ID on the main character,
				// and names the save's autosaves by it. A Watcher from another
				// playthrough brings that one's: the save stays this one.
				final Optional<Object> session = sessionID(existing);
				if (session.isPresent() && sessionID(character).isPresent()) {
					character.component("Player").get().set("SessionID", session.get());
				}
			}

			retainedObjects = removeCharacterObjects(mobileObjects, existing, partys);
			levelName = (String) existing.get("LevelName").orElse(null);
			location = existing.location();
		} else {
			// Plain import: we need a character in the existing save to
			// 'anchor' the imported character to. Just naively try to put the
			// character somewhere near the anchor point.
			final Optional<SaveObject> anchorPoint = save.player();
			if (!anchorPoint.isPresent()) {
				logger.error("Unable to find anchor point in save.%n");
				return false;
			}

			levelName = (String) anchorPoint.get().get("LevelName").orElse(null);
			final Vector3 anchor = anchorPoint.get().location();
			location = new Vector3();
			location.x = anchor.x + 1;
			location.y = anchor.y + 1;
			location.z = anchor.z;

			// Need to regenerate the GUID since it seems to be the same for
			// all player characters.
			final UUID newGUID = UUID.randomUUID();
			character.set("GUID", newGUID);
			character.set("ObjectID", newGUID.toString());
		}

		// Everything else the file brings keeps the IDs it had in the save it
		// came from. Into another playthrough those are unique; into another
		// save of the same one they need not be -- an item handed to someone
		// else since the export, both copies of a character kept -- and each
		// object would exist twice under one ID, which the game drops both of.
		// The file's copy gets a fresh ID and its lists and slots follow it;
		// what the save keeps is left as it is. What names the character itself
		// is left alone too: a companion's ID is the fixed GUID the game knows
		// the companion by, and each ability's Owner is that.
		final Set<String> taken = new HashSet<>();
		for (final SaveObject object : SaveModel.of(retainedObjects).objects()) {
			if (object.id() != null) {
				taken.add(object.id().toLowerCase());
			}
		}

		// The save's areas hold objects too: something of theirs sold since the
		// export is in the store's area under the ID the file still has for it.
		final List<String> brought = new ArrayList<>();
		for (final SaveObject object : SaveModel.of(chrObjects).objects()) {
			brought.add(object.id());
		}

		taken.addAll(GuidRemap.heldByAreas(editing, brought));

		final Map<String, UUID> remap = new LinkedHashMap<>();
		for (final SaveObject object : SaveModel.of(chrObjects).objects()) {
			final String id = object.id();
			if (object.property() != character.property() && id != null && taken.contains(id.toLowerCase())) {
				remap.put(id, UUID.randomUUID());
			}
		}

		if (!remap.isEmpty()) {
			logger.info("Gave %d imported objects IDs of their own (%d references).%n"
				, remap.size(), GuidRemap.rewrite(chrObjects, remap));
		}

		// Only now: the save's containers name the save's own objects, which
		// keep their IDs, and the remap above is for the file's.
		if (keepsPartysBelongings) {
			keepPartysBelongings(existingMatch.get(), character, retainedObjects);
		}

		character.moveTo(levelName, location);

		final SimpleProperty simpleObjCount = deserialized.get().getCount();
		final int count = (int) simpleObjCount.obj;
		final int removed = mobileObjects.size() - retainedObjects.size();
		Property.update(simpleObjCount, count - removed + chrObjects.size());

		final List<Property> totalObjects = new ArrayList<>();
		totalObjects.addAll(chrObjects);
		totalObjects.addAll(retainedObjects);

		deserialized.get().setPackets(totalObjects);
		deserialized.get().replace(mobileObjectsFile);

		return true;
	}

	// Finds a character already in the save that the CHR character would
	// duplicate. Main characters match any other main character (there can
	// only be one Player_ object); companions have fixed GUIDs across saves
	// so they match by ObjectID.
	private static Optional<SaveObject> findExistingMatch (
		final SaveObject character, final SaveModel save) {

		if (character.isPlayer()) {
			return save.player();
		}

		return character.id() == null ? Optional.empty() : save.object(character.id());
	}

	// Everything but the character and what they own; what {@code kept} names
	// (lower case) stays.
	private static List<Property> removeCharacterObjects (
		final List<Property> mobileObjects
		, final SaveObject character
		, final Set<String> kept) {

		final List<Property> retained = new ArrayList<>();
		for (final Property property : mobileObjects) {
			if (property == character.property()) {
				continue;
			}

			final Optional<SaveObject> object = SaveObject.of(property);
			if (object.isPresent()
				&& character.name() != null
				&& character.name().equals(object.get().parent())
				&& (object.get().id() == null || !kept.contains(object.get().id().toLowerCase()))) {

				continue;
			}

			retained.add(property);
		}

		return retained;
	}

	// The party's containers, on the main character. Their money is on
	// PlayerInventory, whose pack is their own.
	private static final List<String> PARTYS =
		Arrays.asList("StashInventory", "QuestInventory", "CraftingInventory");

	// The IDs (lower case) the party's containers on a main character list.
	private static Set<String> partysBelongings (final SaveObject character) {
		final Set<String> ids = new HashSet<>();
		for (final String container : PARTYS) {
			character.component(container)
				.flatMap(component -> component.variable("SerializedItemList"))
				.filter(list -> list instanceof CollectionProperty)
				.ifPresent(list -> {
					for (final Property item : ((CollectionProperty) list).items) {
						if (item instanceof SimpleProperty && ((SimpleProperty) item).value != null) {
							ids.add(((SimpleProperty) item).value.toString().toLowerCase());
						}
					}
				});
		}

		return ids;
	}

	// The containers move from one character's packet to another's whole. A
	// packet is written with the reference IDs it was read with, so one that
	// carried a shared reference would bring an ID the other packet may use
	// for something else. None of thirteen saves measured has one
	// (2026-10-27); a save that does is refused rather than written wrong.
	private static boolean movable (final SaveObject character) {
		for (final String container : PARTYS) {
			final Optional<Component> component = character.component(container);
			if (!component.isPresent()) {
				logger.error("The main character has no %s.%n", container);
				return false;
			}

			if (sharesReference(component.get().property())) {
				logger.error("The main character's %s shares a reference; not moving it.%n", container);
				return false;
			}
		}

		return currency(character).isPresent();
	}

	// The save's containers, and what they hold, go to the newcomer; so does
	// the save's money.
	private static void keepPartysBelongings (
		final SaveObject existing, final SaveObject newcomer, final List<Property> retained)
		throws WriteRefusedException {

		for (final String container : PARTYS) {
			newcomer.replace(newcomer.component(container).get(), existing.component(container).get());
		}

		final Property money = Property.find(currency(existing).get(), "v").get();
		Property.update(currency(newcomer).get(), "v", money.obj);

		// The camping supplies sit beside the money, an int; a save that
		// records none keeps none.
		final Optional<Object> supplies =
			existing.component("PlayerInventory").flatMap(inventory -> inventory.get("campingSupplies"));
		final Component theirs = newcomer.component("PlayerInventory").get();
		if (supplies.isPresent() && theirs.get("campingSupplies").isPresent()) {
			theirs.set("campingSupplies", supplies.get());
		}

		final Set<String> partys = partysBelongings(existing);
		for (final SaveObject object : SaveModel.of(retained).objects()) {
			if (object.id() != null && partys.contains(object.id().toLowerCase())) {
				object.set("Parent", newcomer.name());
			}
		}
	}

	private static Optional<ComplexProperty> currency (final SaveObject character) {
		return character.component("PlayerInventory")
			.flatMap(inventory -> inventory.variable("currencyTotalValue"))
			.filter(value -> value instanceof ComplexProperty)
			.map(value -> (ComplexProperty) value);
	}

	private static boolean sharesReference (final Property property) {
		if (property == null) {
			return false;
		}

		if (property instanceof ReferenceTargetProperty
			&& ((ReferenceTargetProperty) property).reference != null) {

			return true;
		}

		if (property instanceof SingleDimensionalArrayProperty) {
			for (final Object item : ((SingleDimensionalArrayProperty) property).items) {
				if (item instanceof Property && sharesReference((Property) item)) {
					return true;
				}
			}
		}

		if (property instanceof MultiDimensionalArrayProperty) {
			for (final MultiDimensionalArrayProperty.MultiDimensionalArrayItem item
				: ((MultiDimensionalArrayProperty) property).items) {

				if (item != null && sharesReference(item.value)) {
					return true;
				}
			}
		}

		if (property instanceof CollectionProperty) {
			for (final Property item : ((CollectionProperty) property).items) {
				if (sharesReference(item)) {
					return true;
				}
			}
		}

		if (property instanceof DictionaryProperty) {
			for (final Map.Entry<Property, Property> entry : ((DictionaryProperty) property).items) {
				if (entry != null && (sharesReference(entry.getKey()) || sharesReference(entry.getValue()))) {
					return true;
				}
			}
		}

		if (property instanceof ComplexProperty) {
			for (final Property child : ((ComplexProperty) property).properties) {
				if (sharesReference(child)) {
					return true;
				}
			}
		}

		return false;
	}

	// The Player component's SessionID, on the main character's packet.
	private static Optional<Object> sessionID (final SaveObject character) {
		return character.component("Player")
			.flatMap(player -> player.get("SessionID"))
			.filter(value -> value instanceof UUID);
	}

	private static Optional<SaveObject> findCharacter (final SaveModel chr) {
		return chr.objects().stream()
			.filter(object -> object.isPlayer() || object.isCompanion())
			.findFirst();
	}

	private static String idOf (final Property object) {
		return SaveObject.of(object).map(SaveObject::id).orElse(null);
	}
}
