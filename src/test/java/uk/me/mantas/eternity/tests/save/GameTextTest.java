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


package uk.me.mantas.eternity.tests.save;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.DatabaseString.StringTableType;
import uk.me.mantas.eternity.save.GameText;
import uk.me.mantas.eternity.save.CompanionRegistry;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

// The game's own words, read out of the string tables on the player's
// install: data/localized/en/text/game/<table>.stringtable, one XML file per
// DatabaseString.StringTableType. A save stores a hireling's or a prisoner's
// name only as a table and an id into it.
public class GameTextTest extends TestHarness {
	// The shape of the shipped files, BOM and all.
	private static final String CHARACTERS = "﻿<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
		+ "<StringTableFile xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
		+ "  <Name>game\\characters</Name>\n"
		+ "  <EntryCount>3</EntryCount>\n"
		+ "  <Entries>\n"
		+ "    <Entry>\n"
		+ "      <Language>english</Language>\n"
		+ "      <ID>329</ID>\n"
		+ "      <DefaultText>Crucible Knight</DefaultText>\n"
		+ "      <FemaleText />\n"
		+ "      <GenderNeutralText />\n"
		+ "    </Entry>\n"
		+ "    <Entry>\n"
		+ "      <Language>english</Language>\n"
		+ "      <ID>1171</ID>\n"
		+ "      <DefaultText>Kestorik</DefaultText>\n"
		+ "      <FemaleText />\n"
		+ "    </Entry>\n"
		+ "    <Entry>\n"
		+ "      <ID>12</ID>\n"
		+ "      <DefaultText>Hiravias &amp; \"friends\" &lt;3</DefaultText>\n"
		+ "    </Entry>\n"
		+ "  </Entries>\n"
		+ "</StringTableFile>\n";
	private static final String TRANSLATED_CHARACTERS = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
		+ "<StringTableFile><Entries>"
		+ "<Entry><ID>13</ID><DefaultText>Edér locale</DefaultText></Entry>"
		+ "<Entry><ID>634</ID><DefaultText>Matka Żalu</DefaultText></Entry>"
		+ "</Entries></StringTableFile>";

	private File install () throws IOException {
		final Optional<File> game = EKUtils.createTempDir(PREFIX);
		assertTrue(game.isPresent());

		final File tables = new File(game.get()
			, "PillarsOfEternity_Data/data/localized/en/text/game");
		FileUtils.write(new File(tables, "characters.stringtable"), CHARACTERS, "UTF-8");
		final File translated = new File(game.get()
			, "PillarsOfEternity_Data/data/localized/pl/text/game");
		FileUtils.write(new File(translated, "characters.stringtable")
			, TRANSLATED_CHARACTERS, "UTF-8");
		return game.get();
	}

	@After
	public void pinOff () {
		GameText.useNoText();
	}

	@Test
	public void anIdIsLookedUpInItsOwnTable () throws IOException {
		GameText.useTextAt(install());

		assertEquals(Optional.of("Crucible Knight")
			, GameText.getInstance().lookup(StringTableType.Characters, 329));
		assertEquals(Optional.of("Kestorik")
			, GameText.getInstance().lookup(StringTableType.Characters, 1171));
	}

	@Test
	public void entitiesComeBackAsTheCharactersTheyStandFor () throws IOException {
		GameText.useTextAt(install());

		assertEquals(Optional.of("Hiravias & \"friends\" <3")
			, GameText.getInstance().lookup(StringTableType.Characters, 12));
	}

	@Test
	public void anIdTheTableDoesNotHaveIsNothing () throws IOException {
		GameText.useTextAt(install());

		assertEquals(Optional.empty()
			, GameText.getInstance().lookup(StringTableType.Characters, 4000));
		assertEquals(Optional.empty()
			, GameText.getInstance().lookup(StringTableType.Characters, -1));
	}

	@Test
	public void aTableTheInstallDoesNotHaveIsNothing () throws IOException {
		GameText.useTextAt(install());

		assertEquals(Optional.empty()
			, GameText.getInstance().lookup(StringTableType.Stronghold, 79));
	}

	@Test
	public void withNoInstallThereIsNoText () {
		// TestHarness pins this, so a machine with the game installed answers
		// the same as one without.
		assertEquals(Optional.empty()
			, GameText.getInstance().lookup(StringTableType.Characters, 329));
	}

	@Test
	public void companionNamesFollowTheSelectedGameLanguageAndPreserveOverrides () throws IOException {
		GameText.useTextAt(install(), "pl");

		assertEquals("Edér locale", CompanionRegistry.displayName(
			CompanionRegistry.byKey("Eder").get()));
		assertEquals("Matka Żalu", CompanionRegistry.displayName(
			CompanionRegistry.byKey("GrievingMother").get()));
		assertEquals(Optional.of("My custom name")
			, CompanionRegistry.displayNameFor("Companion_Eder(Clone)", " My custom name "));
		assertEquals(Optional.of("Edér locale")
			, CompanionRegistry.displayNameFor("Companion_Eder(Clone)", ""));
		assertEquals(Optional.of("Edér locale")
			, CompanionRegistry.displayNameFor("Companion_Eder_2", ""));
	}

	@Test
	public void onlyACompanionsOwnObjectTakesTheirName () {
		// A name that merely begins with a companion's prefix is someone else.
		assertEquals(Optional.empty()
			, CompanionRegistry.displayNameFor("Companion_EderStatue", ""));
		assertEquals(Optional.empty()
			, CompanionRegistry.displayNameFor("Player_Phantom(Clone)", ""));
	}

	@Test
	public void aBrokenTableIsNothingRatherThanAFailure () throws IOException {
		final File game = install();
		FileUtils.write(new File(game
			, "PillarsOfEternity_Data/data/localized/en/text/game/stronghold.stringtable")
			, "<StringTableFile><Entries><Entry><ID>79</ID>", "UTF-8");

		GameText.useTextAt(game);
		assertEquals(Optional.empty()
			, GameText.getInstance().lookup(StringTableType.Stronghold, 79));
		assertEquals(Optional.of("Kestorik")
			, GameText.getInstance().lookup(StringTableType.Characters, 1171));
	}

	// A quest has a table of its own beside the quest files, named after the
	// quest's file: entry 0 is its title, the rest its objectives and journal
	// entries. A save names the quest by the file the game loaded.
	@Test
	public void aQuestIsReadFromTheTableBesideIt () throws IOException {
		final File game = install();
		FileUtils.write(new File(game, "PillarsOfEternity_Data/data/localized/en/text/quests/"
			+ "critical_path/act_4/cp_qst_confront_lka.stringtable")
			, CHARACTERS.replace("<ID>329</ID>", "<ID>0</ID>")
				.replace("Crucible Knight", "Memories of the Ancients")
				.replace("<ID>1171</ID>", "<ID>4</ID>")
				.replace("Kestorik", "Enter Sun in Shadow."), "UTF-8");

		GameText.useTextAt(game);
		final Map<Integer, String> quest =
			GameText.getInstance().quest("data/quests/critical_path/act_4/cp_qst_confront_lka.quest");

		assertEquals("Memories of the Ancients", quest.get(0));
		assertEquals("Enter Sun in Shadow.", quest.get(4));
		assertEquals("the same quest the save's way round", quest
			, GameText.getInstance().quest("Data\\Quests\\critical_path\\act_4\\cp_qst_confront_lka.quest"));
		assertTrue(GameText.getInstance().quest("data/quests/no_such_quest.quest").isEmpty());
		assertTrue("never outside the quests folder"
			, GameText.getInstance().quest("data/quests/../game/characters.quest").isEmpty());
	}

	// The game ships every table once per language, in
	// data/localized/<code>/text. A player whose game is in Polish sees a
	// Rycerz Tygla, and so should whoever edits that game's saves.
	private static void polish (final File game, final String table, final String contents)
		throws IOException {

		FileUtils.write(new File(game
			, "PillarsOfEternity_Data/data/localized/pl/text/" + table + ".stringtable")
			, contents, "UTF-8");
	}

	@Test
	public void aTableIsReadInTheLanguageAskedFor () throws IOException {
		final File game = install();
		polish(game, "game/characters", CHARACTERS.replace("Crucible Knight", "Rycerz Tygla"));

		GameText.useTextAt(game, "pl");
		assertEquals(Optional.of("Rycerz Tygla")
			, GameText.getInstance().lookup(StringTableType.Characters, 329));
		assertEquals("pl", GameText.getInstance().language());

		GameText.useTextAt(game);
		assertEquals(Optional.of("Crucible Knight")
			, GameText.getInstance().lookup(StringTableType.Characters, 329));
		assertEquals("en", GameText.getInstance().language());
	}

	@Test
	public void whatALanguageLacksIsSaidInEnglish () throws IOException {
		final File game = install();
		// The Polish table has no 1171, an empty 12, and there is no Polish
		// stronghold table at all.
		polish(game, "game/characters", CHARACTERS
			.replace("Crucible Knight", "Rycerz Tygla")
			.replace("<ID>1171</ID>", "<ID>99999</ID>")
			.replace("<DefaultText>Hiravias &amp; \"friends\" &lt;3</DefaultText>", "<DefaultText />"));
		FileUtils.write(new File(game
			, "PillarsOfEternity_Data/data/localized/en/text/game/stronghold.stringtable")
			, CHARACTERS.replace("<ID>329</ID>", "<ID>79</ID>").replace("Crucible Knight", "Main Keep")
			, "UTF-8");

		GameText.useTextAt(game, "pl");
		final GameText text = GameText.getInstance();
		assertEquals(Optional.of("Rycerz Tygla"), text.lookup(StringTableType.Characters, 329));
		assertEquals(Optional.of("Kestorik"), text.lookup(StringTableType.Characters, 1171));
		assertEquals(Optional.of("Hiravias & \"friends\" <3"), text.lookup(StringTableType.Characters, 12));
		assertEquals(Optional.of("Main Keep"), text.lookup(StringTableType.Stronghold, 79));
	}

	// A catalog keeps the English name it was read with, so what it asks for
	// is only the other language's word for it: nothing when the language is
	// English, or has no such entry.
	@Test
	public void aTranslationIsOnlyEverTheOtherLanguagesWord () throws IOException {
		final File game = install();
		polish(game, "game/characters", CHARACTERS
			.replace("Crucible Knight", "Rycerz Tygla")
			.replace("<ID>1171</ID>", "<ID>99999</ID>"));

		GameText.useTextAt(game, "pl");
		assertEquals(Optional.of("Rycerz Tygla"), GameText.getInstance().translated(4, 329));
		assertEquals("no Polish word: the catalog keeps its own"
			, Optional.empty(), GameText.getInstance().translated(4, 1171));
		assertEquals("no such table", Optional.empty(), GameText.getInstance().translated(31337, 329));
		assertEquals(Optional.empty(), GameText.getInstance().translated(4, -1));

		GameText.useTextAt(game);
		assertEquals("English is what the catalog already holds"
			, Optional.empty(), GameText.getInstance().translated(4, 329));
	}

	@Test
	public void aLanguageTheInstallDoesNotHaveIsEnglish () throws IOException {
		final File game = install();

		for (final String language : new String[]{"de", "", null, "../en", "pl/../en", "en/text"}) {
			GameText.useTextAt(game, language);
			assertEquals(String.valueOf(language), "en", GameText.getInstance().language());
			assertEquals(Optional.of("Kestorik")
				, GameText.getInstance().lookup(StringTableType.Characters, 1171));
		}
	}

	@Test
	public void aQuestIsReadInTheLanguageAskedFor () throws IOException {
		final File game = install();
		final String quest = CHARACTERS.replace("<ID>329</ID>", "<ID>0</ID>")
			.replace("Crucible Knight", "Memories of the Ancients")
			.replace("<ID>1171</ID>", "<ID>4</ID>")
			.replace("Kestorik", "Enter Sun in Shadow.");
		FileUtils.write(new File(game, "PillarsOfEternity_Data/data/localized/en/text/quests/"
			+ "critical_path/act_4/cp_qst_confront_lka.stringtable"), quest, "UTF-8");
		// The title is translated; the objective is missing from the Polish table.
		polish(game, "game/characters", CHARACTERS);
		polish(game, "quests/critical_path/act_4/cp_qst_confront_lka", quest
			.replace("Memories of the Ancients", "Wspomnienia Starożytnych")
			.replace("<ID>4</ID>", "<ID>40</ID>"));

		GameText.useTextAt(game, "pl");
		final Map<Integer, String> entries =
			GameText.getInstance().quest("data/quests/critical_path/act_4/cp_qst_confront_lka.quest");

		assertEquals("Wspomnienia Starożytnych", entries.get(0));
		assertEquals("Enter Sun in Shadow.", entries.get(4));
	}
}
