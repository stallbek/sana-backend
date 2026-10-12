#!/usr/bin/env python3
"""Fail the security gate on CodeQL errors or high/critical security results."""
from pathlib import Path
import json

reports = list(Path("target/codeql").glob("*.sarif"))
if not reports:
    raise SystemExit("CodeQL report missing")
findings = 0
for report in reports:
    for run in json.loads(report.read_text())["runs"]:
        rules = {rule["id"]: rule for rule in run["tool"]["driver"].get("rules", [])}
        for invocation in run.get("invocations", []):
            if not invocation.get("executionSuccessful", True):
                raise SystemExit("CodeQL analysis failed")
        for result in run.get("results", []):
            rule = rules.get(result["ruleId"], {})
            severity = float(rule.get("properties", {}).get("security-severity", "0"))
            if severity >= 7 or result.get("level") == "error":
                findings += 1
                print(result["ruleId"], "requires remediation")
if findings:
    raise SystemExit("CodeQL security gate failed")
print("No high/critical CodeQL findings")
