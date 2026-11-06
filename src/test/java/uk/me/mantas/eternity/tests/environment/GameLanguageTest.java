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


package uk.me.mantas.eternity.tests.environment;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.GameLanguage;
import uk.me.mantas.eternity.environment.GameLanguage.Language;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Which language the game's own names are shown in.
 *
 * <p>The game ships its text once per language, in
 * {@code data/localized/<code>}, each folder with a {@code language.xml} that
 * gives the name the game's own setting uses ("polish") and the name the
 * language calls itself ("Polski"). The editor showed every item, ability and
 * upgrade in English whatever the player's game said. It now follows the game
 * unless Settings says otherwise, and falls back to English for a language
 * the install does not have.
 */
public class GameLanguageTest extends TestHarness {
	private static void language (final File game, final String code, final String name
		, final String label) throws IOException {

		final File folder = new File(game, "PillarsOfEternity_Data/data/localized/" + code);
		if (name != null) {
			FileUtils.write(new File(folder, "language.xml")
				, "﻿<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<Language>\n  <Name>" + name
					+ "</Name>\n  <GUIString>" + label + "</GUIString>\n</Language>", "UTF-8");
		}

		assertTrue(new File(folder, "text/game").mkdirs());
	}

	private File install () throws IOException {
		final File game = EKUtils.createTempDir(PREFIX).get();
		language(game, "pl", "polish", "Polski");
		language(game, "en", "english", "English");
		language(game, "ko", "korean", "한국어");
		return game;
	}

	private static List<String> codes (final List<Language> languages) {
		return languages.stream().map(l -> l.code).collect(Collectors.toList());
	}

	@Test
	public void theInstallSaysWhichLanguagesItHas () throws IOException {
		final List<Language> languages = GameLanguage.available(install());

		assertEquals("English first, the rest by the name each calls itself"
			, Arrays.asList("en", "pl", "ko"), codes(languages));
		assertEquals("polish", languages.get(1).name);
		assertEquals("Polski", languages.get(1).label);
		assertEquals("한국어", languages.get(2).label);
	}

	@Test
	public void aFolderThatIsNotALanguageIsLeftOut () throws IOException {
		final File game = install();
		// No language.xml, and one with a description but no text.
		language(game, "xx", null, null);
		FileUtils.write(new File(game, "PillarsOfEternity_Data/data/localized/yy/language.xml")
			, "<Language><Name>yiddish</Name><GUIString>Yiddish</GUIString></Language>", "UTF-8");
		FileUtils.write(new File(game, "PillarsOfEternity_Data/data/localized/readme.txt"), "x", "UTF-8");

		assertEquals(Arrays.asList("en", "pl", "ko"), codes(GameLanguage.available(game)));
	}

	@Test
	public void withNoInstallThereAreNoLanguages () {
		assertEquals(Collections.emptyList(), GameLanguage.available(null));
		assertEquals(Collections.emptyList(), GameLanguage.available(new File("no-such-folder-404")));
	}

	@Test
	public void theChoiceInSettingsWinsWhereTheInstallHasIt () throws IOException {
		final List<Language> available = GameLanguage.available(install());

		assertEquals("pl", GameLanguage.choose("pl", available, Optional.of("english")));
		assertEquals("en", GameLanguage.choose("en", available, Optional.of("polish")));
		assertEquals("a choice the install lacks is no choice"
			, "pl", GameLanguage.choose("de", available, Optional.of("polish")));
	}

	@Test
	public void withNoChoiceTheGameIsFollowed () throws IOException {
		final List<Language> available = GameLanguage.available(install());

		assertEquals("pl", GameLanguage.choose("", available, Optional.of("polish")));
		assertEquals("pl", GameLanguage.choose(null, available, Optional.of("Polish")));
		assertEquals("ko", GameLanguage.choose("", available, Optional.of("korean")));
	}

	@Test
	public void whenNothingSaysItIsEnglish () throws IOException {
		final List<Language> available = GameLanguage.available(install());

		assertEquals("en", GameLanguage.choose("", available, Optional.empty()));
		assertEquals("en", GameLanguage.choose("", available, Optional.of("klingon")));
		assertEquals("en", GameLanguage.choose("pl", Collections.emptyList(), Optional.of("polish")));
	}

	// reg query "HKCU\Software\Obsidian Entertainment\Pillars of Eternity" /f LanguageName
	// The game keeps its settings as Unity PlayerPrefs: the value's name carries
	// a hash and its text is bytes, ending in a zero.
	@Test
	public void theGamesOwnSettingIsReadOutOfTheRegistrysAnswer () {
		assertEquals(Optional.of("english"), GameLanguage.parse(Arrays.asList(
			""
			, "HKEY_CURRENT_USER\\Software\\Obsidian Entertainment\\Pillars of Eternity"
			, "    LanguageName_h2027703280    REG_BINARY    656E676C69736800"
			, ""
			, "End of search: 1 match(es) found.")));

		assertEquals(Optional.of("polish"), GameLanguage.parse(Arrays.asList(
			"    Screenmanager Resolution Width_h182942802    REG_DWORD    0xa00"
			, "    LanguageName_h2027703280    REG_BINARY    706F6C69736800")));

		assertEquals("as text, should a build keep it so"
			, Optional.of("german"), GameLanguage.parse(Collections.singletonList(
				"    LanguageName    REG_SZ    german")));
	}

	@Test
	public void anAnswerWithNoSuchValueIsNoSetting () {
		assertEquals(Optional.empty(), GameLanguage.parse(Collections.emptyList()));
		assertEquals(Optional.empty(), GameLanguage.parse(Arrays.asList(
			"ERROR: The system was unable to find the specified registry key or value."
			, "    Language    REG_BINARY    zz"
			, "    LanguageName_h1    REG_BINARY    6G")));
		assertEquals(Optional.empty(), GameLanguage.parse(Collections.singletonList(
			"    LanguageName_h1    REG_BINARY    00")));
	}

	@Test
	public void testsNeverAskTheRegistry () {
		// TestHarness pins this, so a machine whose game is set to Polish
		// answers the same as one with no game at all.
		assertEquals(Optional.empty(), GameLanguage.gameSetting());
	}
}
