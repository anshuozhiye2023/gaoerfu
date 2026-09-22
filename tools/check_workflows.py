# -*- coding: utf-8 -*-
"""校验 .github/workflows/*.yml 的 YAML 结构与关键字段。

YAML 对缩进极敏感，一次缩进错就要等一轮 CI 才报出来（macOS runner 还要排队）。
改完 workflow 先在本地跑这个。

用法：
  python tools/check_workflows.py
"""
import glob
import os
import sys

try:
    import yaml
except ImportError:
    print("缺 pyyaml：pip install pyyaml")
    sys.exit(2)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATTERN = os.path.join(ROOT, ".github", "workflows", "*.yml")

bad = 0
for path in sorted(glob.glob(PATTERN)):
    name = os.path.basename(path)
    try:
        doc = yaml.safe_load(open(path, encoding="utf-8"))
    except Exception as e:
        print("✗ %s  YAML 解析失败：%s" % (name, e))
        bad += 1
        continue

    jobs = doc.get("jobs") or {}
    if not jobs:
        print("✗ %s  没有 jobs" % name)
        bad += 1
        continue

    print("✓ %s" % name)
    # PyYAML 会把裸 on: 解析成布尔 True，两种键都认一下
    trig = doc.get("on", doc.get(True))
    if isinstance(trig, dict):
        print("    triggers   : %s" % ", ".join(sorted(trig)))
    elif isinstance(trig, list):
        print("    triggers   : %s" % ", ".join(trig))
    else:
        print("    triggers   : %s" % trig)

    for jid, job in jobs.items():
        runner = job.get("runs-on", "?")
        steps = job.get("steps") or []
        print("    job %-8s runs-on=%-12s steps=%d" % (jid, runner, len(steps)))
        if not runner.startswith("macos-26"):
            print("      ⚠ runs-on 不是 macos-26：App Store 只收 Xcode 26+ 构建的包")
        for i, s in enumerate(steps, 1):
            label = s.get("name") or s.get("uses") or "<无名>"
            print("      %2d. %s" % (i, label))

print()
print("结果：%d 个文件有问题" % bad if bad else "结果：全部通过")
sys.exit(1 if bad else 0)
