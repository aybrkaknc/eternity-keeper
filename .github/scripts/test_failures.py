# Turns a failed Maven test run into GitHub annotations.
#
# A run's log is visible only to people signed in to GitHub, but its
# annotations are public, on the run's page and through the API. So when the
# tests fail, each failing test becomes one annotation carrying its message and
# the top of its stack; when no test report exists at all (the build itself
# failed), the [ERROR] lines of the Maven output do instead.
import glob
import os
import sys
import xml.etree.ElementTree as ET

REPORTS = os.path.join("target", "surefire-reports", "TEST-*.xml")
MAVEN_LOG = "mvn.log"
MAX_ANNOTATIONS = 20


def escape(text):
    # Workflow commands take one line; these are the escapes GitHub decodes.
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def annotate(title, message):
    print("::error title=%s::%s" % (escape(title)[:200], escape(message)[:3000]))


def from_reports():
    count = 0
    for report in sorted(glob.glob(REPORTS)):
        for case in ET.parse(report).getroot().iter("testcase"):
            for kind in ("failure", "error"):
                problem = case.find(kind)
                if problem is None:
                    continue
                count += 1
                if count > MAX_ANNOTATIONS:
                    continue
                stack = (problem.text or "").strip().splitlines()[:12]
                annotate("%s.%s" % (case.get("classname"), case.get("name")),
                         "%s\n%s" % (problem.get("message") or kind, "\n".join(stack)))
    return count


def from_maven_log():
    if not os.path.isfile(MAVEN_LOG):
        return 0
    with open(MAVEN_LOG, encoding="utf-8", errors="replace") as handle:
        errors = [line.rstrip() for line in handle if line.startswith("[ERROR]")]
    if errors:
        annotate("Maven build failed", "\n".join(errors[:40]))
    return len(errors)


def main():
    failures = from_reports()
    if failures:
        print("%d failing tests annotated" % failures)
    elif not from_maven_log():
        annotate("Tests failed", "No test report and no [ERROR] lines; see the log.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
