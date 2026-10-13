#!/usr/bin/env python3
"""G12 parity check: the Kotlin SDK's exposed canonical case names vs the
core's canonical Shared Facts (sync ADR-0028 / docs ADR-0003, gate G12).

Kotlin carries the canonical camelCase name of every case as a `code`
string literal (see TrueSealError.kt / Models.kt — "the Kotlin declaration
names follow Kotlin idiom, the codes carry the canonical names"). This
check therefore compares the CODE STRINGS, never the Kotlin identifiers —
mirroring the Swift reference (trueseal-sync-swift/scripts/check-parity.py)
with a Kotlin-flavoured extractor:

  * enum class entries:        NotJoined("notJoined")
  * sealed class case bodies:  override val code = "notJoined"

compared against the vendored, SHA-256-pinned `Parity/shared-facts.json`
(a byte-exact copy of trueseal-sync's generated docs/shared-facts.json —
see Parity/README.md). ANY drift fails the build: a renamed, missing or
extra case, a missing source file, or a missing type.

Usage:
  check-parity.py                 # compare, print a human report
  check-parity.py --report PATH   # also write the machine-readable report
                                  # (Release Evidence for the Conductor, G12)
  check-parity.py --self-test     # prove the check can fail: renamed /
                                  # missing / extra case fixtures must all
                                  # be caught (the negative test G12 needs)
  check-parity.py --facts PATH --sources-root PATH   # overrides (testing)

Pure stdlib; no network.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile

# Canonical list name -> (source file relative to the sources root, Kotlin type).
# One row per canonical list in shared-facts.json. A core case that gains no
# Kotlin counterpart (or vice versa) fails the check — exactly the G12 gate.
TYPE_MAP = {
    "errors":               ("lib/src/main/kotlin/dev/trueseal/sync/TrueSealError.kt", "TrueSealError"),
    "events":               ("lib/src/main/kotlin/dev/trueseal/sync/Models.kt", "TrueSealEvent"),
    "statuses":             ("lib/src/main/kotlin/dev/trueseal/sync/Models.kt", "GroupStatus"),
    "statusReasons":        ("lib/src/main/kotlin/dev/trueseal/sync/Models.kt", "StatusReason"),
    "connectionStates":     ("lib/src/main/kotlin/dev/trueseal/sync/Models.kt", "ConnectionState"),
    "deliveryIssues":       ("lib/src/main/kotlin/dev/trueseal/sync/Models.kt", "DeliveryIssue"),
    "unreadableReasons":    ("lib/src/main/kotlin/dev/trueseal/sync/Models.kt", "UnreadableReason"),
    "sendFailedReasons":    ("lib/src/main/kotlin/dev/trueseal/sync/Models.kt", "SendFailedReason"),
    "admissionDropReasons": ("lib/src/main/kotlin/dev/trueseal/sync/Models.kt", "AdmissionDropReason"),
}

TYPE_DECL_RE = re.compile(
    r"\b(sealed\s+class|enum\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
# sealed class case bodies: override val code = "<canonical>"
SEALED_CODE_RE = re.compile(r'override\s+val\s+code\s*=\s*"([^"]+)"')
# enum class entries at top depth: NotJoined("notJoined"),
ENUM_ENTRY_RE = re.compile(r'^\s*[A-Za-z_][A-Za-z0-9_]*\s*\(\s*"([^"]+)"')


class ParityError(Exception):
    pass


def strip_block_comments(text):
    """Remove /* */ block comments (the enum bodies we parse carry no
    comment-like string literals; // line comments never contain a code
    literal pattern that would confuse the extractors)."""
    return re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)


def type_body(text, type_name):
    """Return (kind, body) for `sealed class|enum class <type_name>` — kind
    is 'sealed' or 'enum'; body is the text between its own braces."""
    for match in TYPE_DECL_RE.finditer(text):
        if match.group(2) != type_name:
            continue
        kind = "sealed" if match.group(1).startswith("sealed") else "enum"
        brace = text.find("{", match.end())
        if brace == -1:
            raise ParityError(f"type {type_name}: declaration without a body")
        depth = 0
        for i in range(brace, len(text)):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    return kind, text[brace + 1 : i]
        raise ParityError(f"type {type_name}: unbalanced braces")
    raise ParityError(f"type {type_name}: not found")


def code_names(kind, body):
    """Canonical code strings declared inside the type body.

    The two declaration shapes each get their OWN extractor — mixing them
    is wrong: a sealed class body's top-depth lines include superclass
    constructor calls (`TrueSealError("<message>")`) that the enum-entry
    pattern would misread as cases, and an enum body's entries never carry
    an `override val code` line.

      - sealed classes: each case carries `override val code = "<code>"`
        (at any nesting depth — data classes/objects inside the body);
      - enum classes: each entry is `Name("<code>")` on its own line at
        the body's top depth (deeper lines are entries' bodies, ignored).
    """
    if kind == "sealed":
        return list(SEALED_CODE_RE.findall(body))
    names = []
    depth = 0
    for line in body.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("//"):
            continue
        if depth == 0:
            m = ENUM_ENTRY_RE.match(line)
            if m:
                names.append(m.group(1))
        depth += line.count("{") - line.count("}")
    return names


def sha256_file(path):
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def compare(sources_root, facts_path):
    """Returns (ok, report_dict). Never raises for drift — drift is data;
    raises ParityError only for structural problems (missing files/types)."""
    with open(facts_path, "rb") as fh:
        facts = json.load(fh)

    sections = []
    ok = True
    for list_name, (rel_path, type_name) in TYPE_MAP.items():
        path = os.path.join(sources_root, rel_path)
        if not os.path.isfile(path):
            raise ParityError(f"source file missing: {rel_path}")
        canonical = facts.get(list_name)
        if canonical is None:
            raise ParityError(f"shared-facts.json has no '{list_name}' list")
        with open(path, "r", encoding="utf-8") as fh:
            source = strip_block_comments(fh.read())
        kind, body = type_body(source, type_name)
        sdk = code_names(kind, body)

        missing = [c for c in canonical if c not in sdk]
        extra = [s for s in sdk if s not in canonical]
        dupes = sorted({s for s in sdk if sdk.count(s) > 1})
        section_ok = not missing and not extra and not dupes
        ok = ok and section_ok
        sections.append(
            {
                "list": list_name,
                "type": type_name,
                "source": rel_path,
                "canonicalCount": len(canonical),
                "sdkCount": len(sdk),
                "missingInSdk": missing,
                "extraInSdk": extra,
                "duplicateInSdk": dupes,
                "ok": section_ok,
            }
        )

    report = {
        "check": "g12-sdk-parity",
        "sdk": "trueseal-sync-kotlin",
        "surface": "canonical `code` strings (Kotlin identifiers follow idiom)",
        "facts": {
            "path": os.path.relpath(facts_path),
            "sha256": sha256_file(facts_path),
            "sdkVersion": facts.get("sdkVersion"),
        },
        "sections": sections,
        "ok": ok,
    }
    return ok, report


def print_human(report):
    for s in report["sections"]:
        mark = "ok " if s["ok"] else "FAIL"
        line = f"[{mark}] {s['list']:<22} {s['type']:<22} canonical={s['canonicalCount']} sdk={s['sdkCount']}"
        if not s["ok"]:
            detail = []
            if s["missingInSdk"]:
                detail.append("missing: " + ", ".join(s["missingInSdk"]))
            if s["extraInSdk"]:
                detail.append("extra: " + ", ".join(s["extraInSdk"]))
            if s["duplicateInSdk"]:
                detail.append("duplicate: " + ", ".join(s["duplicateInSdk"]))
            line += "  (" + "; ".join(detail) + ")"
        print(line)
    print(f"facts sha256: {report['facts']['sha256']} (sdkVersion {report['facts']['sdkVersion']})")
    print("G12 parity: " + ("PASS" if report["ok"] else "FAIL"))


def self_test(sources_root, facts_path):
    """The negative test G12 requires: the check MUST catch a renamed, a
    missing and an extra case. Each mutation is applied to a temp copy of
    the sources; every one must flip the verdict to FAIL (and the pristine
    tree must PASS, or this whole exercise is meaningless)."""
    ok, _ = compare(sources_root, facts_path)
    if not ok:
        print("self-test: FAIL — the pristine tree does not pass; nothing to mutate against")
        return False

    error_file = os.path.join(TYPE_MAP["errors"][0])
    with open(os.path.join(sources_root, error_file), "r", encoding="utf-8") as fh:
        original = fh.read()

    anchor = 'override val code = "manifestVersionExhausted"'
    mutations = {
        "renamed case": original.replace(
            anchor, 'override val code = "manifestVersionExhaustedRenamed"', 1
        ),
        "missing case": original.replace(f"        {anchor}\n", "", 1),
        "extra case": original.replace(
            f"        {anchor}\n",
            f"        {anchor}\n"
            '        override val code = "anExtraCaseTheCoreDoesNotHave"\n',
            1,
        ),
    }

    all_caught = True
    for name, mutated in mutations.items():
        if mutated == original:
            print(f"self-test: FAIL — mutation '{name}' did not apply (source pattern drifted)")
            all_caught = False
            continue
        with tempfile.TemporaryDirectory() as tmp:
            shutil.copytree(sources_root, tmp, dirs_exist_ok=True)
            with open(os.path.join(tmp, error_file), "w", encoding="utf-8") as fh:
                fh.write(mutated)
            try:
                caught_ok, _ = compare(tmp, facts_path)
            except ParityError:
                caught_ok = False
        if caught_ok:
            print(f"self-test: FAIL — mutation '{name}' was NOT caught")
            all_caught = False
        else:
            print(f"self-test: mutation '{name}' correctly caught")
    return all_caught


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--facts", default="Parity/shared-facts.json")
    parser.add_argument("--sources-root", default=".")
    parser.add_argument("--report", default=None)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        sys.exit(0 if self_test(args.sources_root, args.facts) else 1)

    try:
        ok, report = compare(args.sources_root, args.facts)
    except ParityError as exc:
        print(f"G12 parity: FAIL — {exc}")
        sys.exit(1)

    print_human(report)
    if args.report:
        with open(args.report, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2)
            fh.write("\n")
        print(f"report written: {args.report}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
