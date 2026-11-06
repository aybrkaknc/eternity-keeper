# Heal, level up and resupply (Character menu), driven through its own dialog
# on two real mid-game saves, each saved and read back from the written file.
#
# Healing sets the game's own flag for a full refill on load
# (Health.m_needs_current_values), on the active party only; levelling raises
# everyone below the chosen level to its experience and lowers nobody's;
# camping supplies refill to the difficulty's cap (4 on Normal).
#
# The Caed Nua save carries the case the game's own AddExperienceToLevel gets
# wrong: Keira, a hired adventurer, is level 1 with 86,485 experience -- a dozen
# level-ups not yet taken. The game compares levels, so asking it for level 10
# would cut her to 45,000; the editor compares experience and leaves her be.
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

CAED_NUA = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
LATER = "0945952c89c640e4a18cdb293e3946b4 4 SocewCieniu.savegame"
before_files = set(os.listdir(SAVES))

page = Page()
boot(page)
E = page.eval
D = "Eternity.SavedGame.state.saveData"
EVERYONE = ("%s.characters.filter(function(c){ return !c.resurrectable && !c.isDead"
            " && c.stats && c.stats.Experience; })" % D)


def everyone(expr):
    return E("%s.map(function(c){ return %s; })" % (EVERYONE, expr))


def flags(in_party):
    return E("%s.characters.filter(function(c){ return %sc.inParty && c.health; })"
             ".map(function(c){ return String(c.health.m_needs_current_values.value); })"
             % (D, "" if in_party else "!"))


def open_dialog():
    E("$('#menuPartyCare').click()")
    page.wait_for("$('#partyCareDialog').is(':visible')", 30, "the dialog")
    time.sleep(0.6)


def close_dialog():
    E("$('#partyCareDialog').modal('hide')")
    time.sleep(0.8)


def save_and_reopen(name):
    """Saves under `name`, reopens the written file from the list, returns its name."""
    listed = set(os.listdir(SAVES))
    E("Eternity.Modifications.state.saveName = %s; Eternity.Modifications.save()" % json.dumps(name))
    page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving",
                  600, "the Save")
    time.sleep(1.0)
    written = sorted(set(os.listdir(SAVES)) - listed)
    check("Save wrote one new file", len(written) == 1, written)
    if not written:
        return None

    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
    page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
    check("the dialog does not outlive the save", not E("$('#partyCareDialog').is(':visible')"))
    E("Eternity.SaveSearch.search()")
    page.wait_for("!Eternity.SaveSearch.state.searching && Eternity.SaveSearch.state.saves.some("
                  "function(s){ return s.absolutePath.indexOf(%s) >= 0; })" % json.dumps(written[0]),
                  300, "the written save in the list")
    open_save(page, written[0])
    time.sleep(1.5)
    return written[0]


# ==== Caed Nua: heal and level ================================================
to_list(page)
open_save(page, CAED_NUA)
time.sleep(1.5)

names = everyone("c.name")
party = E("%s.characters.filter(function(c){ return c.inParty && c.health; })"
          ".map(function(c){ return c.name; })" % D)
check("the save has a party to heal and people outside it",
      len(party) >= 1 and len(names) > len(party), "%d of %d" % (len(party), len(names)))
check("nobody starts out marked for healing", set(flags(True) + flags(False)) <= {"false"})
check("the menu item is enabled with a save open",
      not E("$('#menuPartyCare').parent().hasClass('disabled')"))

open_dialog()
heal_text = E("$('#partyCareHealText').text()")
check("the heal row counts the active party", ("%d party members" % len(party)) in heal_text,
      heal_text)
supplies_text = E("$('#partyCareSuppliesText').text()")
check("a full stock of camping supplies says so",
      supplies_text == "4 of 4, the most a party can carry on Normal."
      and E("$('#partyCareRefill').text()") == "Full" and E("$('#partyCareRefill').prop('disabled')"),
      supplies_text)

# ---- heal --------------------------------------------------------------------
E("$('#partyCareHeal').click()")
time.sleep(0.4)
check("every party member is marked for a full refill", set(flags(True)) == {"true"},
      flags(True))
check("and nobody outside the party", set(flags(False)) <= {"false"}, flags(False))
check("the button says it is done",
      E("$('#partyCareHeal').text()") == "Healed" and E("$('#partyCareHeal').prop('disabled')"))
check("the status line says Save writes it",
      "Save to write it" in E("$('#partyCareStatus').text()"), E("$('#partyCareStatus').text()"))
check("the Save button is armed", E("Eternity.Modifications.state.modifications") is True)

# ---- level -------------------------------------------------------------------
levels = E("$('#partyCareLevel option').map(function(){ return this.value; }).get()")
check("levels 2 to 16 are offered", levels == [str(n) for n in range(2, 17)], levels)
top = max(everyone("parseInt(c.level, 10) || 1"))
check("the highest level anyone has reached is offered first",
      E("$('#partyCareLevel').val()") == str(min(max(top, 2), 16)), E("$('#partyCareLevel').val()"))

keira = names.index("Keira") if "Keira" in names else -1
before = everyone("parseInt(c.stats.Experience.value, 10)")
levels_now = everyone("parseInt(c.level, 10) || 0")
check("Keira is level 1 with a dozen level-ups untaken", keira >= 0
      and levels_now[keira] == 1 and before[keira] > 500 * 10 * 9,
      (levels_now[keira], before[keira]) if keira >= 0 else names)

E("$('#partyCareLevel').val('10').change()")
time.sleep(0.3)
check("at level 10 nobody is short, Keira included: experience is compared, not level",
      E("$('#partyCareLevelUp').prop('disabled')")
      and "Everyone already has the 45,000" in E("$('#partyCareLevelText').text()"),
      E("$('#partyCareLevelText').text()"))

target = 15
threshold = 500 * target * (target - 1)
below = [i for i, xp in enumerate(before) if xp < threshold]
check("level 15 finds some short of it and some past it", 0 < len(below) < len(before),
      "%d of %d" % (len(below), len(before)))
E("$('#partyCareLevel').val('%d').change()" % target)
time.sleep(0.3)
level_text = E("$('#partyCareLevelText').text()")
check("the level row says whom it would raise, and to what",
      ("Raises %d of %d" % (len(below), len(before))) in level_text
      and "{:,}".format(threshold) in level_text, level_text)
check("past 12 it says the expansions decide", "White March" in level_text, level_text)

E("$('#partyCareLevelUp').click()")
time.sleep(0.4)
after = everyone("parseInt(c.stats.Experience.value, 10)")
check("everyone short of level 15 now has its 105,000 experience",
      all(after[i] == threshold for i in below), [after[i] for i in below])
check("and nobody lost any", all(a >= b for a, b in zip(after, before)),
      [(names[i], before[i], after[i]) for i in range(len(before)) if after[i] < before[i]])
check("the status line names them", all(names[i] in E("$('#partyCareStatus').text()")
                                         for i in below), E("$('#partyCareStatus').text()"))
check("nobody is left below it", E("$('#partyCareLevelUp').prop('disabled')"))

# ---- the console shares it ----------------------------------------------------
close_dialog()
E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.CONSOLE)")
time.sleep(1.0)
E("$('#consoleInput').val('HealParty'); $('#consoleForm').submit()")
time.sleep(0.5)
said = E("$('#consoleOutput').text()")
check("the console's HealParty knows the party is already healed",
      "Nobody in the active party is left to heal" in said, said[-160:])
check("HealParty is no longer listed as in-game only",
      not E("$('#referenceTable td').filter(function(){ return $(this).text() === 'HealParty'; })"
            ".length"))

# ---- Save, then read the file back --------------------------------------------
if save_and_reopen("EK party care"):
    check("written: the party is marked for a full refill", set(flags(True)) == {"true"},
          flags(True))
    check("written: nobody else is", set(flags(False)) <= {"false"}, flags(False))
    check("written: the experience", everyone("parseInt(c.stats.Experience.value, 10)") == after,
          everyone("parseInt(c.stats.Experience.value, 10)"))
    problems = E("((%s.validation || {}).problems || []).length" % D)
    check("written: the validator finds nothing wrong", problems == 0,
          E("JSON.stringify((%s.validation || {}).problems || [])" % D))

# ==== a later save: camping supplies ==========================================
E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
open_save(page, LATER)
time.sleep(1.5)
check("the later save is short of camping supplies", E("%s.campingSupplies" % D) == 3,
      E("%s.campingSupplies" % D))

open_dialog()
supplies_text = E("$('#partyCareSuppliesText').text()")
check("the supplies row says what is carried and the cap on Normal",
      supplies_text == "3 of 4, the most a party can carry on Normal.", supplies_text)
check("where everyone has the level-16 cap there is nobody to raise",
      E("$('#partyCareLevelUp').prop('disabled')"), E("$('#partyCareLevelText').text()"))
E("$('#partyCareRefill').click()")
time.sleep(0.3)
check("refill fills to the cap", E("%s.campingSupplies" % D) == 4, E("%s.campingSupplies" % D))
check("and says it is full",
      E("$('#partyCareRefill').text()") == "Full" and E("$('#partyCareRefill').prop('disabled')"))
close_dialog()

if save_and_reopen("EK camping supplies"):
    check("written: camping supplies", E("%s.campingSupplies" % D) == 4,
          E("%s.campingSupplies" % D))
    check("written: nobody was healed along the way", set(flags(True) + flags(False)) <= {"false"})

E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")

for name in sorted(set(os.listdir(SAVES)) - before_files):
    os.remove(os.path.join(SAVES, name))

sys.exit(summary(page))
