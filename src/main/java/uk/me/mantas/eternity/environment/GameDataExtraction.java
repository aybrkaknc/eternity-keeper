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

package uk.me.mantas.eternity.environment;

import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.save.AbilityCatalog;
import uk.me.mantas.eternity.save.IdentityCatalog;
import uk.me.mantas.eternity.save.ItemCatalog;
import uk.me.mantas.eternity.save.StrongholdCatalog;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs the game-data extractor ({@code tools/gamedata/extract_gamedata.py},
 * shipped frozen in a release) and keeps what the UI polls for: how far it
 * has got, and why it stopped if it failed.
 *
 * <p>The item names and icons, abilities, progression tables, stronghold
 * upgrades and deities all live in Unity asset bundles in the player's own
 * install, which Java 8 cannot realistically parse — and which cannot be
 * shipped with the editor, since they are the game's. So the editor reads them
 * from the install the user already has, once, into its data folder.
 *
 * <p>The extractor speaks a line protocol on stdout: {@code PROGRESS <percent>
 * <what>}, then {@code DONE <folder>} or {@code ERROR <reason>}; anything else
 * is chatter for the log. It assembles its output beside the target and moves
 * it in only when every stage succeeded, so a failed run leaves the old data.
 */
public class GameDataExtraction {
	private static final Logger logger = Logger.getLogger(GameDataExtraction.class);
	private static final String MANIFEST = "gamedata.json";
	private static final int LAST_WORDS = 3;

	private static GameDataExtraction instance = null;

	private final Supplier<Optional<List<String>>> command;
	private final File out;
	private final Runnable onFinished;

	private final Progress progress = new Progress();
	private Process process = null;
	private Thread runner = null;
	private boolean cancelled = false;

	/** What the extractor has said so far. */
	public static class Progress {
		public int percent = 0;
		public String text = "";
		public String error = null;
		public boolean running = false;
		public boolean finished = false;
		private final Deque<String> lastWords = new ArrayDeque<>();

		/** Takes in one line of the extractor's output. */
		public void read (final String line) {
			if (line.startsWith("PROGRESS ")) {
				final String[] parts = line.split(" ", 3);
				try {
					percent = Math.max(0, Math.min(100, Integer.parseInt(parts[1])));
					text = parts.length > 2 ? parts[2] : "";
				} catch (final NumberFormatException ignored) {
					// Not ours after all; leave the bar where it was.
				}
			} else if (line.startsWith("ERROR ")) {
				error = line.substring("ERROR ".length());
			} else if (!line.trim().isEmpty()) {
				lastWords.addLast(line.trim());
				while (lastWords.size() > LAST_WORDS) {
					lastWords.removeFirst();
				}
			}
		}

		private void reset () {
			percent = 0;
			text = "Starting";
			error = null;
			finished = false;
			lastWords.clear();
		}
	}

	public GameDataExtraction (
		final Supplier<Optional<List<String>>> command
		, final File out
		, final Runnable onFinished) {

		this.command = command;
		this.out = out;
		this.onFinished = onFinished;
	}

	/** The editor's own: its extractor, its data folder, and its catalogs. */
	public static synchronized GameDataExtraction getInstance () {
		if (instance == null) {
			final AppPaths paths = AppPaths.forThisProcess();
			instance = new GameDataExtraction(paths::extractor, paths.gameData(), () -> {
				ItemCatalog.reset();
				AbilityCatalog.reset();
				StrongholdCatalog.reset();
				IdentityCatalog.reset();
			});

			// A run left behind by closing the editor would go on writing
			// into its data folder with nothing to show for it.
			Runtime.getRuntime().addShutdownHook(new Thread(instance::cancel, "gamedata-stop"));
		}

		return instance;
	}

	/** Whether there is an extractor to run at all. */
	public boolean available () {
		return command.get().isPresent();
	}

	/**
	 * Starts reading {@code game} in the background.
	 *
	 * @return false if there is no extractor, or a run is already going —
	 *         two would write into the same folder
	 */
	public synchronized boolean start (final File game) {
		final Optional<List<String>> program = command.get();
		if (progress.running || !program.isPresent()) {
			return false;
		}

		final List<String> arguments = new ArrayList<>(program.get());
		arguments.add("--game");
		arguments.add(game.getPath());
		arguments.add("--out");
		arguments.add(out.getAbsolutePath());

		progress.reset();
		progress.running = true;
		cancelled = false;
		runner = new Thread(() -> run(arguments), "gamedata");
		runner.setDaemon(true);
		runner.start();
		return true;
	}

	private void run (final List<String> arguments) {
		logger.info("Reading game data: %s%n", String.join(" ", arguments));
		int exit = -1;

		try {
			final Process started = new ProcessBuilder(arguments).redirectErrorStream(true).start();
			synchronized (this) {
				process = started;
				if (cancelled) {
					started.destroy();
				}
			}

			try (final BufferedReader reader = new BufferedReader(
				new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {

				String line;
				while ((line = reader.readLine()) != null) {
					logger.info("extractor: %s%n", line);
					synchronized (this) {
						progress.read(line);
					}
				}
			}

			exit = started.waitFor();
		} catch (final IOException e) {
			synchronized (this) {
				progress.error = "The game-data reader could not be started: " + e.getMessage();
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		final boolean succeeded;
		synchronized (this) {
			process = null;
			succeeded = exit == 0 && progress.error == null;
			if (!succeeded && progress.error == null) {
				progress.error = String.format(
					"The game-data reader stopped unexpectedly (exit code %d). Its last words: %s"
					, exit, String.join(" / ", progress.lastWords));
			}

			progress.running = false;
			progress.finished = succeeded;
		}

		if (succeeded) {
			logger.info("Game data read into %s%n", out.getAbsolutePath());
			onFinished.run();
		} else {
			logger.error("Reading game data failed: %s%n", progress.error);

			// The reader builds in "<out>.new" and clears it when it fails by
			// itself. Stopped, it was killed where it stood, and the half-read
			// folder stayed in the data folder until the next read.
			FileUtils.deleteQuietly(new File(out.getPath() + ".new"));
		}
	}

	/** Stops a run in progress, leaving whatever was there before. */
	public void cancel () {
		final Process running;
		synchronized (this) {
			if (!progress.running) {
				return;
			}

			// The process may not exist yet; run() stops it the moment it does.
			cancelled = true;
			running = process;
			if (progress.error == null) {
				progress.error = "Stopped before it finished. The game data already there is unchanged.";
			}
		}

		if (running != null) {
			running.destroy();
			try {
				if (!running.waitFor(5, TimeUnit.SECONDS)) {
					running.destroyForcibly();
				}
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** Waits for the current run, if there is one, to finish. */
	public void await (final long millis) throws InterruptedException {
		final Thread current;
		synchronized (this) {
			current = runner;
		}

		if (current != null) {
			current.join(millis);
		}
	}

	/**
	 * What the UI shows: whether an extractor exists, the current or last
	 * run, and what the data folder already holds.
	 */
	public synchronized JSONObject status () {
		final JSONObject status = new JSONObject();
		status.put("available", available());
		status.put("running", progress.running);
		status.put("percent", progress.percent);
		status.put("text", progress.text);
		status.put("finished", progress.finished);
		if (progress.error != null) {
			status.put("error", progress.error);
		}

		manifest().ifPresent(data -> status.put("data", data));
		return status;
	}

	private Optional<JSONObject> manifest () {
		final File manifest = new File(out, MANIFEST);
		if (!manifest.isFile()) {
			return Optional.empty();
		}

		try {
			final JSONObject data =
				new JSONObject(FileUtils.readFileToString(manifest, StandardCharsets.UTF_8));
			data.put("folder", out.getCanonicalPath());
			return Optional.of(data);
		} catch (final IOException | JSONException e) {
			logger.error("Unreadable %s: %s%n", manifest.getAbsolutePath(), e.getMessage());
			return Optional.empty();
		}
	}
}
