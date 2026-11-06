# Change who a character is, and the rest of the editor has to agree.
#
# The opener derives a few things from CharacterStats and ships them as a
# snapshot: which equipment slots a character has, what class the ability
# browser should offer from. The Identity panel edits those same stats live, so
# the snapshot went stale the moment someone changed a class -- the Inventory
# tab kept offering a grimoire slot to a character who was no longer a wizard.
import json, sys, time
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"

page = Page()
boot(page)
open_save(page, SAVE)


def view(name, pause=2.0):
    page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(pause)


def set_identity(field, value):
    return page.eval("""(function(){
      var select = $('#identityGrid select[data-stat=' + %s + ']').first();
      if (!select.length) return 'no ' + %s + ' dropdown';
      var wanted = %s;
      var found = false;
      select.find('option').each(function(){ if (this.value === wanted) found = true; });
      if (!found) return 'no such option: ' + wanted;
      select.val(wanted).trigger('change');
      return 'set';
    })()""" % (json.dumps(field), json.dumps(field), json.dumps(value)))


def blocked_slots():
    return json.loads(page.eval("""JSON.stringify(
      $('#inventoryView .inv-equip-slot').filter(function(){
        return $(this).find('.inv-tile-blocked').length > 0;
      }).map(function(){ return $(this).attr('data-slot'); }).get())"""))


# ---- who the character starts as -------------------------------------------
view("ATTR")
start = page.eval("""(function(){
  var guid = Eternity.SavedGame.state.activeCharacter;
  var c = Eternity.SavedGame.state.saveData.characters.filter(
    function(x){ return x.GUID === guid; })[0];
  return JSON.stringify({name: c.name, cls: c.stats.CharacterClass.value,
                         race: c.stats.CharacterRace.value});
})()""")
print("   starts as: %s" % start)
start = json.loads(start)

view("INVENTORY", 2.6)
before = blocked_slots()
print("   slots blocked to begin with: %s" % before)
check("a Godlike has no head slot", "Head" in before, before)
check("and a non-wizard no grimoire slot", "Grimoire" in before, before)

# ---- become a wizard -------------------------------------------------------
view("ATTR")
result = set_identity("CharacterClass", "Wizard")
print("   %s" % result)
check("the class dropdown accepted Wizard", result == "set", result)

view("INVENTORY", 2.6)
after = blocked_slots()
print("   slots blocked as a wizard: %s" % after)
check("the grimoire slot opens with the class",
      "Grimoire" not in after, after)
check("and the head slot still follows the race",
      "Head" in after, after)

# The two halves reach the disk by different routes -- Apply writes the items,
# Save writes who they belong to -- so a slot that exists only because of an
# unsaved identity edit has to say so.
warning = page.eval("$('#invStatus').text()")
print("   inventory note: %r" % warning)
check("the inventory warns that the slots follow an unsaved edit",
      "unsaved change" in str(warning), warning)
check("and marks it as a warning rather than a result",
      page.eval("$('#invStatus').hasClass('inv-status-warn')"))

# ---- stop being Godlike ----------------------------------------------------
view("ATTR")
race = set_identity("CharacterRace", "Human")
print("   %s" % race)
if race == "set":
    view("INVENTORY", 2.6)
    human = blocked_slots()
    print("   slots blocked as a human wizard: %s" % human)
    check("a race change opens the head slot",
          "Head" not in human, human)

# ---- and the ability browser refetches for the new class -------------------
view("ABILITIES", 3.0)
meta = page.eval("$('#ablCharacterMeta').text()")
print("   abilities header: %r" % meta)
check("the abilities header names the staged class", "Wizard" in str(meta), meta)

page.wait_for("$('#ablBrowseGrid .abl-row').length > 0", 90, "the browser")
time.sleep(1.0)
offered = page.eval("""JSON.stringify($('#ablBrowseGrid .abl-row .abl-meta').slice(0,40)
  .map(function(){ return $(this).text(); }).get())""")
paladin = [m for m in json.loads(offered) if 'Paladin' in m]
wizard = [m for m in json.loads(offered) if 'Wizard' in m]
print("   offered: %d wizard, %d paladin" % (len(wizard), len(paladin)))
check("nothing paladin-only is still offered", len(paladin) == 0, paladin[:3])

# ---- put it all back -------------------------------------------------------
view("ATTR")
page.eval("$('#charPanelRevert').click()")
time.sleep(1.6)
restored = page.eval("""(function(){
  var guid = Eternity.SavedGame.state.activeCharacter;
  var c = Eternity.SavedGame.state.saveData.characters.filter(
    function(x){ return x.GUID === guid; })[0];
  return JSON.stringify({cls: c.stats.CharacterClass.value,
                         race: c.stats.CharacterRace.value});
})()""")
print("   after Revert: %s" % restored)
restored = json.loads(restored)
check("Revert puts the class back", restored["cls"] == start["cls"],
      "%s vs %s" % (restored["cls"], start["cls"]))
check("Revert puts the race back", restored["race"] == start["race"],
      "%s vs %s" % (restored["race"], start["race"]))

view("INVENTORY", 2.6)
final = blocked_slots()
print("   slots blocked again: %s" % final)
check("and the warning goes with them",
      not page.eval("$('#invStatus').is(':visible')")
      or "unsaved change" not in page.eval("$('#invStatus').text()"),
      page.eval("$('#invStatus').text()"))
check("and the slots go back with them", sorted(final) == sorted(before),
      "%s vs %s" % (final, before))

to_list(page)
sys.exit(summary(page))
