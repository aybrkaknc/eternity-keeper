# Release packaging

> Working notes, kept while the editor was built and moved here from a file
> outside the repository in October 2026. They are written for whoever
> changes the code next: what the game does with a save, measured or read
> out of the decompiled game, and what went wrong when that was guessed at.
> "Invariant N" anywhere in these documents is rule N of
> [invariants.md](invariants.md).

How the Windows download is built, what it has to carry, and what broke on the
way. The installer and the faults a clean machine and odd folders turned up are
under "Review, damaged input and the installer" in [lessons.md](lessons.md).

The four blockers to "someone else can use this" are done: a Windows zip that
needs nothing installed, a game-data reader the editor runs itself, running from
any folder, and the DevTools port off by default. What is worth knowing:

- **`environment/AppPaths` owns every path the editor itself uses.** *home* is
  the jar's folder (the UI, natives and reader ship there); *data* is
  `%APPDATA%\Eternity Keeper` unless `-Dek.data` says otherwise (settings.json,
  eternity.log, cef.log, gamedata\). Nothing is relative to the working
  directory any more. `LogFileLocation` hands logback its path, because logging
  starts before `main` runs. `adoptLegacySettings` copies a settings.json kept
  beside an older copy, once.
- **The UI loads from an absolute `file:///…/ui/index.html`**, which also works
  with a space in the path (the URL is %20-encoded).
- **Launch4j passes JVM options verbatim.** The release folder is called
  "Eternity Keeper", so an unquoted `-Djava.library.path=%EXEDIR%\…` split at
  the space, java took `Keeper\lib\…` for the main class, and the editor
  silently never started — the launcher still exits 0 because a GUI header
  does not wait. Every option with a path is quoted, in the pom and in any
  `Eternity Keeper.l4j.ini` (which is how a release gets `-Dek.data` or
  `-Dek.debugPort` for testing). `Launch4j=debug` in the environment makes the
  exe write `launch4j.log` with the exact command line. Plugin 1.7.25 is
  Launch4j 3.12: the missing-runtime message is `bundledJreErr`.
- **The game-data reader** (`tools/gamedata/extract_gamedata.py`) wraps the
  three old extractors behind `configure(game, out)`, prints
  `PROGRESS <pct> <text>` / `DONE` / `ERROR <reason>`, exits 2 for "not a game
  folder", and builds in `<out>.new` before swapping. A full run takes 195 s on an idle machine (11 min with builds competing)
  here and its output was byte-identical to the folder the development setup keeps beside the checkout (`itemdata`).
  `environment/GameDataExtraction` runs it on its own daemon thread (not a
  worker — it takes minutes), keeps the last three chatter lines for a crash
  report, and resets all four catalogs on success. A cancel that arrives
  before the process exists is remembered and applied the moment it does.
- **Ask for the game-data status only after `getDefaultSaveLocation` answers**:
  the install search is what fills in `gameLocation`, so asking at `init` told a
  first-time user to set a folder the editor was about to find.
- **FMOD must not ship.** UnityPy's `fmod_toolkit` bundles the proprietary
  `fmod.dll`; the frozen build uses the stub below instead, and the release
  script fails if any `fmod*.dll`/`libfmod*` is in the frozen folder.
- **Tests that spawn a JVM** (`FakeExtractor`) put the test-classes folder on
  the classpath from the class's own code source: surefire runs tests from a
  manifest-only jar, so `java.class.path` names nothing useful.
- Java's `ProcessBuilder` starts a console program without a window, so the
  frozen reader is built `--console` (a `--windowed` build has no stdout).
- **org.json is 20250517 (public domain) now**, not 20141113, whose JSON
  License ("Good, not Evil") is a known GPL incompatibility. It was a drop-in:
  every Java test and UI suite passed unchanged.
- **A frozen reader can write every catalog and not one icon.** Texture export
  needs UnityPy.export — which imports `fmod_toolkit` at module load even for
  images — plus texture2ddecoder, etcpak, astc_encoder and archspec's CPU
  JSON, none of which PyInstaller finds itself. `fmod_toolkit` loads FMOD's DLL
  on import and FMOD must not ship, so `tools/release/stubs/fmod_toolkit` stands
  in for it; excluding the module instead silently broke all icons (caught only
  by the release test, since `save_icon` swallowed the exception). Now
  `extract_gamedata.py --self-test` imports all of them and the release script
  runs it; the reader fails with the first reason when a stage writes no icons.
- **Chrome 45 has no CDP `awaitPromise`**: `Runtime.evaluate` hands back a
  Promise as `{}`. Leave an answer on `window` and `wait_for` it.
- **A killed editor's `jcef_helper.exe` keeps the DevTools port bound**, and
  the next editor starts without DevTools — indistinguishable from one that
  never started. `config.stop_editors()` kills the helpers and waits for the
  port.
- **Tests never touch `%APPDATA%\Eternity Keeper`.** `TestHarness.setup` points
  `ek.data` at a throwaway `EK-` temp folder before `Environment.initialise()`;
  before that, any test reaching `Settings` created the user's real data folder
  (`TestIsolationTest` pins it).
- **CI** (`.github/workflows/ci.yml`, GitHub Actions on the fork, windows-latest,
  Temurin 8): Java tests, the page's node tests and a compile of the reader on
  every push. **Package** (`.github/workflows/package.yml`) builds the zip and
  the installer and runs `test-installer.ps1 -DefaultFolder` on the runner, a
  machine with none of the development setup: when the packaging or the
  game-data reader changes, when started by hand, and for a `v*` tag, whose zip
  and installer it attaches to a draft release for someone to publish. A tag
  push is never path-filtered. CONTRIBUTING.md's "Making a release" has the steps.
- **The release's page is written by `tools/release/release-notes.ps1`**: the
  download and install text in `tools/release/release-notes.md` (`{version}`
  stands for the version), then the version's section of `CHANGELOG.md`. It
  runs on every `Package` run, so a page that cannot be written shows before
  a tag does. For a tag it is strict: the tag has to be `v` and `pom.xml`'s
  version (otherwise the release would carry files named for another
  version), and the changelog section has to exist and be dated. It names the
  release "Eternity Keeper <version>" and marks a version with a hyphen as a
  pre-release.
- **The workflow drafts; a person publishes.** Publishing is a public act
  that Claude Code's permission check refuses to take on someone's behalf
  (2026-10-06: it refused both pushing the tag and a workflow that would
  publish by itself, though the owner had asked for the release to be
  published), and the browser pane had no GitHub sign-in to press Publish
  with. So the owner pushes the tag and presses **Publish release**; a session
  prepares everything up to that. **Run logs need
  a GitHub sign-in, annotations do not** — failing tests are turned into
  annotations (`.github/scripts/test_failures.py`), readable with
  `curl https://api.github.com/repos/<owner>/<repo>/check-runs/<job id>/annotations`
  (job ids from `.../actions/runs/<run id>/jobs`). `gh` is not installed here.
- **GitHub's Windows runners give Java an 8.3 short temp path**
  (`C:\Users\RUNNER~1\...`); anything that canonicalises paths answers with the
  long spelling. Compare canonical paths in tests. Reproduce locally with
  `-Djava.io.tmpdir=<a folder's ShortPath>` (Scripting.FileSystemObject gives it).
- The UI suites now live in `tools/ui-tests` with paths in `config.py`;
  `run_suites.py` gives the editor its own data folder
  (`target/ui-tests/data`) pointed at the test environment, because a fresh
  data folder would otherwise list the real saves folder, and the suites write
  saves. `gamedata_ui.py` with `EK_RELEASE=<extracted release>` tests a zip
  end to end.
