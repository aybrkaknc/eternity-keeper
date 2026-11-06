# A save for the in-game test of an import into the playthrough the character
# came from: Aloth exported from the Caed Nua save and imported over himself in
# a later save of the same game, then Save. That is the import that used to
# put things in the save twice -- what the file brings and the later save
# keeps somewhere else (food and potions moved to the stash or handed on, and
# anything an area file still holds) arrived under IDs something else had, and
# the game drops both. Both native file dialogs are answered by filedialog.py.
#
# The written save is left in the test-env saves folder for copying into the
# game's folder; what to look for there goes to ingame-expected-import.json.
# In the game: Aloth is as he was at Caed Nua, his pack and the stash both
# hold what is listed, and Player.log has no "Exception" and no "already
# exists" after the load.
import json, os, re, sys, threading, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot
import filedialog

CHR = os.path.join(config.OUT, "ingame-import.chr")
SOURCE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
TARGET = "0945952c89c640e4a18cdb293e3946b4 4 SocewCieniu.savegame"
WHO = "Aloth"
D = "Eternity.SavedGame.state.saveData"
LOG = os.path.join(config.OUT, "data", "eternity.log")

before_files = set(os.listdir(SAVES))
if os.path.exists(CHR):
    os.remove(CHR)

page = Page()
boot(page)
E = page.eval
expect = {}


def who():
    return "(%s.characters.filter(function(c){ return c.name === %s; })[0])" % (D, json.dumps(WHO))


def carried():
    """What the character carries, as the Inventory tab's payload has it."""
    return json.loads(E("""JSON.stringify((function(){
      var guid = %s.GUID, mine = %s.inventory.characters.filter(function(c){ return c.guid === guid; })[0];
      function names(container) {
        return ((container || {}).items || []).map(function(i){
          return (i.displayName || i.prefab) + (i.stackSize > 1 ? ' x' + i.stackSize : ''); });
      }
      return {pack: names(mine.pack), quickbar: names(mine.quickbar),
              stash: %s.inventory.stash.items.length};
    })())""" % (who(), D, D)))


def sheet():
    return json.loads(E("""JSON.stringify((function(){ var s = %s.stats; return {
      level: s.Level.value, experience: s.Experience.value}; })())""" % who()))


# ---- export -----------------------------------------------------------------
to_list(page)
open_save(page, SOURCE)
time.sleep(1.5)
guid = E("(%s || {}).GUID" % who())
check("%s is in the source save" % WHO, bool(guid), guid)
expect["as exported"] = {"sheet": sheet(), "carried": carried()}
answering = threading.Thread(target=filedialog.answer, args=("Save Character", CHR))
answering.start()
E("""window.__export = null; window.exportCharacter({request: JSON.stringify({GUID: %s,
  absolutePath: Eternity.SavedGame.state.info.absolutePath, savedYet: false}),
  onSuccess: function(r){ window.__export = 'ok'; }, onFailure: function(c, m){ window.__export = 'failed: ' + m; }})"""
  % json.dumps(guid))
answering.join()
page.wait_for("window.__export !== null", 120, "the export")
check("%s exported to a .chr" % WHO, E("window.__export") == "ok" and os.path.exists(CHR), E("window.__export"))

# ---- over himself, in a later save of the same playthrough --------------------
E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
open_save(page, TARGET)
time.sleep(1.5)
party = E("%s.characters.map(function(c){ return c.name; })" % D)
check("the target save already has %s" % WHO, WHO in party, party)
expect["in the later save, before"] = {"sheet": sheet(), "carried": carried()}

logged = os.path.getsize(LOG) if os.path.exists(LOG) else 0
E("window.__imported = false; Eternity.GenericError.render({})")
answering = threading.Thread(target=filedialog.answer, args=("Choose a character", CHR))
answering.start()
E("Eternity.ImportCharacter.importCharacter()")
answering.join()
page.wait_for("$('#importOverwriteDialog').is(':visible')", 300, "the overwrite question")
E("Eternity.SavedGame.state.saveData.__before = true; Eternity.ImportCharacter.confirmOverwrite()")
page.wait_for("!Eternity.ImportCharacter.state.importing"
              " && !(Eternity.SavedGame.state.saveData || {}).__before", 300, "the overwrite")
time.sleep(1.5)
shown = E("$('#error').is(':visible') ? $('#error .alert').text() : ''")
check("the overwrite was not refused", shown == "", shown)

with open(LOG, "rb") as handle:
    handle.seek(logged)
    said = handle.read().decode("utf-8", "replace")
renamed = re.search(r"Gave (\d+) imported objects IDs of their own", said)
check("what the file brought that the save keeps elsewhere got IDs of its own",
      renamed is not None and int(renamed.group(1)) > 0, renamed.group(0) if renamed else "no such line")
problems = E("JSON.stringify(((%s.validation || {}).problems || []))" % D)
check("the save validates clean: no ID twice", problems == "[]", problems)
expect["given new IDs"] = int(renamed.group(1)) if renamed else None
expect["in the later save, after"] = {"sheet": sheet(), "carried": carried()}
check("%s is as he was exported" % WHO,
      expect["in the later save, after"]["sheet"] == expect["as exported"]["sheet"]
      and expect["in the later save, after"]["carried"]["pack"] == expect["as exported"]["carried"]["pack"],
      expect["in the later save, after"])
check("the stash kept what it had",
      expect["in the later save, after"]["carried"]["stash"] == expect["in the later save, before"]["carried"]["stash"],
      [expect["in the later save, before"]["carried"]["stash"], expect["in the later save, after"]["carried"]["stash"]])

# ---- Save ---------------------------------------------------------------------
E("Eternity.Modifications.state.saveName = 'EK import over himself'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)
expect["file"] = written[0] if written else None

with open(os.path.join(config.OUT, "ingame-expected-import.json"), "w", encoding="utf-8") as f:
    json.dump(expect, f, indent=1, ensure_ascii=False)
print(json.dumps(expect, indent=1, ensure_ascii=False))
sys.exit(summary(page))
