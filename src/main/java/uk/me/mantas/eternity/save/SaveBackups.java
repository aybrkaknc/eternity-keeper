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

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.joox.Match;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.AppPaths;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

import static org.joox.JOOX.$;

/**
 * Copies of the player's saves, taken before the editor changes or removes a
 * file they already have. The editor never writes to the save it opened, but
 * three things do touch files in the saves folder: Delete removes one, Rename
 * rewrites one in place, and Save replaces a file that happens to have the
 * name it is writing. Each takes a copy here first, and a copy can be put back.
 *
 * <p>Each backup is a folder named for the moment it was taken, holding the
 * save under its own file name and a {@code backup.json} saying where it came
 * from and why. Saves run to tens of megabytes, so only the newest
 * {@link #KEEP} of each reason are kept.
 */
public class SaveBackups {
	private static final Logger logger = Logger.getLogger(SaveBackups.class);
	private static final String RECORD = "backup.json";

	/** How many backups of each reason are kept; the oldest go first. */
	public static final int KEEP = 10;

	/** Why a copy was taken. */
	public enum Reason {
		DELETE("before it was deleted"),
		RENAME("before it was renamed"),
		OVERWRITE("before Save replaced it");

		public final String description;

		Reason (final String description) {
			this.description = description;
		}
	}

	/** One copy, and what it is a copy of. */
	public static final class Backup {
		/** The backup's folder name; what {@link #restore} takes. */
		public final String id;
		public final File file;
		public final File original;
		public final Reason reason;
		public final long time;
		public final long size;
		public final String userSaveName;
		public final String sceneTitle;

		Backup (
			final String id, final File file, final File original, final Reason reason
			, final long time, final String userSaveName, final String sceneTitle) {

			this.id = id;
			this.file = file;
			this.original = original;
			this.reason = reason;
			this.time = time;
			this.size = file.length();
			this.userSaveName = userSaveName;
			this.sceneTitle = sceneTitle;
		}
	}

	private final File root;
	private final int keep;
	private final LongSupplier clock;

	public SaveBackups (final File root, final int keep, final LongSupplier clock) {
		this.root = root;
		this.keep = keep;
		this.clock = clock;
	}

	/** The editor's own, in its data folder. */
	public static SaveBackups forThisProcess () {
		return new SaveBackups(
			AppPaths.forThisProcess().backups(), KEEP, System::currentTimeMillis);
	}

	public File folder () {
		return root;
	}

	/**
	 * Copies {@code save} in, then drops the oldest copies beyond the limit.
	 *
	 * @throws IOException if the copy could not be made, in which case the
	 *         caller must not go on to change the save
	 */
	public synchronized Backup backup (final File save, final Reason reason) throws IOException {
		if (!save.isFile()) {
			throw new IOException("There is no save at " + save.getAbsolutePath() + " to back up.");
		}

		final long time = clock.getAsLong();
		final File folder = freshFolder(time);
		final File copy = new File(folder, save.getName());
		FileUtils.copyFile(save, copy);

		final Match info = saveInfo(copy);
		final JSONObject record = new JSONObject();
		record.put("original", save.getCanonicalPath());
		record.put("reason", reason.name());
		record.put("time", time);
		record.put("userSaveName", info == null ? "" : info.find("Simple[name='UserSaveName']").attr("value"));
		record.put("sceneTitle", info == null ? "" : info.find("Simple[name='SceneTitle']").attr("value"));
		FileUtils.writeStringToFile(new File(folder, RECORD), record.toString(), StandardCharsets.UTF_8);

		logger.info("Backed up '%s' (%s) to %s%n", save.getAbsolutePath(), reason, folder.getAbsolutePath());
		prune();
		return read(folder).orElseThrow(() -> new IOException("The backup could not be read back."));
	}

	/** Every backup there is, newest first. */
	public synchronized List<Backup> list () {
		final List<Backup> backups = new ArrayList<>();
		final File[] folders = root.listFiles(File::isDirectory);
		if (folders != null) {
			for (final File folder : folders) {
				read(folder).ifPresent(backups::add);
			}
		}

		backups.sort(Comparator.comparingLong((Backup backup) -> backup.time)
			.thenComparing(backup -> backup.id).reversed());
		return backups;
	}

	/**
	 * Puts a copy back where it came from, under its own name.
	 *
	 * @return the restored save
	 * @throws IOException if there is no such backup, its folder is gone, or a
	 *         save is already there — restoring never replaces anything
	 */
	public synchronized File restore (final String id) throws IOException {
		final Backup backup = list().stream()
			.filter(candidate -> candidate.id.equals(id))
			.findFirst()
			.orElseThrow(() -> new IOException("There is no backup called " + id + "."));

		final File target = backup.original;
		if (target.exists()) {
			throw new IOException(target.getName() + " is already in " + target.getParent()
				+ ". Rename or move that save first; restoring never replaces one.");
		}

		final File folder = target.getParentFile();
		if (folder == null || !folder.isDirectory()) {
			throw new IOException("The folder it came from, " + folder + ", is gone. The copy is "
				+ backup.file.getAbsolutePath() + ".");
		}

		FileUtils.copyFile(backup.file, target);
		logger.info("Restored %s to %s%n", backup.id, target.getAbsolutePath());
		return target;
	}

	private File freshFolder (final long time) throws IOException {
		final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date(time));
		File folder = new File(root, stamp);
		for (int n = 2; folder.exists(); n++) {
			folder = new File(root, stamp + "-" + n);
		}

		if (!folder.mkdirs()) {
			throw new IOException("Could not create " + folder.getAbsolutePath() + ".");
		}

		return folder;
	}

	// The limit is counted for each reason on its own: every Save after the
	// first replaces the file the first one wrote, and with one count for all,
	// ten of those pushed out the only copy of a save the player had deleted.
	private void prune () {
		final Map<Reason, Integer> seen = new EnumMap<>(Reason.class);
		for (final Backup old : list()) {
			final int newer = seen.merge(old.reason, 1, Integer::sum);
			if (newer <= keep) {
				continue;
			}

			try {
				FileUtils.deleteDirectory(old.file.getParentFile());
			} catch (final IOException e) {
				logger.error("Could not remove the old backup %s: %s%n", old.id, e.getMessage());
			}
		}
	}

	private static Optional<Backup> read (final File folder) {
		final File record = new File(folder, RECORD);
		if (!record.isFile()) {
			return Optional.empty();
		}

		try {
			final JSONObject json =
				new JSONObject(FileUtils.readFileToString(record, StandardCharsets.UTF_8));
			final File original = new File(json.getString("original"));
			final File copy = new File(folder, original.getName());
			if (!copy.isFile()) {
				return Optional.empty();
			}

			return Optional.of(new Backup(
				folder.getName(), copy, original, Reason.valueOf(json.getString("reason"))
				, json.getLong("time"), json.optString("userSaveName", "")
				, json.optString("sceneTitle", "")));
		} catch (final IOException | JSONException | IllegalArgumentException e) {
			logger.error("Unreadable backup %s: %s%n", folder.getAbsolutePath(), e.getMessage());
			return Optional.empty();
		}
	}

	/** saveinfo.xml straight out of the .savegame, or null if it has none. */
	private static Match saveInfo (final File save) {
		try {
			final ZipFile zip = new ZipFile(save);
			final FileHeader header = zip.getFileHeader("saveinfo.xml");
			if (header == null) {
				return null;
			}

			try (final InputStream in = zip.getInputStream(header)) {
				return $(new String(EKUtils.removeBOM(IOUtils.toByteArray(in)), StandardCharsets.UTF_8));
			}
		} catch (final IOException | RuntimeException e) {
			logger.error("Could not read the name of %s: %s%n", save.getAbsolutePath(), e.getMessage());
			return null;
		}
	}
}
