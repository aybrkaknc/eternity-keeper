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
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.model.Component;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which spells sit in which grimoire.
 *
 * <p>A grimoire is an <em>item</em>: {@code Grimoire.Find()} reads the
 * component off whatever is in the wearer's Grimoire equipment slot, so the
 * data lives on the item's own packet, not on the character. That is why a
 * grimoire keeps its spells when it changes hands, and why one sitting in the
 * stash is just as editable as one being carried.
 *
 * <p>The class declares two {@code [Persistent]} members that describe the same
 * thing, and only one of them survives a save. {@code SerializedSpells} is a
 * {@code SpellChapter[8]} of {@code GenericSpell} object references and every
 * chapter comes back all-null — measured across real saves. The payload is
 * {@code SerializedSpellNames}, a flat {@code List&lt;string&gt;} of spell
 * prefab names, whose setter rebuilds the chapters from scratch: each name is
 * resolved with {@code GameResources.LoadPrefab}, filed under its own
 * {@code SpellLevel}, and dropped on the floor without comment once that
 * chapter already holds four. So the editor has to apply the same arithmetic
 * itself — see {@link #plan} — or it will show the player spells the game will
 * quietly discard on load.
 *
 * <p>The list has no cross-references, no UUIDs and no parallel structure,
 * which makes it — like {@code m_upgradesBuilt} — one of the easy ones.
 */
public class GrimoireManager {
	private static final Logger logger = Logger.getLogger(GrimoireManager.class);

	private static final String GRIMOIRE = "Grimoire";
	private static final String SPELL_NAMES = "SerializedSpellNames";

	/** {@code Grimoire.MaxSpellLevel}. */
	public static final int MAX_SPELL_LEVEL = 8;

	/** {@code Grimoire.MaxSpellsPerLevel}. */
	public static final int MAX_SPELLS_PER_LEVEL = 4;

	private final File saveDirectory;

	public GrimoireManager (final File saveDirectory) {
		this.saveDirectory = saveDirectory;
	}

	/** One spell, resolved against the catalog before it gets here. */
	public static final class Spell {
		/** Exact-case prefab file name, as SerializedSpellNames stores it. */
		public final String prefab;
		/** {@code GenericSpell.SpellLevel}, 1-8, which picks the chapter. */
		public final int level;

		public Spell (final String prefab, final int level) {
			this.prefab = prefab;
			this.level = level;
		}
	}

	/** What one grimoire should end up holding. */
	public static final class Change {
		/** The grimoire item's own ObjectID. */
		public final String grimoire;
		public final List<Spell> spells;

		public Change (final String grimoire, final List<Spell> spells) {
			this.grimoire = grimoire;
			this.spells = spells == null ? Collections.emptyList() : spells;
		}
	}

	/**
	 * The spells the game would actually keep, in the order its own getter
	 * emits them.
	 *
	 * <p>Chapter order, because {@code SerializedSpellNames}'s getter walks
	 * {@code Spells} from level one upwards; at most four to a level, because
	 * its setter stops filling a chapter there; nothing outside levels 1-8,
	 * because there are only eight chapters; and each prefab once, because
	 * {@code GameResources.LoadPrefab} lower-cases the name it looks up, so two
	 * spellings would resolve to one spell listed twice.
	 */
	public static List<Spell> plan (final List<Spell> requested) {
		final List<List<Spell>> chapters = new ArrayList<>();
		for (int i = 0; i < MAX_SPELL_LEVEL; i++) {
			chapters.add(new ArrayList<>());
		}

		if (requested == null) {
			return Collections.emptyList();
		}

		final Set<String> seen = new HashSet<>();
		for (final Spell spell : requested) {
			if (spell == null || spell.prefab == null || spell.prefab.isEmpty()) {
				continue;
			}

			if (spell.level < 1 || spell.level > MAX_SPELL_LEVEL) {
				logger.warn("Spell '%s' has no chapter at level %d.%n"
					, spell.prefab, spell.level);

				continue;
			}

			if (!seen.add(spell.prefab.toLowerCase())) {
				continue;
			}

			final List<Spell> chapter = chapters.get(spell.level - 1);
			if (chapter.size() >= MAX_SPELLS_PER_LEVEL) {
				logger.warn("Level %d is full; '%s' would be dropped on load.%n"
					, spell.level, spell.prefab);

				continue;
			}

			chapter.add(spell);
		}

		final List<Spell> planned = new ArrayList<>();
		for (final List<Spell> chapter : chapters) {
			planned.addAll(chapter);
		}

		return planned;
	}

	/** Replaces what each named grimoire holds. */
	public boolean apply (final List<Change> changes) throws IOException {
		if (changes == null || changes.isEmpty()) {
			return false;
		}

		final Optional<DeserializedPackets> deserialized = read();
		if (!deserialized.isPresent()) {
			logger.error("Unable to deserialize MobileObjects.save.%n");
			return false;
		}

		final SaveModel save = SaveModel.of(deserialized.get());
		boolean changed = false;
		for (final Change change : changes) {
			final Optional<Component> grimoire = change.grimoire == null || change.grimoire.isEmpty()
				? Optional.empty()
				: save.object(change.grimoire).flatMap(item -> item.component(GRIMOIRE));

			if (!grimoire.isPresent()) {
				logger.error("No grimoire with ObjectID '%s'.%n", change.grimoire);
				return false;
			}

			changed |= rewrite(grimoire.get(), plan(change.spells));
		}

		if (!changed) {
			return false;
		}

		deserialized.get().replace(new File(saveDirectory, "MobileObjects.save"));
		return true;
	}

	/**
	 * Empties the stored list and writes the planned one back into it. The
	 * entries are plain strings, so there is nothing to keep in sync — no
	 * parallel UUID list, no standalone packet per spell.
	 */
	private boolean rewrite (final Component grimoire, final List<Spell> spells) {
		final Optional<Property> entry = grimoire.variable(SPELL_NAMES);
		if (!entry.isPresent() || !(entry.get() instanceof CollectionProperty)) {
			logger.error("Grimoire has no %s list.%n", SPELL_NAMES);
			return false;
		}

		final CollectionProperty names = (CollectionProperty) entry.get();
		final List<String> before = new ArrayList<>();
		for (final Property item : names.items) {
			before.add(String.valueOf(((SimpleProperty) item).value));
		}

		final List<String> after = new ArrayList<>();
		for (final Spell spell : spells) {
			after.add(spell.prefab);
		}

		if (before.equals(after)) {
			return false;
		}

		names.items.clear();
		for (final String prefab : after) {
			final SimpleProperty item =
				new SimpleProperty(null, new TypePair(String.class, null));

			item.value = prefab;
			item.obj = prefab;
			names.items.add(item);
		}

		return true;
	}

	private Optional<DeserializedPackets> read () throws IOException {
		return Environment.getInstance().factory().packetDeserializer()
			.forFile(new File(saveDirectory, "MobileObjects.save")).deserialize();
	}
}
