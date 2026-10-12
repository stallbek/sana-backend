#!/usr/bin/env python3
"""Fail CI if PostgreSQL migration or undo checks were skipped or failed."""
from pathlib import Path
import xml.etree.ElementTree as ET

required = ["FlywayMigrationTest", "PostgresUndoIntegrationTest"]
for name in required:
    reports = list(Path("target/surefire-reports").glob("TEST-*" + name + ".xml"))
    if len(reports) != 1:
        raise SystemExit("Required PostgreSQL test report missing: " + name)
    suite = ET.parse(reports[0]).getroot()
    if int(suite.get("tests", "0")) == 0 or any(int(suite.get(key, "0")) for key in ["failures", "errors", "skipped"]):
        raise SystemExit("PostgreSQL tests did not fully pass: " + name)
print("Required PostgreSQL tests passed")
