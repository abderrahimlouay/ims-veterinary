"""Dependency gate of ADR-155: fails on a High or Critical advisory (CVSS >= 7.0) that has a fixed version, unless the
advisory is in .github/osv-allowlist.json with a reason and an expiry date not yet passed. Lower or unscored advisories
and those without a fix are printed, not blocking. Usage: osv_gate.py <osv-scanner JSON> <allowlist JSON>."""
import datetime
import json
import sys

BLOCKING = 7.0


def fixed_versions(vulnerability):
    return sorted({event["fixed"] for affected in vulnerability.get("affected", [])
                   for ranges in affected.get("ranges", []) for event in ranges.get("events", []) if "fixed" in event})


def main(report_path, allowlist_path):
    report = json.load(open(report_path))
    today = datetime.date.today()
    allowed = {}
    for entry in json.load(open(allowlist_path)):
        if not entry.get("reason") or not entry.get("until"):
            sys.exit(f"Allowlist entry {entry.get('id')} needs a reason and an until date")
        allowed[entry["id"]] = datetime.date.fromisoformat(entry["until"])
    blocking = []
    for result in report.get("results", []):
        for package in result.get("packages", []):
            name = f"{package['package']['name']} {package['package']['version']}"
            vulns = {v["id"]: v for v in package.get("vulnerabilities", [])}
            for group in package.get("groups", []):
                ids = group.get("ids", [])
                score = float(group.get("max_severity") or 0)
                fixes = sorted({f for i in ids if i in vulns for f in fixed_versions(vulns[i])})
                listed = [i for i in ids if i in allowed]
                expired = [i for i in listed if allowed[i] < today]
                line = f"{name}: {', '.join(ids)} (CVSS {group.get('max_severity') or 'unscored'}; fixed in {', '.join(fixes) or 'none'})"
                if score >= BLOCKING and fixes and (not listed or expired):
                    blocking.append(line + (" - allowlist entry expired" if expired else ""))
                else:
                    print("note: " + line + (" - allowlisted" if listed else ""))
    if blocking:
        print("Blocking advisories (ADR-155):")
        print("\n".join("  " + b for b in blocking))
        sys.exit(1)
    print("No blocking advisory.")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
