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
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;

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
	private static final String TYPE_OPP = "ObjectPersistencePacket, Assembly-CSharp";
	private static final String TYPE_CPP = "ComponentPersistencePacket, Assembly-CSharp";
	private static final String TYPE_STRING = "System.String, mscorlib";
	private static final String TYPE_BOOL = "System.Boolean, mscorlib";
	private static final String TYPE_INT = "System.Int32, mscorlib";
	private static final String TYPE_GUID = "System.Guid, mscorlib";
	private static final String TYPE_OBJECT = "System.Object, mscorlib";
	private static final String TYPE_GUID_LIST =
		"System.Collections.Generic.List`1[[System.Guid, mscorlib]], mscorlib";
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
			recordGuid, unwrap(character), player.levelName(), animalGuid, true));
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
			recordGuid, unwrap(pet), currentLevel, ZERO_GUID, false));
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

		final LinkedHashMap<String, Property> vars = new LinkedHashMap<>();
		vars.put("FormationStyle", simple(int.class, TYPE_INT, 3));
		vars.put("AssignedSlot", simple(int.class, TYPE_INT, slot));
		vars.put("Secondary", simple(boolean.class, TYPE_BOOL, secondary));
		vars.put("AddedThroughScript", simple(boolean.class, TYPE_BOOL, true));
		vars.put("m_prevWaypoint", new NullProperty(null));
		vars.put("m_currentWaypoint", new NullProperty(null));
		vars.put("IsInSlot", simple(boolean.class, TYPE_BOOL, true));
		vars.put("IsActiveInParty", simple(boolean.class, TYPE_BOOL, true));
		vars.put("Summoner", summoner == null
			? new NullProperty(null)
			: simple(UUID.class, TYPE_GUID, summoner));
		vars.put("SummonType",
			simple(AISummonType.class, TYPE_SUMMON_TYPE, summonType));
		vars.put("Aggression",
			simple(AggressionType.class, TYPE_AGGRESSION, AggressionType.DefendMyself));

		return buildComponent("PartyMemberAI", vars);
	}

	private ComplexProperty buildAIPackageController (
		final AISummonType summonType
		, final UUID summoner) {

		final LinkedHashMap<String, Property> vars = new LinkedHashMap<>();
		vars.put("Patroller", simple(boolean.class, TYPE_BOOL, false));
		vars.put("m_prevWaypoint", new NullProperty(null));
		vars.put("m_currentWaypoint", new NullProperty(null));
		vars.put("IsActive", simple(boolean.class, TYPE_BOOL, true));
		vars.put("Summoner", summoner == null
			? new NullProperty(null)
			: simple(UUID.class, TYPE_GUID, summoner));
		vars.put("SummonType",
			simple(AISummonType.class, TYPE_SUMMON_TYPE, summonType));
		vars.put("Aggression",
			simple(AggressionType.class, TYPE_AGGRESSION, AggressionType.DefendMyself));

		return buildComponent("AIPackageController", vars);
	}

	// Builds the "<name>_stored" bookkeeping object the stronghold uses to
	// describe a roster member.
	private Property buildStoredRecord (
		final UUID recordGuid
		, final ObjectPersistencePacket character
		, final String currentLevel
		, final UUID animalGuid
		, final boolean hasRested) {

		final ComplexProperty root =
			new ComplexProperty("Root", new TypePair(ObjectPersistencePacket.class, TYPE_OPP));

		root.properties.add(field("ObjectName", String.class,
			character.ObjectName + "_stored"));
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
		components.elementType = new TypePair(ComponentPersistencePacket.class, TYPE_CPP);
		components.lowerBound = 0;

		final LinkedHashMap<String, Property> instanceVars = new LinkedHashMap<>();
		instanceVars.put("Guid", simple(UUID.class, TYPE_GUID, recordGuid));
		components.items.add(buildComponent("InstanceID", instanceVars));

		final LinkedHashMap<String, Property> persistenceVars = new LinkedHashMap<>();
		persistenceVars.put("m_objDestroyed", simple(boolean.class, TYPE_BOOL, false));
		persistenceVars.put("IsActive", simple(boolean.class, TYPE_BOOL, true));
		persistenceVars.put("UnloadsBetweenLevels", simple(boolean.class, TYPE_BOOL, false));
		components.items.add(buildComponent("Persistence", persistenceVars));

		final LinkedHashMap<String, Property> storedVars = new LinkedHashMap<>();
		storedVars.put("m_portraitSmallPath",
			simple(String.class, TYPE_STRING, smallPortraitPath(character)));
		storedVars.put("AttachedObjects", emptyGuidList());
		storedVars.put("NamedCompanion",
			simple(Companions.class, TYPE_COMPANIONS, namedCompanion(character)));
		storedVars.put("GUID",
			simple(UUID.class, TYPE_GUID, UUID.fromString(character.ObjectID)));
		storedVars.put("DisplayName", simple(String.class, TYPE_STRING, displayName(character)));
		storedVars.put("AnimalCompanionGUID", simple(UUID.class, TYPE_GUID, animalGuid));
		storedVars.put("HasRested", simple(boolean.class, TYPE_BOOL, hasRested));
		components.items.add(buildComponent("StoredCharacterInfo", storedVars));

		root.properties.add(components);
		root.obj = recordPacket(recordGuid, character, currentLevel);
		return root;
	}

	private ObjectPersistencePacket recordPacket (
		final UUID recordGuid
		, final ObjectPersistencePacket character
		, final String currentLevel) {

		final ObjectPersistencePacket record = new ObjectPersistencePacket();
		record.ObjectName = character.ObjectName + "_stored";
		record.LevelName = currentLevel;
		record.ObjectID = recordGuid.toString();
		record.GUID = recordGuid;
		record.Parent = "none";
		return record;
	}

	static ComplexProperty buildComponent (
		final String typeString
		, final LinkedHashMap<String, Property> variables) {

		final ComplexProperty component =
			new ComplexProperty(null, new TypePair(ComponentPersistencePacket.class, null));

		final SimpleProperty type =
			new SimpleProperty("TypeString", new TypePair(String.class, null));
		type.value = typeString;
		type.obj = typeString;
		component.properties.add(type);

		final DictionaryProperty dict =
			new DictionaryProperty("Variables", new TypePair(HashMap.class, null));
		dict.keyType = new TypePair(String.class, TYPE_STRING);
		dict.valueType = new TypePair(Object.class, TYPE_OBJECT);

		for (final Map.Entry<String, Property> variable : variables.entrySet()) {
			final SimpleProperty key =
				new SimpleProperty(null, new TypePair(String.class, null));
			key.value = variable.getKey();
			key.obj = variable.getKey();
			dict.items.add(new SimpleEntry<>(key, variable.getValue()));
		}

		component.properties.add(dict);
		return component;
	}

	static SimpleProperty simple (
		final Class javaType
		, final String cSharpType
		, final Object value) {

		final SimpleProperty property =
			new SimpleProperty(null, new TypePair(javaType, cSharpType));
		property.value = value;
		property.obj = value;
		return property;
	}

	private SimpleProperty field (final String name, final Class javaType, final Object value) {
		final SimpleProperty property =
			new SimpleProperty(name, new TypePair(javaType, null));
		property.value = value;
		property.obj = value;
		return property;
	}

	private ComplexProperty zeroVector (final String name) {
		final ComplexProperty vector =
			new ComplexProperty(name, new TypePair(Vector3.class, null));
		vector.properties.add(field("x", float.class, 0f));
		vector.properties.add(field("y", float.class, 0f));
		vector.properties.add(field("z", float.class, 0f));
		return vector;
	}

	private CollectionProperty emptyGuidList () {
		final CollectionProperty list =
			new CollectionProperty(null, new TypePair(CSharpCollection.class, TYPE_GUID_LIST));
		list.elementType = new TypePair(UUID.class, TYPE_GUID);
		list.properties.add(field("Capacity", int.class, 0));
		return list;
	}

	// -------------------- naming helpers --------------------

	private String displayName (final ObjectPersistencePacket character) {
		final Optional<ComponentPersistencePacket> stats =
			EKUtils.findComponent(character.ComponentPackets, "CharacterStats");

		if (stats.isPresent()) {
			final Object override = stats.get().Variables.get("OverrideName");
			if (override instanceof String && !((String) override).isEmpty()) {
				return (String) override;
			}
		}

		return EKUtils.extractCharacterName(character.ObjectName);
	}

	private String smallPortraitPath (final ObjectPersistencePacket character) {
		final Optional<ComponentPersistencePacket> portrait =
			EKUtils.findComponent(character.ComponentPackets, "Portrait");

		if (portrait.isPresent()) {
			final Object path = portrait.get().Variables.get("m_textureSmallPath");
			if (path instanceof String) {
				return (String) path;
			}
		}

		return "";
	}

	private Companions namedCompanion (final ObjectPersistencePacket character) {
		final String name = EKUtils.extractCharacterName(character.ObjectName);
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

	// The builders below still read the deserializer's mirror of an object;
	// the tree and the mirror agree until an edit, and the builders only read
	// what this class never edits.
	private ObjectPersistencePacket unwrap (final SaveObject object) {
		return (ObjectPersistencePacket) object.property().obj;
	}

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
