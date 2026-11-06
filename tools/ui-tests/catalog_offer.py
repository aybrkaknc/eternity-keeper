# What the item and ability browsers offer, asked of the real catalogs through
# the running editor's own handlers.
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Page, check, summary, open_save, to_list, boot

page = Page()
boot(page)
E = page.eval


def ask(handler, request):
    E("""window.__reply = null; window.%s({request: %s,
      onSuccess: function(r){ window.__reply = r; },
      onFailure: function(c, m){ window.__reply = JSON.stringify({error: m}); }})""" % (
        handler, json.dumps(json.dumps(request))))
    page.wait_for("window.__reply !== null", 60, handler)
    return json.loads(E("window.__reply"))


everything = ask("browseItems", {"search": "", "offset": 0, "limit": 1})
print("   item browser offers %s items" % everything["total"])
check("the item browser offers fewer than the 2,156 catalogued", 1500 < everything["total"] < 2156,
      everything["total"])

for needle in ("debug", "assuring", "unused", "skulltest", "talking sword", "do not use"):
    found = ask("browseItems", {"search": needle, "offset": 0, "limit": 50})
    check("no item offered for '%s'" % needle, found["total"] == 0,
          [i["displayName"] + " " + i["baseItem"] for i in found["items"]][:3])

leather = ask("browseItems", {"search": "leather armor", "offset": 0, "limit": 200})
strays = [i["baseItem"] for i in leather["items"] if "/items/" not in i["baseItem"].lower()]
check("every 'Leather Armor' offered is an item, not a creature", not strays and leather["total"] > 0,
      "%d offered, strays %s" % (leather["total"], strays[:3]))

grimoire = ask("browseItems", {"search": "grimoire", "offset": 0, "limit": 200})
check("no companion offered as their own grimoire",
      not [i for i in grimoire["items"] if "/characters/companion/" in i["baseItem"].lower()],
      grimoire["total"])

keys = ask("browseItems", {"search": "currier", "offset": 0, "limit": 20})
check("a key from the Prototype folder is still offered (the game uses it)", keys["total"] >= 1,
      [i["baseItem"] for i in keys["items"]])

spells = ask("browseAbilities", {"search": "debug", "anyClass": True, "offset": 0, "limit": 50})
check("'Show everything' offers no debug spells", spells["total"] == 0,
      [a["displayName"] for a in spells["abilities"]][:3])

sys.exit(summary(page))
