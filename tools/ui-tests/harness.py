# Shared scaffolding for driving the editor: a pass/fail log, and a way to open
# a save by file name that waits for the view to actually finish drawing.
import json
import sys
import time

import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cdp import Page

RESULTS = []

# The app serves its UI through a JCEF scheme handler at this exact URL.
# Navigating to the real on-disk path instead loses the window.get*/save*
# bridge functions: the page loads but every call into Java silently does
# nothing.


def check(name, ok, detail=""):
    RESULTS.append((bool(ok), name, str(detail)[:300]))
    print("%s  %-52s %s" % ("PASS" if ok else "FAIL", name, str(detail)[:160]),
          flush=True)
    return bool(ok)


def summary(page=None):
    if page is not None:
        for kind, text in page.drain():
            check("no %s in the browser console" % kind, False, text)
    failed = [r for r in RESULTS if not r[0]]
    print("\n==== %d checks, %d failed ====" % (len(RESULTS), len(failed)))
    for _, name, detail in failed:
        print("  FAIL %s :: %s" % (name, detail))
    return len(failed)


def boot(page, seconds=120):
    page.wait_for("typeof Eternity !== 'undefined' && !!Eternity.SavedGame",
                  seconds, "the app to start")
    page.wait_for("$('#saveBlocks .save-info').length > 0", seconds, "the save list")


def to_list(page):
    page.eval("Eternity.render({listView: true})")
    page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")


def open_save(page, needle, seconds=420):
    result = page.eval("""(function(){
      var index = -1;
      Eternity.SaveSearch.state.saves.forEach(function(s, i){
        if ((s.absolutePath || '').indexOf(%s) >= 0) index = i;
      });
      if (index < 0) return 'not listed';
      Eternity.SaveSearch.open(Eternity.SaveSearch.state.saves[index], index);
      return 'opening';
    })()""" % json.dumps(needle))

    if result != 'opening':
        raise TimeoutError("save %r %s" % (needle, result))

    # saveData lands a tick before the save view finishes drawing, so wait for
    # the sidebar too or every later assertion races the render.
    page.wait_for("!!(Eternity.SavedGame.state.saveData "
                  "&& Eternity.SavedGame.state.saveData.characters)", seconds,
                  "the save to open")
    page.wait_for("Eternity.state.saveView && $('#characterList li').length > 0",
                  60, "the save view to draw")
    return page.eval("Eternity.SavedGame.state.saveData.characters.length")


def reload_ui(seconds=180):
    """Re-read src/ui from disk over CDP."""
    for _ in range(3):
        try:
            page = Page()
            page.call("Page.enable")
            # The app loads its UI from an absolute file: URL; reload that.
            page.call("Page.navigate", url=page.eval("window.location.href"))
            # The socket outlives the document it was attached to, and while it
            # does the debugger stops listing the page at all.
            try:
                page.ws.close()
            except Exception:
                pass
            break
        except (Exception, SystemExit):
            time.sleep(2)

    deadline = time.time() + seconds
    page = None
    last = None
    while time.time() < deadline:
        time.sleep(3)
        candidate = None
        try:
            candidate = Page()
            if candidate.eval("typeof Eternity !== 'undefined'"
                              " && !!Eternity.SavedGame"):
                page = candidate
                break
        except (Exception, SystemExit) as e:
            last = e
        finally:
            # This JCEF build tolerates only a handful of live CDP sockets, so
            # a poll that leaks one per attempt stops answering long before the
            # deadline.
            if page is None and candidate is not None:
                try:
                    candidate.ws.close()
                except Exception:
                    pass

    if page is None:
        raise TimeoutError("the UI did not come back after a reload (%s)" % last)

    page.wait_for("$('#saveBlocks .save-info').length > 0", seconds, "the save list")
    return page
