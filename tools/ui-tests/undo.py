# Undo and Redo through the editor's own controls, on a real save.
#
# Typed values first: keystrokes into one field are one step, Ctrl+Z in a
# text box is the box's own, elsewhere it is the save's, and the old value
# comes back into the very slot the page holds. Then the steps an Apply makes,
# which the server undoes by putting files back: an inventory move undone and
# redone (every item checked where it ends up), a stronghold dismissal whose
# Prestige the server changed (which must come back through the merge, not stay
# as if the user had typed it), the achievements toggle. Nothing is undone
# while a tab holds changes it has not applied, and the inventory's Revert
# takes back the money a staged sale put in the purse. A Save starts the
# history again on both sides, and the written save holds what was on screen.
import atexit, io, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIT = io.open(os.path.join(HERE, "shaudit.js"), encoding="utf-8").read()
SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
OTHER = "cf88c16dc9564d77a49e89c8894d5c4e 7763974 CilantLs.savegame"
D = "Eternity.SavedGame.state.saveData"
PLAYER = "%s.characters.filter(function(c){ return c.isMainCharacter; })[0]" % D
MIGHT = "Number(%s.stats.BaseMight.value)" % PLAYER
MIGHT_BOX = "$('#character input').filter(function(){ return $(this).data('fullkey') === 'BaseMight'; }).first()"
MONEY = "Number(%s.currency)" % D
PRESTIGE = "Number(%s.globals.InGameGlobal.Stronghold.Prestige.value)" % D
before_files = set(os.listdir(SAVES))


# Whatever this run writes goes, even if it stops half-way: the other suites
# expect the test-env folder to hold its twelve saves.
@atexit.register
def remove_written():
    for name in set(os.listdir(SAVES)) - before_files:
        os.remove(os.path.join(SAVES, name))


page = Page()
boot(page)
E = page.eval

# Where every item is: the inventory, as a page of guid -> place.
WHERE = """(function(){ var out = {};
  %s.inventory.characters.forEach(function(c){
    [['pack', c.pack], ['quick', c.quickbar]].forEach(function(pair){
      (pair[1].items || []).forEach(function(i){ out[i.guid] = c.objectName + ' ' + pair[0] + '@' + i.uiSlot + ' x' + i.stackSize; }); });
    c.equipment.slots.forEach(function(s){ if (s.item) out[s.item.guid] = c.objectName + ' worn ' + s.slot; }); });
  (%s.inventory.stash.items || []).forEach(function(i){ out[i.guid] = 'stash x' + i.stackSize; });
  return out; })()""" % (D, D)


def steps():
    return E("Eternity.EditHistory.steps()")


def buttons():
    return E("""[$('#undoButton').is(':visible'), $('#undoButton').prop('disabled'), $('#undoButton').attr('title'),
      $('#redoButton').is(':visible'), $('#redoButton').prop('disabled'), $('#redoButton').attr('title')]""")


def toast():
    return E("$('#historyToast').hasClass('show') ? $('#historyToast').text() : ''")


def key(code, shift=False, target="document.body"):
    E("$(%s).trigger($.Event('keydown', {ctrlKey: true, shiftKey: %s, keyCode: %d, which: %d}))"
      % (target, "true" if shift else "false", code, code))
    time.sleep(0.6)


def view(name, wait=2.5):
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(wait)


def undo_applied(word="Undone"):
    E("$('#historyToast').removeClass('show').text('')")
    E("Eternity.EditHistory.%s()" % ("undo" if word == "Undone" else "redo"))
    page.wait_for("$('#historyToast').text().indexOf(%s) === 0" % json.dumps(word), 300, word.lower())
    time.sleep(1.5)


def type_might(*values):
    E("%s.focus()" % MIGHT_BOX)
    for value in values:
        E("%s.val(%s).trigger('keyup')" % (MIGHT_BOX, json.dumps(value)))
        time.sleep(0.1)
    E("%s.trigger('change')" % MIGHT_BOX)
    time.sleep(0.3)


# ---- a save opens with nothing to undo -------------------------------------------
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)
view("ATTR", 1.0)
might = E(MIGHT)
check("Undo and Redo sit beside Save, with nothing to do yet",
      buttons() == [True, True, "Nothing to undo", True, True, "Nothing to redo"], buttons())
row = E("""(function(){ return ['#undoButton', '#redoButton', '#saveButton'].map(function(s){
  var b = $(s)[0].getBoundingClientRect(); return [b.left, b.right, b.top, b.bottom]; }); })()""")
check("the three share a line, left to right, and stay inside the window",
      row[0][1] <= row[1][0] and row[1][1] <= row[2][0] and abs(row[0][3] - row[2][3]) <= 1
      and abs(row[1][3] - row[2][3]) <= 1 and row[2][1] <= E("window.innerWidth"), row)

# ---- typing ---------------------------------------------------------------------
type_might("2", "25")
check("two keystrokes are one step, named as the sheet names it",
      steps()["undo"] == [u"Phantom’s Might, %d to 25" % might], steps())
check("and Undo says what it would undo", buttons()[1:3] == [False, u"Undo Phantom’s Might, %d to 25 (Ctrl+Z)" % might],
      buttons())

key(90, target=MIGHT_BOX)
check("Ctrl+Z in the box is the box's own undo, not the save's", E(MIGHT) == 25 and len(steps()["undo"]) == 1)

E("document.activeElement.blur()")
key(90)
check("Ctrl+Z elsewhere undoes it", E(MIGHT) == might, E(MIGHT))
check("in the save and in the box", E("Number(%s.val())" % MIGHT_BOX) == might, E("%s.val()" % MIGHT_BOX))
check("and says so", toast() == u"Undone: Phantom’s Might, %d to 25" % might, toast())
check("Redo is offered", buttons()[4:6] == [False, u"Redo Phantom’s Might, %d to 25 (Ctrl+Y)" % might], buttons())

key(89)
check("Ctrl+Y redoes it", E(MIGHT) == 25 and toast().startswith("Redone"), (E(MIGHT), toast()))
key(90, shift=True)
key(90)
key(90, shift=True)
check("Ctrl+Shift+Z redoes too", E(MIGHT) == 25 and steps()["redo"] == [], (E(MIGHT), steps()))

# ---- other places that change values --------------------------------------------
money = E(MONEY)
view("CONSOLE", 1.0)
E("$('#consoleInput').val('GivePlayerMoney 100'); $('#consoleForm').submit()")
time.sleep(0.6)
check("a console command is a step named as it was typed",
      steps()["undo"][0] == "GivePlayerMoney 100" and E(MONEY) == money + 100, steps()["undo"][:2])
E("$('#consoleInput').val('help'); $('#consoleForm').submit()")
time.sleep(0.4)
check("a command that changes nothing is no step", steps()["undo"][0] == "GivePlayerMoney 100", steps()["undo"][:2])
key(90)
check("undone, the money is back", E(MONEY) == money, E(MONEY))
key(89)

view("ATTR", 1.0)
# A new face is its two paths and its picture, and the sheet draws the picture.
E("""(function(){ var p = %s; window.__face = p.portrait;
  var other = %s.characters.filter(function(c){ return c.portrait && c.portrait !== p.portrait; })[0];
  p.portraitPaths.m_textureLargePath.value = 'data/art/gui/portraits/companion/portrait_eder_lg.png';
  p.portraitPaths.m_textureSmallPath.value = 'data/art/gui/portraits/companion/portrait_eder_sm.png';
  p.portrait = other.portrait;
  Eternity.Modifications.transition({modifications: true}); })()""" % (PLAYER, D))
time.sleep(0.4)
check("a new face is one step", steps()["undo"][0] == u"Phantom’s portrait", steps()["undo"][:1])
type_might("3", "30")
E("$('#charPanelRevert').click()")
time.sleep(0.6)
check("the sheet's Revert is a step too", steps()["undo"][0] == u"Revert Phantom’s changes"
      and E(MIGHT) == might, (steps()["undo"][:2], E(MIGHT)))
check("and it puts back the picture with the paths", E("%s.portrait === window.__face" % PLAYER))
key(90)
check("and Undo brings back what it threw away", E(MIGHT) == 30
      and E("%s.portrait !== window.__face" % PLAYER), E(MIGHT))
key(90)
key(90)
check("down to the face it replaced", E("%s.portrait === window.__face" % PLAYER)
      and E(MIGHT) == 25, E(MIGHT))

# ---- an inventory Apply -----------------------------------------------------------
typed = E(MIGHT)
view("INVENTORY", 3.0)
before = E(WHERE)
E("$('#invPacks .inv-pack-row:eq(0) .inv-tile').eq(0).click()")
time.sleep(0.4)
empty = E("$('#invPacks .inv-pack-row:eq(1) .inv-tile').toArray().findIndex(function(t){ return $(t).hasClass('inv-tile-empty'); })")
E("$('#invPacks .inv-pack-row:eq(1) .inv-tile').eq(%d).click()" % empty)
time.sleep(0.6)
waiting = len(steps()["undo"])
E("$('#historyToast').removeClass('show').text('')")
key(90)
check("nothing is undone while a tab holds changes it has not applied",
      toast() == "Apply or revert the changes in the Inventory tab first." and len(steps()["undo"]) == waiting,
      toast())

E("$('#invApply').click()")
page.wait_for("!Eternity.InventoryEditor.state.working", 300, "the Apply")
time.sleep(1.5)
after = E(WHERE)
moved = [guid for guid in set(before) | set(after) if before.get(guid) != after.get(guid)]
label = steps()["undo"][0]
print("   %s: %s" % (label, [(before.get(g), after.get(g)) for g in moved]))
check("an Apply is one step, naming the item and where it went",
      label.startswith("Inventory: ") and " to " in label and len(moved) == 1, label)

undo_applied()
check("undone: every item is where it was before the Apply", E(WHERE) == before,
      [(g, E(WHERE).get(g), before.get(g)) for g in moved])
check("and the typed Might is untouched by it", E(MIGHT) == typed, (typed, E(MIGHT)))
undo_applied("Redone")
check("redone: every item is where the Apply put it", E(WHERE) == after)
check("the save validates clean", E("((%s.validation || {}).problems || []).length" % D) == 0)

# ---- a staged sale is a draft like any other: no step, no money, until Apply ---------
money = E(MONEY)
so_far = steps()["undo"]
E("$('#invSellMode').click()")
time.sleep(0.4)
E("$('#invSellJunk').click()")
time.sleep(0.4)
E("$('#invSellConfirm').click()")
time.sleep(0.8)
owed = E("Eternity.InventoryEditor.stagedSale().total")
check("a staged sale is owed its price but is no step yet", owed > 0 and E(MONEY) == money
      and steps()["undo"] == so_far, (owed, E(MONEY), steps()["undo"][:1]))
E("$('#historyToast').removeClass('show').text('')")
key(90)
check("and holds Undo back like any other draft",
      toast() == "Apply or revert the changes in the Inventory tab first." and steps()["undo"] == so_far, toast())
E("$('#invRevert').click()")
time.sleep(0.8)
check("Revert drops it with nothing to take back", E(MONEY) == money
      and E("Eternity.InventoryEditor.stagedSale().total") == 0 and steps()["undo"] == so_far,
      (money, E(MONEY)))

# ---- a stronghold Apply: the server changed Prestige ------------------------------
view("STRONGHOLD", 2.5)
prestige = E(PRESTIGE)
knight = [h for h in E("%s.stronghold.hirelings" % D) if h["key"] == "b_crucible_hireling"][0]
E("""$('#shHirelings .sh-person').filter(function(){
  return $(this).find('.sh-person-name').text() === 'Crucible Knight'; }).find('.sh-person-btn').click()""")
time.sleep(0.5)
E("$('#shApply').click()")
page.wait_for("/updated|failed|could not/i.test($('#shStatus').text())", 300, "the dismissal")
time.sleep(1.5)
check("the dismissal is one step and Prestige dropped",
      steps()["undo"][0] == "Stronghold: 1 change" and E(PRESTIGE) == prestige - knight["prestige"],
      (steps()["undo"][:1], E(PRESTIGE)))
undo_applied()
check("undone, the server's Prestige comes back too (not kept as if typed)", E(PRESTIGE) == prestige,
      (prestige, E(PRESTIGE)))
check("and the knight is hired again", len(E("%s.stronghold.hirelings" % D)) == 3)
undo_applied("Redone")
check("redone, he is gone again", E(PRESTIGE) == prestige - knight["prestige"]
      and len(E("%s.stronghold.hirelings" % D)) == 2)

# ---- the achievements toggle --------------------------------------------------------
view("CONSOLE", 1.0)
flag = E("!!%s.achievementsDisabled" % D)
E("$('#achievementsToggle').click()")
page.wait_for("!!%s.achievementsDisabled !== %s" % (D, "true" if flag else "false"), 120, "the toggle")
time.sleep(1.0)
check("the achievements toggle is a step", steps()["undo"][0].startswith("Turn achievements"), steps()["undo"][:1])
undo_applied()
check("and undoing it puts the flag back", E("!!%s.achievementsDisabled" % D) == flag)

# ---- how it looks -------------------------------------------------------------------------
E("$('#historyToast').text('Undone: a change with a long enough name to wrap').addClass('show')")
for light in (False, True):
    E("""(function(l){ var isLight = document.body.className.indexOf('theme-light') >= 0;
      if (isLight !== l) { $('#themeToggle').click(); } })(%s)""" % ("true" if light else "false"))
    time.sleep(0.5)
    for selector in ("#fabRow", "#historyToast"):
        issues = E(AUDIT + "(%s)" % json.dumps(selector)).get("issues", [])
        check("%s is clean in %s mode" % (selector, "light" if light else "dark"), not issues,
              json.dumps(issues[:2]))
E("(function(){ if (document.body.className.indexOf('theme-light') >= 0) $('#themeToggle').click(); })()")
E("$('#historyToast').removeClass('show')")

# ---- Save starts it again -------------------------------------------------------------------
E("Eternity.Modifications.state.saveName = 'EK undo'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
time.sleep(1.0)
check("after a Save there is nothing to undo", steps() == {"undo": [], "redo": []}
      and buttons()[1] and buttons()[4], (steps(), buttons()))
E("""window.__history = null; window.changeHistory({request: JSON.stringify({
  oldSave: Eternity.SavedGame.state.info.absolutePath, savedYet: true, action: 'undo', step: 1}),
  onSuccess: function(){ window.__history = 'undone'; }, onFailure: function(c, m){ window.__history = m; }})""")
page.wait_for("window.__history !== null", 120, "the server's answer")
check("and the server has forgotten its steps too", "no longer" in E("window.__history"), E("window.__history"))

written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)
on_screen = (E(MIGHT), E(MONEY), E(PRESTIGE))
if written:
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
    page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
    E("Eternity.SaveSearch.search()")
    page.wait_for("!Eternity.SaveSearch.state.searching && Eternity.SaveSearch.state.saves.some("
                  "function(s){ return s.absolutePath.indexOf(%s) >= 0; })" % json.dumps(written[0]),
                  300, "the written save in the list")
    open_save(page, written[0])
    time.sleep(1.5)
    check("the written save holds what was on screen", (E(MIGHT), E(MONEY), E(PRESTIGE)) == on_screen,
          ((E(MIGHT), E(MONEY), E(PRESTIGE)), on_screen))
    check("and opens with nothing to undo", steps() == {"undo": [], "redo": []})

E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
check("the list shows no Undo or Redo", not E("$('#undoButton').is(':visible') || $('#redoButton').is(':visible')"))
sys.exit(summary(page))
