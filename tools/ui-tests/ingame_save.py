# Builds the save the in-game test loads: one edit through every editor
# feature, made through the editor's own controls, then Save. Writes what each
# edit should look like in the game to ingame-expected.json. The written save
# is left in the test-env saves folder for copying into the game's folder.
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

HERE = os.path.dirname(os.path.abspath(__file__))
SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
before_files = set(os.listdir(SAVES))

page = Page()
boot(page)
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)
E = page.eval
D = "Eternity.SavedGame.state.saveData"
CHARS = D + ".characters"
expect = {}


def char(name_or_player):
    if name_or_player == "player":
        return "(%s.filter(function(c){ return c.isMainCharacter; })[0])" % CHARS
    return "(%s.filter(function(c){ return c.name === %s; })[0])" % (CHARS, json.dumps(name_or_player))


def view(name, wait=2.8):
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(wait)


def status(element, label):
    page.wait_for("/updated|failed|could not|Could not/i.test($('#%s').text())" % element, 300, label)
    time.sleep(1.0)
    return E("$('#%s').text()" % element)


PLAYER = char("player")
player_guid = E(PLAYER + ".GUID")
E("Eternity.SavedGame.switchCharacter(%s)" % json.dumps(player_guid))
time.sleep(1.0)
view("ATTR", 2.0)

# ---- attributes, skills, identity: the character view's own controls --------
might = int(E(PLAYER + ".stats.BaseMight.value"))
E("$('.attributes input[data-fullkey=\"BaseMight\"]').val(%d).trigger('change')" % (might + 2))
athletics_rank = E("parseInt($('.skill-row').filter(function(){ return $(this).find('.skill-name').text() === 'Athletics'; }).find('input').val(), 10)")
E("""$('.skill-row').filter(function(){ return $(this).find('.skill-name').text() === 'Athletics'; })
  .find('input').val(%d).trigger('change')""" % (athletics_rank + 2))
background_before = E("String(%s.stats.CharacterBackground.value)" % PLAYER)
background_after = E("""(function(){ var s = $('select[data-stat=CharacterBackground]');
  var next = s.find('option').filter(function(){ return this.value !== s.val(); }).first().val();
  s.val(next).trigger('change'); return next; })()""")
expect["player"] = {
    "BaseMight": [might, int(E(PLAYER + ".stats.BaseMight.value"))],
    "Athletics rank": [athletics_rank, athletics_rank + 2],
    "AthleticsSkill points": int(E(PLAYER + ".stats.AthleticsSkill.value")),
    "Background": [background_before, background_after],
}

# ---- portrait ---------------------------------------------------------------
current_large = E(PLAYER + ".portraitPaths.m_textureLargePath.value")
E("""window.__p = null; window.browsePortraits({request: JSON.stringify({category: 'player/female', offset: 0, limit: 40}),
  onSuccess: function(r){ window.__p = JSON.parse(r); }, onFailure: function(){ window.__p = {portraits: []}; }})""")
page.wait_for("window.__p !== null", 60, "portraits")
chosen = E("""(function(){ var cur = %s; var p = window.__p.portraits.filter(function(x){
  return x.large !== cur; })[3];
  Eternity.PortraitPicker.open(%s); Eternity.PortraitPicker.choose(p); return p.large; })()""" % (
    json.dumps(current_large), json.dumps(player_guid)))
time.sleep(2.0)
expect["portrait"] = [current_large, chosen]

# ---- difficulty, globals ----------------------------------------------------
difficulty = E("String(%s.globals.Global.GameState.Difficulty.value)" % D)
new_difficulty = "Easy" if difficulty != "Easy" else "Normal"
E("%s.globals.Global.GameState.Difficulty.value = %s; Eternity.Modifications.transition({modifications: true})"
  % (D, json.dumps(new_difficulty)))
expect["difficulty"] = [difficulty, new_difficulty]

# ---- console ----------------------------------------------------------------
view("CONSOLE", 1.5)
money = float(E(D + ".currency"))
E("$('#consoleInput').val('GivePlayerMoney 12345'); $('#consoleForm').submit()")
time.sleep(0.8)
achievements = E("!!%s.achievementsDisabled" % D)
E("$('#achievementsToggle').click()")
page.wait_for("!!%s.achievementsDisabled !== %s" % (D, json.dumps(achievements)), 240, "achievements")
time.sleep(1.0)
expect["achievementsDisabled"] = [achievements, not achievements]

# ---- party: move one companion out, then resurrect the dead one ------------
view("ATTR", 1.5)
in_party = E("%s.filter(function(c){ return c.inParty && !c.isMainCharacter && !c.resurrectable; }).map(function(c){ return c.name + '|' + c.GUID; })" % CHARS)
leaving = in_party[-1].split("|")
E("Eternity.PartyManagement.open()")
time.sleep(1.5)
E("Eternity.PartyManagement.toggle(%s); Eternity.PartyManagement.accept()" % json.dumps(leaving[1]))
page.wait_for("!$('#partyManagementDialog').is(':visible')", 300, "the party change")
page.wait_for("!%s.inParty" % char(leaving[0]), 300, "the companion to leave the party")
time.sleep(1.5)
dead = E("(function(){ var c = %s.filter(function(c){ return c.resurrectable; })[0]; return c ? c.name + '|' + c.GUID : null; })()" % CHARS)
expect["party before"] = [n.split("|")[0] for n in in_party]
expect["left the party"] = leaving[0]
if dead:
    dead_name, dead_guid = dead.split("|")
    E("Eternity.SavedGame.resurrect(%s)" % json.dumps(dead_guid))
    page.wait_for("!%s.some(function(c){ return c.GUID === %s; })" % (CHARS, json.dumps(dead_guid)), 400, "the resurrection")
    time.sleep(1.5)
    expect["resurrected"] = dead_name
expect["party after"] = E("%s.filter(function(c){ return c.inParty; }).map(function(c){ return c.name; })" % CHARS)
expect["money before sale"] = money + 12345

# ---- inventory: a new item in the player's pack, and a junk sale -----------
view("INVENTORY", 3.5)
E("""$('#invBrowseTarget').val($('#invBrowseTarget option').filter(function(){
  return $(this).text() === %s; }).val()).trigger('change')""" % json.dumps(E(PLAYER + ".name")))
E("$('#invBrowseSearch').val('ring of').trigger('keyup')")
page.wait_for("$('#invBrowseGrid').children().length > 0", 90, "catalog items")
time.sleep(1.0)
added_item = E("""(function(){ var t = $('#invBrowseGrid').children().first();
  var name = (t.attr('title') || '').split('\\n')[0]; t.click(); return name; })()""")
time.sleep(0.8)
stash_before = E("%s.inventory.stash.items.length" % D)
E("$('#invSellMode').click()")
time.sleep(1.0)
E("$('#invSellJunk').click()")
time.sleep(1.0)
sale = E("$('#invSellSummary').text()")
E("$('#invSellConfirm').click()")
time.sleep(1.0)
E("$('#invApply').click()")
page.wait_for("!Eternity.InventoryEditor.state.working", 400, "the inventory Apply")
time.sleep(2.0)
expect["item added to player's pack"] = added_item
expect["junk sale"] = sale
expect["stash items"] = [stash_before, E("%s.inventory.stash.items.length" % D)]
expect["money"] = float(E(D + ".currency"))

# ---- abilities: a talent -----------------------------------------------------
view("ABILITIES", 3.0)
E("$('#ablBrowseSearch').val('weapon focus').trigger('keyup')")
page.wait_for("$('#ablBrowseGrid .abl-add:not(:disabled)').length > 0", 90, "a talent")
time.sleep(1.0)
talent = E("""(function(){ var b = $('#ablBrowseGrid .abl-add:not(:disabled)').first();
  var name = b.closest('.abl-row').find('.abl-name').text(); b.click(); return name; })()""")
time.sleep(0.8)
E("$('#ablApply').click()")
page.wait_for("!Eternity.AbilityEditor.state.working", 400, "the ability Apply")
time.sleep(2.0)
expect["talent added to player"] = talent

# ---- grimoire: swap a level 1 spell in Aloth's book --------------------------
view("GRIMOIRE", 3.5)
removed_spell = E("$('#grmChapters .grm-spell-remove').first().closest('.grm-spell, .grm-spell-row, div').find('.grm-spell-name').first().text() || $('#grmChapters .grm-spell-remove').first().parent().text()")
E("$('#grmChapters .grm-spell-remove').first().click()")
time.sleep(0.8)
E("$('#grmSearch').val('eldritch').trigger('keyup')")
page.wait_for("$('#grmBrowse .grm-btn-add:not(:disabled)').length > 0", 90, "a spell to add")
time.sleep(0.8)
added_spell = E("""(function(){ var b = $('#grmBrowse .grm-btn-add:not(:disabled)').first();
  var n = b.closest('.grm-browse-row').find('.grm-browse-name').text(); b.click(); return n; })()""")
time.sleep(0.8)
E("$('#grmApply').click()")
grimoire_status = status("grmStatus", "the grimoire Apply")
expect["grimoire"] = {"book": E("$('.grm-book-title, #grmTitle').first().text()") or "Aloth's Grimoire",
                      "removed": removed_spell.strip(), "added": added_spell, "status": grimoire_status}

# ---- stronghold: demolish Curio Shop, three turns ----------------------------
view("STRONGHOLD", 3.0)
prestige = E("String(%s.globals.InGameGlobal.Stronghold.Prestige.value)" % D)
E("""$('#shUpgrades .sh-upgrade').filter(function(){ return $(this).find('.sh-upgrade-name').text() === 'Curio Shop'; })
  .find('.sh-upgrade-btn').click()""")
time.sleep(1.0)
E("""$('.sh-number').filter(function(){ return $(this).find('label').text() === 'Turns available'; })
  .find('input').val(3).trigger('change')""")
time.sleep(0.8)
E("$('#shApply').click()")
stronghold_status = status("shStatus", "the stronghold Apply")
expect["stronghold"] = {"demolished": "Curio Shop", "Prestige": [prestige,
    E("String(%s.globals.InGameGlobal.Stronghold.Prestige.value)" % D)],
    "AvailableTurns": E("String(%s.globals.InGameGlobal.Stronghold.AvailableTurns.value)" % D),
    "status": stronghold_status}

# ---- every scalar edit still there after the Applies, then Save --------------
check("Might survived every Apply", int(E(PLAYER + ".stats.BaseMight.value")) == might + 2)
check("the portrait survived every Apply", E(PLAYER + ".portraitPaths.m_textureLargePath.value") == chosen)
problems = E("JSON.stringify(((%s.validation || {}).problems || []))" % D)
check("the working save validates clean", problems == "[]", problems)

E("Eternity.Modifications.state.saveName = 'EK in-game test'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)
expect["file"] = written[0] if written else None

with open(os.path.join(config.OUT, "ingame-expected.json"), "w", encoding="utf-8") as f:
    json.dump(expect, f, indent=1, ensure_ascii=False)
print(json.dumps(expect, indent=1, ensure_ascii=False))
sys.exit(summary(page))
