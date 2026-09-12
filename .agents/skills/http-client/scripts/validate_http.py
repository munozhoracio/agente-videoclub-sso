#!/usr/bin/env python3
"""
Linter & Validator for .http files.
Checks compliance with authoring guidelines (VS Code REST Client & IntelliJ).
"""

import json
import re
import sys


def validate_file(filepath):
    with open(filepath, "r", encoding="utf-8") as f:
        lines = f.readlines()

    errors = []
    warnings = []

    in_body = False
    body_lines = []
    current_req = None
    has_seen_empty_line = False

    for idx, raw_line in enumerate(lines, start=1):
        line = raw_line.rstrip("\r\n")

        if line.startswith("###"):
            # Check previous request body
            if body_lines:
                _check_body(current_req, body_lines, errors, warnings)
                body_lines = []

            in_body = False
            has_seen_empty_line = False
            current_req = f"Line {idx}"

            # Check rule 1: Text after ###
            after_hash = line[3:].strip()
            if after_hash and not after_hash.startswith("#"):
                warnings.append(f"Line {idx}: '###' contains trailing text '{after_hash}'. Keep '###' alone on its line when using # @name.")
            continue

        if not in_body:
            name_m = re.match(r"^\s*#\s*@name\s+(\S+)", line)
            if name_m:
                current_req = f"@{name_m.group(1)} (Line {idx})"

            if line.strip() == "" and current_req:
                in_body = True
                has_seen_empty_line = True
        else:
            # Inside body
            if line.strip().startswith("> {%"):
                errors.append(f"Line {idx} [{current_req}]: IntelliJ '> {{% ... %}}' test script detected after body. In VS Code REST Client this corrupts the payload.")
            elif line.strip().startswith("#") and "}" in "".join(l for _, l in body_lines):
                errors.append(f"Line {idx} [{current_req}]: Comment detected after body closing brace before '###'. This will be treated as part of the body and cause a 400 Bad Request.")
            body_lines.append((idx, line))

    if body_lines:
        _check_body(current_req, body_lines, errors, warnings)

    print(f"\nValidating: {filepath}")
    if errors:
        print(f"❌ Found {len(errors)} error(s):")
        for e in errors:
            print(f"  • {e}")
    else:
        print("✔ No critical authoring errors found.")

    if warnings:
        print(f"⚠ Found {len(warnings)} warning(s):")
        for w in warnings:
            print(f"  • {w}")

    return len(errors) == 0


def _check_body(req_name, body_tuples, errors, warnings):
    raw_text = "\n".join(l for _, l in body_tuples).strip()
    if not raw_text or not raw_text.startswith("{"):
        return

    # Check valid JSON
    try:
        json.loads(raw_text)
    except json.JSONDecodeError as e:
        first_line = body_tuples[0][0] if body_tuples else "?"
        errors.append(f"Around line {first_line} [{req_name}]: Body is invalid JSON: {e}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 validate_http.py <path-to-file.http>")
        sys.exit(1)

    ok = validate_file(sys.argv[1])
    sys.exit(0 if ok else 1)
