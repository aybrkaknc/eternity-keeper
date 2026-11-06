# Revert and Apply, on the panels that had neither. The character sheet, the
# raw and globals tables and the console write into saveData as you type, so
# until now a number you had changed could only be undone by closing the save.
import json, sys, time
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"

VIEWS = [("Attributes", "ATTR", "#character", "charPanel"),
         ("Raw", "RAW", "#rawTable", "rawPanel"),
         ("Globals", "GLOBALS", "#globalsTable", "globalsPanel"),
         ("Inventory", "INVENTORY", "#inventoryView", "inv"),
         ("Abilities", "ABILITIES", "#abilitiesView", "abl"),
         ("Stronghold", "STRONGHOLD", "#strongholdView", "sh"),
         ("Grimoire", "GRIMOIRE", "#grimoireView", "grm"),
         ("Console", "CONSOLE", "#consoleView", "consolePanel")]

page = Page()
boot(page)
open_save(page, SAVE)


def view(name, pause=1.8):
    page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(pause)


# ---- every view has the pair, top right ------------------------------------
for label, key, selector, prefix in VIEWS:
    view(key)
    ok = page.eval("$('%s .panel-bar').length" % selector) > 0
    check("%s has a panel bar" % label, ok,
          page.eval("$('%s .panel-bar').length" % selector))
    if not ok:
        continue

    check("%s bar carries Revert and Apply" % label,
          page.eval("$('#%sRevert').length && $('#%sApply').length" % (prefix, prefix)) == 1,
          "%s / %s" % (page.eval("$('#%sRevert').length" % prefix),
                       page.eval("$('#%sApply').length" % prefix)))

    # Top right: the actions must sit in the upper part of the view and end
    # near its right edge.
    geometry = page.eval("""(function(){
      var view = $(%s)[0].getBoundingClientRect();
      var bar = $(%s + ' .panel-bar')[0].getBoundingClientRect();
      var acts = $(%s + ' .panel-bar .panel-bar-actions')[0];
      if (!acts) return 'no actions';
      var a = acts.getBoundingClientRect();
      // "First" as a reader sees it: nothing else in the view is drawn
      // above the bar. A pixel threshold would just measure the view's
      // own padding.
      var above = $(%s).find('*').filter(function(){
        if (!$(this).is(':visible')) return false;
        if ($(this).closest('.panel-bar').length) return false;
        return this.getBoundingClientRect().top < bar.top - 1;
      }).length;
      return JSON.stringify({
        nothingAbove: above,
        actionsRight: Math.round(view.right - a.right),
        actionsInBar: a.top >= bar.top - 2 && a.bottom <= bar.bottom + 8
      });
    })()""" % (json.dumps(selector), json.dumps(selector), json.dumps(selector),
                json.dumps(selector)))
    print("   %-11s %s" % (label, geometry))
    check("%s bar is the first thing in the view" % label,
          '"nothingAbove":0' in str(geometry), geometry)
    check("%s actions sit inside the bar" % label,
          '"actionsInBar":true' in str(geometry), geometry)
    check("%s actions reach the right edge" % label,
          0 <= json.loads(geometry)["actionsRight"] < 60, geometry)

# ---- the character bar actually reverts ------------------------------------
view("ATTR")
before = page.eval("""(function(){
  var input = $('#character .stats input[data-fullkey=BaseMight]');
  return input.length ? input.val() : null;
})()""")
print("   Might before: %s" % before)
check("the sheet has an editable Might", before is not None, before)

page.eval("""(function(){
  var input = $('#character .stats input[data-fullkey=BaseMight]');
  input.val(parseInt(input.val(), 10) + 3).trigger('change');
})()""")
time.sleep(0.8)
check("one edit reads as one unconfirmed change",
      page.eval("$('#charPanelNote').text()") == '1 unconfirmed change.',
      page.eval("$('#charPanelNote').text()"))
check("and Revert wakes up", not page.eval("$('#charPanelRevert').prop('disabled')"))
check("the raw table's bar counts the same character",
      page.eval("$('#rawPanelNote').text()") == '1 unconfirmed change.',
      page.eval("$('#rawPanelNote').text()"))

page.eval("$('#charPanelRevert').click()")
time.sleep(1.4)
after = page.eval("$('#character .stats input[data-fullkey=BaseMight]').val()")
check("Revert puts the number back", str(after) == str(before),
      "%s -> %s" % (before, after))
check("and the bar goes quiet",
      page.eval("$('#charPanelNote').text()") == 'No unconfirmed changes.',
      page.eval("$('#charPanelNote').text()"))
check("with both buttons off again",
      page.eval("$('#charPanelRevert').prop('disabled')")
      and page.eval("$('#charPanelApply').prop('disabled')"))

# ---- Apply confirms rather than undoing ------------------------------------
page.eval("""(function(){
  var input = $('#character .stats input[data-fullkey=BaseMight]');
  input.val(parseInt(input.val(), 10) + 5).trigger('change');
})()""")
time.sleep(0.8)
page.eval("$('#charPanelApply').click()")
time.sleep(1.0)
confirmed = page.eval("$('#character .stats input[data-fullkey=BaseMight]').val()")
check("Apply keeps the edit", int(confirmed) == int(before) + 5,
      "%s vs %s" % (confirmed, before))
check("Apply clears the count",
      page.eval("$('#charPanelNote').text()") == 'No unconfirmed changes.',
      page.eval("$('#charPanelNote').text()"))

page.eval("$('#charPanelRevert').click()")
time.sleep(1.2)
check("and Revert no longer reaches past it",
      int(page.eval("$('#character .stats input[data-fullkey=BaseMight]').val()"))
      == int(before) + 5,
      page.eval("$('#character .stats input[data-fullkey=BaseMight]').val()"))

# ---- globals have their own scope ------------------------------------------
view("GLOBALS", 1.6)
check("the globals bar starts quiet",
      page.eval("$('#globalsPanelNote').text()") == 'No unconfirmed changes.',
      page.eval("$('#globalsPanelNote').text()"))

changed = page.eval("""(function(){
  var cell = $('#globalsTable tbody td[contenteditable]').filter(function(){
    return /^\d+$/.test($(this).text().trim()); }).first();
  if (!cell.length) return 'no numeric global';
  var was = cell.text().trim();
  cell.text(String(parseInt(was, 10) + 1)).trigger('keyup');
  return JSON.stringify({key: String(cell.data('fullkey')).slice(0, 50), was: was});
})()""")
time.sleep(0.9)
print("   %s" % changed)
check("editing a global counts on the globals bar",
      page.eval("$('#globalsPanelNote').text()") == '1 unconfirmed change.',
      page.eval("$('#globalsPanelNote').text()"))
check("and not on the character bar",
      page.eval("$('#charPanelNote').text()") == 'No unconfirmed changes.',
      page.eval("$('#charPanelNote').text()"))

page.eval("$('#globalsPanelRevert').click()")
time.sleep(1.4)
check("reverting the globals clears them",
      page.eval("$('#globalsPanelNote').text()") == 'No unconfirmed changes.',
      page.eval("$('#globalsPanelNote').text()"))

to_list(page)
sys.exit(summary(page))
