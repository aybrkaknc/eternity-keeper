# The pictures the README shows: a character's sheet, the Inventory tab, the
# Abilities tab and the Stronghold tab, drawn at 1680x1050
# from a mid-game save of the test environment. Writes docs/screenshots/*.png.
# Run it after a change to how those look, and look at the pictures before
# committing them. Needs Pillow (it is in tools/gamedata/requirements.txt):
# the browser hands back its whole window, with the page in one corner of it.
import base64, io, os, sys, time
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from harness import Page, check, summary, open_save, to_list, boot

SAVE = os.environ.get("EK_SCREENSHOT_SAVE") or "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
OUT = os.path.join(config.REPO, "docs", "screenshots")
WIDTH, HEIGHT = 1680, 1050
os.makedirs(OUT, exist_ok=True)

page = Page()
boot(page)
E = page.eval


def shot(name):
    data = page.call("Page.captureScreenshot", format="png")
    png = base64.b64decode(data.get("data") or data.get("result", {}).get("data"))
    picture = Image.open(io.BytesIO(png)).convert("RGB")
    check("%s: the window is at least as large as the picture wanted" % name,
          picture.width >= WIDTH and picture.height >= HEIGHT, picture.size)
    picture.crop((0, 0, WIDTH, HEIGHT)).save(os.path.join(OUT, name + ".png"), optimize=True)
    check("%s.png was drawn" % name, os.path.getsize(os.path.join(OUT, name + ".png")) > 20000)


def view(name, wait=3.0):
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(wait)
    E("window.scrollTo(0, 0)")
    time.sleep(0.3)


page.call("Emulation.setDeviceMetricsOverride", width=WIDTH, height=HEIGHT, deviceScaleFactor=1, mobile=False,
          fitWindow=False)
time.sleep(1.0)

# Not the save list: in the test environment it is a page of test saves.
to_list(page)
open_save(page, SAVE)
time.sleep(2.0)
view("ATTR")
shot("character")
view("INVENTORY", 4.0)
shot("inventory")
view("ABILITIES", 4.0)
shot("abilities")
view("STRONGHOLD", 4.0)
shot("stronghold")

page.call("Emulation.clearDeviceMetricsOverride")
sys.exit(summary(page))
