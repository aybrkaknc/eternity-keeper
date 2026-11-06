# A save for the in-game test of Trial of Iron: the Difficulty dialog's Trial of
# Iron switch flipped, through the dialog's own controls, then Save. By default
# the prologue test save, which IS a Trial of Iron game, so the switch goes
# off; EK_IRON_SAVE names another save to flip the other way, for example
#
#   EK_IRON_SAVE="0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
#
# The written save is left in the test-env saves folder for copying into the
# game's folder; what to look for goes to ingame-expected-iron-<on|off>.json.
#
# In the game: with Trial of Iron on, the game's menu offers no manual save and
# the game rewrites the save when it quits -- it deletes the file it loaded
# and writes a new one under a new game ID -- so clean-up has to look for that
# file as well as the one that was copied in. With it off, the menu saves and
# loads as usual and the file is still there afterwards.
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

SAVE = os.environ.get("EK_IRON_SAVE") or "cf88c16dc9564d77a49e89c8894d5c4e 7763974 CilantLs.savegame"
D = "Eternity.SavedGame.state.saveData"
IRON = D + ".globals.Global.GameState.TrialOfIron.value"
before_files = set(os.listdir(SAVES))

page = Page()
boot(page)
E = page.eval
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)


def iron():
    return str(E("String(%s)" % IRON)).lower() == "true"


def lit():
    return "Trial of Iron" in E("""$('#modesRow .console-mode.active').map(function(){
      return $(this).find('.console-option-label').text(); }).get()""")


before = iron()
E("Eternity.DifficultyEditor.open()")
page.wait_for("$('#difficultyDialog').is(':visible') && $('#modesRow .console-mode').length === 3", 30,
              "the Difficulty dialog")
time.sleep(0.6)
check("the dialog shows Trial of Iron as the save has it (%s)" % ("on" if before else "off"), lit() == before, lit())
E("""$('#modesRow .console-mode').filter(function(){
  return $(this).find('.console-option-label').text() === 'Trial of Iron'; }).click()""")
time.sleep(0.4)
check("the switch flipped in the dialog", lit() == (not before), lit())
E("$('#difficultyEditorDone').click()")
time.sleep(0.8)
after = iron()
check("the save's data follows the switch", after == (not before), after)

word = "on" if after else "off"
name = "EK Trial of Iron " + word
E("Eternity.Modifications.state.saveName = %s; Eternity.Modifications.save()" % json.dumps(name))
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)

expect = {"file": written[0] if written else None, "from": SAVE, "TrialOfIron": [before, after],
          "name in the load list": name}
with open(os.path.join(config.OUT, "ingame-expected-iron-%s.json" % word), "w", encoding="utf-8") as f:
    json.dump(expect, f, indent=1, ensure_ascii=False)
print(json.dumps(expect, indent=1, ensure_ascii=False))
sys.exit(summary(page))
