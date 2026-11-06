# Does an Apply in one panel throw away unsaved edits made in another?
# Invariant 12 says it must not. Each handler merges the server's reply
# differently, so each is tried against the edit it looks most likely to drop.
import json, sys, time
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"

page = Page()
boot(page)


def fresh():
    to_list(page)
    open_save(page, SAVE)
    time.sleep(1.2)


def wait_idle(expr, label, seconds=180):
    page.wait_for(expr, seconds, label)
    time.sleep(1.0)


def might():
    return page.eval("""(function(){
      var d = Eternity.SavedGame.state.saveData;
      var p = d.characters.filter(function(c){ return c.isMainCharacter; })[0];
      return String(p.stats.BaseMight.value);
    })()""")


def bump_might():
    page.eval("""(function(){
      var d = Eternity.SavedGame.state.saveData;
      var p = d.characters.filter(function(c){ return c.isMainCharacter; })[0];
      p.stats.BaseMight.value = parseInt(p.stats.BaseMight.value, 10) + 7;
      Eternity.Modifications.transition({modifications: true});
    })()""")


# ---- A. the party dialog ----------------------------------------------------
fresh()
before = might()
bump_might()
edited = might()
print("   Might %s -> %s (unsaved)" % (before, edited))

toggled = page.eval("""(function(){
  var d = Eternity.SavedGame.state.saveData;
  var companion = d.characters.filter(function(c){
    return !c.isMainCharacter && !c.isDead && c.inParty; })[0];
  if (!companion) return 'no companion';
  Eternity.PartyManagement.open();
  return companion.GUID + '|' + companion.name;
})()""")
time.sleep(1.5)
print("   party dialog open for: %s" % toggled)
result = page.eval("""(function(){
  var tiles = $('#partyManagementDialog .pm-grid .pm-tile, #partyManagementDialog [data-guid]');
  return tiles.length;
})()""")
print("   tiles in dialog: %s" % result)

moved = page.eval("""(function(){
  var guid = %s.split('|')[0];
  Eternity.PartyManagement.toggle(guid);
  Eternity.PartyManagement.accept();
  return 'accepted';
})()""" % json.dumps(toggled))
print("   %s" % moved)
wait_idle("!$('#partyManagementDialog').is(':visible')", "the party change")
time.sleep(3)
after_party = might()
print("   Might after a party Apply: %s" % after_party)
check("A party change keeps an unsaved attribute edit", after_party == edited,
      "%s, expected %s" % (after_party, edited))

# ---- B. the grimoire, against an unsaved global edit ------------------------
fresh()
global_key = page.eval("""(function(){
  var g = Eternity.SavedGame.state.saveData.globals;
  var vars = g.InGameGlobal && g.InGameGlobal.GlobalVariables;
  if (!vars) return null;
  var key = Object.keys(vars).filter(function(k){
    return k && /^n/.test(k) && !isNaN(parseInt(vars[k].value, 10)); })[0];
  return key || null;
})()""")
print("   editing global %s" % global_key)
page.eval("""(function(){
  var e = Eternity.SavedGame.state.saveData.globals.InGameGlobal.GlobalVariables[%s];
  e.value = 4242;
  Eternity.Modifications.transition({modifications: true});
})()""" % json.dumps(global_key))

page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.GRIMOIRE)")
time.sleep(2.5)
removed = page.eval("""(function(){
  var btn = $('#grmChapters .grm-spell-remove').first();
  if (!btn.length) return 'no spell';
  btn.click();
  return 'removed one';
})()""")
print("   %s" % removed)
time.sleep(0.8)
page.eval("$('#grmApply').click()")
wait_idle("$('#grmStatus').text().indexOf('updated') >= 0 || $('#grmStatus').text().indexOf('failed') >= 0",
          "the grimoire Apply")
after_grimoire = page.eval("""String(Eternity.SavedGame.state.saveData.globals
  .InGameGlobal.GlobalVariables[%s].value)""" % json.dumps(global_key))
print("   global after a grimoire Apply: %s" % after_grimoire)
check("A grimoire change keeps an unsaved global edit", after_grimoire == "4242",
      after_grimoire)

# ---- C. the inventory, against an unsaved portrait pick ---------------------
fresh()
page.eval("""(function(){
  var d = Eternity.SavedGame.state.saveData;
  var p = d.characters.filter(function(c){ return c.isMainCharacter; })[0];
  p.portraitPaths.m_textureLargePath.value = 'data/art/gui/portraits/companion/portrait_eder_lg.png';
  p.portraitPaths.m_textureSmallPath.value = 'data/art/gui/portraits/companion/portrait_eder_sm.png';
  Eternity.Modifications.transition({modifications: true});
})()""")

page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.INVENTORY)")
time.sleep(2.8)
page.eval("$('#invSellMode').click()")
time.sleep(1.2)
page.eval("$('#invSellJunk').click()")
time.sleep(1.2)
page.eval("$('#invSellConfirm').click()")
time.sleep(1.0)
page.eval("$('#invApply').click()")
wait_idle("!Eternity.InventoryEditor.state.working", "the inventory Apply")
time.sleep(2)
portrait = page.eval("""(function(){
  var d = Eternity.SavedGame.state.saveData;
  var p = d.characters.filter(function(c){ return c.isMainCharacter; })[0];
  return String(p.portraitPaths.m_textureLargePath.value);
})()""")
print("   portrait after an inventory Apply: %s" % portrait)
check("An inventory change keeps an unsaved portrait pick",
      portrait.endswith("portrait_eder_lg.png"), portrait)

to_list(page)
sys.exit(summary(page))
