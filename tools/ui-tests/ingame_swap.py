# A save for the in-game test of a main character taken into another
# playthrough: the Watcher of the prologue game (Cilant Lis) exported to a .chr
# and imported over the Watcher of the Caed Nua game, then Save. This is the
# "chapter select" a player asked about: a new character dropped into a save
# that is already at Caed Nua. Both native file dialogs are answered by
# filedialog.py.
#
# The written save is left in the test-env saves folder for copying into the
# game's folder; what to look for there goes to ingame-expected-swap.json.
# In the game: the load list files it under the newcomer's name, its
# autosaves keep the Caed Nua playthrough's ID, the Watcher
# in the party bar and on the character sheet is the newcomer (name,
# portrait, race, class, level, pack), the companions, the stash, the quest
# items, the money and the camping supplies are the Caed Nua party's, and
# Player.log has no "Exception" and no "already exists" after the
# load beyond the Caed Nua saves' own (docs/testing.md).
import json, os, re, sys, threading, time, uuid, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot
import filedialog

CHR = os.path.join(config.OUT, "ingame-swap.chr")
SOURCE = "cf88c16dc9564d77a49e89c8894d5c4e 7763974 CilantLs.savegame"
TARGET = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
D = "Eternity.SavedGame.state.saveData"
LOG = os.path.join(config.OUT, "data", "eternity.log")

before_files = set(os.listdir(SAVES))
if os.path.exists(CHR):
    os.remove(CHR)

page = Page()
boot(page)
E = page.eval
expect = {}

MAIN = "(%s.characters.filter(function(c){ return c.isMainCharacter; })[0])" % D


def main():
    return json.loads(E("""JSON.stringify((function(){
      var c = %s, s = c.stats, guid = c.GUID;
      var mine = %s.inventory.characters.filter(function(x){ return x.guid === guid; })[0] || {};
      function names(container) {
        return ((container || {}).items || []).map(function(i){
          return (i.displayName || i.prefab) + (i.stackSize > 1 ? ' x' + i.stackSize : ''); });
      }
      function v(name) { return s[name] ? s[name].value : null; }
      return {name: c.name, guid: guid, level: v('Level'), experience: v('Experience'),
              race: v('CharacterRace'), subrace: v('CharacterSubrace'), cls: v('CharacterClass'),
              might: v('BaseMight'), pack: names(mine.pack)};
    })())""" % (MAIN, D)))


# What is the party's rather than the Watcher's: the stash, the money and the
# camping supplies.
def partys():
    return json.loads(E("""JSON.stringify({currency: %s.currency, supplies: %s.campingSupplies,
      stash: ((%s.inventory.stash || {}).items || []).map(function(i){
        return (i.displayName || i.prefab) + (i.stackSize > 1 ? ' x' + i.stackSize : ''); })})""" % (D, D, D)))


def party():
    return E("%s.characters.filter(function(c){ return !c.isMainCharacter && !c.isDead; })"
             ".map(function(c){ return c.name; })" % D)


# ---- export the prologue game's Watcher ----------------------------------------
to_list(page)
open_save(page, SOURCE)
time.sleep(1.5)
expect["exported"] = main()
check("the source save has a main character", bool(expect["exported"]["guid"]), expect["exported"])
answering = threading.Thread(target=filedialog.answer, args=("Save Character", CHR))
answering.start()
E("""window.__export = null; window.exportCharacter({request: JSON.stringify({GUID: %s,
  absolutePath: Eternity.SavedGame.state.info.absolutePath, savedYet: false}),
  onSuccess: function(r){ window.__export = 'ok'; }, onFailure: function(c, m){ window.__export = 'failed: ' + m; }})"""
  % json.dumps(expect["exported"]["guid"]))
answering.join()
page.wait_for("window.__export !== null", 120, "the export")
check("the Watcher exported to a .chr", E("window.__export") == "ok" and os.path.exists(CHR), E("window.__export"))

# ---- over the other game's Watcher ---------------------------------------------
E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
open_save(page, TARGET)
time.sleep(1.5)
expect["target before"] = main()
expect["target party"] = party()
expect["target partys"] = partys()
check("the Caed Nua stash is not empty", len(expect["target partys"]["stash"]) > 0, expect["target partys"])

logged = os.path.getsize(LOG) if os.path.exists(LOG) else 0
E("window.__imported = false; Eternity.GenericError.render({})")
answering = threading.Thread(target=filedialog.answer, args=("Choose a character", CHR))
answering.start()
E("Eternity.ImportCharacter.importCharacter()")
answering.join()
page.wait_for("$('#importOverwriteDialog').is(':visible')", 300, "the overwrite question")
asked = E("$('#importOverwriteDialog .overwrite-message').text()")
check("the editor asks before replacing the main character", "main character" in asked, asked)
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
expect["given new IDs"] = int(renamed.group(1)) if renamed else 0
problems = E("JSON.stringify(((%s.validation || {}).problems || []))" % D)
check("the save validates clean: no ID twice", problems == "[]", problems)

after = main()
expect["target after"] = after
count = E("%s.characters.filter(function(c){ return c.isMainCharacter; }).length" % D)
check("still exactly one main character", count == 1, count)
same = {k: after[k] for k in ("name", "level", "experience", "race", "subrace", "cls", "might", "pack")}
want = {k: expect["exported"][k] for k in same}
check("the main character is the one exported", same == want, [same, want])
check("the companions are the Caed Nua party's", party() == expect["target party"],
      [party(), expect["target party"]])
check("the stash, the money and the camping supplies are the Caed Nua party's", partys() == expect["target partys"],
      [partys(), expect["target partys"]])

# ---- Save ----------------------------------------------------------------------
E("Eternity.Modifications.state.saveName = 'EK Watcher swap'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)
expect["file"] = written[0] if written else None


def summary_of(name):
    with zipfile.ZipFile(os.path.join(SAVES, name)) as archive:
        xml = archive.read("saveinfo.xml").decode("utf-8-sig")
        world = archive.read("MobileObjects.save")
    fields = dict(re.findall(r'<Simple name="(\w+)" type="[^"]*" value="([^"]*)"', xml))
    return fields, world


if written:
    # The game keeps the playthrough's ID on the main character (the Player
    # component's SessionID) and names autosaves by it; the load list files a
    # save under saveinfo.xml's PlayerName.
    source, _ = summary_of(SOURCE)
    target, _ = summary_of(TARGET)
    out, world = summary_of(written[0])
    check("the load list files it under the newcomer",
          out.get("PlayerName") == expect["exported"]["name"], out.get("PlayerName"))
    check("it stays in this playthrough",
          world.count(uuid.UUID(target["SessionID"]).bytes_le) == 1
          and world.count(uuid.UUID(source["SessionID"]).bytes_le) == 0,
          [target["SessionID"], source["SessionID"]])

with open(os.path.join(config.OUT, "ingame-expected-swap.json"), "w", encoding="utf-8") as f:
    json.dump(expect, f, indent=1, ensure_ascii=False)
print(json.dumps(expect, indent=1, ensure_ascii=False))
sys.exit(summary(page))
