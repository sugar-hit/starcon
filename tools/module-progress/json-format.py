#!/usr/bin/env python3
"""把 stdin 的 JSON 按 codegen 同款 json.dumps 重新序列化后写到 stdout。

**为什么需要这个脚本**：并入驱动器（merge-union.mjs）求并后要把 .openapi.json 写回磁盘，
而它的输出必须与 codegen 写出的快照**逐字节相同** —— 否则每并入一次契约文件，
CI 的「重新生成后无差异」检查就红一次。JS 的 JSON.stringify 做不到这件事：

  - 数字：``JSON.stringify(1e-6)`` → ``0.000001``，而 Python 的 ``json.dumps`` → ``1e-06``
    （``1e-8`` 同理：JS 写 ``1e-8``，Python 写 ``1e-08``）；
  - 键序：JSON.stringify 是插入序，codegen 那边是 ``sort_keys=True`` 的字典序；
  - 末尾：codegen 补一个 ``\\n``，JSON.stringify 不补。

所以这里只做一件事：用**与生成器完全相同的 json.dumps 调用**重新序列化。
数字怎么写、键怎么排、末尾有没有换行，全部由同一个 json.dumps 决定 —— 只有一份真相。
生成器那侧的等价调用见 tools/codegen/catalog-contracts.py 里的 snapshot.write_text(...)。
"""
import json
import sys


def main() -> int:
    data = json.load(sys.stdin)
    sys.stdout.write(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
