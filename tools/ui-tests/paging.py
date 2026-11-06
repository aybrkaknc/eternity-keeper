# "Show more" has to reach the end of the list. Both browsers that append a
# page rather than replacing one were adding the request offset to the number
# of rows already on screen, which double-counts everything after the first
# page: the button hid itself halfway through and quoted a negative remainder.
import sys, time
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"

page = Page()
boot(page)
open_save(page, SAVE)


def exhaust(label, tiles_selector, more_selector, expected):
    """Click Show more until it goes away; every row must be reachable."""
    seen = []
    for _ in range(20):
        count = page.eval("$(%s).length" % tiles_selector)
        seen.append(count)
        label_text = page.eval("$(%s).text()" % more_selector)
        check("%s never quotes a negative remainder" % label,
              "(-" not in str(label_text), label_text)

        if not page.eval("$(%s).is(':visible')" % more_selector):
            break

        page.eval("$(%s).click()" % more_selector)
        time.sleep(2.0)

    final = page.eval("$(%s).length" % tiles_selector)
    check("%s reaches every row" % label, final == expected,
          "%d of %d (pages: %s)" % (final, expected, seen))


# ---- the portrait picker ---------------------------------------------------
page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.ATTR)")
time.sleep(1.6)
page.eval("$('.portrait-btn').first().click()")
page.wait_for("$('#portraitDialog .ptr-tile').length > 0", 120, "the portraits")
time.sleep(0.8)

total = page.eval("""(function(){
  var m = /\\((\\d+) left\\)/.exec($('#portraitMore').text() || '');
  return m ? parseInt(m[1], 10) + $('#portraitDialog .ptr-tile').length : -1;
})()""")
print("   the picker says it holds %s portraits" % total)
check("the installed set is the 118 pairs the game ships", total == 118, total)

exhaust("the portrait picker", "'#portraitDialog .ptr-tile'", "'#portraitMore'", 118)

# Every category has to be reachable too, and they must add up.
per = page.eval("""JSON.stringify($('#portraitFilters button').map(
  function(){ return $(this).text(); }).get())""")
print("   filters: " + str(per))
check("the picker offers a filter per folder plus All",
      per.count(",") == 4, per)

page.eval("$('#portraitDialog').modal('hide')")
time.sleep(0.9)

# ---- the wizard spell list -------------------------------------------------
page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.GRIMOIRE)")
time.sleep(2.4)
check("the grimoire view is showing", page.eval("$('#grimoireView').is(':visible')"))

spells = page.eval("""(function(){
  var m = /\\((\\d+) left\\)/.exec($('#grmBrowseMore').text() || '');
  return m ? parseInt(m[1], 10) + $('#grmBrowse .grm-browse-row').length
           : $('#grmBrowse .grm-browse-row').length;
})()""")
print("   the spell list says it holds %s wizard spells" % spells)
exhaust("the wizard spell list", "'#grmBrowse .grm-browse-row'",
        "'#grmBrowseMore'", spells)

to_list(page)
sys.exit(summary(page))
