# Builds a save for checking the Vendors tab in the game itself: everything
# the party sold to Caed Nua's General Goods Merchant taken out of his stock.
#
# The test saves all stand in the keep's courtyard, one door from the Great
# Hall where he trades, and his 693 items are none of them original stock --
# so in the game his store should show only what he restocks when opened.
# Not a pass/fail suite: it leaves the written save in the test-env saves
# folder and prints its name, for copying into the game's own saves folder.
# Delete it afterwards (the suites expect 12 saves).
import os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
MERCHANT = "General Goods Merchant"

before_files = set(os.listdir(SAVES))
page = Page()
boot(page)
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)
E = page.eval

E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.VENDORS)")
page.wait_for("$('#vndList .vnd-vendor').length > 0", 120, "the vendor list")
time.sleep(0.8)
E("""$('#vndList .vnd-vendor').filter(function(){
  return $(this).find('.vnd-vendor-name').contents().first().text() === '%s'; }).click()""" % MERCHANT)
time.sleep(1.0)
held = E("$('#vndGrid .vnd-tile').length")
original = E("$('#vndGrid .vnd-tile-original').length")
print("   %s holds %d items, %d of them original stock" % (MERCHANT, held, original))

E("$('#vndSelectSold').click()")
time.sleep(0.5)
print("   " + E("$('#vndStatus').text()"))
E("$('#vndApply').click()")
page.wait_for("/Took|could not|failed/i.test($('#vndStatus').text())", 300, "the Apply")
time.sleep(1.0)
print("   " + E("$('#vndStatus').text()"))
check("the merchant keeps only his original stock",
      E("$('#vndGrid .vnd-tile').length") == original, E("$('#vndGrid .vnd-tile').length"))

E("Eternity.Modifications.state.saveName = 'EK vendors ingame'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving",
              600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)
for name in written:
    print("   WRITTEN: " + os.path.join(SAVES, name))
E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
sys.exit(summary(page))
