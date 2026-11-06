# Where the UI suites find the editor, Java, the test saves and the game.
# Every value can be overridden with an environment variable; the defaults fit
# the layout the project is developed in, with the checkout beside a tools\
# folder holding the JDK and a test-env\ folder holding the test saves:
#
#     <root>\eternity-keeper\       the checkout
#     <root>\tools\jdk8u492-b09\    a Java 8 JDK
#     <root>\test-env\EK_TEST_ENV\  made by uk.me.mantas.eternity.TestEnvironment
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# The checkout: target\eternity-keeper.jar must be built (mvn install -Pwin64).
REPO = os.environ.get("EK_REPO") or os.path.normpath(os.path.join(HERE, "..", ".."))
ROOT = os.path.dirname(REPO)


def _java():
    bundled = os.path.join(ROOT, "tools", "jdk8u492-b09", "bin", "java.exe")
    return bundled if os.path.isfile(bundled) else "java"


# A Java 8 java.exe. Anything newer cannot load the embedded browser.
JAVA = os.environ.get("EK_JAVA") or _java()

# The test environment's saves folder. The suites open, edit and write saves
# here, and delete what they write; never point this at your real saves.
SAVES = os.environ.get("EK_TEST_SAVES") or os.path.join(ROOT, "test-env", "EK_TEST_ENV", "save")

# A real game install, for the suites that read game data (gamedata_ui full).
GAME = os.environ.get("EK_GAME") or r"D:\Steam\steamapps\common\Pillars of Eternity"

# Logs, screenshots and scratch folders the suites write.
OUT = os.environ.get("EK_UI_TEST_OUT") or os.path.join(REPO, "target", "ui-tests")

# The embedded browser's DevTools port the editor is started with.
PORT = int(os.environ.get("EK_DEBUG_PORT") or 13002)

# Extracted game data (item names and icons...). Several suites need it:
# tools/gamedata/extract_gamedata.py writes it.
GAMEDATA = os.environ.get("EK_GAMEDATA") or os.path.join(ROOT, "itemdata")

# What run_suites.py starts the editor with. The window size matters: the
# layout checks were written against a maximised 2560x1440 screen.
SETTINGS = {
    "savesLocation": SAVES,
    "gameLocation": os.path.join(os.path.dirname(SAVES), "poe"),
    "itemDataLocation": GAMEDATA,
    # Names in English whatever the game on this machine is set to: left to
    # follow the game, every suite that reads a name would depend on it.
    "language": "en",
    "width": 2576, "height": 1408, "x": -8, "y": -8,
}

os.makedirs(OUT, exist_ok=True)


def stop_editors():
    """Close every editor, and wait until the DevTools port is free again.

    The embedded browser's helper processes (jcef_helper.exe) outlive a killed
    java and keep the port bound, and a new editor that cannot bind it starts
    with no DevTools at all -- which looks exactly like one that never started.
    """
    import socket, subprocess, time
    for image in ("java.exe", "javaw.exe", "jcef_helper.exe"):
        subprocess.call(["taskkill", "/F", "/IM", image], stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL)
    deadline = time.time() + 30
    while time.time() < deadline:
        with socket.socket() as probe:
            if probe.connect_ex(("127.0.0.1", PORT)) != 0:
                return
        time.sleep(1)
    raise RuntimeError("port %d is still in use after closing every editor" % PORT)
