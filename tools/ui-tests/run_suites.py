# Runs UI suites against a freshly started editor, one boot per suite.
#
#   python run_suites.py                 the default set
#   python run_suites.py smoke.py mint.py
import json, os, subprocess, sys, time, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import JAVA, REPO

HERE = os.path.dirname(os.path.abspath(__file__))
SUITES = sys.argv[1:] or ["smoke.py", "functional.py", "panels.py", "audit.py", "audit2.py",
                          "paging.py", "format_ui.py", "consistency.py", "merge_bugs.py",
                          "writes.py", "mint.py", "import_chr.py", "layout_rules.py", "look_variants.py",
                          "catalog_offer.py", "backups_ui.py", "stronghold_people.py",
                          "quick_and_weapons.py", "responsive.py", "vendors.py",
                          "party_care.py", "find.py", "compare.py", "loadout.py",
                          "undo.py", "sale.py", "damaged.py", "checklist.py", "language.py",
                          "save_views.py"]


def settings():
    """The editor's own data folder for the suites, pointed at the test saves.

    Never the user's: with no settings the editor would list the real saves
    folder, and the suites write saves. The window size is pinned because the
    layout checks measure at it."""
    data = os.path.join(config.OUT, "data")
    os.makedirs(data, exist_ok=True)
    path = os.path.join(data, "settings.json")
    current = {}
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as handle:
            current = json.load(handle)
    current.update(config.SETTINGS)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(current, handle)
    return data


def restart():
    config.stop_editors()
    subprocess.Popen([JAVA, "-Dek.data=" + settings(),
                      "-Dek.ui=" + os.path.join(REPO, "src", "ui"),
                      "-Dek.debugPort=%d" % config.PORT,
                      "-Djava.library.path=" + os.path.join(REPO, "lib", "native", "win64"),
                      "-jar", os.path.join(REPO, "target", "eternity-keeper.jar")], cwd=REPO,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     creationflags=0x00000008)  # DETACHED_PROCESS
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            urllib.request.urlopen("http://127.0.0.1:%d/json/list" % config.PORT,
                                   timeout=3).read()
            time.sleep(6)
            return
        except Exception:
            time.sleep(2)
    raise RuntimeError("the editor did not come up")


env = dict(os.environ, PYTHONIOENCODING="utf-8")
totals = []
for suite in SUITES:
    restart()
    started = time.time()
    proc = subprocess.run([sys.executable, os.path.join(HERE, suite)], cwd=HERE, env=env,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=3600)
    out = proc.stdout.decode("utf-8", "replace")
    # A script outside this folder is named by its path; the log only wants the name.
    name = os.path.basename(suite).replace(".py", ".log")
    with open(os.path.join(config.OUT, "suite-" + name), "w",
              encoding="utf-8") as log:
        log.write(out)
    tail = [l for l in out.splitlines() if l.startswith("====") or l.startswith("FAIL")
            or "Traceback" in l or "Error" in l[:20]]
    totals.append((suite, proc.returncode, int(time.time() - started), tail[-12:]))
    print("%-16s exit %s  %4ds  %s" % (suite, proc.returncode, time.time() - started,
                                       " | ".join(tail[-12:])), flush=True)

print("\nDONE")
