# A sale's money and the items it sold reach the save together, or not at all.
#
# The Inventory tab stages a sale like every other change there: the items
# leave the save on Apply, and the money goes into the purse when that Apply
# succeeds, as one step that Undo takes back whole. Until then nothing has
# moved: Revert has nothing to refund, a Save in between writes the items and
# no money for them, and a resurrection, an import or a party change -- each of
# which reopens the save under the draft -- waits for the draft to be applied
# or reverted, as Undo does.
#
# It used to pay at Confirm. The purse is what Save writes, so a Save between
# Confirm and Apply wrote the money beside every item it was paid for (measured:
# 210,763 cp and the sold ring, both in the written save), and each way of
# dropping the draft had to remember to take the money back.
import atexit, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import SAVES
from harness import Page, check, summary, open_save, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
D = "Eternity.SavedGame.state.saveData"
MONEY = "Number(%s.currency)" % D
MIGHT = "%s.characters.filter(function(c){ return c.isMainCharacter; })[0].stats.BaseMight" % D
WAIT = "Apply or revert the changes in the Inventory tab first."
before_files = set(os.listdir(SAVES))

# Every item the save holds, wherever it is, and every item the tab's draft does.
IN_SAVE = """(function(){ var out = []; var inv = %s.inventory;
  (inv.stash.items || []).forEach(function(i){ out.push(i.guid); });
  inv.characters.forEach(function(c){
    (c.pack.items || []).concat(c.quickbar.items || []).forEach(function(i){ out.push(i.guid); });
    (c.equipment.slots || []).forEach(function(s){ if (s.item) out.push(s.item.guid); });
    (c.equipment.weaponSets || []).forEach(function(s){
      ['primary', 'secondary'].forEach(function(h){ if (s[h]) out.push(s[h].guid); }); }); });
  return out; })()""" % D
IN_DRAFT = "(Eternity.InventoryEditor.everything() || []).map(function(e){ return e.item.guid; })"
OWED = "Eternity.InventoryEditor.stagedSale()"
STAGED = "Eternity.InventoryEditor.unapplied()"
STATUS = "$('#invStatus').is(':visible') ? $('#invStatus').text() : ''"


# Whatever this run writes goes, even if it stops half-way: the other suites
# expect the test-env folder to hold its twelve saves.
@atexit.register
def remove_written():
    for name in set(os.listdir(SAVES)) - before_files:
        os.remove(os.path.join(SAVES, name))


page = Page()
boot(page)
E = page.eval


def written():
    return sorted(set(os.listdir(SAVES)) - before_files)


def steps():
    return E("Eternity.EditHistory.steps()")


def toast():
    return E("$('#historyToast').hasClass('show') ? $('#historyToast').text() : ''")


def quiet():
    E("$('#historyToast').removeClass('show').text('')")


def leave():
    # The way the menu leaves a save, which also forgets the name and the
    # "saved yet" of the last one.
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
    page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")


def fresh(name=SAVE):
    leave()
    open_save(page, name)
    time.sleep(1.5)
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.INVENTORY)")
    time.sleep(3.0)


def sell():
    """Sells what the tab calls junk; returns (the items sold, what is owed for them)."""
    E("$('#invSellMode').click()")
    time.sleep(0.6)
    E("$('#invSellJunk').click()")
    time.sleep(0.6)
    said = E("$('#invSellSummary').text()")
    E("$('#invSellConfirm').click()")
    time.sleep(0.8)
    sold = sorted(set(E(IN_SAVE)) - set(E(IN_DRAFT)))
    print("   sold: %s (%d out of the draft)" % (said, len(sold)))
    return sold, E(OWED)["total"]


def move():
    """Stages one item from the first pack into the second."""
    E("$('#invPacks .inv-pack-row:eq(0) .inv-tile:not(.inv-tile-empty)').first().click()")
    time.sleep(0.4)
    E("$('#invPacks .inv-pack-row:eq(1) .inv-tile.inv-tile-empty').first().click()")
    time.sleep(0.6)


def apply():
    E("$('#invApply').click()")
    page.wait_for("!Eternity.InventoryEditor.state.working", 300, "the inventory Apply")
    time.sleep(2.0)


def history(word):
    quiet()
    E("Eternity.EditHistory.%s()" % ("undo" if word == "Undone" else "redo"))
    page.wait_for("$('#historyToast').text().indexOf(%s) === 0" % json.dumps(word), 300, word.lower())
    time.sleep(1.5)


def save_as(name):
    earlier = written()
    quiet()
    E("Eternity.Modifications.state.saveName = %s; $('#saveButton').click()" % json.dumps(name))
    time.sleep(0.4)
    refused = toast()
    page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
    time.sleep(1.0)
    return sorted(set(written()) - set(earlier)), refused


def reopen(name):
    leave()
    E("Eternity.SaveSearch.search()")
    page.wait_for("!Eternity.SaveSearch.state.searching && Eternity.SaveSearch.state.saves.some("
                  "function(s){ return s.absolutePath.indexOf(%s) >= 0; })" % json.dumps(name),
                  300, "the written save in the list")
    open_save(page, name)
    time.sleep(1.5)


def problems():
    return E("JSON.stringify((%s.validation || {}).problems || [])" % D)


# ---- staged, a sale has moved nothing ---------------------------------------------
fresh()
money = E(MONEY)
sold, owed = sell()
check("a sale takes its items out of the draft", len(sold) > 0 and E(STAGED), len(sold))
check("and is owed their price, which is not in the purse yet", owed > 0 and E(MONEY) == money,
      (owed, money, E(MONEY)))
check("nothing is in the history, and Save is not armed by it",
      steps() == {"undo": [], "redo": []} and E("$('#saveButton').prop('disabled')"), steps())
words = E(OWED)["words"]
check("the status line says what Apply will do",
      E(STATUS).startswith("Sold %s. Apply changes takes" % words) and "adds the money" in E(STATUS), E(STATUS))
E("$('#invSellMode').click()")
time.sleep(0.4)
E("$('#invSellMode').click()")
time.sleep(0.4)
check("and goes on saying it while the sale is staged", E(STATUS).startswith("Sold %s." % words), E(STATUS))

E("$('#invRevert').click()")
time.sleep(0.8)
check("Revert brings the items back and owes nothing",
      set(sold) <= set(E(IN_DRAFT)) and E(OWED)["total"] == 0 and not E(STAGED) and E(MONEY) == money,
      (E(OWED), E(MONEY)))
check("with the history still empty", steps() == {"undo": [], "redo": []}, steps())

# ---- a Save in between writes neither half -----------------------------------------
sold, owed = sell()
E("(function(){ %s.value = 30; Eternity.Modifications.transition({modifications: true}); })()" % MIGHT)
saved, refused = save_as("EK sale")
check("a Save with a sale staged goes ahead", len(saved) == 1 and refused == "", (saved, refused))
check("and leaves the sale staged, still owed", E(STAGED) and E(OWED)["total"] == owed and E(MONEY) == money,
      (E(OWED), E(MONEY)))

if saved:
    reopen(saved[0])
    check("written: the edit that was saved", int(E("%s.value" % MIGHT)) == 30, E("%s.value" % MIGHT))
    check("written: no money for the sale", E(MONEY) == money, (E(MONEY), money))
    check("written: and every item it would have sold", set(sold) <= set(E(IN_SAVE)),
          len(set(sold) - set(E(IN_SAVE))))

# ---- applied, the sale is one step ---------------------------------------------------
fresh()
money = E(MONEY)
sold, owed = sell()
words = E(OWED)["words"]
busy = E("""(function(){ $('#invApply').click(); var view = $('#inventoryView');
  return [view.hasClass('view-working'), window.getComputedStyle(view[0]).pointerEvents]; })()""")
check("while the Apply is on its way the tab takes no clicks", busy == [True, "none"], busy)
page.wait_for("!Eternity.InventoryEditor.state.working", 300, "the inventory Apply")
time.sleep(2.0)
check("and takes them again afterwards", E("""(function(){ var view = $('#inventoryView');
  return [view.hasClass('view-working'), window.getComputedStyle(view[0]).pointerEvents]; })()""")
      == [False, "auto"])
check("applied: the items are out of the save and their price is in the purse",
      not (set(sold) & set(E(IN_SAVE))) and E(MONEY) == money + owed, (E(MONEY), money + owed))
check("as one step, named as the sale it was", steps()["undo"] == ["Sell " + words], steps())
check("and the tab says so", E(STATUS) == "Inventory updated: sold %s. Save to write it to a file." % words,
      E(STATUS))
check("nothing is owed any more, and Save is armed",
      E(OWED)["total"] == 0 and not E(STAGED) and not E("$('#saveButton').prop('disabled')"))

history("Undone")
check("Undo brings the items back and takes the money out again",
      set(sold) <= set(E(IN_SAVE)) and E(MONEY) == money, (E(MONEY), money))
history("Redone")
check("Redo sells them again", not (set(sold) & set(E(IN_SAVE))) and E(MONEY) == money + owed,
      (E(MONEY), money + owed))

# The purse moves by the sale's amount, whatever else has happened to it.
E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.CONSOLE)")
time.sleep(1.0)
E("$('#consoleInput').val('GivePlayerMoney 100'); $('#consoleForm').submit()")
time.sleep(0.6)
E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.INVENTORY)")
time.sleep(2.5)
check("money given afterwards is a step on top of the sale",
      steps()["undo"] == ["GivePlayerMoney 100", "Sell " + words] and E(MONEY) == money + owed + 100, steps())
quiet()
E("Eternity.EditHistory.undo()")
time.sleep(0.8)
history("Undone")
check("both undone: the purse and the items are as the save opened",
      E(MONEY) == money and set(sold) <= set(E(IN_SAVE)), (E(MONEY), money))
history("Redone")
quiet()
E("Eternity.EditHistory.redo()")
time.sleep(0.8)
check("both redone", E(MONEY) == money + owed + 100 and not (set(sold) & set(E(IN_SAVE))),
      (E(MONEY), money + owed + 100))

saved, refused = save_as("EK sale")
check("Save writes it", len(saved) == 1, saved)
if saved:
    reopen(saved[0])
    check("written: the sale's money and what was given", E(MONEY) == money + owed + 100,
          (E(MONEY), money + owed + 100))
    left = set(sold) & set(E(IN_SAVE))
    check("written: none of the items it sold", not left, len(left))
    check("written: the validator finds nothing wrong", problems() == "[]", problems())

# ---- a sale among other changes -------------------------------------------------------
fresh()
money = E(MONEY)
before = sorted(E(IN_SAVE))
move()
sold, owed = sell()
words = E(OWED)["words"]
apply()
label = steps()["undo"][0]
check("a sale applied with a move is still one step, saying both",
      label.startswith("Inventory: ") and label.endswith(", selling " + words) and len(steps()["undo"]) == 1,
      label)
check("and pays", E(MONEY) == money + owed, (E(MONEY), money + owed))
history("Undone")
check("undone, everything is back and the money is out", sorted(E(IN_SAVE)) == before and E(MONEY) == money,
      (E(MONEY), money))

# ---- what was only added here has no price ---------------------------------------------
# The save never held it, so there is nothing to be paid for: "selling" it
# takes it back out of the draft, and money owed always comes with something
# for Apply to take out of the save.
fresh()
money = E(MONEY)
E("""$('#invBrowseTarget').val($('#invBrowseTarget option').filter(function(){
  return $(this).text() === 'Stash'; }).val()).trigger('change')""")
E("$('#invBrowseSearch').val('hatchet').trigger('keyup')")
page.wait_for("$('#invBrowseGrid').children().length > 0", 90, "catalog items")
time.sleep(1.0)
E("$('#invBrowseGrid').children().first().click()")
time.sleep(0.8)
added = E("(Eternity.InventoryEditor.everything() || []).filter(function(e){ return e.item.isNew; })"
          ".map(function(e){ return e.item.guid; })")
check("an item from the catalog is staged", len(added) == 1 and E(STAGED), added)
E("$('#invSellMode').click()")
time.sleep(0.6)
E("$('#inventoryView .inv-tile[data-guid=%s]').click()" % json.dumps(added[0] if added else ""))
time.sleep(0.6)
E("$('#invSellConfirm').click()")
time.sleep(0.8)
check("sold again, it is simply out of the draft: nothing owed, nothing staged",
      E(OWED)["total"] == 0 and E(OWED)["units"] == 0 and not E(STAGED) and E(MONEY) == money, E(OWED))
check("and the tab says why no money comes of it", "no" in E(STATUS).lower() and "price" in E(STATUS), E(STATUS))

# ---- what reopens the save waits for the draft --------------------------------------------
fresh()
money = E(MONEY)
dead = E("(function(){ var c = %s.characters.filter(function(c){ return c.resurrectable; })[0];"
         " return c ? c.name + '|' + c.GUID : null; })()" % D)
check("the save has a companion to resurrect", bool(dead), dead)
sold, owed = sell()
party = len(E("%s.characters" % D))

if dead:
    name, guid = dead.split("|")
    quiet()
    E("Eternity.SavedGame.resurrect(%s)" % json.dumps(guid))
    time.sleep(1.0)
    check("a resurrection waits for the draft, and says why", toast() == WAIT, toast())
    check("so nobody is resurrected and the draft is as it was",
          E("%s.characters.some(function(c){ return c.GUID === %s; })" % (D, json.dumps(guid)))
          and E(OWED)["total"] == owed and E(STAGED))

quiet()
E("Eternity.PartyManagement.open()")
time.sleep(0.8)
check("a party change waits too", toast() == WAIT and not E("$('#partyManagementDialog').is(':visible')"),
      toast())
quiet()
E("Eternity.ImportCharacter.importCharacter()")
time.sleep(0.8)
check("and an import", toast() == WAIT and not E("Eternity.ImportCharacter.state.importing"), toast())

# If something reopens the save under a draft all the same, the tab says what
# became of it. This goes past the check above, the way a caller that forgot
# it would.
if dead:
    E("""window.__raised = null; window.resurrectCharacter({request: JSON.stringify({
      oldSave: Eternity.SavedGame.state.info.absolutePath, savedYet: false, companion: %s}),
      onSuccess: function(reply){
        Eternity.SavedGame.render({saveData: Eternity.SavedGame.adopt(JSON.parse(reply), 'Resurrect'),
          info: Eternity.SavedGame.state.info, view: Eternity.SavedGame.views.INVENTORY});
        window.__raised = 'ok'; },
      onFailure: function(c, m){ window.__raised = m; }})""" % json.dumps(guid.replace("dead:", "")))
    page.wait_for("window.__raised !== null", 400, "the resurrection")
    time.sleep(2.0)
    check("the resurrection went through", E("window.__raised") == "ok", E("window.__raised"))
    check("a draft rebuilt under the user is said to be dropped",
          E(STATUS) == "The party changed, so the changes staged here were dropped.", E(STATUS))
    check("its items are back in it, nothing is owed and the purse never moved",
          set(sold) <= set(E(IN_DRAFT)) and E(OWED)["total"] == 0 and not E(STAGED) and E(MONEY) == money,
          (E(OWED), E(MONEY), money))

leave()
check("nothing else was written to the saves folder", len(written()) <= 2, written())
sys.exit(summary(page))
