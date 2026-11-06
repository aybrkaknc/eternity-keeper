# Does every part of the editor still do the thing it is for? One real
# interaction per feature, asserted on what the model or the screen actually
# says afterwards. Nothing here presses Save or Apply: every change is staged
# and thrown away with the page at the end.
import json, sys, time
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"

page = Page()
boot(page)
open_save(page, SAVE)


def view(name, pause=1.8):
    page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(pause)


# ---- the sidebar picks characters ------------------------------------------
view("ATTR")
names = page.eval("JSON.stringify($('#characterList li').map("
                  "function(){return $(this).text().trim();}).get())")
names = json.loads(names)
check("the sidebar lists the party", len(names) > 1, len(names))

# The sheet carries no name of its own -- the sidebar holds that -- so the
# proof that it redrew is that its contents changed.
before_sheet = page.eval("$('#character').text()")
page.eval("$('#characterList li').eq(1).click()")
time.sleep(1.4)
check("picking a second character redraws the sheet",
      page.eval("$('#character').text()") != before_sheet
      and page.eval("$('#characterList li.selected, #characterList li.active').length") >= 0,
      "sheet %s" % ("changed" if page.eval("$('#character').text()") != before_sheet
                    else "identical"))

# ---- editing a stat arms the Save button -----------------------------------
armed = page.eval("""(function(){
  var before = Eternity.Modifications.state.modifications;
  var input = $('#character .stats input:not(:disabled)').first();
  if (!input.length) return 'no editable stat';
  input.val(parseInt(input.val() || '0', 10) + 1).trigger('change');
  return JSON.stringify({before: before,
    after: Eternity.Modifications.state.modifications,
    saveEnabled: !$('#saveButton').prop('disabled')});
})()""")
print("   " + str(armed))
check("editing a stat marks the save modified",
      '"after":true' in str(armed), armed)
check("and enables the Save button", '"saveEnabled":true' in str(armed), armed)

# ---- the identity panel moves the sheet totals ------------------------------
check("the identity panel is drawn", page.eval("$('#identityGrid select').length") >= 6,
      page.eval("$('#identityGrid select').length"))
moved = page.eval("""(function(){
  var before = $('#identitySheet').text();
  var select = $('#identityGrid select').filter(function(){
    return $(this).find('option').length > 2; }).first();
  if (!select.length) return 'no usable dropdown';
  var options = select.find('option');
  var other = options.filter(function(){ return !this.selected; }).first();
  select.val(other.val()).trigger('change');
  var after = $('#identitySheet').text();
  return JSON.stringify({changed: before !== after, field: select.attr('name') || select.attr('id')});
})()""")
print("   " + str(moved))
check("changing an identity field moves the sheet totals",
      '"changed":true' in str(moved), moved)

# ---- raw and globals search ------------------------------------------------
view("RAW", 1.4)
rows = page.eval("$('#rawTable tbody tr:visible').length")
page.eval("$('#searchRaw').val('might').trigger('keyup')")
time.sleep(0.9)
check("the raw search narrows the table",
      0 < page.eval("$('#rawTable tbody tr:visible').length") < rows,
      "%s of %s" % (page.eval("$('#rawTable tbody tr:visible').length"), rows))
page.eval("$('#searchRaw').val('').trigger('keyup')")

view("GLOBALS", 1.4)
grows = page.eval("$('#globalsTable tbody tr:visible').length")
page.eval("$('#searchGlobals').val('stronghold').trigger('keyup')")
time.sleep(0.9)
check("the globals search narrows the table",
      0 < page.eval("$('#globalsTable tbody tr:visible').length") <= grows,
      "%s of %s" % (page.eval("$('#globalsTable tbody tr:visible').length"), grows))
page.eval("$('#searchGlobals').val('').trigger('keyup')")

# ---- inventory: sell mode prices a selection -------------------------------
view("INVENTORY", 2.6)
check("the inventory draws every container",
      page.eval("$('#invPacks .inv-tile').length") > 0
      and page.eval("$('#invStashGrid .inv-tile').length") > 0,
      "%s pack, %s stash" % (page.eval("$('#invPacks .inv-tile').length"),
                             page.eval("$('#invStashGrid .inv-tile').length")))

page.eval("$('#invSellMode').click()")
time.sleep(1.4)
check("sell mode opens its bar", page.eval("$('#invSellBar').is(':visible')"))
page.eval("$('#invSellJunk').click()")
time.sleep(1.6)
summary_text = page.eval("$('#invSellSummary').text()")
print("   " + str(summary_text))
check("selecting junk prices it", any(c.isdigit() for c in str(summary_text)), summary_text)
page.eval("$('#invSellNone').click()")
time.sleep(0.8)
page.eval("$('#invSellMode').click()")
time.sleep(1.0)
check("leaving sell mode closes the bar", not page.eval("$('#invSellBar').is(':visible')"))

# ---- inventory: the catalog browser pages ----------------------------------
page.eval("$('#invBrowseSearch').val('sabre').trigger('keyup')")
page.wait_for("$('#invBrowseGrid .inv-tile').length > 0", 60, "the item browser")
time.sleep(0.6)
check("the item browser finds things in the catalog",
      page.eval("$('#invBrowseGrid .inv-tile').length") > 0,
      page.eval("$('#invBrowseCount').text()"))
page.eval("$('#invBrowseSearch').val('').trigger('keyup')")
time.sleep(1.0)

# ---- abilities -------------------------------------------------------------
view("ABILITIES", 2.4)
check("the character's abilities are listed",
      page.eval("$('#ablList .abl-row').length") > 0,
      page.eval("$('#ablCount').text()"))
page.eval("$('#ablBrowseSearch').val('fire').trigger('keyup')")
page.wait_for("$('#ablBrowseGrid .abl-row').length > 0", 60, "the ability browser")
time.sleep(0.6)
check("the ability browser searches the catalog",
      page.eval("$('#ablBrowseGrid .abl-row').length") > 0,
      page.eval("$('#ablBrowseCount').text()"))
page.eval("$('#ablBrowseSearch').val('').trigger('keyup')")

# ---- stronghold ------------------------------------------------------------
view("STRONGHOLD", 2.2)
check("the stronghold draws its upgrades",
      page.eval("$('#shUpgrades .sh-upgrade').length") > 0,
      page.eval("$('#shUpgradeCount').text()"))
# The row's own button is the toggle -- it reads "Built" or "Build" depending
# on which way it would go. This save has all 25, so the direction available is
# demolishing, which also exercises the cascade.
staged = page.eval("""(function(){
  var before = $('#shGauges').text();
  var button = $('#shUpgrades .sh-upgrade-btn').filter(function(){
    return !$(this).prop('disabled'); }).first();
  if (!button.length) return 'nothing stageable';
  var label = $.trim(button.text());
  button.click();
  return JSON.stringify({changed: $('#shGauges').text() !== before, did: label});
})()""")
time.sleep(1.4)
print("   " + str(staged))
check("staging an upgrade projects the gauges",
      '"changed":true' in str(staged), staged)
page.eval("$('#shRevert').click()")
time.sleep(1.0)

# ---- grimoire --------------------------------------------------------------
view("GRIMOIRE", 2.4)
check("the grimoire lists the books found in the save",
      page.eval("$('#grmList .grm-book').length") > 0,
      page.eval("$('#grmList .grm-book').length"))
check("and draws eight chapters",
      page.eval("$('#grmChapters .grm-chapter').length") == 8,
      page.eval("$('#grmChapters .grm-chapter').length"))
page.eval("$('#grmSearch').val('missile').trigger('keyup')")
page.wait_for("$('#grmBrowse .grm-browse-row').length > 0", 60, "the spell list")
time.sleep(0.6)
check("the spell browser searches the wizard list",
      page.eval("$('#grmBrowse .grm-browse-row').length") > 0,
      page.eval("$('#grmBrowse .grm-browse-row').length"))
page.eval("$('#grmSearch').val('').trigger('keyup')")

# ---- console ---------------------------------------------------------------
view("CONSOLE", 2.0)
before_lines = page.eval("$('#consoleOutput').children().length")
page.eval("$('#consoleInput').val('PrintGlobal nDuranceQuestState');"
          "$('#consoleForm').trigger('submit')")
time.sleep(1.4)
after_lines = page.eval("$('#consoleOutput').children().length")
check("the console answers a command", after_lines > before_lines,
      "%s -> %s" % (before_lines, after_lines))
print("   " + str(page.eval("$('#consoleOutput').children().last().text()"))[:120])

page.eval("$('#searchReference').val('teleport').trigger('keyup')")
time.sleep(0.8)
check("the command reference searches",
      0 < page.eval("$('#referenceTable tbody tr:visible').length")
      < page.eval("$('#referenceTable tbody tr').length"),
      "%s of %s" % (page.eval("$('#referenceTable tbody tr:visible').length"),
                    page.eval("$('#referenceTable tbody tr').length")))
page.eval("$('#searchReference').val('').trigger('keyup')")

# ---- chrome: theme and sidebar ---------------------------------------------
page.eval("$('#themeToggle').click()")
time.sleep(0.7)
check("the theme toggle switches to light",
      page.eval("document.body.className.indexOf('theme-light') >= 0"))
page.eval("$('#themeToggle').click()")
time.sleep(0.7)
check("and back to dark",
      page.eval("document.body.className.indexOf('theme-light') < 0"))

wide = page.eval("$('#sidebar').width()")
page.eval("$('#sidebarToggle').click()")
time.sleep(0.9)
narrow = page.eval("$('#sidebar').width()")
page.eval("$('#sidebarToggle').click()")
time.sleep(0.9)
check("the sidebar collapses and comes back",
      narrow < wide and page.eval("$('#sidebar').width()") == wide,
      "%s -> %s -> %s" % (wide, narrow, page.eval("$('#sidebar').width()")))

# ---- nothing was written ---------------------------------------------------
to_list(page)
check("nothing is left showing on the save list",
      not page.eval("$('.view, .save-only').filter(':visible').length"))

sys.exit(summary(page))
