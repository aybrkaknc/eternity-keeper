# Character import through the editor's own flows: a companion exported from
# one save and imported over the same companion in another save of the same
# playthrough, then into a save of a different playthrough as a newcomer.
#
# The first case is the one that went wrong. A .chr keeps the IDs its objects
# had where they came from, and an overwrite removes only what the replaced
# character owns, so anything the file brings that the other save keeps
# somewhere else -- an item handed on since, food moved to the stash -- came
# back under an ID something else still had. The game drops both. Now the
# file's copy gets an ID of its own, and the save must read back with no ID
# twice.
import json, os, re, sys, threading, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot
import filedialog

CHR = os.path.join(config.OUT, "import-test.chr")
# Aloth's pack in the Caed Nua save holds 14 things save 4 keeps elsewhere by
# then: food and potions that moved to the stash or to someone else.
SOURCE = os.environ.get("EK_IMPORT_FROM") or "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
TARGET = os.environ.get("EK_IMPORT_INTO") or "0945952c89c640e4a18cdb293e3946b4 4 SocewCieniu.savegame"
OTHER = "cf88c16dc9564d77a49e89c8894d5c4e 7763974 CilantLs.savegame"
WHO = os.environ.get("EK_IMPORT_WHO") or "Aloth"
D = "Eternity.SavedGame.state.saveData"

before_files = set(os.listdir(SAVES))
if os.path.exists(CHR):
    os.remove(CHR)

page = Page()
boot(page)
E = page.eval


def leave():
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
    page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")


def names():
    return E("%s.characters.map(function(c){ return c.name; })" % D)


def problems():
    return E("JSON.stringify(((%s.validation || {}).problems || []))" % D)


def error_shown():
    return E("$('#error').is(':visible') ? $('#error .alert').text() : ''")


LOG = os.path.join(config.OUT, "data", "eternity.log")


def log_size():
    return os.path.getsize(LOG) if os.path.exists(LOG) else 0


def logged_since(offset):
    with open(LOG, "rb") as handle:
        handle.seek(offset)
        return handle.read().decode("utf-8", "replace")


def import_chr(expect_overwrite):
    E("window.__imported = false; Eternity.GenericError.render({})")
    answering = threading.Thread(target=filedialog.answer, args=("Choose a character", CHR))
    answering.start()
    before = E("%s.characters.length" % D)
    E("Eternity.ImportCharacter.importCharacter()")
    answering.join()
    if expect_overwrite:
        page.wait_for("$('#importOverwriteDialog').is(':visible')", 300, "the overwrite question")
        check("importing %s again asks before overwriting" % WHO, True)
        E("Eternity.SavedGame.state.saveData.__before = true; Eternity.ImportCharacter.confirmOverwrite()")
        page.wait_for("!Eternity.ImportCharacter.state.importing"
                      " && !(Eternity.SavedGame.state.saveData || {}).__before", 300, "the overwrite")
    else:
        page.wait_for("%s.characters.length > %d || $('#error').is(':visible')" % (D, before),
                      300, "the import")
    time.sleep(1.5)


# ---- export -----------------------------------------------------------------
to_list(page)
open_save(page, SOURCE)
time.sleep(1.5)
guid = E("(%s.characters.filter(function(c){ return c.name === %s; })[0] || {}).GUID" % (D, json.dumps(WHO)))
check("%s is in the source save" % WHO, bool(guid), guid)
answering = threading.Thread(target=filedialog.answer, args=("Save Character", CHR))
answering.start()
E("""window.__export = null; window.exportCharacter({request: JSON.stringify({GUID: %s,
  absolutePath: Eternity.SavedGame.state.info.absolutePath, savedYet: false}),
  onSuccess: function(r){ window.__export = 'ok'; }, onFailure: function(c, m){ window.__export = 'failed: ' + m; }})"""
  % json.dumps(guid))
answering.join()
page.wait_for("window.__export !== null", 120, "the export")
check("%s exported to a .chr" % WHO, E("window.__export") == "ok" and os.path.exists(CHR),
      E("window.__export"))

# ---- over the same companion, in another save of the same playthrough --------
leave()
open_save(page, TARGET)
time.sleep(1.5)
party = names()
check("the target save already has %s" % WHO, WHO in party, party)
logged = log_size()
import_chr(expect_overwrite=True)
check("the overwrite was not refused", error_shown() == "", error_shown())
renamed = re.search(r"Gave (\d+) imported objects IDs of their own", logged_since(logged))
check("what the file brought that the save keeps elsewhere got IDs of its own",
      renamed is not None and int(renamed.group(1)) > 0,
      renamed.group(0) if renamed else "no such line in eternity.log")
check("%s is there once" % WHO, names().count(WHO) == 1, names())
check("the party is the same size", len(names()) == len(party), names())
check("the save validates clean: no ID twice", problems() == "[]", problems())

# ---- into another playthrough, as a newcomer --------------------------------
leave()
open_save(page, OTHER)
time.sleep(1.5)
party = names()
check("the other playthrough has no %s" % WHO, WHO not in party, party)
import_chr(expect_overwrite=False)
check("the import was not refused", error_shown() == "", error_shown())
check("%s joined" % WHO, WHO in names() and len(names()) == len(party) + 1, names())
check("that save validates clean too", problems() == "[]", problems())

leave()
check("nothing was written to the saves folder", set(os.listdir(SAVES)) == before_files,
      sorted(set(os.listdir(SAVES)) ^ before_files))
os.remove(CHR)
sys.exit(summary(page))
