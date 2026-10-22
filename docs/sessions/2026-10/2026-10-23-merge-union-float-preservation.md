# 2026-10-23 merge-union 数值字面量保真修复（工具链车道 merge-union-float-68）

## 决策摘要

`.openapi.json` 的**求并整体搬到 Python 侧**（`tools/module-progress/json-format.py --merge`），
JS 侧只保留 `contracts/*.ts` 与台账 markdown 的求并。

驱动合并后写出的字节，现在与 codegen 对同一份 spec 的输出**逐字节相同** ——
包括 float 字面量（`0.0`）、指数记法（`1e-06`）、键序、缩进、末尾换行。

本车道**不碰任何契约产物**（`packages/api-contracts/**`），只修工具链。

## 根因

驱动器的求并链路是：

```
JS: JSON.parse(原文) → 合并 → JSON.stringify(value) → spawnSync('python3', json-format.py) → 写盘
```

**问题出在 `JSON.stringify` 这一步**：JavaScript 的数字只有一种类型。

```
JS   : JSON.parse + JSON.stringify  →  {"m":0, "n":0.000001, "i":0, "big":1e-7, "neg":0}
Python: json.loads + json.dumps      →  {"i": 0, "m": 0.0, "n": 1e-06}
```

`JSON.parse("0.0")` 得到的也是一个 double，但再序列化时**不再带小数点**；
Python 端 `json.load` 读到的是 **int `0`**，写出来就是 `0`。
而 codegen（`tools/codegen/catalog-contracts.py:205`）**不经 JS**：

```
Python: json.load(spec) → json.dumps(spec, ensure_ascii=False, indent=2, sort_keys=True) → 写盘
```

Python 的 `json.load` **区分 int 与 float**：`0.0` 是 float → 写回 `0.0`。
⇒ 驱动器与 codegen 的输出在 `0.0` 上分道扬镳 ⇒ 契约闸红。

**`1e-06` 那一例也是坏的**（旧 docstring 声称已处理）：JS 先把它写成 `0.000001`，
Python 拿到的就已经是 `0.000001`，只是 Python 的 `repr` 又把它渲染回 `1e-06` ——
**输出字节碰巧对了，输入保真早就在 JS 那一步丢了**。docstring 与实际行为不符。

实测（batch-68）：6 个 channel schema 的 `minimum` 从 `0.0` 塌成 `0`：

| schema                             | 字段                                       | 后端注解                             |
| ---------------------------------- | ------------------------------------------ | ------------------------------------ |
| `AdSearchTermInput`                | `sales` / `spend`                          | `@DecimalMin("0.0") BigDecimal`      |
| `AdKeywordInput`                   | `bid`                                      | `@DecimalMin("0.0") BigDecimal`      |
| `AdKeywordMetricInput`             | `sales` / `spend`                          | `@DecimalMin("0.0") BigDecimal`      |
| `BatchCandidateRequest`            | `minSimilarity`                            | `@DecimalMin("0.0") BigDecimal`      |
| `LogisticsCostParameterRequest`    | `amountPerWaybill`                         | `@DecimalMin("0.0") BigDecimal`      |
| `CreateListingPublishJobRequest`   | `priceAmount`                              | `@DecimalMin(value = "0.00")`        |

（`@Min(1)` 这类 int 约束产出的是 int `1`，不受影响 —— 所以漂移只发生在 BigDecimal 约束上。）

## 为什么以前没被发现

1. **驱动器以前根本没被调用**：channel 每批只有一条车道改 `channel.openapi.json` ⇒ 走单边快进，
   `merge-union.mjs` 不执行。batch-68 是第一次有 3 条 channel 车道同时改同一个文件。
2. **塌掉的只有「整数值 float」**：`0.0` / `3.0` / `1.0` 会塌；`0.0001` / `0.01` / `1e-06` 这类
   非整数值 float 经 JS 往返后，Python 的 `repr` 仍能渲染回同样的字面量 ⇒ 漂移面很窄，
   看上去像「只坏了 6 个 schema」。
3. **旧用例正好落在不会暴露的那一类**：batch-63 加的回归用例用的是 `1e-6`，
   而 `1e-06` 的输出字节碰巧是对的 ⇒ 用例绿着，缺陷还在。
4. **codegen 那侧一直是对的**（Python 直读 Spring spec）⇒ 坏的是驱动，
   漂移只在「经过合并之后」才显形。

## 改法

**`tools/module-progress/json-format.py`**：加 `--merge <base> <ours> <theirs>` 模式 ——
解析（`json.load`，保留 int/float）、三方按键求并、序列化（codegen 同款 `json.dumps`）
三步全在 Python 侧。默认模式（stdin → stdout 的 canonical 序列化）保持不变。

**`tools/module-progress/merge-union.mjs`**：JSON 分支从「JS 求并 + JS 序列化 + Python 格式化」
改成「一次 spawn 交给 Python」。`contracts/*.ts` 与台账的求并仍在 JS（未动）。

**`tools/module-progress/merge-union.itest.mjs`**：新增用例 5（数值字面量保真）与用例 6（真实契约产物）。

### 关键取舍

| 选项                                            | 结论                                                                                       |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------ |
| 在 JS 里保留字面量（数字换占位符，Python 还原） | **否决**：求并语义要拆成两套（JS 求并 + Python 还原），且「值相等字面量不同」的比较语义要另立规则，复杂度高于直接搬 |
| 在 JS 里手搓 Python 的 float repr               | **否决**：JS 本来就分不出 int/float（这正是本次根因），指数边界规则还要抄一遍               |
| **求并整体搬到 Python**                          | **采用**：与 codegen 用同一套 `json.load`/`json.dumps` ⇒ 只有一份真相；且字面量保真是硬约束 |

**代价（如实记录）**：JSON 分支的求并语义现在在 Python，台账 / 契约 TS 分支仍在 JS ——
不再是同一份代码。理由：JSON 分支本来就有自己的一层包装（`paths`/`components` 的深浅合并），
不是纯粹复用 `mergeEntries`；而字面量保真是硬约束，语义分叉是它的代价。

**一处刻意的行为变化**：相等判定从 JS 的 `JSON.stringify`（分不出 int/float）
改成 Python 的规范化文本比较（分得开）⇒ 「只有一边相对 base 把 `0.0` 改成 `0`」
不再被误判成「两边一样」，三方语义（base 才是裁判）更名副其实。

**顺带修掉的既有缺陷**：base 文件为空 / 不是 JSON 时，旧版整个 JSON 分支会掉进行级兜底并集；
新版把 base 当空基线（base 是「谁真的改了它」的裁判，缺了它三方判定就没意义）。

## 文件清单

| 文件                                     | 改动                                                                 |
| ---------------------------------------- | -------------------------------------------------------------------- |
| `tools/module-progress/json-format.py`  | 新增 `--merge` 模式（解析 + 三方求并 + canonical 序列化全在 Python） |
| `tools/module-progress/merge-union.mjs` | JSON 分支改为一次 spawn 委托 Python；TS / 台账分支未动              |
| `tools/module-progress/merge-union.itest.mjs` | 新增用例 5（数值字面量保真）+ 用例 6（真实契约产物）            |

未改：`packages/api-contracts/**`（契约产物由调度者单独重新生成）、`backend/**`、`apps/**`。

## 验证

```bash
# 判据 A：驱动器求并结果 == codegen 输出（逐字节）
python3 tools/codegen/catalog-contracts.py --spec-dir .tmp/float68/specs --out-dir .tmp/float68/cg --no-prettier
node tools/module-progress/merge-union.mjs .tmp/float68/base.json .tmp/float68/ours.json .tmp/float68/theirs.json
cmp .tmp/float68/ours.json .tmp/float68/cg/channel.openapi.json   # → 逐字节相同

# 判据 B：仓库既有回归测试仍绿
node tools/module-progress/merge-union.itest.mjs                 # → 44 条断言全通过
python3 -m unittest discover -s tools/codegen -p 'test_*.py'    # → Ran 17 tests … OK
```

新用例在**修复前的驱动**上会红（4 条：逐字节相同 / `0.0` 没塌 / `3.0` 没塌 / 类型仍是 float），
在修复后全绿 ⇒ 这条用例真的锁住了这个缺陷。

## 后续待办

1. **调度者**：本车道合入后**重新生成 6 份契约产物**（`packages/api-contracts/src/*.openapi.json`）
   并单独提交 —— 本车道按职责不碰产物；不重新生成，契约闸仍然红。
2. **契约语义决策（不在工具链车道范围）**：后端 `@DecimalMin("0")` / `@DecimalMin("0.00")`
   在 springdoc 侧产出 float `0.0`。若希望契约里的 `minimum` 稳定为 int，
   要在后端注解层统一（`@Min/@Max` 或显式 schema `minimum = "0"`），
   否则「int 0」与「float 0.0」会继续在契约里并存 —— 那是**契约语义**问题，不是工具问题。
3. **已知既有失败（本次未改）**：`tools/module-progress/merge-summary.itest.mjs`
   在没有 `upstream/main` ref 的工作区里必然失败（读不到台账）—— 与本次改动无关。
