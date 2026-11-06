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

import com.google.common.primitives.UnsignedInteger;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.InventoryItem;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.game.StrongholdUpgrade;
import uk.me.mantas.eternity.save.SaveDiff.Change;
import uk.me.mantas.eternity.save.SaveDiff.Leaf;
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static uk.me.mantas.eternity.EKUtils.findComponent;

/**
 * Two saves side by side, in the words the rest of the editor uses: a stat
 * under its character, an item by where it went, a global by its name, a
 * quest by its title.
 *
 * <p>{@link SaveDiff} finds every value that differs between the two world
 * states. Each section then <em>claims</em> the differences it can explain
 * and says them its own way; whatever no section claims is shown as it is,
 * under "Everything else", so nothing the files disagree about is hidden.
 * Two kinds of difference are counted rather than listed, because they
 * change between any two saves of a playthrough and say nothing about what
 * happened: where an object stands (its area, position and rotation -- the
 * party's belongings ride along into every area) and the timers and counters
 * the game updates as it runs. Measured on two real saves fifty minutes of
 * play apart, those are 3,500 of 3,600 differences.
 *
 * <p>Area files are compared whole, and a store's stock by count, since the
 * Vendors tab is the one thing that rewrites them; a full read of every area
 * of a late save is 100 MB.
 *
 * <p>Read-only throughout: both saves are the unpacked copies the save list
 * made.
 */
public final class SaveComparison {
	private static final Logger logger = Logger.getLogger(SaveComparison.class);

	private static final String WORLD = "MobileObjects.save";
	private static final String INFO = "saveinfo.xml";

	/** Rows "Everything else" lists before it only counts the rest. */
	private static final int MOST_OTHER_ROWS = 400;
	/** Places inside one value listed before the rest are only counted. */
	private static final int MOST_LEAVES = 8;
	/**
	 * Items one group lists before it only counts the rest: two saves of
	 * different playthroughs gain 745 between them, and every one carries an
	 * icon.
	 */
	private static final int MOST_ITEM_ROWS = 200;

	// ---------------------------------------------------------------- output

	/** One line: what something was and what it is. */
	static final class Row {
		final String label;
		Object before;
		Object after;
		String format;
		String note;
		/** How far it moved, where the server knows better than the page: the clock. */
		String change;
		String icon;
		/** The item whose icon this row shows, looked up only if it is shown. */
		String item;

		Row (final String label, final Object before, final Object after) {
			this.label = label;
			this.before = before;
			this.after = after;
		}

		Row format (final String format) {
			this.format = format;
			return this;
		}

		Row note (final String note) {
			this.note = note;
			return this;
		}

		JSONObject toJSON () {
			final JSONObject json = new JSONObject().put("label", label);
			if (before != null) {
				json.put("before", before);
			}

			if (after != null) {
				json.put("after", after);
			}

			if (format != null) {
				json.put("format", format);
			}

			if (note != null) {
				json.put("note", note);
			}

			if (change != null) {
				json.put("change", change);
			}

			if (icon != null) {
				json.put("icon", icon);
			}

			return json;
		}
	}

	/** Rows under one heading: a character, a grimoire, the keep. */
	static final class Group {
		final String title;
		String note;
		final List<Row> rows = new ArrayList<>();

		Group (final String title, final String note) {
			this.title = title;
			this.note = note;
		}

		JSONObject toJSON () {
			final JSONArray rowsJson = new JSONArray();
			rows.forEach(row -> rowsJson.put(row.toJSON()));
			final JSONObject json = new JSONObject().put("title", title).put("rows", rowsJson);
			if (note != null) {
				json.put("note", note);
			}

			return json;
		}
	}

	static final class Section {
		final String id;
		final String title;
		final List<Group> groups = new ArrayList<>();

		Section (final String id, final String title) {
			this.id = id;
			this.title = title;
		}

		Group group (final String title, final String note) {
			for (final Group group : groups) {
				if (Objects.equals(group.title, title)) {
					return group;
				}
			}

			final Group group = new Group(title, note);
			groups.add(group);
			return group;
		}

		boolean isEmpty () {
			return groups.stream().allMatch(group -> group.rows.isEmpty() && group.note == null);
		}

		JSONObject toJSON () {
			final JSONArray groupsJson = new JSONArray();
			groups.stream()
				.filter(group -> !group.rows.isEmpty() || group.note != null)
				.forEach(group -> groupsJson.put(group.toJSON()));

			return new JSONObject().put("id", id).put("title", title).put("groups", groupsJson);
		}
	}

	// ------------------------------------------------------------------ input

	/** One save: its world state, as much as could be read, and saveinfo.xml. */
	static final class Side {
		final File directory;
		final List<Property> packets;
		final Map<String, String> info;
		final String problem;

		private Side (
			final File directory, final List<Property> packets
			, final Map<String, String> info, final String problem) {

			this.directory = directory;
			this.packets = packets;
			this.info = info;
			this.problem = problem;
		}

		static Side read (final File directory) {
			final Map<String, String> info = readInfo(new File(directory, INFO));
			final File world = new File(directory, WORLD);
			if (!world.isFile()) {
				return new Side(directory, new ArrayList<>(), info
					, label(info, directory) + " has no world state (" + WORLD + ") to compare.");
			}

			try {
				final Optional<DeserializedPackets> read = new PacketDeserializer(world).deserializeEvenIfShort();
				if (!read.isPresent()) {
					return new Side(directory, new ArrayList<>(), info
						, "The world state of " + label(info, directory) + " could not be read at all.");
				}

				String problem = null;
				if (!read.get().isWhole()) {
					problem = String.format(
						"Only %d of the %d objects in the world state of %s could be read; "
							+ "the comparison covers those."
						, read.get().getPackets().size()
						, ((Number) read.get().getCount().obj).intValue()
						, label(info, directory));
				}

				return new Side(directory, read.get().getPackets(), info, problem);
			} catch (final IOException | RuntimeException e) {
				logger.error(e, "Unable to read %s: %s%n", world.getAbsolutePath(), e.toString());
				return new Side(directory, new ArrayList<>(), info
					, "The world state of " + label(info, directory) + " could not be read at all.");
			}
		}

		JSONObject toJSON () {
			return new JSONObject()
				.put("label", label(info, directory))
				.put("scene", info.getOrDefault("SceneTitle", ""))
				.put("date", info.getOrDefault("RealTimestamp", ""))
				.put("userSaveName", info.getOrDefault("UserSaveName", ""))
				.put("playtime", number(info.get("RealtimePlayDurationSeconds")))
				.put("objects", packets.size());
		}
	}

	/** How the save list names a save: player, area and the name typed for it. */
	static String label (final Map<String, String> info, final File directory) {
		final String player = info.getOrDefault("PlayerName", "");
		final String scene = info.getOrDefault("SceneTitle", "");
		if (player.isEmpty() && scene.isEmpty()) {
			return directory.getName();
		}

		final String user = info.getOrDefault("UserSaveName", "");
		return player + " - " + scene + (user.isEmpty() ? "" : " (" + user + ")");
	}

	private static Object number (final String text) {
		try {
			return text == null ? 0 : Long.parseLong(text.trim());
		} catch (final NumberFormatException e) {
			return 0;
		}
	}

	/** Every simple value saveinfo.xml holds, by name. */
	static Map<String, String> readInfo (final File file) {
		final Map<String, String> info = new LinkedHashMap<>();
		if (!file.isFile()) {
			return info;
		}

		try {
			final byte[] bytes = EKUtils.removeBOM(FileUtils.readFileToByteArray(file));
			final XMLInputFactory factory = XMLInputFactory.newInstance();
			factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
			factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

			final XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes), "UTF-8");
			try {
				while (reader.hasNext()) {
					if (reader.next() == XMLStreamConstants.START_ELEMENT
						&& "Simple".equals(reader.getLocalName())) {

						final String name = reader.getAttributeValue(null, "name");
						final String value = reader.getAttributeValue(null, "value");
						if (name != null && value != null) {
							info.put(name, value);
						}
					}
				}
			} finally {
				reader.close();
			}
		} catch (final IOException | XMLStreamException e) {
			logger.error("Unable to read %s: %s%n", file.getAbsolutePath(), e.getMessage());
		}

		return info;
	}

	// ------------------------------------------------------------- the whole

	/**
	 * What differs between two unpacked saves, {@code before} first, as the
	 * comparison dialog shows it.
	 */
	public static JSONObject compare (final File before, final File after) {
		final long start = System.currentTimeMillis();

		// Two files of a few megabytes each; they read faster side by side.
		final CompletableFuture<Side> first = CompletableFuture.supplyAsync(() -> Side.read(before));
		final Side second = Side.read(after);
		final Side firstSide = first.join();
		final long read = System.currentTimeMillis();
		final SaveComparison comparison = new SaveComparison(firstSide, second);
		final JSONObject json = comparison.toJSON();

		final long millis = System.currentTimeMillis() - start;
		json.put("millis", millis);
		logger.info("Compared %s with %s in %d ms (%d reading, %d the area files): %s%n"
			, before.getName(), after.getName(), millis, read - start, comparison.areaMillis
			, json.getJSONObject("counts"));

		return json;
	}

	private final Side first;
	private final Side second;
	private final SaveDiff diff;

	private final Map<String, List<Change>> changesByObject = new LinkedHashMap<>();
	private final Set<Change> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<String> claimedObjects = new HashSet<>();
	private int shown = 0;

	private final Set<String> placesChanged = new HashSet<>();
	private int bookkeeping = 0;
	private final Map<String, Integer> bookkeepingNames = new TreeMap<>();
	private int leftOut = 0;
	private int maps = 0;
	private long areaMillis = 0;

	private final Map<String, Person> people = new LinkedHashMap<>();
	private final Map<String, String> personByObjectName = new HashMap<>();
	private final Map<String, String> idsBefore;
	private final Map<String, String> idsAfter;
	private final Map<String, Place> placesBefore;
	private final Map<String, Place> placesAfter;
	private final JSONObject icons = new JSONObject();

	private SaveComparison (final Side first, final Side second) {
		this.first = first;
		this.second = second;
		diff = SaveDiff.between(first.packets, second.packets);
		for (final Change change : diff.changes) {
			changesByObject.computeIfAbsent(change.id, id -> new ArrayList<>()).add(change);
		}

		idsBefore = idsByName(diff.before);
		idsAfter = idsByName(diff.after);
		findPeople(first.packets, true);
		findPeople(second.packets, false);
		placesBefore = places(first.packets);
		placesAfter = places(second.packets);
	}

	private static Map<String, String> idsByName (final Map<String, ComplexProperty> objects) {
		final Map<String, String> ids = new HashMap<>();
		for (final Map.Entry<String, ComplexProperty> object : objects.entrySet()) {
			ids.putIfAbsent(text(object.getValue(), "ObjectName"), object.getKey());
		}

		return ids;
	}

	private JSONObject toJSON () {
		leaveOut();

		// Claimed in this order -- what is most specific first, the rest of a
		// character's object last of all -- and shown in the other.
		final Section save = saveSection();
		final Section grimoires = grimoireSection();
		final Section items = itemSection();
		final Section abilities = abilitySection();
		final Section stronghold = strongholdSection();
		final Section globals = globalSection();
		final Section journal = journalSection();
		final Section characters = characterSection();
		final long areasFrom = System.currentTimeMillis();
		final Section areas = areaSection();
		areaMillis = System.currentTimeMillis() - areasFrom;
		final Section other = otherSection();

		final JSONArray sections = new JSONArray();
		for (final Section section : Arrays.asList(
			save, characters, items, abilities, grimoires, stronghold, globals, journal, areas, other)) {

			if (!section.isEmpty()) {
				sections.put(section.toJSON());
			}
		}

		final JSONArray problems = new JSONArray();
		for (final Side side : Arrays.asList(first, second)) {
			if (side.problem != null) {
				problems.put(side.problem);
			}
		}

		final JSONArray names = new JSONArray();
		bookkeepingNames.keySet().forEach(names::put);

		return new JSONObject()
			.put("before", first.toJSON())
			.put("after", second.toJSON())
			.put("samePlaythrough", Objects.equals(
				first.info.getOrDefault("SessionID", ""), second.info.getOrDefault("SessionID", "")))
			.put("sections", sections)
			.put("leftOut", new JSONObject()
				.put("positions", placesChanged.size())
				.put("bookkeeping", bookkeeping)
				.put("names", names)
				.put("maps", maps))
			.put("counts", new JSONObject()
				.put("differences", diff.changes.size() + diff.added.size() + diff.removed.size())
				.put("shown", shown)
				.put("leftOut", leftOut))
			.put("problems", problems)
			.put("icons", icons);
	}

	// ---------------------------------------------------------------- claims

	private boolean claim (final Change change) {
		if (!claimed.add(change)) {
			return false;
		}

		shown++;
		return true;
	}

	private boolean claimObject (final String id) {
		if ((!diff.added.contains(id) && !diff.removed.contains(id)) || !claimedObjects.add(id)) {
			return false;
		}

		shown++;
		return true;
	}

	/** One object's differences no section has explained yet. */
	private List<Change> open (final String id, final Predicate<Change> which) {
		return changesByObject.getOrDefault(id, Collections.emptyList()).stream()
			.filter(change -> !claimed.contains(change))
			.filter(which)
			.collect(Collectors.toList());
	}

	private List<Change> open (final String id, final String component) {
		return open(id, change -> change.component.equals(component));
	}

	private Optional<Change> open (final String id, final String component, final String variable) {
		return open(id, change -> change.component.equals(component) && change.variable.equals(variable))
			.stream().findFirst();
	}

	// ------------------------------------------------------------- left out

	private static final Set<String> PLACE = new HashSet<>(Arrays.asList("Location", "Rotation", "LevelName"));

	// Values the game keeps changing as it runs, which differ between any two
	// saves and say nothing about what happened.
	private static final Set<String> BOOKKEEPING = new HashSet<>(Arrays.asList(
		"Faction.CurrentTeamInstance"
		, "CharacterStats.m_availableStatusEffectID"
		, "CharacterStats.m_noiseLevel"
		, "GameState.AutosaveCycleNumber"
		, "WorldTime.RealWorldPlayTime"
		, "FatigueWhispers.m_DesiredVolume"
		, "Trap.m_selfDestructStartTime"
		, "AchievementTracker.m_trackedAchievementStatCount"
		, "AchievementTracker.m_trackedUniqueValues"));

	private void leaveOut () {
		for (final Change change : diff.changes) {
			if (change.component.isEmpty() && PLACE.contains(change.variable)) {
				claimed.add(change);
				placesChanged.add(change.id);
				leftOut++;
			} else if (change.variable.toLowerCase(Locale.ROOT).contains("timer")
				|| BOOKKEEPING.contains(change.component + "." + change.variable)) {

				bookkeeping(change, change.variable);
			} else if (renumbered(change)) {
				bookkeeping(change, change.variable);
			} else if (onlyCapacity(change)) {
				bookkeeping(change, CAPACITY);
			}
		}
	}

	private void bookkeeping (final Change change, final String name) {
		claimed.add(change);
		bookkeeping++;
		bookkeepingNames.merge(name, 1, Integer::sum);
		leftOut++;
	}

	/**
	 * The game numbers the objects it makes from a prefab as it makes them,
	 * "Companion_Sagani(Clone)_5", and numbers them afresh on every load: the
	 * same companion is "_4" in the next save, and everything she owns names
	 * her by that. A name that differs only in that number, and a parent that
	 * names the same object either way, is no change.
	 */
	private boolean renumbered (final Change change) {
		if (!change.component.isEmpty()) {
			return false;
		}

		final String before = String.valueOf(value(change.before));
		final String after = String.valueOf(value(change.after));
		if (change.variable.equals("ObjectName")) {
			return unnumbered(before).equals(unnumbered(after));
		}

		if (change.variable.equals("Parent")) {
			final String was = idsBefore.get(before);
			return was != null && was.equals(idsAfter.get(after));
		}

		return false;
	}

	private static String unnumbered (final String objectName) {
		return objectName.replaceFirst("_\\d+$", "");
	}

	private static final String CAPACITY = "Capacity";

	/**
	 * A .NET list keeps the size of the buffer behind it, and the buffer grows
	 * by doubling as items come and go: a list holding the same items can
	 * differ in that alone.
	 */
	private static boolean onlyCapacity (final Change change) {
		if (!(change.before instanceof CollectionProperty) || !(change.after instanceof CollectionProperty)) {
			return false;
		}

		final List<Leaf> leaves = SaveDiff.leaves(change.before, change.after);
		return !leaves.isEmpty() && leaves.stream().allMatch(leaf -> leaf.path.equals("." + CAPACITY));
	}

	// ---------------------------------------------------------------- people

	/** A character either save holds. */
	private static final class Person {
		final String id;
		/** Their object's name in each save; the game renumbers it on every load. */
		String nameBefore;
		String nameAfter;
		String name;
		String kind;
		int slot = 99;
		boolean player;

		Person (final String id) {
			this.id = id;
		}
	}

	private void findPeople (final List<Property> packets, final boolean before) {
		for (final Property property : packets) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			if (packet.ObjectID == null || packet.ObjectName == null || packet.ComponentPackets == null) {
				continue;
			}

			// The player and the companions, and whoever else keeps a party
			// member's records: an animal companion, most often.
			final String lower = packet.ObjectName.toLowerCase(Locale.ROOT);
			final boolean party = lower.startsWith("player_") || lower.startsWith("companion_");
			final Optional<ComponentPersistencePacket> stats = findComponent(packet.ComponentPackets, "CharacterStats");
			if (!stats.isPresent()
				|| (!party && !findComponent(packet.ComponentPackets, "PartyMemberStats").isPresent())) {

				continue;
			}

			final String id = packet.ObjectID.toLowerCase(Locale.ROOT);
			final Person person = people.computeIfAbsent(id, Person::new);
			personByObjectName.put(packet.ObjectName, id);
			if (before) {
				person.nameBefore = packet.ObjectName;
			} else {
				person.nameAfter = packet.ObjectName;
			}

			// The second save's word wins: it is the later name.
			person.player = lower.startsWith("player_");
			person.name = party ? nameOf(packet, stats.get()) : petName(packet, stats.get());
			person.kind = person.player ? "Main character"
				: !party ? (lower.contains("animal_companion") ? "Animal companion" : "Party member")
				: lower.startsWith("companion_generic") ? "Hired adventurer"
				: lower.contains("_stored") ? "Companion (roster copy)"
				: "Companion";

			findComponent(packet.ComponentPackets, "PartyMemberAI")
				.map(ai -> ai.Variables.get("AssignedSlot"))
				.filter(slot -> slot instanceof Integer)
				.ifPresent(slot -> person.slot = (Integer) slot);
		}
	}

	/** As the character list names them: the name typed, else who they are. */
	private static String nameOf (final ObjectPersistencePacket packet, final ComponentPersistencePacket stats) {
		final Object override = stats.Variables.get("OverrideName");
		if (override instanceof String && !((String) override).isEmpty()) {
			return (String) override;
		}

		final String name = EKUtils.extractCharacterName(packet.ObjectName);
		return CompanionRegistry.displayNameFor(packet.ObjectName, "").orElse(name);
	}

	/** "CRE_ArcticFox_Animal_Companion(Clone)_11" is "Arctic Fox". */
	private static String petName (final ObjectPersistencePacket packet, final ComponentPersistencePacket stats) {
		final Object override = stats.Variables.get("OverrideName");
		if (override instanceof String && !((String) override).isEmpty()) {
			return (String) override;
		}

		return title(EKUtils.extractCharacterName(packet.ObjectName)
			.replaceFirst("(?i)_?Animal_Companion$", "")
			.replaceFirst("_\\d+$", ""));
	}

	private List<Person> peopleInOrder () {
		final List<Person> ordered = new ArrayList<>(people.values());
		ordered.sort((a, b) -> {
			if (a.player != b.player) {
				return a.player ? -1 : 1;
			}

			if (a.slot != b.slot) {
				return Integer.compare(a.slot, b.slot);
			}

			return a.name.compareToIgnoreCase(b.name);
		});

		return ordered;
	}

	private String personName (final String id) {
		final Person person = id == null ? null : people.get(id.toLowerCase(Locale.ROOT));
		return person == null ? "someone no longer in the save" : person.name;
	}

	// ----------------------------------------------------------------- items

	private static final Map<String, String> CONTAINERS = new LinkedHashMap<>();
	static {
		CONTAINERS.put("PlayerInventory", "pack");
		CONTAINERS.put("Inventory", "pack");
		CONTAINERS.put("QuickbarInventory", "quick slot");
		CONTAINERS.put("StashInventory", "Stash");
		CONTAINERS.put("QuestInventory", "Quest items");
		CONTAINERS.put("CraftingInventory", "Crafting bag");
	}

	// The game's slot names, as the Inventory tab's paper doll shows them.
	private static final String[] SLOT_NAMES = {
		"head", "neck", "chest", "hands", "right-hand ring", "left-hand ring"
		, "cape", "feet", "waist", "grimoire", "pet"};

	private static final String[] SETS = {"I", "II", "III", "IV"};

	/** Where one item is: whose, in what, and how many. */
	private static final class Place {
		final String holder;
		final String where;
		final boolean shared;
		final int stack;
		final String baseItem;

		Place (final String holder, final String where, final boolean shared, final int stack, final String baseItem) {
			this.holder = holder;
			this.where = where;
			this.shared = shared;
			this.stack = stack;
			this.baseItem = baseItem;
		}

		boolean samePlace (final Place other) {
			return Objects.equals(holder, other.holder) && where.equals(other.where);
		}
	}

	/** Every item the party carries, wears or keeps in the stash, by its lower-case ID. */
	private Map<String, Place> places (final List<Property> packets) {
		final Map<String, String> names = new HashMap<>();
		for (final Property property : packets) {
			if (property.obj instanceof ObjectPersistencePacket) {
				final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
				if (packet.ObjectID != null && packet.ObjectName != null) {
					names.put(packet.ObjectID.toLowerCase(Locale.ROOT), packet.ObjectName);
				}
			}
		}

		final Map<String, Place> places = new LinkedHashMap<>();
		for (final Property property : packets) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			if (packet.ObjectID == null || !people.containsKey(packet.ObjectID.toLowerCase(Locale.ROOT))
				|| packet.ComponentPackets == null) {

				continue;
			}

			final String holder = packet.ObjectID.toLowerCase(Locale.ROOT);
			for (final Map.Entry<String, String> container : CONTAINERS.entrySet()) {
				final Optional<ComponentPersistencePacket> component =
					findComponent(packet.ComponentPackets, container.getKey());

				if (!component.isPresent()) {
					continue;
				}

				final Object items = component.get().Variables.get("ItemList");
				final List<String> guids = SavedGameOpener.guidList(component.get().Variables.get("SerializedItemList"));
				if (!(items instanceof CSharpCollection)) {
					continue;
				}

				final boolean shared = !"pack".equals(container.getValue())
					&& !"quick slot".equals(container.getValue());

				int i = 0;
				for (final Iterator it = ((CSharpCollection) items).iterator(); it.hasNext(); i++) {
					final Object item = it.next();
					if (!(item instanceof InventoryItem) || i >= guids.size()) {
						continue;
					}

					final String guid = guids.get(i).toLowerCase(Locale.ROOT);
					final InventoryItem entry = (InventoryItem) item;
					places.put(guid, new Place(holder, container.getValue(), shared, entry.StackSize
						, baseItem(entry.BaseItem, names.get(guid))));
				}
			}

			final Optional<ComponentPersistencePacket> equipment = findComponent(packet.ComponentPackets, "Equipment");
			if (!equipment.isPresent()) {
				continue;
			}

			final List<String> worn = SavedGameOpener.guidList(equipment.get().Variables.get("EquipmentSetSerialized"));
			for (int i = 0; i < worn.size(); i++) {
				final String guid = worn.get(i).toLowerCase(Locale.ROOT);
				if (names.containsKey(guid)) {
					places.put(guid, new Place(holder, i < SLOT_NAMES.length ? SLOT_NAMES[i] : "slot " + i
						, false, 1, baseItem(null, names.get(guid))));
				}
			}

			final List<String> held = SavedGameOpener.guidList(equipment.get().Variables.get("WeaponSetsSerialized"));
			for (int i = 0; i < held.size(); i++) {
				final String guid = held.get(i).toLowerCase(Locale.ROOT);
				if (names.containsKey(guid)) {
					final String set = i / 2 < SETS.length ? SETS[i / 2] : String.valueOf(i / 2 + 1);
					places.put(guid, new Place(holder, "weapon set " + set + (i % 2 == 0 ? ", main hand" : ", off hand")
						, false, 1, baseItem(null, names.get(guid))));
				}
			}
		}

		return places;
	}

	/**
	 * What an item is, as the inventory names it: the entry's own prefab path,
	 * or -- for the many entries that carry none, and for worn gear, which has
	 * no entry -- its object's name less the "(Clone)".
	 */
	private static String baseItem (final String path, final String objectName) {
		if (path != null && !path.isEmpty()) {
			return path;
		}

		return objectName == null ? "" : objectName.replace("(Clone)", "").trim();
	}

	private String itemName (final String baseItem) {
		final Optional<ItemCatalog.Entry> entry = ItemCatalog.getInstance().lookup(baseItem);
		return entry.isPresent() && !entry.get().name.isEmpty()
			? entry.get().name : SavedGameOpener.prettifyItemName(baseItem);
	}

	private String iconOf (final String baseItem) {
		final String key = ItemCatalog.keyOf(baseItem);
		if (!icons.has(key)) {
			ItemCatalog.getInstance().lookup(baseItem)
				.filter(entry -> !entry.icon.isEmpty())
				.map(entry -> ItemCatalog.getInstance().iconData(entry.icon))
				.filter(data -> !data.isEmpty())
				.ifPresent(data -> icons.put(key, data));
		}

		return icons.has(key) ? key : null;
	}

	private String describe (final Place place) {
		return describe(place, false);
	}

	private String describe (final Place place, final boolean count) {
		final String where = place.shared ? place.where : personName(place.holder) + " · " + place.where;
		return place.stack > 1 || count ? where + " · ×" + place.stack : where;
	}

	private Row itemRow (final String baseItem, final Object before, final Object after) {
		final Row row = new Row(itemName(baseItem), before, after);
		row.item = baseItem;
		return row;
	}

	private Section itemSection () {
		final Section section = new Section("items", "Items");
		final Set<String> guids = new LinkedHashSet<>(placesBefore.keySet());
		guids.addAll(placesAfter.keySet());

		final List<String> lost = new ArrayList<>();
		final List<String> gained = new ArrayList<>();
		for (final String guid : guids) {
			final Place before = placesBefore.get(guid);
			final Place after = placesAfter.get(guid);
			final String baseItem = (after != null ? after : before).baseItem;
			claimObject(guid);

			if (before == null) {
				gained.add(guid);
			} else if (after == null) {
				lost.add(guid);
			} else if (!before.samePlace(after)) {
				section.group("Moved", null).rows.add(itemRow(baseItem, describe(before), describe(after)));
			} else if (before.stack != after.stack) {
				section.group("Quantity", null).rows.add(
					itemRow(baseItem, describe(before, true), describe(after, true)));
			}

			// The item's own object: what the lists say explains its parent
			// and its coming and going; anything else about it is its own.
			for (final Change change : open(guid, change -> true)) {
				if (change.variable.equals("Mods") && reordered(change)) {
					bookkeeping(change, "Mods");
					continue;
				}

				claim(change);
				if (change.component.isEmpty()) {
					continue;
				}

				if (change.variable.equals("Mods")) {
					for (final String mod : minus(strings(change.before), strings(change.after))) {
						section.group("Enchantments", null).rows.add(itemRow(baseItem, modName(mod), null));
					}

					for (final String mod : minus(strings(change.after), strings(change.before))) {
						section.group("Enchantments", null).rows.add(itemRow(baseItem, null, modName(mod)));
					}

					continue;
				}

				rows(itemName(baseItem) + " · " + words(change.variable), change, null)
					.forEach(section.group("Changed", null).rows::add);
			}
		}

		// The game makes some items afresh, under a new ID, for the same thing
		// in the same place -- stacks in the stash, mostly. That is no news, and
		// the same thing in the same place in another number is a quantity.
		for (final Iterator<String> it = lost.iterator(); it.hasNext();) {
			final Place was = placesBefore.get(it.next());
			final Optional<String> same = gained.stream()
				.filter(guid -> sameThing(was, placesAfter.get(guid)) && placesAfter.get(guid).stack == was.stack)
				.findFirst();

			if (same.isPresent()) {
				gained.remove(same.get());
				it.remove();
			}
		}

		for (final Iterator<String> it = lost.iterator(); it.hasNext();) {
			final Place was = placesBefore.get(it.next());
			final Optional<String> restacked = gained.stream()
				.filter(guid -> sameThing(was, placesAfter.get(guid)))
				.findFirst();

			if (restacked.isPresent()) {
				final Place is = placesAfter.get(restacked.get());
				section.group("Quantity", null).rows.add(
					itemRow(is.baseItem, describe(was, true), describe(is, true)));
				gained.remove(restacked.get());
				it.remove();
			}
		}

		// And the same thing somewhere else has moved: in two real saves fifty
		// minutes apart, boots put in the stash came out of it under a new ID.
		for (final Iterator<String> it = lost.iterator(); it.hasNext();) {
			final Place was = placesBefore.get(it.next());
			final Optional<String> moved = gained.stream()
				.filter(guid -> ItemCatalog.keyOf(was.baseItem).equals(ItemCatalog.keyOf(placesAfter.get(guid).baseItem)))
				.findFirst();

			if (moved.isPresent()) {
				final Place is = placesAfter.get(moved.get());
				section.group("Moved", null).rows.add(itemRow(is.baseItem, describe(was), describe(is)));
				gained.remove(moved.get());
				it.remove();
			}
		}

		for (final String guid : lost) {
			section.group("Lost", null).rows.add(
				itemRow(placesBefore.get(guid).baseItem, describe(placesBefore.get(guid)), null));
		}

		for (final String guid : gained) {
			section.group("Gained", null).rows.add(
				itemRow(placesAfter.get(guid).baseItem, null, describe(placesAfter.get(guid))));
		}

		// The lists the rows above were read from.
		for (final Person person : people.values()) {
			for (final Change change : open(person.id, change -> isItemList(change))) {
				claim(change);
				if (!containerChangedPlaces(person.id, change)) {
					section.group("Rearranged", null).rows.add(
						new Row(containerName(person, change.component), null, null)
							.note("the same items in another order"));
				}
			}
		}

		// Gained before lost before the rest, as a player reads it.
		final List<String> order = Arrays.asList(
			"Gained", "Lost", "Moved", "Quantity", "Enchantments", "Changed", "Rearranged");
		section.groups.sort((a, b) -> Integer.compare(order.indexOf(a.title), order.indexOf(b.title)));

		for (final Group group : section.groups) {
			final int more = group.rows.size() - MOST_ITEM_ROWS;
			if (more > 0) {
				group.rows.subList(MOST_ITEM_ROWS, group.rows.size()).clear();
				group.rows.add(new Row(String.format("%,d more", more), null, null)
					.note("not listed, to keep this readable"));
			}

			for (final Row row : group.rows) {
				if (row.item != null) {
					row.icon = iconOf(row.item);
				}
			}
		}

		return section;
	}

	/** The same item in the same place: what a player would call the same thing. */
	private static boolean sameThing (final Place a, final Place b) {
		return a != null && b != null && a.samePlace(b)
			&& ItemCatalog.keyOf(a.baseItem).equals(ItemCatalog.keyOf(b.baseItem));
	}

	/**
	 * An item's mods are a list of prefab paths, and the game sorts them
	 * afresh on load: the same enchantments in another order are no change.
	 */
	private static boolean reordered (final Change change) {
		final List<String> before = strings(change.before);
		final List<String> after = strings(change.after);
		return minus(before, after).isEmpty() && minus(after, before).isEmpty();
	}

	/** "Assets/Data/Prefabs/ItemMods/ArmorMods/OfResolve2.prefab" is "Of Resolve2". */
	private static String modName (final String path) {
		final String file = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
		return title(file.replaceFirst("(?i)\\.prefab$", ""));
	}

	private static boolean isItemList (final Change change) {
		if (CONTAINERS.containsKey(change.component)) {
			return change.variable.equals("ItemList") || change.variable.equals("SerializedItemList");
		}

		return change.component.equals("Equipment")
			&& (change.variable.equals("EquipmentSetSerialized") || change.variable.equals("WeaponSetsSerialized"));
	}

	private String containerName (final Person person, final String component) {
		final String where = CONTAINERS.get(component);
		if (where == null) {
			return person.name + " · worn and held";
		}

		return "pack".equals(where) || "quick slot".equals(where) ? person.name + " · " + where : where;
	}

	/** Whether any item row explains a change to one of this character's lists. */
	private boolean containerChangedPlaces (final String holder, final Change change) {
		final Predicate<Place> inIt = place -> place != null && holder.equals(place.holder)
			&& (CONTAINERS.containsKey(change.component)
				? CONTAINERS.get(change.component).equals(place.where)
				: !CONTAINERS.containsValue(place.where));

		final Set<String> guids = new LinkedHashSet<>(placesBefore.keySet());
		guids.addAll(placesAfter.keySet());
		for (final String guid : guids) {
			final Place before = placesBefore.get(guid);
			final Place after = placesAfter.get(guid);
			final boolean differs = before == null || after == null
				|| !before.samePlace(after) || before.stack != after.stack;

			if (differs && (inIt.test(before) || inIt.test(after))) {
				return true;
			}
		}

		return false;
	}

	// ------------------------------------------------------------- grimoires

	private Section grimoireSection () {
		final Section section = new Section("grimoires", "Grimoires");
		final Set<String> ids = new LinkedHashSet<>(diff.before.keySet());
		ids.addAll(diff.after.keySet());

		for (final String id : ids) {
			final List<Change> changes = open(id, "Grimoire");
			if (changes.isEmpty()) {
				continue;
			}

			// Where the book is: in someone's lists, else hanging off whoever its
			// Parent names, which is how the Grimoire tab says who holds it.
			final Place place = placesAfter.containsKey(id) ? placesAfter.get(id) : placesBefore.get(id);
			final String title = place != null ? itemName(place.baseItem) : objectTitle(id);
			final String holder = personByObjectName.get(text(packetOf(id), "Parent"));
			final Group group = section.group(title, place != null ? describe(place)
				: holder != null ? personName(holder) : areaOf(id));

			for (final Change change : changes) {
				claim(change);
				if (change.variable.equals("SerializedSpells")) {
					// Eight chapters of object references, null in every save.
					continue;
				}

				if (!change.variable.equals("SerializedSpellNames")) {
					group.rows.addAll(rows(words(change.variable), change, null));
					continue;
				}

				final List<String> before = strings(change.before);
				final List<String> after = strings(change.after);
				final TreeMap<String, Row> spells = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
				for (final String spell : minus(before, after)) {
					spells.put(abilityName(spell), new Row(abilityName(spell), "in the book", null));
				}

				for (final String spell : minus(after, before)) {
					spells.put(abilityName(spell), new Row(abilityName(spell), null, "in the book"));
				}

				group.rows.addAll(spells.values());
			}
		}

		return section;
	}

	// ------------------------------------------------------------- abilities

	private Section abilitySection () {
		final Section section = new Section("abilities", "Abilities and talents");
		for (final Person person : peopleInOrder()) {
			final Map<String, ObjectPersistencePacket> before =
				AbilityManager.abilitiesOf(first.packets, person.nameBefore);
			final Map<String, ObjectPersistencePacket> after =
				AbilityManager.abilitiesOf(second.packets, person.nameAfter);

			final List<Row> rows = new ArrayList<>();
			final List<String> lost = new ArrayList<>();
			final List<String> learned = new ArrayList<>();
			for (final Map.Entry<String, ObjectPersistencePacket> ability : before.entrySet()) {
				if (!after.containsKey(ability.getKey())) {
					lost.add(prefabOf(ability.getValue().ObjectName));
					changedHands(ability.getKey().toLowerCase(Locale.ROOT));
				}
			}

			for (final Map.Entry<String, ObjectPersistencePacket> ability : after.entrySet()) {
				if (!before.containsKey(ability.getKey())) {
					learned.add(prefabOf(ability.getValue().ObjectName));
					changedHands(ability.getKey().toLowerCase(Locale.ROOT));
				}
			}

			// The same ability under a new object is not news.
			for (final String prefab : minus(lost, learned)) {
				rows.add(new Row(abilityName(prefab), kindOf(prefab, "ability"), null));
			}

			for (final String prefab : minus(learned, lost)) {
				rows.add(new Row(abilityName(prefab), null, kindOf(prefab, "ability")));
			}

			open(person.id, "CharacterStats", "m_serializedTalents").ifPresent(change -> {
				claim(change);
				for (final String talent : minus(strings(change.before), strings(change.after))) {
					rows.add(new Row(abilityName(talent), "talent", null));
				}

				for (final String talent : minus(strings(change.after), strings(change.before))) {
					rows.add(new Row(abilityName(talent), null, "talent"));
				}
			});

			if (!rows.isEmpty()) {
				section.group(person.name, person.kind).rows.addAll(rows);
			}
		}

		return section;
	}

	/**
	 * An ability one character lost and another learned is often the same
	 * object: an item's abilities belong to whoever wears it, and handing the
	 * item over re-parents them. Its parent and owner are the rows' news.
	 */
	private void changedHands (final String id) {
		claimObject(id);
		open(id, change -> (change.component.isEmpty() && change.variable.equals("Parent"))
			|| change.variable.equals("Owner")).forEach(this::claim);
	}

	private static String prefabOf (final String objectName) {
		return objectName == null ? "" : objectName.replace("(Clone)", "").replaceFirst("_\\d+$", "").trim();
	}

	private static String abilityName (final String prefab) {
		final Optional<AbilityCatalog.Entry> entry = AbilityCatalog.getInstance().lookup(prefab);
		return entry.isPresent() && !entry.get().name.isEmpty() ? entry.get().name : prefab.replace('_', ' ').trim();
	}

	private static String kindOf (final String prefab, final String otherwise) {
		return AbilityCatalog.getInstance().lookup(prefab)
			.map(entry -> entry.kind)
			.filter(kind -> kind != null && !kind.isEmpty())
			.orElse(otherwise);
	}

	// -------------------------------------------------------------- the save

	private static final Map<String, String> INFO_LABELS = new LinkedHashMap<>();
	static {
		INFO_LABELS.put("SceneTitle", "Area");
		INFO_LABELS.put("Chapter", "Chapter");
		INFO_LABELS.put("UserSaveName", "Name in the load list");
		INFO_LABELS.put("PlayerName", "Main character's name");
		INFO_LABELS.put("RealtimePlayDurationSeconds", "Time played");
		INFO_LABELS.put("RealTimestamp", "Saved");
		INFO_LABELS.put("GameComplete", "Game finished");
		INFO_LABELS.put("ActivePackages", "Expansions");
	}

	private static final Map<String, String> SAVE_LABELS = new HashMap<>();
	static {
		SAVE_LABELS.put("GameState.Difficulty", "Difficulty");
		SAVE_LABELS.put("GameState.ExpertMode", "Expert mode");
		SAVE_LABELS.put("GameState.TrialOfIron", "Trial of Iron");
		SAVE_LABELS.put("GameState.TacticalMode", "Turn-based mode");
		SAVE_LABELS.put("GameState.CheatsEnabled", "Cheats used");
		SAVE_LABELS.put("GameState.ActiveScalers", "Difficulty scaling");
		SAVE_LABELS.put("WorldTime.CurrentTime", "Game time");
		SAVE_LABELS.put("WorldTime.TimeSpentTravelling", "Time spent travelling");
		SAVE_LABELS.put("WorldTime.TimeInCombat", "Time in combat");
		SAVE_LABELS.put("PlayerInventory.currencyTotalValue", "Money");
		SAVE_LABELS.put("PlayerInventory.campingSupplies", "Camping supplies");
		SAVE_LABELS.put("AchievementTracker.m_disableAchievements", "Achievements turned off");
	}

	private Section saveSection () {
		final Section section = new Section("save", "The save");
		final Group group = section.group("", null);

		for (final Map.Entry<String, String> field : INFO_LABELS.entrySet()) {
			final String before = first.info.get(field.getKey());
			final String after = second.info.get(field.getKey());
			if (Objects.equals(before, after)) {
				continue;
			}

			final Row row = new Row(field.getValue(), infoValue(before), infoValue(after));
			if (field.getKey().equals("RealtimePlayDurationSeconds")) {
				row.format("duration");
			}

			group.rows.add(row);
		}

		final String world = objectNamed(name -> name.startsWith("Global(Clone)") || name.equals("Global"));
		if (world != null) {
			for (final Change change : open(world, change -> change.component.equals("GameState")
				|| change.component.equals("WorldTime"))) {

				claim(change);
				if (change.variable.equals("CurrentTime")) {
					group.rows.add(gameTime(change));
					continue;
				}

				group.rows.addAll(rows(saveLabel(change), change, change.component.equals("WorldTime") ? "duration" : null));
			}
		}

		final String player = people.values().stream().filter(person -> person.player)
			.map(person -> person.id).findFirst().orElse(null);
		if (player != null) {
			for (final String variable : Arrays.asList("currencyTotalValue", "campingSupplies")) {
				open(player, "PlayerInventory", variable).ifPresent(change -> {
					claim(change);
					group.rows.addAll(rows(saveLabel(change), change
						, variable.equals("currencyTotalValue") ? "money" : null));
				});
			}
		}

		final String global = objectNamed(name -> name.startsWith("InGameGlobal"));
		if (global != null) {
			open(global, "AchievementTracker", "m_disableAchievements").ifPresent(change -> {
				claim(change);
				group.rows.addAll(rows(saveLabel(change), change, null));
			});
		}

		return section;
	}

	private static Object infoValue (final String value) {
		if (value == null) {
			return null;
		}

		if (value.matches("-?\\d{1,15}")) {
			return Long.parseLong(value);
		}

		if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
			return Boolean.parseBoolean(value);
		}

		return value;
	}

	private static String saveLabel (final Change change) {
		final String label = SAVE_LABELS.get(change.component + "." + change.variable);
		return label != null ? label : change.component + " · " + words(change.variable);
	}

	/**
	 * The game's clock, as days into the adventure and the time of day: a day
	 * is 24 hours here ({@code WorldTime.HoursPerDay}), and the adventure
	 * begins at {@code WorldTime.AdventureStart}.
	 */
	private Row gameTime (final Change change) {
		final String world = objectNamed(name -> name.startsWith("Global(Clone)") || name.equals("Global"));
		final long start = seconds(variableOf(diff.after.get(world), "WorldTime", "AdventureStart"));
		final Object before = clock(seconds(change.before), start);
		final Object after = clock(seconds(change.after), start);
		final Row row = new Row("Game time", before, after);
		if (change.before != null && change.after != null) {
			final long passed = seconds(change.after) - seconds(change.before);
			row.change = (passed < 0 ? "−" : "+") + duration(Math.abs(passed));
		}

		return row;
	}

	// Days are counted by the calendar, so that the day turns at midnight
	// with the time of day beside it.
	private static Object clock (final long seconds, final long start) {
		if (seconds < 0) {
			return null;
		}

		final long day = seconds / 86400 - Math.max(0, start) / 86400 + 1;
		final long time = seconds % 86400;
		return String.format(Locale.ROOT, "day %d, %02d:%02d", day, time / 3600, time % 3600 / 60);
	}

	private static String duration (final long seconds) {
		final long days = seconds / 86400;
		final long hours = seconds % 86400 / 3600;
		final long minutes = seconds % 3600 / 60;
		if (days > 0) {
			return days + " d " + hours + " h";
		}

		return hours > 0 ? hours + " h " + minutes + " min" : minutes + " min";
	}

	/** A time held as a whole number of seconds, however the value wraps it. */
	private static long seconds (final Property property) {
		final Object value = value(property);
		return value instanceof Number ? ((Number) value).longValue() : -1;
	}

	private static Property variableOf (final ComplexProperty packet, final String component, final String variable) {
		if (packet == null) {
			return null;
		}

		final Property list = packet.findProperty("ComponentPackets").orElse(null);
		if (!(list instanceof SingleDimensionalArrayProperty)) {
			return null;
		}

		for (final Object item : ((SingleDimensionalArrayProperty) list).items) {
			if (item instanceof ComplexProperty
				&& component.equals(text((ComplexProperty) item, "TypeString"))) {

				final Property variables = ((ComplexProperty) item).findProperty("Variables").orElse(null);
				if (variables instanceof DictionaryProperty) {
					return ((DictionaryProperty) variables).findEntry(variable).map(p -> (Property) p).orElse(null);
				}
			}
		}

		return null;
	}

	private static String text (final ComplexProperty owner, final String field) {
		return owner.findProperty(field).map(p -> String.valueOf(((Property) p).obj)).orElse("");
	}

	/** The ID of an object either save holds whose name passes {@code test}. */
	private String objectNamed (final Predicate<String> test) {
		for (final Map<String, ComplexProperty> objects : Arrays.asList(diff.after, diff.before)) {
			for (final Map.Entry<String, ComplexProperty> object : objects.entrySet()) {
				if (test.test(text(object.getValue(), "ObjectName"))) {
					return object.getKey();
				}
			}
		}

		return null;
	}

	// ------------------------------------------------------------ stronghold

	private static final Map<String, String> STRONGHOLD_LABELS = new HashMap<>();
	static {
		STRONGHOLD_LABELS.put("SerializedIsActivated", "Owned");
		STRONGHOLD_LABELS.put("Prestige", "Prestige");
		STRONGHOLD_LABELS.put("Security", "Security");
		STRONGHOLD_LABELS.put("AvailableTurns", "Turns waiting to be played");
		STRONGHOLD_LABELS.put("m_currentTurn", "Turn");
		STRONGHOLD_LABELS.put("m_Debt", "Debt");
		STRONGHOLD_LABELS.put("BonusTurnMoney", "Bonus money per turn");
		STRONGHOLD_LABELS.put("IsErlTaxActive", "Erl's tax");
		STRONGHOLD_LABELS.put("m_disabled", "Disabled");
		STRONGHOLD_LABELS.put("UnviewedEventCount", "Events not yet seen");
		STRONGHOLD_LABELS.put("m_log", "Log");
		STRONGHOLD_LABELS.put("m_events", "Events");
		STRONGHOLD_LABELS.put("m_adventuresEngaged", "Adventures under way");
		STRONGHOLD_LABELS.put("m_upgradesSpawned", "Upgrades standing");
	}

	private Section strongholdSection () {
		final Section section = new Section("stronghold", "Stronghold");
		final String global = objectNamed(name -> name.startsWith("InGameGlobal"));
		if (global == null) {
			return section;
		}

		final Group group = section.group("Caed Nua", null);
		final List<Change> changes = open(global, "Stronghold");

		// Ownership first, then the numbers, then who and what is there.
		changes.sort((a, b) -> Integer.compare(strongholdOrder(a), strongholdOrder(b)));
		for (final Change change : changes) {
			claim(change);
			switch (change.variable) {
				case "m_upgradesBuilt":
					upgrades(change, group);
					break;

				case "m_hirelingsHired":
					people(SavedGameOpener.hirelings(mirror(global, true, "m_hirelingsHired"))
						, SavedGameOpener.hirelings(mirror(global, false, "m_hirelingsHired")), "on the payroll", group);
					break;

				case "m_prisoners":
					people(SavedGameOpener.prisoners(mirror(global, true, "m_prisoners"))
						, SavedGameOpener.prisoners(mirror(global, false, "m_prisoners")), "in the dungeon", group);
					break;

				case "SerializedStoredGuids":
					for (final String guid : minus(strings(change.before), strings(change.after))) {
						group.rows.add(new Row(personName(guid), "waiting at the keep", null));
					}

					for (final String guid : minus(strings(change.after), strings(change.before))) {
						group.rows.add(new Row(personName(guid), null, "waiting at the keep"));
					}

					break;

				default:
					final String label = STRONGHOLD_LABELS.get(change.variable);
					group.rows.addAll(rows(label != null ? label : words(change.variable), change, null));
			}
		}

		return section;
	}

	private static int strongholdOrder (final Change change) {
		switch (change.variable) {
			case "SerializedIsActivated": return 0;
			case "Prestige": return 1;
			case "Security": return 2;
			case "m_upgradesBuilt": return 3;
			case "m_hirelingsHired": return 4;
			case "m_prisoners": return 5;
			case "SerializedStoredGuids": return 6;
			default: return 7;
		}
	}

	/** The mirror object a stronghold list deserialized to, on one side. */
	private Object mirror (final String id, final boolean before, final String variable) {
		for (final Property property : (before ? first : second).packets) {
			if (property.obj instanceof ObjectPersistencePacket
				&& id.equalsIgnoreCase(((ObjectPersistencePacket) property.obj).ObjectID)) {

				return findComponent(((ObjectPersistencePacket) property.obj).ComponentPackets, "Stronghold")
					.map(component -> component.Variables.get(variable))
					.orElse(null);
			}
		}

		return null;
	}

	private void upgrades (final Change change, final Group group) {
		final List<String> before = upgradeNames(change.before);
		final List<String> after = upgradeNames(change.after);
		for (final String name : minus(before, after)) {
			group.rows.add(new Row(name, "built", null));
		}

		for (final String name : minus(after, before)) {
			group.rows.add(new Row(name, null, "built"));
		}
	}

	private static List<String> upgradeNames (final Property list) {
		final List<String> names = new ArrayList<>();
		for (final Property item : items(list)) {
			final Object value = item instanceof SimpleProperty ? ((SimpleProperty) item).value : null;
			if (value instanceof StrongholdUpgrade.Type) {
				final StrongholdUpgrade.Type type = (StrongholdUpgrade.Type) value;
				names.add(StrongholdCatalog.getInstance().lookup(type)
					.map(upgrade -> upgrade.name)
					.filter(name -> !name.isEmpty())
					.orElse(title(type.name())));
			} else if (value != null) {
				names.add("Upgrade " + value);
			}
		}

		return names;
	}

	private static void people (
		final JSONArray before, final JSONArray after, final String where, final Group group) {

		final Map<String, JSONObject> first = byKey(before);
		final Map<String, JSONObject> second = byKey(after);
		for (final Map.Entry<String, JSONObject> entry : first.entrySet()) {
			if (!second.containsKey(entry.getKey())) {
				group.rows.add(new Row(entry.getValue().optString("name"), where, null));
			}
		}

		for (final Map.Entry<String, JSONObject> entry : second.entrySet()) {
			final JSONObject was = first.get(entry.getKey());
			if (was == null) {
				group.rows.add(new Row(entry.getValue().optString("name"), null, where));
			} else if (was.has("paid") && was.optBoolean("paid") != entry.getValue().optBoolean("paid")) {
				group.rows.add(new Row(entry.getValue().optString("name") + " · paid"
					, was.optBoolean("paid"), entry.getValue().optBoolean("paid")));
			}
		}
	}

	private static Map<String, JSONObject> byKey (final JSONArray list) {
		final Map<String, JSONObject> byKey = new LinkedHashMap<>();
		for (int i = 0; i < list.length(); i++) {
			byKey.put(list.getJSONObject(i).optString("key") + "#" + i, list.getJSONObject(i));
		}

		// Keyed by the global; the place in the list only tells namesakes apart.
		final Map<String, JSONObject> keyed = new LinkedHashMap<>();
		final Map<String, Integer> seen = new HashMap<>();
		for (final JSONObject entry : byKey.values()) {
			final String key = entry.optString("key");
			keyed.put(key + "#" + seen.merge(key, 1, Integer::sum), entry);
		}

		return keyed;
	}

	// --------------------------------------------------------------- globals

	private Section globalSection () {
		final Section section = new Section("globals", "Global variables");
		final String global = objectNamed(name -> name.startsWith("InGameGlobal"));
		if (global == null) {
			return section;
		}

		open(global, "GlobalVariables", "m_data").ifPresent(change -> {
			claim(change);
			final Map<String, Row> rows = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			for (final Leaf leaf : SaveDiff.leaves(change.before, change.after)) {
				final String name = leaf.path.startsWith("[") && leaf.path.endsWith("]")
					? leaf.path.substring(1, leaf.path.length() - 1) : leaf.path;
				rows.put(name, new Row(name.isEmpty() ? "(unnamed)" : name, value(leaf.before), value(leaf.after)));
			}

			section.group("", null).rows.addAll(rows.values());
		});

		return section;
	}

	// --------------------------------------------------------------- journal

	private static final String[] AXES = {
		"Benevolent", "Cruel", "Clever", "Stoic", "Aggressive"
		, "Diplomatic", "Passionate", "Rational", "Honest", "Deceptive"};

	private Section journalSection () {
		final Section section = new Section("journal", "Quests and reputation");
		final String global = objectNamed(name -> name.startsWith("InGameGlobal"));
		if (global == null) {
			return section;
		}

		final List<Change> quests = open(global, "QuestManager");
		if (!quests.isEmpty()) {
			final Group group = section.group("Quests", null);
			quests.forEach(this::claim);
			group.rows.addAll(questRows(global));
			if (group.rows.isEmpty()) {
				group.rows.add(new Row("Active quests", null, null).note("changed, in a form the editor does not read"));
			}
		}

		for (final Change change : open(global, "ConversationManager")) {
			claim(change);
			section.group("Conversations", null).rows.add(
				change.variable.equals("MarkedAsRead")
					? new Row("Lines read", null, null).note("changed")
					: new Row(words(change.variable), value(change.before), value(change.after)));
		}

		for (final Change change : open(global, "ReputationManager")) {
			claim(change);
			final Group group = section.group("Reputation", null);
			if (change.variable.equals("PlayerDisposition")) {
				for (final Leaf leaf : SaveDiff.leaves(change.before, change.after)) {
					final String axis = axis(leaf.path);
					group.rows.add(new Row(axis != null ? "Disposition · " + axis : "Disposition" + leaf.path
						, value(leaf.before), value(leaf.after)));
				}
			} else {
				group.rows.addAll(rows(words(change.variable), change, null));
			}
		}

		return section;
	}

	private static String axis (final String path) {
		final java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("^\\.m_dispositions\\[(\\d+)]$").matcher(path);

		if (!matcher.matches()) {
			return null;
		}

		final int index = Integer.parseInt(matcher.group(1));
		return index < AXES.length ? AXES[index] : null;
	}

	/**
	 * A quest by its title, how it stands and what was reached in it.
	 * {@code Timestamps} keys each quest the party has met by its file and
	 * records when each objective was reached; the QuestTrackers blob keys the
	 * same files and says whether each is finished or failed and how many of
	 * its events have fired. The titles and objectives are the game's own text,
	 * beside the quest files.
	 */
	private List<Row> questRows (final String global) {
		final Optional<QuestTrackerBlob> trackersBefore =
			trackers(variableOf(diff.before.get(global), "QuestManager", "QuestTrackers"));
		final Optional<QuestTrackerBlob> trackersAfter =
			trackers(variableOf(diff.after.get(global), "QuestManager", "QuestTrackers"));
		final Map<Object, Property> before = entries(variableOf(diff.before.get(global), "QuestManager", "Timestamps"));
		final Map<Object, Property> after = entries(variableOf(diff.after.get(global), "QuestManager", "Timestamps"));

		final Set<String> quests = new LinkedHashSet<>();
		before.keySet().forEach(quest -> quests.add(String.valueOf(quest)));
		after.keySet().forEach(quest -> quests.add(String.valueOf(quest)));
		trackersBefore.ifPresent(blob -> quests.addAll(blob.quests()));
		trackersAfter.ifPresent(blob -> quests.addAll(blob.quests()));

		final TreeMap<String, Row> rows = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (final String quest : quests) {
			final Property was = before.get(quest);
			final Property is = after.get(quest);
			final Optional<QuestTrackerBlob.Tracker> trackedBefore = trackersBefore.flatMap(blob -> blob.tracker(quest));
			final Optional<QuestTrackerBlob.Tracker> trackedAfter = trackersAfter.flatMap(blob -> blob.tracker(quest));
			final boolean metBefore = was != null || trackedBefore.isPresent();
			final boolean metAfter = is != null || trackedAfter.isPresent();
			final String stateBefore = metBefore ? state(trackersBefore, quest) : null;
			final String stateAfter = metAfter ? state(trackersAfter, quest) : null;
			final int firedBefore = trackedBefore.map(QuestTrackerBlob.Tracker::eventsFired).orElse(0);
			final int firedAfter = trackedAfter.map(QuestTrackerBlob.Tracker::eventsFired).orElse(0);

			if (SaveDiff.same(was, is) && Objects.equals(stateBefore, stateAfter) && firedBefore == firedAfter) {
				continue;
			}

			final QuestText text = QuestText.of(quest);
			final Row row = new Row(text.title, stateBefore, stateAfter);
			final List<String> reached = new ArrayList<>();
			if (is != null) {
				for (final Leaf leaf : SaveDiff.leaves(was, is)) {
					final java.util.regex.Matcher objective = java.util.regex.Pattern
						.compile("^\\.objectiveTimestamps\\[(\\d+)]").matcher(leaf.path);

					if (objective.find() && seconds(leaf.after) > 0) {
						text.objective(Integer.parseInt(objective.group(1))).ifPresent(reached::add);
					}
				}
			}

			if (!reached.isEmpty()) {
				row.note(String.join(" ", new LinkedHashSet<>(reached)));
			} else if (firedAfter > firedBefore && Objects.equals(stateBefore, stateAfter)) {
				row.note("further on");
			}

			rows.put(text.title + "\u0000" + quest, row);
		}

		return new ArrayList<>(rows.values());
	}

	/**
	 * Whether the game counts a quest as under way, finished or failed: the
	 * QuestTrackers blob keys each quest by the same file Timestamps does.
	 */
	private static String state (final Optional<QuestTrackerBlob> trackers, final String quest) {
		final Optional<QuestTrackerBlob.Tracker> tracker = trackers.flatMap(blob -> blob.tracker(quest));
		if (!tracker.isPresent()) {
			return "under way";
		}

		return tracker.get().failed() ? "failed" : tracker.get().endState() >= 0 ? "finished" : "under way";
	}

	private static Optional<QuestTrackerBlob> trackers (final Property blob) {
		if (!(blob instanceof SimpleProperty) || !(((SimpleProperty) blob).value instanceof Byte[])) {
			return Optional.empty();
		}

		final Byte[] boxed = (Byte[]) ((SimpleProperty) blob).value;
		final byte[] bytes = new byte[boxed.length];
		for (int i = 0; i < boxed.length; i++) {
			bytes[i] = boxed[i] == null ? 0 : boxed[i];
		}

		return QuestTrackerBlob.parse(bytes);
	}

	private static Map<Object, Property> entries (final Property dictionary) {
		final Map<Object, Property> entries = new LinkedHashMap<>();
		if (dictionary instanceof DictionaryProperty) {
			for (final Map.Entry<Property, Property> entry : ((DictionaryProperty) dictionary).items) {
				if (entry != null) {
					entries.put(SaveDiff.keyOf(entry.getKey()), entry.getValue());
				}
			}
		}

		return entries;
	}

	// ------------------------------------------------------------ characters

	private static final Map<String, String> CHARACTER_LABELS = new HashMap<>();
	static {
		for (final String attribute : Arrays.asList(
			"Might", "Constitution", "Dexterity", "Perception", "Intellect", "Resolve")) {

			CHARACTER_LABELS.put("CharacterStats.Base" + attribute, attribute);
		}

		for (final String skill : Arrays.asList(
			"Athletics", "Lore", "Mechanics", "Stealth", "Survival", "Crafting")) {

			CHARACTER_LABELS.put("CharacterStats." + skill + "Skill", skill);
		}

		CHARACTER_LABELS.put("CharacterStats.RemainingSkillPoints", "Unspent skill points");
		CHARACTER_LABELS.put("CharacterStats.CharacterClass", "Class");
		CHARACTER_LABELS.put("CharacterStats.CharacterRace", "Race");
		CHARACTER_LABELS.put("CharacterStats.CharacterSubrace", "Subrace");
		CHARACTER_LABELS.put("CharacterStats.CharacterCulture", "Culture");
		CHARACTER_LABELS.put("CharacterStats.CharacterBackground", "Background");
		CHARACTER_LABELS.put("CharacterStats.PaladinOrder", "Paladin order");
		CHARACTER_LABELS.put("CharacterStats.OverrideName", "Name");
		CHARACTER_LABELS.put("CharacterStats.BaseDeflection", "Deflection");
		CHARACTER_LABELS.put("CharacterStats.BaseFortitude", "Fortitude");
		CHARACTER_LABELS.put("CharacterStats.BaseReflexes", "Reflex");
		CHARACTER_LABELS.put("CharacterStats.BaseWill", "Will");
		CHARACTER_LABELS.put("CharacterStats.MaxHealth", "Maximum health");
		CHARACTER_LABELS.put("CharacterStats.MaxStamina", "Maximum endurance");
		CHARACTER_LABELS.put("CharacterStats.m_CurrentFatigueLevel", "Fatigue");
		CHARACTER_LABELS.put("CharacterStats.SerializedStatusEffects", "Effects in play");
		CHARACTER_LABELS.put("Health.CurrentHealth", "Health");
		CHARACTER_LABELS.put("Health.CurrentStamina", "Endurance");
		CHARACTER_LABELS.put("Health.m_needs_current_values", "Refilled on the next load");
		CHARACTER_LABELS.put("PartyMemberStats.TotalDamageDone", "Damage done");
		CHARACTER_LABELS.put("PartyMemberStats.TotalHits", "Hits");
		CHARACTER_LABELS.put("PartyMemberStats.TimesKOed", "Times knocked out");
		CHARACTER_LABELS.put("PartyMemberStats.MaxSingleTargetDamage", "Biggest hit");
		CHARACTER_LABELS.put("PartyMemberStats.TimeInParty", "Time in the party");
		CHARACTER_LABELS.put("PartyMemberAI.IsActiveInParty", "In the party");
		CHARACTER_LABELS.put("PartyMemberAI.AssignedSlot", "Party slot");
		CHARACTER_LABELS.put("Portrait.m_textureLargePath", "Portrait");
		CHARACTER_LABELS.put("Portrait.m_textureSmallPath", "Portrait (party bar)");
		CHARACTER_LABELS.put("Equipment.SelectedWeaponSetSerialized", "Weapon set in hand");
	}

	/** Where a character's values live that need no component named beside them. */
	private static final Set<String> OWN = new HashSet<>(Arrays.asList(
		"CharacterStats", "Health", "PartyMemberStats", "PartyMemberAI", "Portrait"));

	private Section characterSection () {
		final Section section = new Section("characters", "Characters");
		for (final Person person : peopleInOrder()) {
			final Group group = section.group(person.name, person.kind);
			if (claimObject(person.id)) {
				group.note = person.kind + (diff.added.contains(person.id)
					? ", new in the save" : ", no longer in the save");
			}

			for (final Change change : open(person.id, change -> true)) {
				claim(change);
				final String key = change.component + "." + change.variable;
				final String label = CHARACTER_LABELS.containsKey(key) ? CHARACTER_LABELS.get(key)
					: change.component.isEmpty() ? words(change.variable)
					: OWN.contains(change.component) ? words(change.variable)
					: change.component + " · " + words(change.variable);

				final String format = key.endsWith("Skill") && key.startsWith("CharacterStats.") ? "skill"
					: key.startsWith("PartyMemberStats.Time") ? "duration"
					: null;

				final List<Row> rows = rows(label, change, format);
				if (change.component.equals("Portrait")) {
					rows.forEach(row -> {
						row.before = fileName(row.before);
						row.after = fileName(row.after);
					});
				}

				group.rows.addAll(rows);
			}

			if (group.rows.isEmpty() && !claimedObjects.contains(person.id)) {
				section.groups.remove(group);
			}
		}

		return section;
	}

	private static Object fileName (final Object path) {
		if (!(path instanceof String)) {
			return path;
		}

		final String text = (String) path;
		return text.substring(Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\')) + 1);
	}

	// ----------------------------------------------------------------- areas

	private static final String AREA = ".lvl";
	private static final String MAP = ".fog";

	private Section areaSection () {
		final Section section = new Section("areas", "Areas");
		final Set<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		all.addAll(filesIn(first.directory));
		all.addAll(filesIn(second.directory));
		final List<String> names = new ArrayList<>(all);

		// A late save has 190 of them, 100 MB a side; they are independent, so
		// they are compared side by side, and so are the stores in those that
		// changed.
		final Map<String, Boolean> same = names.parallelStream().collect(Collectors.toMap(
			name -> name, name -> sameFile(new File(first.directory, name), new File(second.directory, name))));

		final List<String> changed = names.stream()
			.filter(name -> name.endsWith(AREA) && !same.get(name)
				&& new File(first.directory, name).isFile() && new File(second.directory, name).isFile())
			.collect(Collectors.toList());

		final Map<String, List<Row>> stores = changed.parallelStream().collect(Collectors.toMap(
			name -> name, name -> stores(new File(first.directory, name), new File(second.directory, name))));

		for (final String name : names) {
			if (same.get(name)) {
				continue;
			}

			if (name.endsWith(MAP)) {
				maps++;
				continue;
			}

			final String area = VendorStock.areaName(name.substring(0, name.length() - AREA.length()));
			if (!new File(first.directory, name).isFile()) {
				section.group("First visited", null).rows.add(new Row(area, null, "visited"));
			} else if (!new File(second.directory, name).isFile()) {
				section.group("No longer in the save", null).rows.add(new Row(area, "visited", null));
			} else {
				section.group("Changed", null).rows.add(new Row(area, null, null).note("changed"));
				if (!stores.get(name).isEmpty()) {
					section.group("Stores", null).rows.addAll(stores.get(name));
				}
			}
		}

		return section;
	}

	private static List<String> filesIn (final File directory) {
		final String[] names = directory.list((dir, name) ->
			(name.endsWith(AREA) || name.endsWith(MAP)) && new File(dir, name).isFile());

		return names == null ? Collections.emptyList() : Arrays.asList(names);
	}

	private static boolean sameFile (final File a, final File b) {
		if (!a.isFile() || !b.isFile()) {
			return a.isFile() == b.isFile();
		}

		try {
			return a.length() == b.length()
				&& Arrays.equals(Files.readAllBytes(a.toPath()), Files.readAllBytes(b.toPath()));
		} catch (final IOException e) {
			logger.error("Unable to compare %s: %s%n", a.getName(), e.getMessage());
			return false;
		}
	}

	/** What each store in a changed area holds, counted. */
	private static List<Row> stores (final File before, final File after) {
		final List<Row> rows = new ArrayList<>();
		if (!VendorStock.mayHoldStore(before) && !VendorStock.mayHoldStore(after)) {
			return rows;
		}

		final Map<String, VendorStock.Vendor> first = vendors(before);
		final Map<String, VendorStock.Vendor> second = vendors(after);
		final Set<String> ids = new LinkedHashSet<>(first.keySet());
		ids.addAll(second.keySet());

		for (final String id : ids) {
			final VendorStock.Vendor was = first.get(id);
			final VendorStock.Vendor is = second.get(id);
			final Integer countBefore = was == null ? null : was.items.size();
			final Integer countAfter = is == null ? null : is.items.size();
			if (Objects.equals(countBefore, countAfter)) {
				continue;
			}

			final VendorStock.Vendor vendor = is != null ? is : was;
			rows.add(new Row(vendor.name + (vendor.area.isEmpty() ? "" : ", " + vendor.area), countBefore, countAfter)
				.format("items"));
		}

		return rows;
	}

	private static Map<String, VendorStock.Vendor> vendors (final File file) {
		final Map<String, VendorStock.Vendor> vendors = new LinkedHashMap<>();
		VendorStock.readWhole(file).ifPresent(packets -> {
			for (final VendorStock.Vendor vendor : VendorStock.vendorsIn(file.getName(), packets.getPackets())) {
				vendors.put(vendor.id.toLowerCase(Locale.ROOT), vendor);
			}
		});

		return vendors;
	}

	// ---------------------------------------------------------- everything else

	private Section otherSection () {
		final Section section = new Section("other", "Everything else");
		int listed = 0;
		int more = 0;

		for (final Map.Entry<String, List<Change>> object : changesByObject.entrySet()) {
			final List<Change> changes = open(object.getKey(), change -> true);
			if (changes.isEmpty()) {
				continue;
			}

			final Group group = section.group(objectTitle(object.getKey()), areaOf(object.getKey()));
			for (final Change change : changes) {
				claim(change);
				if (listed >= MOST_OTHER_ROWS) {
					more++;
					continue;
				}

				final List<Row> rows = rows(
					change.component.isEmpty() ? change.variable : change.component + " · " + change.variable
					, change, null);
				group.rows.addAll(rows);
				listed += rows.size();
			}
		}

		for (final String id : diff.removed) {
			if (claimObject(id)) {
				if (listed++ < MOST_OTHER_ROWS) {
					section.group("No longer in the save", null).rows.add(new Row(objectTitle(id), areaOf(id), null));
				} else {
					more++;
				}
			}
		}

		for (final String id : diff.added) {
			if (claimObject(id)) {
				if (listed++ < MOST_OTHER_ROWS) {
					section.group("New in the save", null).rows.add(new Row(objectTitle(id), null, areaOf(id)));
				} else {
					more++;
				}
			}
		}

		if (more > 0) {
			section.group("And more", null).rows.add(new Row(String.format(
				"%,d more difference%s", more, more == 1 ? "" : "s"), null, null)
				.note("not listed, to keep this readable"));
		}

		return section;
	}

	/** An object's name as words: "InGameGlobal(Clone)" is "In Game Global". */
	private String objectTitle (final String id) {
		final Person person = people.get(id);
		if (person != null) {
			return person.name;
		}

		final ComplexProperty packet = packetOf(id);
		final String name = packet == null ? id : text(packet, "ObjectName");
		return title(name.replace("(Clone)", "").replaceFirst("_\\d+$", "").trim());
	}

	/** An object as the second save holds it, else as the first did. */
	private ComplexProperty packetOf (final String id) {
		return diff.after.containsKey(id) ? diff.after.get(id) : diff.before.get(id);
	}

	private String areaOf (final String id) {
		final ComplexProperty packet = packetOf(id);
		if (packet == null) {
			return null;
		}

		final String area = VendorStock.areaName(text(packet, "LevelName"));
		return area.isEmpty() || area.equals("null") ? null : area;
	}

	// ---------------------------------------------------------------- values

	/** A marker for a value with nothing to show but that it changed. */
	private static final Object CHANGED = new Object();

	/**
	 * A value as the comparison shows it: a number, a flag or some text; the
	 * size of a list; null for nothing. Money, a time of day and a stretch of
	 * time are each one number wrapped in an object, and are shown as the
	 * number.
	 */
	static Object value (final Property property) {
		if (SaveDiff.isNull(property)) {
			return null;
		}

		if (property instanceof SimpleProperty) {
			final Object value = ((SimpleProperty) property).value;
			if (value instanceof String && ((String) value).toLowerCase(Locale.ROOT).endsWith(".prefab")) {
				final String path = (String) value;
				return title(path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
					.replaceFirst("(?i)\\.prefab$", ""));
			}

			if (value instanceof Boolean || value instanceof Number || value instanceof String) {
				return value instanceof Double && ((Double) value).isNaN() ? "NaN" : value;
			}

			if (value instanceof Enum) {
				return title(((Enum<?>) value).name());
			}

			if (value instanceof UnsignedInteger) {
				return ((UnsignedInteger) value).longValue();
			}

			if (value instanceof UUID) {
				return value.toString();
			}

			return value.getClass().isArray() ? CHANGED : String.valueOf(value);
		}

		if (property instanceof CollectionProperty || property instanceof DictionaryProperty
			|| property instanceof SingleDimensionalArrayProperty) {

			return items(property).size();
		}

		if (property instanceof ComplexProperty) {
			final List<Property> fields = ((ComplexProperty) property).properties;
			if (fields.size() == 1 && fields.get(0) instanceof SimpleProperty) {
				return value(fields.get(0));
			}

			final Map<String, Object> xyz = new LinkedHashMap<>();
			for (final Property field : fields) {
				if (field != null && field.name != null && field.name.matches("[xyz]")) {
					xyz.put(field.name, value(field));
				}
			}

			if (xyz.size() == 3 && fields.size() == 3) {
				return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)"
					, number(xyz.get("x")), number(xyz.get("y")), number(xyz.get("z")));
			}
		}

		return CHANGED;
	}

	private static double number (final Object value) {
		return value instanceof Number ? ((Number) value).doubleValue() : 0;
	}

	/** One change as rows: one for a value, one per place for a structure. */
	private static List<Row> rows (final String label, final Change change, final String format) {
		final List<Row> rows = new ArrayList<>();
		final Object before = value(change.before);
		final Object after = value(change.after);
		final boolean structure = isStructure(change.before) || isStructure(change.after);

		if (!structure) {
			rows.add(row(label, before, after, format));
			return rows;
		}

		if (change.before instanceof CollectionProperty || change.after instanceof CollectionProperty) {
			// A list of names or lines reads as what came and went: the
			// stronghold's log is a rolling list of its last 64 lines, and
			// place by place every line of it moves.
			if (onlyWords(change.before) && onlyWords(change.after)) {
				return wordRows(label, strings(change.before), strings(change.after));
			}

			// A list of objects reads as its size: the stronghold's coming
			// events, a character's effects in play.
			if (hasObjects(change.before) || hasObjects(change.after)) {
				final int a = change.before == null ? 0 : items(change.before).size();
				final int b = change.after == null ? 0 : items(change.after).size();
				final Row row = new Row(label, change.before == null ? null : a, change.after == null ? null : b)
					.format("count");
				rows.add(a == b ? row.note("changed") : row);
				return rows;
			}
		}

		final List<Leaf> leaves = SaveDiff.leaves(change.before, change.after).stream()
			.filter(leaf -> !leaf.path.equals("." + CAPACITY))
			.collect(Collectors.toList());
		for (int i = 0; i < leaves.size() && i < MOST_LEAVES; i++) {
			final Leaf leaf = leaves.get(i);
			rows.add(row(label + (leaf.path.isEmpty() ? "" : " · " + path(leaf.path))
				, value(leaf.before), value(leaf.after), format));
		}

		if (leaves.size() > MOST_LEAVES) {
			rows.add(new Row(label, null, null).note(String.format(
				"and %,d more difference%s inside it", leaves.size() - MOST_LEAVES
				, leaves.size() - MOST_LEAVES == 1 ? "" : "s")));
		}

		return rows;
	}

	private static List<Row> wordRows (final String label, final List<String> before, final List<String> after) {
		final List<Row> rows = new ArrayList<>();
		final List<String> gone = minus(before, after);
		final List<String> come = minus(after, before);
		if (gone.isEmpty() && come.isEmpty()) {
			rows.add(new Row(label, null, null).note("the same entries in another order"));
			return rows;
		}

		for (int i = 0; i < gone.size() && rows.size() < MOST_LEAVES; i++) {
			rows.add(new Row(label, gone.get(i), null));
		}

		for (int i = 0; i < come.size() && rows.size() < MOST_LEAVES; i++) {
			rows.add(new Row(label, null, come.get(i)));
		}

		final int more = gone.size() + come.size() - rows.size();
		if (more > 0) {
			rows.add(new Row(label, null, null).note(String.format(
				"and %,d more entr%s", more, more == 1 ? "y" : "ies")));
		}

		return rows;
	}

	/** Whether a list holds nothing but names, lines of text or enum values. */
	private static boolean onlyWords (final Property list) {
		if (list == null) {
			return true;
		}

		if (!(list instanceof CollectionProperty)) {
			return false;
		}

		for (final Property item : items(list)) {
			if (SaveDiff.isNull(item)) {
				continue;
			}

			if (!(item instanceof SimpleProperty)) {
				return false;
			}

			final Object value = ((SimpleProperty) item).value;
			if (!(value instanceof String) && !(value instanceof Enum)) {
				return false;
			}
		}

		return true;
	}

	private static Row row (final String label, final Object before, final Object after, final String format) {
		if (before == CHANGED || after == CHANGED) {
			return new Row(label, null, null).note(before == null ? "added" : after == null ? "removed" : "changed");
		}

		return new Row(label, before, after).format(format);
	}

	private static String path (final String path) {
		return path.startsWith(".") ? path.substring(1) : path;
	}

	/** A list, an array, a dictionary or an object that is not one wrapped number. */
	private static boolean isStructure (final Property property) {
		if (property instanceof CollectionProperty || property instanceof DictionaryProperty
			|| property instanceof SingleDimensionalArrayProperty) {

			return true;
		}

		if (!(property instanceof ComplexProperty)) {
			return false;
		}

		final Object value = value(property);
		return !(value instanceof Number) && !(value instanceof String) && !(value instanceof Boolean);
	}

	private static boolean hasObjects (final Property property) {
		for (final Property item : items(property)) {
			if (item instanceof ComplexProperty) {
				return true;
			}
		}

		return false;
	}

	/** The items of a list, an array or a dictionary's values. */
	private static List<Property> items (final Property property) {
		final List<Property> items = new ArrayList<>();
		if (property instanceof CollectionProperty) {
			items.addAll(((CollectionProperty) property).items);
		} else if (property instanceof DictionaryProperty) {
			((DictionaryProperty) property).items.forEach(entry -> items.add(entry.getValue()));
		} else if (property instanceof SingleDimensionalArrayProperty) {
			for (final Object item : ((SingleDimensionalArrayProperty) property).items) {
				if (item instanceof Property) {
					items.add((Property) item);
				}
			}
		}

		return items;
	}

	/** A list of names, as text. */
	private static List<String> strings (final Property list) {
		final List<String> strings = new ArrayList<>();
		for (final Property item : items(list)) {
			final Object value = value(item);
			if (value != null && value != CHANGED) {
				strings.add(String.valueOf(value));
			}
		}

		return strings;
	}

	/** What {@code from} holds that {@code take} does not, counting repeats. */
	private static List<String> minus (final List<String> from, final List<String> take) {
		final List<String> rest = new ArrayList<>(take);
		final List<String> left = new ArrayList<>();
		for (final String item : from) {
			if (!rest.remove(item)) {
				left.add(item);
			}
		}

		return left;
	}

	/**
	 * A variable's name as a label, in the editor's sentence case:
	 * "m_CurrentFatigueLevel" is "Current fatigue level", "DTBypass" is "DT
	 * bypass". A word in capitals stays in capitals.
	 */
	static String words (final String name) {
		final String[] words = split(name);
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < words.length; i++) {
			if (i > 0) {
				out.append(' ');
			}

			final String word = words[i];
			final boolean acronym = word.length() > 1 && word.equals(word.toUpperCase(Locale.ROOT));
			out.append(i == 0 || acronym ? word : word.toLowerCase(Locale.ROOT));
		}

		return capitalised(out);
	}

	/**
	 * A name as a title: "PathOfTheDamned" is "Path of the Damned",
	 * "InGameGlobal" is "In Game Global".
	 */
	static String title (final String name) {
		final String[] words = split(name);
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < words.length; i++) {
			if (i > 0) {
				out.append(' ');
			}

			out.append(i > 0 && SMALL.contains(words[i]) ? words[i].toLowerCase(Locale.ROOT) : words[i]);
		}

		return capitalised(out);
	}

	private static String[] split (final String name) {
		if (name == null) {
			return new String[0];
		}

		final String bare = name.startsWith("m_") ? name.substring(2) : name;
		return bare
			.replace('_', ' ')
			.replaceAll("(?<=[a-z])(?=[A-Z])", " ")
			.replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", " ")
			.replaceAll("\\s+", " ")
			.trim()
			.split(" ");
	}

	private static String capitalised (final StringBuilder out) {
		if (out.length() > 0 && Character.isLowerCase(out.charAt(0))) {
			out.setCharAt(0, Character.toUpperCase(out.charAt(0)));
		}

		return out.toString();
	}

	private static final Set<String> SMALL = new HashSet<>(Arrays.asList(
		"Of", "The", "And", "In", "On", "To", "A", "An", "For", "At", "By"));

	// ---------------------------------------------------------------- quests

	/** A quest's title and objectives, as the journal shows them. */
	static final class QuestText {
		final String title;
		private final Map<Integer, String> entries;

		private QuestText (final String title, final Map<Integer, String> entries) {
			this.title = title;
			this.entries = entries;
		}

		/**
		 * The quest whose file a save names, "data/quests/critical_path/
		 * act_4/cp_qst_confront_lka.quest" say: its title is entry 0 of the
		 * string table beside it, and objective N is entry N. Without the
		 * game's text the file name has to do.
		 */
		static QuestText of (final String questFile) {
			final Map<Integer, String> entries = GameText.getInstance().quest(questFile);
			final String title = entries.get(0);
			return new QuestText(title != null && !title.isEmpty() ? title : fromFile(questFile), entries);
		}

		Optional<String> objective (final int id) {
			return id > 0 && id < 10000 ? Optional.ofNullable(entries.get(id)) : Optional.empty();
		}

		// "01_qst_intimate_stranger" is "Intimate stranger", and
		// "cp_qst_confront_lka" "Confront lka": the area number, the critical
		// path or companion marker and the quest or task marker go.
		private static String fromFile (final String questFile) {
			String name = questFile.replace('\\', '/');
			name = name.substring(name.lastIndexOf('/') + 1).replaceFirst("(?i)\\.quest$", "");
			name = name.replaceFirst("(?i)^px\\d+_", "")
				.replaceFirst("^\\d+_", "")
				.replaceFirst("(?i)^(cp|companion)_", "")
				.replaceFirst("(?i)^(qst|tsk)_", "");
			final String spaced = name.replace('_', ' ').trim();
			return spaced.isEmpty() ? questFile
				: Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
		}
	}
}
