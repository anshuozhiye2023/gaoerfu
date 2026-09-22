"""扫描 Swift 源码里「普通字符串字面量跨行」的问题。

为什么需要这个检测器：
  Swift 的普通 "..." 不能跨行（要跨行必须用三引号），但写代码时很容易顺手
  把字典字面量塞进 "\\(...)" 里分成几行，编译器报的却是
      error: unterminated string literal
      error: cannot find ')' to match opening '(' in string interpolation
  而且行号指向字符串**起点**，不指向真正出错的那几行 —— 在 CI 上每来回一轮
  都要等好几分钟。这个脚本按「每行未转义引号数是否为奇数」粗略找出来，
  本地先过一遍再 push。

用法：
  python diag/swiftstr.py <包含 .swift 的目录>
  python diag/swiftstr.py GolfAppNative/ios
"""

import glob
import sys

BS = chr(92)      # 反斜杠（不在源码里直接写它，避免被外层 shell 转义吃掉）
QUOTE = chr(34)   # 双引号


def scan(path):
    src = open(path, encoding="utf-8").read()
    if QUOTE * 3 in src:
        print("NOTE: %s 含三引号多行字符串，本检测器可能误报，需人工确认" % path)
    bad = []
    for i, line in enumerate(src.splitlines(), 1):
        code = line
        # 粗略剥掉行尾 // 注释，减少误报
        idx = code.find("//")
        if idx > 0 and code[:idx].count(QUOTE) % 2 == 0:
            code = code[:idx]
        n = 0
        j = 0
        while j < len(code):
            if code[j] == BS:
                j += 2
                continue
            if code[j] == QUOTE:
                n += 1
            j += 1
        if n % 2 == 1:
            bad.append((i, line.rstrip()))
    return bad


def main(root):
    files = sorted(glob.glob(root + "/**/*.swift", recursive=True))
    if not files:
        print("没找到 .swift 文件，检查路径: %s" % root)
        return 1
    total = 0
    for f in files:
        bad = scan(f)
        if bad:
            total += len(bad)
            print("")
            print("X %s -- %d 行引号数为奇数（字符串可能跨行）:" % (f, len(bad)))
            for i, line in bad:
                print("   %4d| %s" % (i, line.strip()[:110]))
        else:
            print("OK %s -- 无跨行普通字符串" % f)
    print("")
    print("总计可疑行: %d" % total)
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
