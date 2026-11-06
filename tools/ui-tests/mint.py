# The two ways the editor manufactures an object the save never had -- an item
# from the catalog and an ability -- driven, saved and read back. Both now go
# through save/PacketMint, and the validator is what catches a minting mistake
# (an InstanceID that is not its own ObjectID, an item with no packet).
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
before_files = set(os.listdir(SAVES))

page = Page()
boot(page)
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)

E = page.eval
D = "Eternity.SavedGame.state.saveData"
PROBLEMS = "JSON.stringify(((%s.validation || {}).problems || []))" % D
STASH = "%s.inventory.stash.items.map(function(i){ return i.guid + '|' + i.baseItem; })" % D
guid = E("%s.characters.filter(function(c){ return c.isMainCharacter; })[0].GUID" % D)
ABILITIES = ("(%s.abilities.characters.filter(function(c){ return c.guid === %s; })[0]"
             ".abilities || []).map(function(a){ return a.guid + '|' + a.prefab; })" % (D, json.dumps(guid)))
E("Eternity.SavedGame.switchCharacter(%s)" % json.dumps(guid))
time.sleep(0.8)

# ---- an item from the catalog, into the stash -------------------------------
E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.INVENTORY)")
time.sleep(3.0)
stash_before = set(E(STASH))
E("""$('#invBrowseTarget').val($('#invBrowseTarget option').filter(function(){
  return $(this).text() === 'Stash'; }).val()).trigger('change')""")
E("$('#invBrowseSearch').val('ring').trigger('keyup')")
page.wait_for("$('#invBrowseGrid').children().length > 0", 90, "catalog items")
time.sleep(1.0)
picked = E("""(function(){ var t = $('#invBrowseGrid').children().first();
  var name = (t.attr('title') || '').split('\\n')[0]; t.click(); return name; })()""")
time.sleep(0.8)
E("$('#invApply').click()")
page.wait_for("!Eternity.InventoryEditor.state.working", 300, "the inventory Apply")
time.sleep(2.0)
new_items = sorted(set(E(STASH)) - stash_before)
print("   added %s -> %s" % (picked, new_items))
check("a catalog item is minted into the stash", len(new_items) == 1, new_items)
check("and the reopened save validates clean", E(PROBLEMS) == "[]", E(PROBLEMS))

# ---- a new ability ----------------------------------------------------------
E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.ABILITIES)")
time.sleep(2.5)
abilities_before = set(E(ABILITIES))
E("$('.abl-kind').filter(function(){ return $.trim($(this).text()) === 'Abilities'; }).click()")
time.sleep(0.5)
E("$('#ablBrowseSearch').val('').trigger('keyup')")
page.wait_for("$('#ablBrowseGrid .abl-add:not(:disabled)').length > 0", 90, "an ability to add")
time.sleep(1.0)
added = E("""(function(){ var b = $('#ablBrowseGrid .abl-add:not(:disabled)').first();
  var name = b.closest('.abl-row').find('.abl-name').text(); b.click(); return name; })()""")
time.sleep(0.8)
E("$('#ablApply').click()")
page.wait_for("!Eternity.AbilityEditor.state.working", 300, "the ability Apply")
time.sleep(2.0)
new_abilities = sorted(set(E(ABILITIES)) - abilities_before)
print("   added %s -> %s" % (added, new_abilities))
check("a new ability object is minted on the character", len(new_abilities) >= 1, new_abilities)
check("and the reopened save validates clean", E(PROBLEMS) == "[]", E(PROBLEMS))

# ---- Save and read back -----------------------------------------------------
E("Eternity.Modifications.state.saveName = 'EK mint check'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving",
              600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)

if written:
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
    page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
    E("Eternity.SaveSearch.search()")
    page.wait_for("!Eternity.SaveSearch.state.searching && Eternity.SaveSearch.state.saves.some("
                  "function(s){ return s.absolutePath.indexOf(%s) >= 0; })" % json.dumps(written[0]),
                  300, "the written save in the list")
    open_save(page, written[0])
    time.sleep(1.5)
    check("written: the minted item", set(new_items) <= set(E(STASH)))
    check("written: the minted ability", set(new_abilities) <= set(E(ABILITIES)))
    check("written: the validator finds nothing wrong", E(PROBLEMS) == "[]", E(PROBLEMS))
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")

for name in written:
    os.remove(os.path.join(SAVES, name))

sys.exit(summary(page))
