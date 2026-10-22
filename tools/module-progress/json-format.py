#!/usr/bin/env python3
"""JSON 快照的统一入口：canonical 序列化（默认模式）与 OpenAPI 三方求并（``--merge``）。

**为什么需要这个脚本**：并入驱动器（merge-union.mjs）求并后要把 .openapi.json 写回磁盘，
而它的输出必须与 codegen 写出的快照**逐字节相同** —— 否则每并入一次契约文件，
CI 的「重新生成后无差异」检查就红一次。生成器那侧的等价调用见
tools/codegen/catalog-contracts.py 的 ``snapshot.write_text(...)``：

    json.dumps(spec, ensure_ascii=False, indent=2, sort_keys=True) + "\\n"

**为什么求并也搬到了 Python 侧（2026-10-23 修）**
--------------------------------------------------
JSON 里有两类「值相等、字面量不同」的数字，而 JavaScript 分不出来：

  * ``0.0``（float）vs ``0``（int）—— 值相等，字节不同；
  * ``1e-06`` vs ``0.000001`` —— 值相等，字节不同。

JS 的 JSON 没有 int/float 之分：``JSON.parse("0.0")`` 得到的 double，再
``JSON.stringify`` 就写成 ``0``；``JSON.parse("1e-06")`` 再 stringify 就写成 ``0.000001``。
所以**只要求并经过 JS**，这些字面量就在 JS 序列化那一步被抹掉了 ——
Python 端 ``json.load`` 拿到的已经是 int ``0``，写出来自然是 ``0``。

2026-10-23 的实测（batch-68）：6 个 channel schema 的 ``minimum`` 从 ``0.0`` 塌成 ``0``，
L2 契约闸红。第一次真的触发，是因为那批有 3 条 channel 车道同时改同一个
channel.openapi.json —— 此前每批只有一条车道改它，走的是单边快进，驱动器根本没被调用。

Python 的 ``json`` 模块天生分得清 int 与 float（``json.load`` 保留类型），
所以**整个 .openapi.json 求并都放在这边做**：解析、求并、序列化全在 Python，
与 codegen 用的是同一套 ``json.load`` / ``json.dumps`` 调用 ⇒ 只有一份真相。

用法
----
    json-format.py                    # stdin 的 JSON → stdout 的 canonical 字节
    json-format.py --merge B O T      # 三方求并（B/O/T 是文件路径）→ stdout 的 canonical 字节

退出码：0 成功 / 3「输入不是 JSON」（调用方走行级兜底并集）/ 1 其他错误。
"""
import json
import sys
from typing import Any, Dict, List, Optional

# 与 codegen 的 json.dumps 调用逐参数一致（见 tools/codegen/catalog-contracts.py）。
CANONICAL_DUMP = dict(ensure_ascii=False, indent=2, sort_keys=True)

# components 下需要**深合并**的子键；其余子键是「theirs 优先」的浅合并。
DEEP_MERGE_KEYS = ("schemas", "responses", "parameters", "examples", "requestBodies")

# 「输入不是 JSON」的退出码：调用方（merge-union.mjs）据此走行级兜底并集。
EXIT_NOT_JSON = 3


def format_json(data: Any) -> str:
    """把内存里的结构写成 codegen 同款的 canonical 字节（末尾带一个换行）。"""
    return json.dumps(data, **CANONICAL_DUMP) + "\n"


def _reject_constant(name: str):
    """NaN / Infinity / -Infinity 一律判为「不是 JSON」。

    JS 的 ``JSON.parse`` 拒绝这三个字面量；Python 的 ``json`` 默认接受。
    这里显式拒绝，好让「是不是 JSON」这件事在两侧口径一致 ——
    否则 Python 会把一个 JS 侧当普通文本的坏文件合并成一个非法 JSON。
    """
    raise ValueError(f"not a JSON document: {name}")


def loads(text: str) -> Any:
    """按 ``JSON.parse`` 的口径解析：拒绝 NaN/Infinity，拒绝字符串里的裸控制字符。"""
    return json.loads(text, parse_constant=_reject_constant)


def _read_text(path: str) -> Optional[str]:
    """读文件（UTF-8，坏字节按 U+FFFD 替换 —— 与 JS 的 readFileSync(path,'utf8') 同口径）。"""
    if not path:
        return None
    try:
        with open(path, "rb") as fh:
            return fh.read().decode("utf-8", errors="replace")
    except OSError:
        return None


def _canonical(value: Any) -> str:
    """比较用的规范化文本：键序无关（sort_keys），但 int 与 float 分得开（0 vs 0.0）。"""
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def _same(a: Any, b: Any) -> bool:
    """两个值是否「同一个值」。

    与旧 JS 版的 ``JSON.stringify(a) === JSON.stringify(b)`` 有一处**刻意**的差别：
    JS 的比较分不出 ``0`` 与 ``0.0``，这里分得开。

    为什么这是改进而不是回归：三方求和的裁判是 base ——「谁相对 base 真的改了它」。
    int/float 分不开时，``0`` → ``0.0`` 这种**只有一个字面量变了**的改动会被当成
    「两边都一样」，于是把对方那侧的旧值留下来 = 静默丢掉一侧的改动。
    分得开之后，三方语义才是名副其实的。
    """
    return _canonical(a) == _canonical(b)


def _as_map(value: Any) -> Dict[str, Any]:
    """只有**普通对象**能按键求并；数组 / null / 标量都当空对象（与旧 JS 版同口径）。"""
    return value if isinstance(value, dict) else {}


def merge_entries(
    base: Dict[str, Any],
    ours: Dict[str, Any],
    theirs: Dict[str, Any],
    label: str,
    conflicts: List[str],
) -> Dict[str, Any]:
    """逐键做三方求并（base 才是「谁真的改了它」的裁判）。

    规则（与旧 JS 版一致）：
      只有一边相对 base 变了 → 取变的那边；
      两边都变了同一个键   → 取 theirs，并把键名记进 conflicts 报出来。

    返回的键序 = ours 的插入序 + theirs 独有的键（Python 3.7+ 的 dict 保序）。
    """
    order = list(ours.keys())
    for key in theirs:
        if key not in order:
            order.append(key)

    out: Dict[str, Any] = {}
    for key in order:
        in_ours = key in ours
        in_theirs = key in theirs

        if not in_theirs:
            # 只有我们这边有（对方删了的不自动跟随，删行交给人工）
            if in_ours:
                out[key] = ours[key]
            continue
        if not in_ours:
            out[key] = theirs[key]
            continue

        o, t = ours[key], theirs[key]
        if _same(t, o):
            out[key] = t
            continue
        if key in base:
            b = base[key]
            if _same(o, b):
                out[key] = t  # 只有 theirs 相对基线变了
                continue
            if _same(t, b):
                out[key] = o  # 只有 ours 相对基线变了（并行交付的常见情况）
                continue
        # 两边都动了同一个键：取 theirs，但必须报出来，不能静默
        conflicts.append(f"{label}:{key}")
        out[key] = t

    return out


def merge_obj(
    base: Any,
    ours: Any,
    theirs: Any,
    label: str,
    conflicts: List[str],
) -> Dict[str, Any]:
    """按键求并两个普通对象。"""
    return merge_entries(_as_map(base), _as_map(ours), _as_map(theirs), label, conflicts)


def merge_openapi(
    base: Any,
    ours: Any,
    theirs: Any,
    conflicts: List[str],
) -> Any:
    """OpenAPI 快照的三方求并（paths / components.schemas 等按键求并）。

    顶层其它键**只取 ours**（与旧 JS 版同口径）—— ours 是「当前分支」，theirs 只贡献
    它新增的 path / schema。
    """
    if not isinstance(ours, dict):
        return ours

    a = ours
    b = theirs if isinstance(theirs, dict) else {}
    g = base if isinstance(base, dict) else {}

    if a.get("paths") or b.get("paths"):
        a["paths"] = merge_obj(g.get("paths"), a.get("paths"), b.get("paths"), "paths", conflicts)

    if a.get("components") or b.get("components"):
        # 注意：components 本身要浅合并，但 components.schemas 要深合并。
        # 早期版本先做了 {...a.components, ...b.components} 把 a.schemas 直接顶掉，
        # 导致车道 A 的 schema 静默丢失 —— 深合并必须在覆盖之前取到两边的原值。
        ac = _as_map(a.get("components"))
        bc = _as_map(b.get("components"))
        gc = _as_map(g.get("components"))
        out: Dict[str, Any] = dict(ac)
        keys = list(ac.keys()) + [k for k in bc if k not in ac]
        for key in keys:
            if key in DEEP_MERGE_KEYS:
                out[key] = merge_obj(gc.get(key), ac.get(key), bc.get(key), f"components.{key}", conflicts)
                continue
            theirs_value = bc.get(key)
            if theirs_value is None:
                # JS 的 ?? 语义：theirs 为 null/undefined 时退回 ours
                value = ac.get(key)
                if value is None and key not in ac:
                    continue  # 两边都没有实值 ⇒ 该键整体消失（与 JSON.stringify 一致）
                out[key] = value
                continue
            out[key] = theirs_value
        a["components"] = out

    return a


def _merge_mode(base_path: str, ours_path: str, theirs_path: str) -> int:
    ours_text = _read_text(ours_path)
    theirs_text = _read_text(theirs_path)
    if ours_text is None or theirs_text is None:
        sys.stderr.write("json-format: 读不到待合并文件\n")
        return 1

    try:
        ours = loads(ours_text)
    except ValueError:
        return EXIT_NOT_JSON
    try:
        theirs = loads(theirs_text)
    except ValueError:
        return EXIT_NOT_JSON

    # base 缺失 / 不是 JSON ⇒ 当成空基线（没有基线可比 = 对方新增的都是 theirs 的）
    base: Any = {}
    base_text = _read_text(base_path)
    if base_text is not None and base_text.strip():
        try:
            base = loads(base_text)
        except ValueError:
            base = {}

    conflicts: List[str] = []
    merged = merge_openapi(base, ours, theirs, conflicts)
    sys.stdout.buffer.write(format_json(merged).encode("utf-8"))
    if conflicts:
        sys.stderr.write("CONFLICT_KEYS " + " ".join(conflicts) + "\n")
    return 0


def main(argv: List[str]) -> int:
    if len(argv) > 1 and argv[1] == "--merge":
        if len(argv) != 5:
            sys.stderr.write("usage: json-format.py --merge <base> <ours> <theirs>\n")
            return 1
        return _merge_mode(argv[2], argv[3], argv[4])

    raw = sys.stdin.buffer.read().decode("utf-8", errors="replace")
    try:
        data = loads(raw)
    except ValueError as exc:
        sys.stderr.write(f"json-format: stdin 不是合法 JSON：{exc}\n")
        return 1
    sys.stdout.buffer.write(format_json(data).encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
