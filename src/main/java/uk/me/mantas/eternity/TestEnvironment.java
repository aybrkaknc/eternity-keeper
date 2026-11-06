/**
 * Eternity Keeper, a Pillars of Eternity save game editor.
 * Copyright (C) 2016 the authors.
 * <p>
 * Eternity Keeper is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * Eternity Keeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package uk.me.mantas.eternity;

import org.apache.commons.io.FileUtils;
import se.softhouse.jargo.Argument;
import se.softhouse.jargo.ArgumentException;
import se.softhouse.jargo.CommandLineParser;
import se.softhouse.jargo.ParsedArguments;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static com.google.common.collect.Range.atLeast;
import static se.softhouse.jargo.Arguments.*;

public class TestEnvironment {
	private static final class Args {
		private static final int DEFAULT_NUM_SAVES = 3;
		private static final String DEFAULT_WORKSPACE = "EK_TEST_ENV";

		private static final Argument<?> helpArgument = helpArgument("-h", "--help");
		private static final Argument<File> gameLocation =
			fileArgument()
				.required()
				.metaDescription("<game location>")
				.description("Your Pillars of Eternity install location.")
				.build();
		private static final Argument<File> saveLocation =
			fileArgument()
				.required()
				.metaDescription("<save location>")
				.description("Your save game location.")
				.build();
		private static final Argument<Integer> numSaves =
			integerArgument("-n", "--num-saves")
				.defaultValue(DEFAULT_NUM_SAVES)
				.description("The maximum number of saves to copy from your "
					+ "save directory to the test environment.")
				.limitTo(atLeast(1))
				.build();
		private static final Argument<String> workspace =
			stringArgument("-w", "--workspace")
				.defaultValue(DEFAULT_WORKSPACE)
				.description("The name of the directory to create as the test environment. "
					+ "This will get cleaned on every run of this script so change it if you "
					+ "want to preserve a previous environment.")
				.build();
	}

	public static void main (final String[] args) {
		try {
			final ParsedArguments arguments =
				CommandLineParser
					.withArguments(
						Args.gameLocation
						, Args.saveLocation
						, Args.numSaves
						, Args.workspace)
					.andArguments(Args.helpArgument)
					.parse(args);

			new TestEnvironment(arguments);
		} catch (final ArgumentException e) {
			System.out.printf("%s%n", e.getMessageAndUsage());
			System.exit(1);
		}
	}

	public TestEnvironment (final ParsedArguments args) {
		final File gameLocation = args.get(Args.gameLocation);
		final File saveLocation = args.get(Args.saveLocation);
		final Integer numSaves = args.get(Args.numSaves);
		final String workspacePrefix = args.get(Args.workspace);

		assert gameLocation != null
			&& saveLocation != null
			&& numSaves != null
			&& workspacePrefix != null;

		final File workspace = new File(System.getProperty("java.io.tmpdir"), workspacePrefix);

		if (workspace.exists()) {
			try {
				System.out.printf("Deleting old workspace...%n");
				FileUtils.deleteDirectory(workspace);
			} catch (final IOException e) {
				System.err.printf(
					"Unable to clean old workspace directory '%s': %s%n"
					, workspace.getAbsolutePath()
					, e.getMessage());
				System.exit(1);
			}
		}

		final File gameWorkspace = new File(workspace, "poe");
		final File saveWorkspace = new File(workspace, "save");

		final File portraitsLocation =
			gameLocation.toPath()
				.resolve("PillarsOfEternity_Data")
				.resolve("data")
				.resolve("art")
				.resolve("gui")
				.resolve("portraits")
				.toFile();

		final File portraitsWorkspace =
			gameWorkspace.toPath()
				.resolve("PillarsOfEternity_Data")
				.resolve("data")
				.resolve("art")
				.resolve("gui")
				.resolve("portraits")
				.toFile();

		if (!workspace.mkdirs() || !saveWorkspace.mkdir() || !portraitsWorkspace.mkdirs()) {
			System.err.printf(
				"Unable to create workspace directory '%s'.%n"
				, workspace.getAbsolutePath());
			System.exit(1);
		}

		// The game's own words, in every language it ships: hireling and
		// prisoner names are only ids into these tables (save/GameText), quests
		// are named out of them, and the catalogs' names are shown in the
		// language of the game through them. Not the conversations, which are
		// three quarters of the text and which nothing reads.
		final Path localized = Paths.get("PillarsOfEternity_Data", "data", "localized");

		try {
			System.out.printf("Copying portraits...%n");
			FileUtils.copyDirectory(portraitsLocation, portraitsWorkspace);

			System.out.printf("Copying the game's text...%n");
			final File[] languages = gameLocation.toPath().resolve(localized).toFile()
				.listFiles(File::isDirectory);

			for (final File language : languages == null ? new File[0] : languages) {
				final File copy =
					gameWorkspace.toPath().resolve(localized).resolve(language.getName()).toFile();

				final File description = new File(language, "language.xml");
				if (description.isFile()) {
					FileUtils.copyFileToDirectory(description, copy);
				}

				for (final String part : new String[]{"game", "quests"}) {
					final File tables = new File(new File(language, "text"), part);
					if (tables.isDirectory()) {
						FileUtils.copyDirectory(tables, new File(new File(copy, "text"), part));
					}
				}
			}
		} catch (final IOException e) {
			System.err.printf("Failed: %s%n", e.getMessage());
			System.exit(1);
		}

		System.out.printf("Copying saves...%n");

		final File[] saveGames = saveLocation.listFiles((dir, name) -> {
			return name.endsWith(".savegame");
		});

		Arrays.stream(saveGames).limit(numSaves).forEach(f -> {
			try {
				FileUtils.copyFileToDirectory(f, saveWorkspace);
			} catch (final IOException e) {
				System.err.printf(
					"Unable to copy '%s' to '%s': %s%n"
					, f.getAbsolutePath()
					, saveWorkspace.getAbsolutePath()
					, e.getMessage());
			}
		});

		final Settings settings = Settings.getInstance();
		settings.json.put("gameLocation", gameWorkspace.getAbsolutePath());
		settings.json.put("savesLocation", saveWorkspace.getAbsolutePath());
		settings.save();
	}
}
