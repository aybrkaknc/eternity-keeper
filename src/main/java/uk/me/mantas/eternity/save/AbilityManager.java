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
import uk.me.mantas.eternity.game.GenericAbility.AbilityType;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.properties.*;

import java.io.File;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Adds and removes a character's abilities, spells and talents.
 *
 * <p>The two are stored quite differently. A <b>talent</b> is nothing but its
 * prefab name in {@code CharacterStats.m_serializedTalents}, a plain
 * {@code List&lt;string&gt;}. An <b>ability</b> is a standalone top-level
 * object, parented to the character exactly like a carried item, whose sole
 * components are the ability class itself, an {@code InstanceID} and a
 * {@code Persistence} — {@code CharacterStats.Restored} rebuilds the character's
 * ability list purely from those child objects.
 *
 * <p>That asymmetry is what makes adding a talent more than a one-liner.
 * {@code GenericTalent.Purchase} — the level-up path — both records the name
 * <i>and</i> instantiates the abilities the talent grants, and it is never
 * re-run on load, so the granted objects have to be minted here too. Talents of
 * the {@code ModExistingAbility} kind are the easy case: {@code Restored} walks
 * {@code m_talents} and reapplies their mods itself, so the name alone is
 * enough. Skill bonuses, on the other hand, are baked into {@code &lt;Skill&gt;Bonus}
 * at purchase time and never recomputed, so those are applied here as well.
 */
public class AbilityManager {
	private static final Logger logger = Logger.getLogger(AbilityManager.class);

	private static final String CHARACTER_STATS = "CharacterStats";
	private static final String TALENT_LIST = "m_serializedTalents";

	private final File saveDirectory;

	public AbilityManager (final File saveDirectory) {
		this.saveDirectory = saveDirectory;
	}

	/**
	 * Everything needed to mint one ability object, resolved from the catalog
	 * before it gets here so this class stays independent of it.
	 */
	public static final class NewAbility {
		/** Exact-case prefab file name, e.g. {@code Fireball}. */
		public final String prefab;
		/** Written verbatim as the packet's PrefabResource. */
		public final String path;
		/** Exact C# component class — GenericSpell, Chant, Carnage, ... */
		public final String component;
		/** GenericAbility.AbilityType ordinal for EffectType. */
		public final int effect;
		/**
		 * True when the component derives from {@code GenericSpell}, which is
		 * the only branch of the hierarchy carrying IsFree and NeedsGrimoire.
		 * The catalog knows because it reads those very fields off the prefab.
		 */
		public final boolean spell;
		/**
		 * The spell's owning class, where it has one. Only wizards cast from a
		 * grimoire, and {@code InstantiateAbility} records that on the object.
		 */
		public final String spellClass;

		public NewAbility (
			final String prefab
			, final String path
			, final String component
			, final int effect) {

			this(prefab, path, component, effect, false, "");
		}

		public NewAbility (
			final String prefab
			, final String path
			, final String component
			, final int effect
			, final boolean spell
			, final String spellClass) {

			this.prefab = prefab;
			this.path = path;
			this.component = component;
			this.effect = effect;
			this.spell = spell;
			this.spellClass = spellClass;
		}
	}

	/** One addition or removal, addressed by the owning character. */
	public static final class Change {
		public enum Kind { ADD_ABILITY, REMOVE_ABILITY, ADD_TALENT, REMOVE_TALENT }

		public final Kind kind;
		/** ObjectID of the character this applies to. */
		public final String character;
		/** Removal: the ability object's own ObjectID. */
		public final String abilityGuid;
		/** Talents: the talent's prefab name as m_serializedTalents stores it. */
		public final String talent;
		/** Adding: the ability, or the abilities a talent grants. */
		public final List<NewAbility> abilities;
		/** Removing a talent: prefab names of the abilities it granted. */
		public final List<String> granted;
		/** Skill name to bonus, as GenericTalent.SkillBonuses records it. */
		public final Map<String, Integer> skills;

		private Change (
			final Kind kind
			, final String character
			, final String abilityGuid
			, final String talent
			, final List<NewAbility> abilities
			, final List<String> granted
			, final Map<String, Integer> skills) {

			this.kind = kind;
			this.character = character;
			this.abilityGuid = abilityGuid;
			this.talent = talent;
			this.abilities = abilities;
			this.granted = granted;
			this.skills = skills;
		}

		public static Change addAbility (final String character, final NewAbility ability) {
			return new Change(Kind.ADD_ABILITY, character, null, null
				, Collections.singletonList(ability), Collections.emptyList()
				, Collections.emptyMap());
		}

		public static Change removeAbility (final String character, final String abilityGuid) {
			return new Change(Kind.REMOVE_ABILITY, character, abilityGuid, null
				, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
		}

		public static Change addTalent (
			final String character
			, final String talent
			, final List<NewAbility> grants
			, final Map<String, Integer> skills) {

			return new Change(Kind.ADD_TALENT, character, null, talent, grants
				, Collections.emptyList(), skills);
		}

		public static Change removeTalent (
			final String character
			, final String talent
			, final List<String> granted
			, final Map<String, Integer> skills) {

			return new Change(Kind.REMOVE_TALENT, character, null, talent
				, Collections.emptyList(), granted, skills);
		}
	}

	public boolean apply (final List<Change> changes) throws IOException {
		final File mobileObjects = new File(saveDirectory, "MobileObjects.save");
		final Optional<DeserializedPackets> deserializedOpt =
			new PacketDeserializer(mobileObjects).deserialize();

		if (!deserializedOpt.isPresent()) {
			logger.error("Unable to deserialize '%s'.%n", mobileObjects.getAbsolutePath());
			return false;
		}

		final DeserializedPackets deserialized = deserializedOpt.get();
		final List<Property> packets = new ArrayList<>(deserialized.getPackets());

		for (final Change change : changes) {
			final Optional<Property> owner = EKUtils.findPacketById(packets, change.character);
			if (!owner.isPresent()) {
				logger.error("No character '%s' in this save.%n", change.character);
				return false;
			}

			switch (change.kind) {
				case REMOVE_ABILITY:
					if (!removeAbility(packets, owner.get(), change.abilityGuid)) {
						return false;
					}

					break;

				case ADD_ABILITY:
					if (!addAbilities(packets, owner.get(), change.abilities)) {
						return false;
					}

					break;

				case ADD_TALENT:
					if (!addTalent(packets, owner.get(), change)) {
						return false;
					}

					break;

				case REMOVE_TALENT:
					if (!removeTalent(packets, owner.get(), change)) {
						return false;
					}

					break;

				default:
					logger.error("Unhandled change kind '%s'.%n", change.kind);
					return false;
			}
		}

		deserialized.setPackets(packets);
		deserialized.replace(mobileObjects);
		return true;
	}

	/**
	 * Deletes one ability object. Abilities are identified by their own
	 * ObjectID rather than by name because a wizard can legitimately carry two
	 * copies of the same spell.
	 */
	private boolean removeAbility (
		final List<Property> packets, final Property owner, final String abilityGuid) {

		final String ownerName = ((ObjectPersistencePacket) owner.obj).ObjectName;

		for (int i = 0; i < packets.size(); i++) {
			final Property property = packets.get(i);
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			if (!abilityGuid.equalsIgnoreCase(packet.ObjectID)) {
				continue;
			}

			// Refuse to reach into someone else's abilities; the UI addresses
			// them per character and a mismatch means the two have drifted.
			if (ownerName == null || !ownerName.equals(packet.Parent)) {
				logger.error(
					"Ability '%s' is not owned by '%s'.%n", abilityGuid, ownerName);

				return false;
			}

			packets.remove(i);
			return true;
		}

		logger.error("No ability object '%s' in this save.%n", abilityGuid);
		return false;
	}

	private boolean addAbilities (
		final List<Property> packets, final Property owner, final List<NewAbility> abilities) {

		for (final NewAbility ability : abilities) {
			if (!addAbility(packets, owner, ability)) {
				return false;
			}
		}

		return true;
	}

	private boolean addAbility (
		final List<Property> packets, final Property owner, final NewAbility ability) {

		final Optional<Property> template = findAbilityTemplate(packets, ability.component);
		if (!template.isPresent()) {
			logger.error("This save has no ability object to model a new one on.%n");
			return false;
		}

		final ObjectPersistencePacket character = (ObjectPersistencePacket) owner.obj;
		final Optional<Property> packet = buildAbilityPacket(
			template.get(), UUID.randomUUID(), ability, character);

		if (!packet.isPresent()) {
			return false;
		}

		packets.add(packet.get());
		return true;
	}

	private boolean addTalent (
		final List<Property> packets, final Property owner, final Change change) {

		final Optional<CollectionProperty> talents = findTalentList(owner);
		if (!talents.isPresent()) {
			logger.error("Character '%s' has no %s.%n", change.character, TALENT_LIST);
			return false;
		}

		// Already there: leave everything alone. Minting the granted objects a
		// second time would give the character two copies of the same ability.
		if (indexOfTalent(talents.get(), change.talent) >= 0) {
			logger.warn("'%s' already has talent '%s'.%n", change.character, change.talent);
			return true;
		}

		final SimpleProperty entry =
			new SimpleProperty(null, new TypePair(String.class, null));

		entry.value = change.talent;
		entry.obj = change.talent;
		talents.get().items.add(entry);

		return addAbilities(packets, owner, change.abilities)
			&& adjustSkillBonuses(owner, change.skills, 1);
	}

	private boolean removeTalent (
		final List<Property> packets, final Property owner, final Change change) {

		final Optional<CollectionProperty> talents = findTalentList(owner);
		if (!talents.isPresent()) {
			logger.error("Character '%s' has no %s.%n", change.character, TALENT_LIST);
			return false;
		}

		final int index = indexOfTalent(talents.get(), change.talent);
		if (index < 0) {
			logger.error("'%s' does not have talent '%s'.%n", change.character, change.talent);
			return false;
		}

		talents.get().items.remove(index);

		final String ownerName = ((ObjectPersistencePacket) owner.obj).ObjectName;
		for (final String prefab : change.granted) {
			removeOwnedAbilityNamed(packets, ownerName, prefab);
		}

		return adjustSkillBonuses(owner, change.skills, -1);
	}

	/**
	 * Deletes the object a talent granted. Missing is not an error: a talent
	 * bought before the ability existed, or one already tidied away by the
	 * game, simply leaves nothing to remove.
	 */
	private void removeOwnedAbilityNamed (
		final List<Property> packets, final String ownerName, final String prefab) {

		// Matched without regard to case: the game writes Cautious_Attack(Clone)
		// while an object minted from a catalog path can be lowercased, and both
		// name the same prefab as far as the game is concerned.
		final String objectName = prefab + "(Clone)";
		for (int i = 0; i < packets.size(); i++) {
			final Property property = packets.get(i);
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			if (objectName.equalsIgnoreCase(packet.ObjectName) && ownerName != null
				&& ownerName.equals(packet.Parent)) {

				packets.remove(i);
				return;
			}
		}
	}

	/**
	 * Applies a talent's skill bonuses to {@code &lt;Skill&gt;Bonus}, the same
	 * fields {@code CharacterStats.AdjustSkillBonus} writes.
	 */
	private boolean adjustSkillBonuses (
		final Property owner, final Map<String, Integer> skills, final int sign) {

		if (skills.isEmpty()) {
			return true;
		}

		final Optional<DictionaryProperty> variables = statVariables(owner);
		if (!variables.isPresent()) {
			logger.error("Character has no CharacterStats to adjust.%n");
			return false;
		}

		for (final Map.Entry<String, Integer> bonus : skills.entrySet()) {
			final String field = bonus.getKey() + "Bonus";
			final Optional<Property> current = variables.get().findEntry(field);

			if (!current.isPresent() || !(current.get() instanceof SimpleProperty)) {
				logger.error("CharacterStats has no '%s'.%n", field);
				return false;
			}

			final SimpleProperty property = (SimpleProperty) current.get();
			final int existing = property.value instanceof Number
				? ((Number) property.value).intValue() : 0;

			final int updated = existing + sign * bonus.getValue();
			property.value = updated;
			property.obj = updated;
		}

		return true;
	}

	/**
	 * An existing ability object to model a new one on, used purely for its
	 * type information. One of the same component class is preferred, since
	 * each class persists its own extra fields — a GenericSpell modelled on a
	 * plain GenericAbility would be missing IsFree and NeedsGrimoire entirely.
	 */
	private static Optional<Property> findAbilityTemplate (
		final List<Property> packets, final String component) {

		Optional<Property> fallback = Optional.empty();

		for (final Property packet : packets) {
			if (!(packet.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket candidate = (ObjectPersistencePacket) packet.obj;
			if (candidate.ComponentPackets == null) {
				continue;
			}

			for (final uk.me.mantas.eternity.game.ComponentPersistencePacket part
				: candidate.ComponentPackets) {

				if (part == null || part.Variables == null
					|| !part.Variables.containsKey("EffectType")
					|| !part.Variables.containsKey("Owner")) {

					continue;
				}

				if (component != null && component.equals(part.TypeString)) {
					return Optional.of(packet);
				}

				if (!fallback.isPresent()) {
					fallback = Optional.of(packet);
				}
			}
		}

		return fallback;
	}

	/**
	 * Builds an ability object from the shape of one already in the save, so
	 * every type string and field type matches whatever the game wrote. It
	 * carries three components — the ability class itself, an InstanceID and a
	 * Persistence — and only the handful of fields the game writes for a
	 * freshly instantiated ability are set; everything else comes back from
	 * the prefab, which is exactly right for a new one.
	 */
	private static Optional<Property> buildAbilityPacket (
		final Property template
		, final UUID guid
		, final NewAbility ability
		, final ObjectPersistencePacket owner) {

		final boolean[] wroteAbility = {false};
		final Optional<Property> packet = PacketMint.mint(template
			, new PacketMint.Identity(ability.prefab + "(Clone)", guid.toString(), guid
				, ability.path, owner.ObjectName, owner.LevelName)
			, (type, component) -> "InstanceID".equals(type) || "Persistence".equals(type)
				|| isAbility(component)
			, (type, copy) -> {
				if (!"InstanceID".equals(type) && isAbility(copy)) {
					stampNewAbility(copy, ability, owner);
					wroteAbility[0] = true;
				}
			});

		if (packet.isPresent() && !wroteAbility[0]) {
			logger.error("Template ability object had no ability component to copy.%n");
			return Optional.empty();
		}

		return packet;
	}

	/** An ability's own component is the one carrying an EffectType. */
	private static boolean isAbility (final ComplexProperty component) {
		return component.<DictionaryProperty>findProperty("Variables")
			.flatMap(variables -> variables.findEntry("EffectType"))
			.isPresent();
	}

	private static void stampNewAbility (
		final ComplexProperty copy, final NewAbility ability, final ObjectPersistencePacket owner) {

		// The class matters: the save writes the concrete component
		// name, and a spell recorded as a plain GenericAbility has none
		// of its state applied.
		copy.findProperty("TypeString").ifPresent(property -> {
			((SimpleProperty) property).value = ability.component;
			property.obj = ability.component;
		});

		setVariable(copy, "Owner", UUID.fromString(owner.ObjectID));
		setVariable(copy, "EffectType", effectTypeOf(ability.effect));

		// A brand new ability is dormant; Restored() activates the
		// passive ones itself on the next load.
		setVariable(copy, "m_activated", false);
		setVariable(copy, "m_activatedLaunching", false);
		setVariable(copy, "m_applied", false);
		setVariable(copy, "m_UITriggered", false);
		setVariable(copy, "m_cooldownCounter", 0);
		setVariable(copy, "m_perEncounterResetTimer", 0f);
		setVariable(copy, "m_statusEffectsActivated", true);
		setVariable(copy, "m_statusEffectsNeeded", true);
		setVariable(copy, "MasteryLevel", 0);
		setVariable(copy, "AppliedViaMod", false);
		setVariable(copy, "IsVisibleOnUI", true);
		setVariable(copy, "OverrideName", "");

		// Spells carry two extra flags InstantiateAbility sets by hand:
		// a learned spell is never free, and only a wizard's has to be
		// in a grimoire to be cast. The template may well have been a
		// non-spell ability, so these are added rather than assigned.
		if (ability.spell) {
			putVariable(copy, "IsFree", false);
			putVariable(copy, "NeedsGrimoire"
				, "Wizard".equalsIgnoreCase(ability.spellClass));
		}
	}

	private static AbilityType effectTypeOf (final int ordinal) {
		final AbilityType[] values = AbilityType.values();
		return ordinal >= 0 && ordinal < values.length
			? values[ordinal] : AbilityType.Ability;
	}

	/** Assigns a variable the component already has; does nothing otherwise. */
	private static void setVariable (
		final ComplexProperty component, final String name, final Object value) {

		component.<DictionaryProperty>findProperty("Variables")
			.flatMap(variables -> variables.findEntry(name))
			.filter(entry -> entry instanceof SimpleProperty)
			.ifPresent(entry -> {
				((SimpleProperty) entry).value = value;
				entry.obj = value;
			});
	}

	/**
	 * Assigns a variable, adding it when the template we copied didn't have
	 * one. The key's type is taken from a sibling so it stays identical to what
	 * the game writes.
	 */
	private static void putVariable (
		final ComplexProperty component, final String name, final Object value) {

		final Optional<DictionaryProperty> variables =
			component.findProperty("Variables");

		if (!variables.isPresent()) {
			return;
		}

		final Optional<Property> existing = variables.get().findEntry(name);
		if (existing.isPresent()) {
			setVariable(component, name, value);
			return;
		}

		if (variables.get().items.isEmpty()) {
			return;
		}

		// Both halves get their type from a sibling entry rather than being
		// constructed here: the serializer writes the C# type name it was given,
		// and inventing one produces a save the game cannot read back.
		final Property sibling = variables.get().items.get(0).getKey();
		final SimpleProperty key = new SimpleProperty(sibling.name, sibling.type);
		key.value = name;
		key.obj = name;

		final Optional<TypePair> valueType = sameTypedValue(variables.get(), value);
		if (!valueType.isPresent()) {
			logger.error(
				"No existing %s variable to copy a type from; leaving '%s' unset.%n"
				, value.getClass().getSimpleName(), name);

			return;
		}

		final SimpleProperty entry = new SimpleProperty(null, valueType.get());
		entry.value = value;
		entry.obj = value;
		variables.get().items.add(new AbstractMap.SimpleEntry<>(key, entry));
	}

	/** The type of any existing variable already holding this kind of value. */
	private static Optional<TypePair> sameTypedValue (
		final DictionaryProperty variables, final Object value) {

		for (final Map.Entry<Property, Property> entry : variables.items) {
			final Property held = entry.getValue();
			if (held instanceof SimpleProperty && held.type != null
				&& ((SimpleProperty) held).value != null
				&& ((SimpleProperty) held).value.getClass() == value.getClass()) {

				return Optional.of(held.type);
			}
		}

		return Optional.empty();
	}

	private static Optional<DictionaryProperty> statVariables (final Property character) {
		return ((ComplexProperty) character)
			.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
			.flatMap(c -> EKUtils.findSubComponent(c, CHARACTER_STATS))
			.flatMap(stats -> stats.findProperty("Variables"));
	}

	private static Optional<CollectionProperty> findTalentList (final Property character) {
		return statVariables(character).flatMap(v -> v.findEntry(TALENT_LIST));
	}

	private static int indexOfTalent (final CollectionProperty talents, final String prefab) {
		for (int i = 0; i < talents.items.size(); i++) {
			final Property item = talents.items.get(i);
			if (item instanceof SimpleProperty
				&& prefab.equals(String.valueOf(((SimpleProperty) item).value))) {

				return i;
			}
		}

		return -1;
	}

	/**
	 * Every ability a character owns, as ObjectID to the packet, in save order.
	 * Shared with {@link SavedGameOpener} so the list the UI shows and the list
	 * this class edits can't drift apart.
	 */
	public static Map<String, ObjectPersistencePacket> abilitiesOf (
		final List<Property> packets, final String ownerName) {

		final Map<String, ObjectPersistencePacket> owned = new LinkedHashMap<>();
		if (ownerName == null) {
			return owned;
		}

		for (final Property property : packets) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			if (!ownerName.equals(packet.Parent) || packet.ComponentPackets == null
				|| packet.ObjectID == null) {

				continue;
			}

			boolean ability = false;
			boolean item = false;
			for (final uk.me.mantas.eternity.game.ComponentPersistencePacket component
				: packet.ComponentPackets) {

				if (component == null || component.TypeString == null) {
					continue;
				}

				// A potion or a trap carries an ability component describing
				// what it does; those belong to the inventory, not here.
				if (ITEM_COMPONENTS.contains(component.TypeString)) {
					item = true;
				}

				if (component.Variables != null
					&& component.Variables.containsKey("EffectType")) {

					ability = true;
				}
			}

			if (ability && !item) {
				owned.put(packet.ObjectID, packet);
			}
		}

		return owned;
	}

	private static final java.util.Set<String> ITEM_COMPONENTS =
		new java.util.HashSet<>(java.util.Arrays.asList(
			"Consumable", "Equippable", "Weapon", "Container", "Trap", "Grimoire", "Item"));

}
