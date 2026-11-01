#!/usr/bin/env python3
"""汇总 JUnit XML：总数、通过、失败、错误、跳过，并列出失败用例。

用法：junit-summary.py [根目录...]，默认扫描所有 build/test-results。
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

roots = sys.argv[1:] or ["."]
files = []
for root in roots:
    if os.path.isfile(root):
        files.append(root)
        continue
    pattern = "**/*.xml" if root != "." else "**/build/test-results/**/*.xml"
    files += glob.glob(os.path.join(root, pattern), recursive=True)

total = failures = errors = skipped = 0
failed = []
suites = 0
for path in sorted(set(files)):
    try:
        tree = ET.parse(path)
    except ET.ParseError:
        continue
    for suite in tree.iter("testsuite"):
        suites += 1
        for case in suite.iter("testcase"):
            total += 1
            name = f'{case.get("classname")}.{case.get("name")}'
            if case.find("failure") is not None:
                failures += 1
                failed.append(name)
            elif case.find("error") is not None:
                errors += 1
                failed.append(name)
            elif case.find("skipped") is not None:
                skipped += 1

passed = total - failures - errors - skipped
print(f"### Test summary ({len(set(files))} files, {suites} suites)")
print(f"- total {total} · passed {passed} · failed {failures} · errors {errors} · skipped {skipped}")
for name in failed[:200]:
    print(f"  - FAILED {name}")
