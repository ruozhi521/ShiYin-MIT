#!/usr/bin/env python3
"""校验 JVM 单元测试结果（CI 用）。

存在的理由：`gradle testDebugUnitTest` 在两种情况下会「看起来成功但其实没测什么」——
  1. 测试被静默过滤（例如配置改动导致某个 sourceSet 不再参与编译），实际跑了 0 个用例；
  2. 报告目录残留上一次的结果，这次没生成却读到旧文件。
两者在 CI 日志里都表现为 BUILD SUCCESSFUL。所以这里自己读 XML 报告做断言。

用法：python3 .github/scripts/check-test-results.py <结果目录> [最少用例数]
"""
import glob
import os
import re
import sys

MIN_CASES_DEFAULT = 400

SUITE_RE = re.compile(
    r'<testsuite\b[^>]*\btests="(\d+)"[^>]*\bfailures="(\d+)"[^>]*\berrors="(\d+)"'
)


def main() -> int:
    results_dir = sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/testDebugUnitTest"
    min_cases = int(sys.argv[2]) if len(sys.argv) > 2 else MIN_CASES_DEFAULT

    files = sorted(glob.glob(os.path.join(results_dir, "TEST-*.xml")))
    if not files:
        print(f"::error::没有找到任何测试报告（{results_dir}）—— 测试可能根本没跑")
        return 1

    total = failures = errors = 0
    failed_suites = []
    for path in files:
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError as exc:
            print(f"::error::读不了 {path}: {exc}")
            return 1
        match = SUITE_RE.search(text)
        if not match:
            # 报告存在但格式不对：多半是只写了一半就中断，属于失败
            print(f"::error::{os.path.basename(path)} 里找不到 testsuite 统计")
            return 1
        t, f, e = map(int, match.groups())
        total += t
        failures += f
        errors += e
        if f or e:
            failed_suites.append(os.path.basename(path))

    print(f"测试套件 {len(files)} 个，用例 {total} 个，失败 {failures}，错误 {errors}")

    if failures or errors:
        print("::error::有用例失败：" + ", ".join(failed_suites))
        for path in files:
            if os.path.basename(path) not in failed_suites:
                continue
            text = open(path, encoding="utf-8", errors="replace").read()
            # 把失败用例名与消息打到日志里，避免只看到「BUILD FAILED」还要去翻 artifact
            for m in re.finditer(
                r'<testcase\b[^>]*\bname="([^"]+)"[^>]*>\s*<(?:failure|error)\b[^>]*message="([^"]{0,400})',
                text,
            ):
                print(f"  ✗ {m.group(1)}: {m.group(2)}")
        return 1

    if total < min_cases:
        print(
            f"::error::用例数 {total} 少于下限 {min_cases} —— "
            "可能被静默过滤，请检查测试是否真的在跑"
        )
        return 1

    print("✅ 单元测试全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
