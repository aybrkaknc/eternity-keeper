# The game-data banner and Settings row, on an editor that has never read the
# game's data: its own fresh data folder, started from a folder with no
# itemdata anywhere near it.
#
#   python gamedata_ui.py            banner, settings row, a refused run
#   python gamedata_ui.py full       ...then a real run against the Steam install
#
# With EK_RELEASE set to an extracted release folder, it tests that instead of
# the checkout: "Eternity Keeper.exe", its bundled Java and its frozen reader.
# Extract it somewhere with a space in the path; the data folder has one too.
import base64, json, os, shutil, subprocess, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from harness import check, summary
from cdp import Page
import config
from config import GAME, JAVA, REPO, SAVES

# Normalised: the launcher's own path is compared with what Windows reports,
# which never has a forward slash in it.
RELEASE = os.environ.get("EK_RELEASE")
RELEASE = os.path.normpath(RELEASE) if RELEASE else RELEASE
DATA = os.path.join(config.OUT, "fresh data" if RELEASE else "fresh-data")
CWD = os.path.join(config.OUT, "fresh-cwd")
NOT_A_GAME = os.path.join(config.OUT, "not-a-game")
os.makedirs(NOT_A_GAME, exist_ok=True)
FULL = "full" in sys.argv[1:]


def launch():
    config.stop_editors()
    for folder in (DATA, CWD):
        shutil.rmtree(folder, ignore_errors=True)
        os.makedirs(folder)
    # Only the saves folder, so the list shows the test saves; the game is
    # left for the editor to find, as it would on a first launch.
    with open(os.path.join(DATA, "settings.json"), "w") as handle:
        json.dump({"savesLocation": SAVES}, handle)

    if RELEASE:
        # The launcher reads extra JVM options from <exe name>.l4j.ini and
        # passes them on verbatim, so a path with a space must be quoted.
        with open(os.path.join(RELEASE, "Eternity Keeper.l4j.ini"), "w") as handle:
            handle.write('"-Dek.data=%s"\n-Dek.debugPort=%d\n' % (DATA, config.PORT))
        subprocess.Popen([os.path.join(RELEASE, "Eternity Keeper.exe")], cwd=CWD)
    else:
        subprocess.Popen([JAVA, "-Dek.data=" + DATA, "-Dek.debugPort=%d" % config.PORT,
                          "-Djava.library.path=" + os.path.join(REPO, "lib", "native", "win64"),
                          "-jar", os.path.join(REPO, "target", "eternity-keeper.jar")], cwd=CWD,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         creationflags=0x00000008)
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            urllib.request.urlopen("http://127.0.0.1:%d/json/list" % config.PORT, timeout=3).read()
            return
        except Exception:
            time.sleep(2)
    raise RuntimeError("the editor did not come up")


def shot(page, name):
    data = page.call("Page.captureScreenshot")["data"]
    with open(os.path.join(config.OUT, name), "wb") as handle:
        handle.write(base64.b64decode(data))


launch()
page = Page()
page.wait_for("typeof Eternity !== 'undefined' && Eternity.GameData && Eternity.GameData.state.loaded",
              120, "the game-data status")
page.wait_for("$('#saveBlocks .save-info').length > 0", 120, "the save list")

state = page.eval("Eternity.GameData.state")
check("an editor with no data knows it has none", state["items"] == 0, state)
check("the game-data reader is found", state["available"], state)
check("the game was found for it", state["game"].lower() == GAME.lower(), state["game"])

if RELEASE:
    running = subprocess.run(["powershell", "-NoProfile", "-Command",
        "Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'java*.exe' } | "
        "ForEach-Object { $_.ExecutablePath }"], capture_output=True, text=True).stdout
    # One path per line; the paths themselves have spaces in them.
    running = [line.strip() for line in running.splitlines() if line.strip()]
    bundled = os.path.join(RELEASE, "jre", "bin").lower()
    check("the editor runs on the Java it ships with", running and all(
        path.lower().startswith(bundled) for path in running), running)
    check("its settings are in the data folder asked for", os.path.isfile(
        os.path.join(DATA, "settings.json")), DATA)
    files = page.eval("Eternity.GameData.state.logFile")
    check("Settings will name the log in that folder", files.lower() == os.path.join(
        DATA, "eternity.log").lower(), files)

banner = page.eval("""(function(){ var b = $('#gameDataBanner');
  return {visible: b.is(':visible'), text: b.text().replace(/\\s+/g, ' ').trim(),
          button: $('#gameDataStart').text(), buttonVisible: $('#gameDataStart').is(':visible'),
          dismiss: $('#gameDataDismiss').is(':visible'),
          top: b.offset().top, width: b.outerWidth()}; })()""")
check("the banner is offered on the save list", banner["visible"], banner)
check("it names the folder it will read", GAME.lower() in banner["text"].lower(), banner["text"])
check("its button reads 'Read game data'", banner["button"] == "Read game data"
      and banner["buttonVisible"], banner)
check("it cannot be dismissed before anything happened", not banner["dismiss"], banner)
check("it sits above the save tiles", banner["top"] < page.eval(
    "$('#saveBlocks .save-info').first().offset().top"), banner)
shot(page, "gamedata-banner.png")

page.eval("$('#settingsDialog').modal('show')")
time.sleep(1)
row = page.eval("""({text: $('#settingsGameData').text(),
  button: $('#settingsGameDataStart').text(),
  visible: $('#settingsGameDataStart').is(':visible')})""")
check("Settings says nothing has been read", row["text"].startswith("Not read yet"), row)
check("Settings offers the same action", row["button"] == "Read game data" and row["visible"], row)
shot(page, "gamedata-settings.png")
page.eval("$('#settingsDialog').modal('hide')")
time.sleep(1)

# A folder that is not an install: the extractor's own reason should reach the user.
page.eval("""window.saveSettings({request: JSON.stringify({gameLocation: %s}),
  onSuccess: function(){ Eternity.Settings.transition({gameLocation: %s});
                         Eternity.GameData.refresh(); }, onFailure: function(){}})"""
          % (json.dumps(NOT_A_GAME), json.dumps(NOT_A_GAME)))
page.wait_for("Eternity.GameData.state.game === %s" % json.dumps(NOT_A_GAME), 20, "the new folder")
page.eval("$('#gameDataStart').click()")
page.wait_for("!!Eternity.GameData.state.error", 90, "the refusal")
failed = page.eval("""({error: Eternity.GameData.state.error,
  cls: $('#gameDataBanner').hasClass('gd-failed'),
  text: $('#gameDataMessage').text(), button: $('#gameDataStart').text(),
  dismiss: $('#gameDataDismiss').is(':visible'), running: Eternity.GameData.state.running})""")
check("a folder that is not the game is refused", "not a Pillars of Eternity install"
      in failed["error"] or "has no asset bundles" in failed["error"], failed)
check("the banner shows the refusal", failed["cls"] and "failed" in failed["text"], failed)
check("and offers to try again", failed["button"] == "Try again", failed)
check("and can be dismissed", failed["dismiss"], failed)
shot(page, "gamedata-failed.png")

if FULL:
    page.eval("""window.saveSettings({request: JSON.stringify({gameLocation: %s}),
      onSuccess: function(){ Eternity.Settings.transition({gameLocation: %s});
                             Eternity.GameData.refresh(); }, onFailure: function(){}})"""
              % (json.dumps(GAME), json.dumps(GAME)))
    page.wait_for("Eternity.GameData.state.game === %s" % json.dumps(GAME), 20, "the Steam folder")
    started = time.time()
    page.eval("$('#gameDataStart').click()")
    page.wait_for("Eternity.GameData.state.running && Eternity.GameData.state.percent > 0",
                  120, "progress")
    running = page.eval("""({bar: $('#gameDataProgress').is(':visible'),
      width: $('#gameDataProgress .gd-bar')[0].style.width,
      button: $('#gameDataStart').text(), text: $('#gameDataMessage').text()})""")
    check("a run shows a progress bar", running["bar"] and running["width"] != "0%", running)
    check("and can be stopped", running["button"] == "Stop", running)
    shot(page, "gamedata-running.png")

    page.wait_for("!Eternity.GameData.state.running", 3600, "the run to finish")
    done = page.eval("""({state: Eternity.GameData.state,
      cls: $('#gameDataBanner').hasClass('gd-done'), text: $('#gameDataMessage').text()})""")
    check("the run finished without an error", not done["state"]["error"], done["state"])
    check("the catalog is reloaded without a restart", done["state"]["items"] > 2000,
          done["state"]["items"])
    check("the banner says so", done["cls"] and "Game data read" in done["text"], done["text"])
    print("  run took %ds" % (time.time() - started))
    shot(page, "gamedata-done.png")

    page.eval("$('#settingsDialog').modal('show')")
    time.sleep(1)
    row = page.eval("$('#settingsGameData').text()")
    check("Settings says when and from where", "read from" in row and GAME.lower() in row.lower(), row)
    page.eval("$('#settingsDialog').modal('hide')")

    # The browsers use the new data straight away. (Chrome 45 predates
    # awaitPromise, so the answer is left on window and polled for.)
    page.eval("""window.__browsed = null; window.browseItems({
      request: JSON.stringify({search: 'sword', filter: 0, offset: 0, limit: 10}),
      onSuccess: function(r){ window.__browsed = JSON.parse(r); },
      onFailure: function(c, m){ window.__browsed = {failed: m}; }}); true""")
    page.wait_for("window.__browsed !== null", 30, "the item browser")
    items = page.eval("""(window.__browsed.items || []).map(function(i){
      return {name: i.displayName, key: i.key, icon: (i.icon || '').length}; })""")
    check("the item browser shows real names", items and all(
        "_" not in item["name"] for item in items), items[:3])
    # A reader that wrote every catalog and no icon at all once passed every
    # check above.
    check("and real icons", items and all(item["icon"] > 0 for item in items), items[:3])

    data = page.eval("Eternity.GameData.state.data")
    check("every item icon was written", data and data.get("icons") == 1387, data)
    check("and every stronghold icon", data and data.get("strongholdIcons") == 25, data)

result = summary(page)
# Nothing restarts this editor, so it would stay open, and an open editor
# keeps the launcher's mutex: the next setup then stops and asks for it to be
# closed (it did, in the middle of the installer's own test).
config.stop_editors()
sys.exit(result)
