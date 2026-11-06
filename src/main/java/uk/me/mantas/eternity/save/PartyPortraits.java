/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2016 the authors.
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
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The party thumbnails the game's load list shows: {@code 0.png}…{@code 5.png}
 * inside a save, 32×41, one per party member in slot order. The game draws
 * them when it saves, so after the editor changes the party or a portrait
 * the load list went on showing the old faces while the loaded game showed
 * the new ones. Save redraws them from each member's small portrait.
 *
 * <p>Measured on a real save: the five thumbnails are the five party
 * members' {@code _sm.png} portraits (76×96) scaled down, in
 * {@code PartyMemberAI.AssignedSlot} order; a pet shares the party but sits
 * at slot 6 and up and has no thumbnail.
 */
public final class PartyPortraits {
	private static final Logger logger = Logger.getLogger(PartyPortraits.class);

	public static final int WIDTH = 32;
	public static final int HEIGHT = 41;
	public static final int PARTY_SIZE = 6;

	private static final String COMPANION_PORTRAIT = "data/art/gui/portraits/companion/portrait_%s_sm.png";

	/** A party member, and the portrait their thumbnail is drawn from. */
	public static final class Member {
		public final String objectName;
		public final int slot;
		/** Relative to PillarsOfEternity_Data, as the save stores it; empty if unknown. */
		public final String smallPortrait;

		Member (final String objectName, final int slot, final String smallPortrait) {
			this.objectName = objectName;
			this.slot = slot;
			this.smallPortrait = smallPortrait;
		}
	}

	private PartyPortraits () {}

	/**
	 * The active party in slot order. Read from the property tree rather than
	 * the packets' mirror objects: Save writes a newly picked portrait into
	 * the tree, and the mirror still holds the old one.
	 */
	public static List<Member> members (final List<Property> packets) {
		final List<Member> members = new ArrayList<>();
		for (final Property packet : packets) {
			if (!(packet.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final Optional<DictionaryProperty> ai = variables(packet, "PartyMemberAI");
			if (!ai.isPresent()) {
				continue;
			}

			final Object slot = value(ai.get(), "AssignedSlot");
			final Object active = value(ai.get(), "IsActiveInParty");
			if (!(slot instanceof Integer) || !Boolean.TRUE.equals(active)) {
				continue;
			}

			final int assigned = (Integer) slot;
			if (assigned < 0 || assigned >= PARTY_SIZE) {
				continue;
			}

			final String objectName = EKUtils.unwrapPacket(packet).ObjectName;
			members.add(new Member(objectName, assigned, smallPortrait(packet, objectName)));
		}

		members.sort(Comparator.comparingInt(member -> member.slot));
		return members;
	}

	/**
	 * Redraws the thumbnails in {@code saveDirectory}, and removes any for
	 * slots nobody fills now.
	 *
	 * @param gameData the install's PillarsOfEternity_Data folder, or null
	 * @return false, leaving the thumbnails as they were, when there is no
	 *         game to read portraits from or any member's portrait cannot be
	 *         read — half a party of new faces beside old ones would be wrong
	 */
	public static boolean refresh (
		final File saveDirectory, final List<Property> packets, final File gameData)
		throws IOException {

		if (gameData == null || !gameData.isDirectory()) {
			logger.info("No game install to read portraits from; the load list keeps its thumbnails.%n");
			return false;
		}

		final List<Member> members = members(packets);
		if (members.isEmpty()) {
			return false;
		}

		final List<BufferedImage> thumbnails = new ArrayList<>();
		for (final Member member : members) {
			final Optional<BufferedImage> portrait = read(gameData, member);
			if (!portrait.isPresent()) {
				return false;
			}

			thumbnails.add(thumbnail(portrait.get()));
		}

		for (int i = 0; i < thumbnails.size(); i++) {
			if (!ImageIO.write(thumbnails.get(i), "png", new File(saveDirectory, i + ".png"))) {
				throw new IOException("No PNG writer is available.");
			}
		}

		for (int i = thumbnails.size(); i < PARTY_SIZE; i++) {
			final File stale = new File(saveDirectory, i + ".png");
			if (stale.exists() && !stale.delete()) {
				logger.warn("Could not remove the old thumbnail %s.%n", stale.getAbsolutePath());
			}
		}

		return true;
	}

	private static Optional<BufferedImage> read (final File gameData, final Member member) {
		if (member.smallPortrait.isEmpty()) {
			logger.info("%s has no portrait to draw; the load list keeps its thumbnails.%n"
				, member.objectName);
			return Optional.empty();
		}

		final File file = new File(gameData, member.smallPortrait);
		try {
			final BufferedImage image = file.isFile() ? ImageIO.read(file) : null;
			if (image == null) {
				logger.info("%s's portrait %s cannot be read; the load list keeps its thumbnails.%n"
					, member.objectName, file.getAbsolutePath());
			}

			return Optional.ofNullable(image);
		} catch (final IOException e) {
			logger.error("Reading %s: %s%n", file.getAbsolutePath(), e.getMessage());
			return Optional.empty();
		}
	}

	private static BufferedImage thumbnail (final BufferedImage portrait) {
		final BufferedImage thumbnail = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = thumbnail.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(portrait, 0, 0, WIDTH, HEIGHT, null);
		g.dispose();
		return thumbnail;
	}

	// An empty path means the game works the portrait out from who the
	// companion is (Portrait.Start()); the files follow the companion's name.
	private static String smallPortrait (final Property packet, final String objectName) {
		final Object stored = variables(packet, "Portrait")
			.map(portrait -> value(portrait, "m_textureSmallPath"))
			.orElse(null);

		if (stored instanceof String && !((String) stored).isEmpty()) {
			return (String) stored;
		}

		if (objectName == null || !objectName.startsWith("Companion_")) {
			return "";
		}

		final String name = CompanionRegistry.all().stream()
			.filter(companion -> objectName.startsWith(companion.objectNamePrefix + "(")
				|| objectName.equals(companion.objectNamePrefix))
			.map(CompanionRegistry.Companion::portraitName)
			.findFirst()
			.orElse(EKUtils.extractCharacterName(objectName).toLowerCase(Locale.ROOT).replace(" ", "_"));

		return String.format(COMPANION_PORTRAIT, name);
	}

	private static Optional<DictionaryProperty> variables (final Property packet, final String component) {
		return SaveObject.of(packet)
			.flatMap(object -> object.component(component))
			.flatMap(found -> found.property().<DictionaryProperty>findProperty("Variables"));
	}

	private static Object value (final DictionaryProperty variables, final String key) {
		return variables.<Property>findEntry(key).map(property -> property.obj).orElse(null);
	}
}
