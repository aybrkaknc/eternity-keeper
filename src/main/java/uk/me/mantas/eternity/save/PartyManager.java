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
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.game.AIController.AISummonType;
import uk.me.mantas.eternity.game.AIController.AggressionType;
import uk.me.mantas.eternity.game.CompanionNames.Companions;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.model.Component;
import uk.me.mantas.eternity.save.model.ComponentBuilder;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static uk.me.mantas.eternity.game.UnityEngine.Vector3;

/**
 * Moves characters between the active party and the stronghold roster,
 * replicating the structures the game itself writes (verified against real
 * saves):
 *
 * - A party member's mobile object carries a PartyMemberAI component with
 *   IsActiveInParty=true and an AssignedSlot (0 = the player).
 * - A stronghold roster member has AIPackageController instead of
 *   PartyMemberAI, is physically placed in the stronghold, and is described
 *   by a lightweight "<name>_stored" record object whose StoredCharacterInfo
 *   component points back at it. The record's ObjectID is listed in the
 *   Stronghold component's SerializedStoredGuids (on InGameGlobal(Clone));
 *   ranger animal companions get the same treatment via
 *   SerializedStoredAnimalCompanionGuids.
 */
public class PartyManager {
	private static final Logger logger = Logger.getLogger(PartyManager.class);

	// Exact C# type strings as written by the game (harvested from saves).
	private static final String TYPE_AGGRESSION = "AIController+AggressionType, Assembly-CSharp";
	private static final String TYPE_SUMMON_TYPE = "AIController+AISummonType, Assembly-CSharp";
	private static final String TYPE_COMPANIONS = "CompanionNames+Companions, Assembly-CSharp";

	// Dismissed companions stand around this spot in the Great Hall, which is
	// where the game itself puts them.
	private static final String STRONGHOLD_LEVEL = "AR_0604_Stronghold_Great_Hall";
	private static final float STRONGHOLD_X = -3.96f;
	private static final float STRONGHOLD_Y = 1.0576783f;
	private static final float STRONGHOLD_Z = 12.789214f;

	private static final int MAX_PARTY_SIZE = 6;
	private static final UUID ZERO_GUID = new UUID(0L, 0L);

	private final File saveDirectory;

	public PartyManager (final File saveDirectory) {
		this.saveDirectory = saveDirectory;
	}

	/**
	 * @param desired map of character ObjectID -> true to put them in the
	 *                party, false to store them at the stronghold.
	 */
	public boolean apply (final Map<String, Boolean> desired) throws IOException {
		final File mobileObjectsFile = new File(saveDirectory, "MobileObjects.save");
		final Optional<DeserializedPackets> deserialized =
			Environment.getInstance().factory().packetDeserializer()
				.forFile(mobileObjectsFile).deserialize();

		if (!deserialized.isPresent()) {
			logger.error("Unable to deserialize MobileObjects.save.%n");
			return false;
		}

		final List<Property> packets =
			new ArrayList<>(deserialized.get().getPackets());

		final SaveModel save = SaveModel.of(packets);
		final Optional<SaveObject> player = save.player();
		if (!player.isPresent()) {
			logger.error("Unable to find player character.%n");
			return false;
		}

		final Optional<CollectionProperty> storedGuids =
			findStrongholdList(save, "SerializedStoredGuids");
		final Optional<CollectionProperty> storedAnimalGuids =
			findStrongholdList(save, "SerializedStoredAnimalCompanionGuids");

		if (!storedGuids.isPresent()) {
			logger.error("Save has no Stronghold data; cannot manage the party.%n");
			return false;
		}

		boolean changed = false;

		// Positions vacated by dismissed characters; recruits take these
		// exact spots so they always spawn somewhere the game considers
		// valid.
		final Deque<Anchor> vacatedSpots = new ArrayDeque<>();

		// Dismiss first so recruits can take the freed party slots.
		for (final boolean recruitPass : new boolean[]{false, true}) {
			for (final Map.Entry<String, Boolean> change : desired.entrySet()) {
				if (change.getValue() != recruitPass) {
					continue;
				}

				final Optional<SaveObject> character =
					findCharacterByID(save, change.getKey());

				if (!character.isPresent()) {
					logger.error("No character with ObjectID '%s'.%n", change.getKey());
					return false;
				}

				if (character.get().isPlayer()) {
					logger.error("The main character cannot leave the party.%n");
					return false;
				}

				if (recruitPass == isInParty(character.get().property())) {
					continue; // Already in the desired state.
				}

				if (recruitPass) {
					if (!recruit(packets, character.get(), player.get()
						, storedGuids.get(), storedAnimalGuids.orElse(null)
						, vacatedSpots.pollFirst())) {
						return false;
					}
				} else {
					if (!dismiss(packets, character.get(), player.get()
						, storedGuids.get(), storedAnimalGuids.orElse(null)
						, vacatedSpots)) {
						return false;
					}
				}

				changed = true;
			}
		}

		if (!changed) {
			return true;
		}

		deserialized.get().setPackets(packets);
		deserialized.get().replace(mobileObjectsFile);
		return true;
	}

	// -------------------- dismiss --------------------

	// A level name plus an exact position that is known to be valid.
	private static final class Anchor {
		final String levelName;
		final Vector3 location;

		Anchor (final String levelName, final Vector3 location) {
			this.levelName = levelName;
			this.location = location;
		}
	}

	private boolean dismiss (
		final List<Property> packets
		, final SaveObject character
		, final SaveObject player
		, final CollectionProperty storedGuids
		, final CollectionProperty storedAnimalGuids
		, final Deque<Anchor> vacatedSpots) throws WriteRefusedException {

		final Optional<Component> ai = character.component("PartyMemberAI");
		if (!ai.isPresent()) {
			logger.error("'%s' has no PartyMemberAI component.%n", character.name());
			return false;
		}

		character.remove(ai.get());

		// Remember the exact spot this character stood on; a recruit in the
		// same operation will take their place.
		if (character.levelName() != null) {
			vacatedSpots.addLast(new Anchor(character.levelName(), character.location()));
		}

		character.add(buildAIPackageController(AISummonType.NotSummoned, null));

		final int rosterIndex = storedGuids.items.size();
		final Vector3 spot = new Vector3();
		spot.x = STRONGHOLD_X + (rosterIndex % 3) * 1.2f;
		spot.y = STRONGHOLD_Y;
		spot.z = STRONGHOLD_Z - (rosterIndex / 3) * 1.2f;

		character.moveTo(STRONGHOLD_LEVEL, spot);

		// A stored character is "packed" (not instantiated in a level)
		// unless the save was made in the very level they are stored in.
		// The game only spawns unpacked objects, so this flag decides
		// whether a character actually appears in the world.
		character.set("Packed", !STRONGHOLD_LEVEL.equals(player.levelName()));

		// Rangers: their animal companion is stored along with them, exactly
		// like the game does it.
		UUID animalGuid = ZERO_GUID;
		final Optional<SaveObject> pet = findPartyPet(packets, character.id());
		if (pet.isPresent() && storedAnimalGuids != null) {
			animalGuid = dismissPet(packets, pet.get(), character, player.levelName()
				, storedAnimalGuids, spot);
		} else if (pet.isPresent()) {
			logger.error("Save has no stored-animal list; leaving '%s' in the party.%n"
				, pet.get().name());
		}

		final UUID recordGuid = UUID.randomUUID();
		packets.add(buildStoredRecord(
			recordGuid, character, player.levelName(), animalGuid, true));
		addGuidToList(storedGuids, recordGuid);

		return true;
	}

	private UUID dismissPet (
		final List<Property> packets
		, final SaveObject pet
		, final SaveObject owner
		, final String currentLevel
		, final CollectionProperty storedAnimalGuids
		, final Vector3 ownerSpot) throws WriteRefusedException {

		final Optional<Component> ai = pet.component("PartyMemberAI");
		if (ai.isPresent()) {
			pet.remove(ai.get());
		}

		// A stored pet keeps its summon type and its link to the owner
		// (observed in real saves).
		pet.add(buildAIPackageController(
			AISummonType.AnimalCompanion, UUID.fromString(owner.id())));

		final Vector3 spot = new Vector3();
		spot.x = ownerSpot.x - 0.7f;
		spot.y = ownerSpot.y;
		spot.z = ownerSpot.z - 0.7f;
		pet.moveTo(STRONGHOLD_LEVEL, spot);
		pet.set("Packed", !STRONGHOLD_LEVEL.equals(currentLevel));

		final UUID recordGuid = UUID.randomUUID();
		packets.add(buildStoredRecord(
			recordGuid, pet, currentLevel, ZERO_GUID, false));
		addGuidToList(storedAnimalGuids, recordGuid);

		return UUID.fromString(pet.id());
	}

	// -------------------- recruit --------------------

	private boolean recruit (
		final List<Property> packets
		, final SaveObject character
		, final SaveObject player
		, final CollectionProperty storedGuids
		, final CollectionProperty storedAnimalGuids
		, final Anchor vacated) throws WriteRefusedException {

		final int slot = firstFreeSlot(packets);
		if (slot < 0) {
			logger.error("The party is already full.%n");
			return false;
		}

		final Optional<Component> controller = character.component("AIPackageController");
		if (controller.isPresent()) {
			character.remove(controller.get());
		}

		character.add(buildPartyMemberAI(slot, false, AISummonType.NotSummoned, null));

		// Take the exact spot of a character dismissed in the same operation
		// when there is one; otherwise stand on the player, which is always a
		// valid position. Made-up offsets risk landing outside the walkable
		// area, in which case the game silently fails to spawn the character.
		final String levelName = vacated != null
			? vacated.levelName : player.levelName();
		final Vector3 spot = vacated != null
			? vacated.location : player.location();

		character.moveTo(levelName, spot);

		// Party members are always unpacked: they live in the same level as
		// the player and the game must instantiate them on load.
		character.set("Packed", false);

		// Retire the roster record and follow it to any stored animal
		// companion (rangers).
		final Optional<SaveObject> record = findStoredRecord(packets, character.id());
		if (record.isPresent()) {
			final Optional<UUID> animalGuid = record.get().component("StoredCharacterInfo")
				.flatMap(info -> info.get("AnimalCompanionGUID"))
				.filter(guid -> guid instanceof UUID)
				.map(guid -> (UUID) guid);

			packets.remove(record.get().property());
			removeGuidFromList(storedGuids, record.get().id());

			if (animalGuid.isPresent() && !ZERO_GUID.equals(animalGuid.get())) {
				recruitAnimalCompanion(packets, animalGuid.get().toString()
					, UUID.fromString(character.id()), slot
					, levelName, spot, storedAnimalGuids);
			}
		}

		return true;
	}

	private void recruitAnimalCompanion (
		final List<Property> packets
		, final String animalID
		, final UUID ownerGuid
		, final int ownerSlot
		, final String levelName
		, final Vector3 ownerSpot
		, final CollectionProperty storedAnimalGuids) throws WriteRefusedException {

		final Optional<SaveObject> animal = SaveModel.of(packets).objects().stream()
			.filter(object -> animalID.equals(object.id()) && !isStoredRecord(object))
			.findFirst();

		if (!animal.isPresent()) {
			logger.error("Stored animal companion '%s' not found.%n", animalID);
			return;
		}

		// In-party pets sit in slot 6 + their owner's slot with
		// Secondary=true (observed in real saves: Sagani in slot 5, her fox
		// in slot 11). They stand on their owner's (known-valid) spot.
		final Optional<Component> controller = animal.get().component("AIPackageController");
		if (controller.isPresent()) {
			animal.get().remove(controller.get());
		}

		animal.get().add(buildPartyMemberAI(
			MAX_PARTY_SIZE + ownerSlot, true, AISummonType.AnimalCompanion, ownerGuid));

		animal.get().moveTo(levelName, ownerSpot);
		animal.get().set("Packed", false);

		final Optional<SaveObject> animalRecord = findStoredRecord(packets, animalID);
		if (animalRecord.isPresent()) {
			packets.remove(animalRecord.get().property());
			if (storedAnimalGuids != null) {
				removeGuidFromList(storedAnimalGuids, animalRecord.get().id());
			}
		}
	}

	// Finds a party character's living animal companion: an object whose
	// PartyMemberAI declares SummonType=AnimalCompanion and names the
	// character as its summoner.
	private Optional<SaveObject> findPartyPet (
		final List<Property> packets
		, final String ownerID) {

		for (final SaveObject object : SaveModel.of(packets).objects()) {
			if (isStoredRecord(object)) {
				continue;
			}

			final Optional<Component> ai = object.component("PartyMemberAI");
			if (!ai.isPresent()) {
				continue;
			}

			final Object summonType = ai.get().get("SummonType").orElse(null);
			final Object summoner = ai.get().get("Summoner").orElse(null);

			if (summonType == AISummonType.AnimalCompanion
				&& summoner != null
				&& ownerID.equals(summoner.toString())) {

				return Optional.of(object);
			}
		}

		return Optional.empty();
	}

	// -------------------- component builders --------------------

	static ComplexProperty buildPartyMemberAI (
		final int slot
		, final boolean secondary
		, final AISummonType summonType
		, final UUID summoner) {

		return ComponentBuilder.of("PartyMemberAI")
			.integer("FormationStyle", 3)
			.integer("AssignedSlot", slot)
			.bool("Secondary", secondary)
			.bool("AddedThroughScript", true)
			.nothing("m_prevWaypoint")
			.nothing("m_currentWaypoint")
			.bool("IsInSlot", true)
			.bool("IsActiveInParty", true)
			.guid("Summoner", summoner)
			.constant("SummonType", summonType, TYPE_SUMMON_TYPE)
			.constant("Aggression", AggressionType.DefendMyself, TYPE_AGGRESSION)
			.build();
	}

	private ComplexProperty buildAIPackageController (
		final AISummonType summonType
		, final UUID summoner) {

		return ComponentBuilder.of("AIPackageController")
			.bool("Patroller", false)
			.nothing("m_prevWaypoint")
			.nothing("m_currentWaypoint")
			.bool("IsActive", true)
			.guid("Summoner", summoner)
			.constant("SummonType", summonType, TYPE_SUMMON_TYPE)
			.constant("Aggression", AggressionType.DefendMyself, TYPE_AGGRESSION)
			.build();
	}

	// Builds the "<name>_stored" bookkeeping object the stronghold uses to
	// describe a roster member.
	private Property buildStoredRecord (
		final UUID recordGuid
		, final SaveObject character
		, final String currentLevel
		, final UUID animalGuid
		, final boolean hasRested) {

		final ComplexProperty root = new ComplexProperty(
			"Root", new TypePair(ObjectPersistencePacket.class, ComponentBuilder.TYPE_OPP));

		root.properties.add(field("ObjectName", String.class,
			character.name() + "_stored"));
		root.properties.add(field("LevelName", String.class, currentLevel));
		root.properties.add(field("PrefabResource", String.class, ""));
		root.properties.add(field("Mobile", boolean.class, true));
		root.properties.add(field("Global", boolean.class, false));
		root.properties.add(field("GUID", UUID.class, recordGuid));
		root.properties.add(field("ObjectID", String.class, recordGuid.toString()));
		root.properties.add(zeroVector("Location"));
		root.properties.add(zeroVector("Rotation"));
		root.properties.add(field("Packed", boolean.class, false));
		root.properties.add(field("LoadManually", boolean.class, false));
		root.properties.add(field("Parent", String.class, "none"));

		final SingleDimensionalArrayProperty components =
			new SingleDimensionalArrayProperty(
				"ComponentPackets"
				, new TypePair(ComponentPersistencePacket[].class, null));
		components.elementType =
			new TypePair(ComponentPersistencePacket.class, ComponentBuilder.TYPE_CPP);
		components.lowerBound = 0;

		components.items.add(ComponentBuilder.of("InstanceID")
			.guid("Guid", recordGuid)
			.build());

		components.items.add(ComponentBuilder.of("Persistence")
			.bool("m_objDestroyed", false)
			.bool("IsActive", true)
			.bool("UnloadsBetweenLevels", false)
			.build());

		components.items.add(ComponentBuilder.of("StoredCharacterInfo")
			.text("m_portraitSmallPath", smallPortraitPath(character))
			.emptyGuidList("AttachedObjects")
			.constant("NamedCompanion", namedCompanion(character), TYPE_COMPANIONS)
			.guid("GUID", UUID.fromString(character.id()))
			.text("DisplayName", displayName(character))
			.guid("AnimalCompanionGUID", animalGuid)
			.bool("HasRested", hasRested)
			.build());

		root.properties.add(components);
		root.obj = recordPacket(recordGuid, character, currentLevel);
		return root;
	}

	// The header-only mirror every object this editor makes keeps.
	private ObjectPersistencePacket recordPacket (
		final UUID recordGuid
		, final SaveObject character
		, final String currentLevel) {

		final ObjectPersistencePacket record = new ObjectPersistencePacket();
		record.ObjectName = character.name() + "_stored";
		record.LevelName = currentLevel;
		record.ObjectID = recordGuid.toString();
		record.GUID = recordGuid;
		record.Parent = "none";
		return record;
	}

	private SimpleProperty field (final String name, final Class javaType, final Object value) {
		return ComponentBuilder.field(name, javaType, value);
	}

	private ComplexProperty zeroVector (final String name) {
		final ComplexProperty vector =
			new ComplexProperty(name, new TypePair(Vector3.class, null));
		vector.properties.add(field("x", float.class, 0f));
		vector.properties.add(field("y", float.class, 0f));
		vector.properties.add(field("z", float.class, 0f));
		return vector;
	}

	// -------------------- naming helpers --------------------

	private String displayName (final SaveObject character) {
		final Object override = character.component("CharacterStats")
			.flatMap(stats -> stats.get("OverrideName"))
			.orElse(null);

		if (override instanceof String && !((String) override).isEmpty()) {
			return (String) override;
		}

		return EKUtils.extractCharacterName(character.name());
	}

	private String smallPortraitPath (final SaveObject character) {
		final Object path = character.component("Portrait")
			.flatMap(portrait -> portrait.get("m_textureSmallPath"))
			.orElse(null);

		return path instanceof String ? (String) path : "";
	}

	private Companions namedCompanion (final SaveObject character) {
		final String name = EKUtils.extractCharacterName(character.name());
		if (name.equals("GM")) {
			return Companions.Mother;
		}

		try {
			return Companions.valueOf(name);
		} catch (final IllegalArgumentException e) {
			return Companions.Invalid;
		}
	}

	// -------------------- roster lists --------------------

	private void addGuidToList (final CollectionProperty list, final UUID guid) {
		final SimpleProperty item =
			new SimpleProperty(null, new TypePair(UUID.class, null));
		item.value = guid;
		item.obj = guid;
		list.items.add(item);
		bumpCapacity(list);
	}

	private void removeGuidFromList (final CollectionProperty list, final String guid) {
		list.items.removeIf(item -> item instanceof SimpleProperty
			&& ((SimpleProperty) item).value != null
			&& ((SimpleProperty) item).value.toString().equals(guid));
	}

	private void bumpCapacity (final CollectionProperty list) {
		for (final Object propertyObj : list.properties) {
			final Property property = (Property) propertyObj;
			if ("Capacity".equals(property.name) && property instanceof SimpleProperty) {
				final int current = (int) ((SimpleProperty) property).value;
				if (list.items.size() > current) {
					Property.update(property, list.items.size());
				}
			}
		}
	}

	// -------------------- lookups --------------------

	public boolean isInParty (final Property character) {
		return SaveObject.of(character)
			.flatMap(object -> object.component("PartyMemberAI"))
			.isPresent();
	}

	private static boolean isStoredRecord (final SaveObject object) {
		return object.name() != null && object.name().endsWith("_stored");
	}

	private Optional<SaveObject> findCharacterByID (final SaveModel save, final String objectID) {
		return save.objects().stream()
			.filter(object -> objectID.equalsIgnoreCase(object.id())
				&& !isStoredRecord(object)
				&& (object.isPlayer() || object.isCompanion()))
			.findFirst();
	}

	// Finds the "_stored" record whose StoredCharacterInfo.GUID points at the
	// given character.
	private Optional<SaveObject> findStoredRecord (
		final List<Property> packets
		, final String characterID) {

		return SaveModel.of(packets).objects().stream()
			.filter(PartyManager::isStoredRecord)
			.filter(record -> record.component("StoredCharacterInfo")
				.flatMap(info -> info.get("GUID"))
				.filter(guid -> guid != null && characterID.equals(guid.toString()))
				.isPresent())
			.findFirst();
	}

	private int firstFreeSlot (final List<Property> packets) {
		final Set<Integer> taken = new HashSet<>();
		for (final SaveObject object : SaveModel.of(packets).objects()) {
			object.component("PartyMemberAI")
				.flatMap(ai -> ai.get("AssignedSlot"))
				.filter(slot -> slot instanceof Integer)
				.ifPresent(slot -> taken.add((Integer) slot));
		}

		for (int slot = 0; slot < MAX_PARTY_SIZE; slot++) {
			if (!taken.contains(slot)) {
				return slot;
			}
		}

		return -1;
	}

	private Optional<CollectionProperty> findStrongholdList (
		final SaveModel save
		, final String listName) {

		return save.inGameGlobal()
			.flatMap(global -> global.component("Stronghold"))
			.flatMap(stronghold -> stronghold.variable(listName))
			.filter(list -> list instanceof CollectionProperty)
			.map(list -> (CollectionProperty) list);
	}
}
