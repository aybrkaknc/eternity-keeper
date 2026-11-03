# One save for checking in the game that a list the editor added to past its
# Capacity loads: the prologue playthrough's stash holds 16 items and has room
# written for 16, and one catalog item is added to it, so the stash's two
# lists are written with 17 items and a Capacity of 17 (invariant 5). Before
# 2026-11-03 the editor wrote 17 items with a Capacity of 16, which the game
# never does.
#
#   "EK capacity test"   that save, with the item in the stash
#
# It is left in the test-env saves folder for loading in the game; delete it
# afterwards (the suites expect twelve saves).
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "cf88c16dc9564d77a49e89c8894d5c4e 7763974 CilantLs.savegame"
D = "Eternity.SavedGame.state.saveData"

page = Page()
boot(page)
E = page.eval

to_list(page)
open_save(page, SAVE)
time.sleep(1.5)
E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.INVENTORY)")
time.sleep(3)

stash_before = E("%s.inventory.stash.items.length" % D)
check("the stash starts with 16 items", stash_before == 16, stash_before)

E("$('#invBrowseTarget').val($('#invBrowseTarget option').filter(function(){"
  " return $(this).text() === 'Stash'; }).val()).trigger('change')")
E("$('#invBrowseSearch').val('ring of').trigger('keyup')")
page.wait_for("$('#invBrowseGrid').children().length > 0", 90, "catalog items")
time.sleep(1.0)
added = E("""(function(){ var t = $('#invBrowseGrid').children().first();
  var name = (t.attr('title') || '').split('\\n')[0]; t.click(); return name; })()""")
time.sleep(0.8)
E("$('#invApply').click()")
page.wait_for("!Eternity.InventoryEditor.state.working", 400, "the inventory Apply")
time.sleep(2.0)
stash_after = E("%s.inventory.stash.items.length" % D)
check("the stash holds 17 items after the Apply", stash_after == 17, stash_after)
check("the validator finds nothing wrong",
      E("((%s.validation || {}).problems || []).length" % D) == 0,
      E("JSON.stringify((%s.validation || {}).problems || [])" % D))

before = set(os.listdir(SAVES))
E("Eternity.Modifications.state.saveName = 'EK capacity test'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before)
check("Save wrote one new file", len(written) == 1, written)

with open(os.path.join(config.OUT, "ingame-capacity.json"), "w") as out:
    json.dump({"file": written[0] if written else None, "added to the stash": added
               , "stash": [stash_before, stash_after]}, out, indent=1)
print(json.dumps({"file": written, "added": added}))
sys.exit(summary(page))
