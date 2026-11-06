# Light theme at full width, and dark theme in a 1440px-wide viewport.
import base64, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from harness import Page, open_save, to_list, boot, check, summary

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
OUT = os.path.join(config.OUT, "shots")
os.makedirs(OUT, exist_ok=True)
page = Page()
boot(page)
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)
E = page.eval


def shot(name):
    data = page.call("Page.captureScreenshot", format="png")
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(base64.b64decode(data["data"]))


def overflow():
    return E("""$('.view:visible *').filter(function(){
      var b = this.getBoundingClientRect(); return b.width > 0 && b.right > window.innerWidth + 1
        && $(this).css('position') !== 'fixed'; }).length""")


E("$('body').addClass('theme-light')")
for name in ("INVENTORY", "GRIMOIRE", "ABILITIES", "VENDORS"):
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(3)
    shot("light-" + name.lower())
E("$('body').removeClass('theme-light')")

page.call("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=1, mobile=False, fitWindow=False)
time.sleep(1.5)
for name in ("INVENTORY", "GRIMOIRE", "ABILITIES", "STRONGHOLD", "VENDORS", "ATTR", "CONSOLE"):
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(3)
    count = overflow()
    check("1440px: nothing on the %s tab runs off the right edge" % name.lower(), count == 0, count)
    if name in ("INVENTORY", "ABILITIES"):
        shot("narrow-" + name.lower())
rows = E("""$('#invPacks .inv-pack-row:visible').toArray().map(function(e){
  return Math.round(e.getBoundingClientRect().top); })""") if False else None
E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.INVENTORY)")
time.sleep(3)
tops = E("""$('#invPacks .inv-pack-row:visible').toArray().map(function(e){ return Math.round(e.getBoundingClientRect().top); })""")
check("1440px: at least two party cards to a row", len([t for t in tops if t == tops[0]]) >= 2,
      len([t for t in tops if t == tops[0]]))
page.call("Emulation.clearDeviceMetricsOverride")
sys.exit(summary(page))
