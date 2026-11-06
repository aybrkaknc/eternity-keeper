# A save for checking Heal, level up and resupply in the game itself, built
# through the dialog's own buttons on save 4 (Socew Cieniu): a party with
# health to lose -- the Watcher at 1,054 health on 252 stamina -- and three of
# Normal's four camping supplies. "Heal the party" and "Refill" are clicked;
# nobody there is short of experience, since everyone holds the level-16 cap.
# Saved as "EK heal test" and left in the test-env saves folder for loading in
# the game beside the untouched original; delete it afterwards (the suites
# expect twelve saves).
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 4 SocewCieniu.savegame"
before_files = set(os.listdir(SAVES))

page = Page()
boot(page)
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)
E = page.eval
D = "Eternity.SavedGame.state.saveData"

party = E("%s.characters.filter(function(c){ return c.inParty && c.health; })"
          ".map(function(c){ return c.name; })" % D)
print("active party: " + ", ".join(party))

E("$('#menuPartyCare').click()")
page.wait_for("$('#partyCareDialog').is(':visible')", 30, "the dialog")
time.sleep(0.6)
E("$('#partyCareHeal').click()")
time.sleep(0.3)
E("$('#partyCareRefill').click()")
time.sleep(0.3)
check("the party is marked", set(E(
    "%s.characters.filter(function(c){ return c.inParty && c.health; })"
    ".map(function(c){ return String(c.health.m_needs_current_values.value); })" % D)) == {"true"})
check("the supplies are full", E("%s.campingSupplies" % D) == 4)
E("$('#partyCareDialog').modal('hide')")
time.sleep(0.8)

E("Eternity.Modifications.state.saveName = 'EK heal test'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving",
              600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)
E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")

print(json.dumps({"file": written[0] if written else None, "party": party}, ensure_ascii=False))
sys.exit(summary(page))
