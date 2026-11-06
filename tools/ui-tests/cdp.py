# A very small Chrome DevTools Protocol client for the editor. The editor opens
# a debugging port only when started with -Dek.debugPort (run_suites.py does).
import json
import time
import urllib.request

import websocket

from config import PORT


def page_socket(seconds=30):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            raw = urllib.request.urlopen(
                "http://127.0.0.1:%d/json/list" % PORT, timeout=4).read().decode()
            for page in json.loads(raw):
                if page.get("type") == "page":
                    return page["webSocketDebuggerUrl"]
        except Exception:
            pass
        time.sleep(1)
    raise SystemExit("no devtools page")


class Page:
    def __init__(self, capture=True):
        self.ws = websocket.create_connection(page_socket(), timeout=120)
        self.id = 0
        self.messages = []
        if capture:
            # This JCEF build predates the Log domain; Runtime alone still
            # reports uncaught exceptions and console.error calls.
            self.call("Runtime.enable")
            try:
                self.call("Console.enable")
            except Exception:
                pass

    def call(self, method, **params):
        self.id += 1
        self.ws.send(json.dumps(
            {"id": self.id, "method": method, "params": params}))
        while True:
            message = json.loads(self.ws.recv())
            if message.get("id") == self.id:
                if "error" in message:
                    raise RuntimeError("%s: %s" % (method, message["error"]))
                return message.get("result", {})
            self._note(message)

    def _note(self, message):
        method = message.get("method")
        if method == "Console.messageAdded":
            entry = message["params"]["message"]
            if entry.get("level") in ("error", "warning"):
                self.messages.append((entry["level"], entry.get("text", "")))
        elif method == "Runtime.exceptionThrown":
            detail = message["params"]["exceptionDetails"]
            self.messages.append(
                ("exception", detail.get("text", "") + " " + json.dumps(
                    detail.get("exception", {}).get("description", ""))))

    def drain(self):
        found, self.messages = self.messages, []
        return found

    def eval(self, expression):
        # No awaitPromise: the editor's Chrome 45 predates it and ignores it,
        # handing back a Promise as {}. Leave an answer on window and
        # wait_for it instead.
        result = self.call(
            "Runtime.evaluate", expression=expression, returnByValue=True)
        detail = result.get("exceptionDetails")
        if detail:
            raise RuntimeError("JS error: %s" % (
                detail.get("exception", {}).get("description")
                or detail.get("text")))
        return result.get("result", {}).get("value")

    def wait_for(self, expression, seconds=60, label=None):
        deadline = time.time() + seconds
        last = None
        while time.time() < deadline:
            try:
                if self.eval(expression):
                    return True
            except Exception as e:
                last = e
            time.sleep(0.5)
        raise TimeoutError("timed out waiting for %s%s"
                           % (label or expression, " (%s)" % last if last else ""))
