# 2026-10-11 契约按服务分文件：同名 DTO 从此没有静默路径

## 结论

backlog `2026-10-10-contract-flat-overwrite-and-nav-gaps.md` 的 **建议方向 2 已落地**。
契约不再合并成一个扁平文件，改为 `contracts/<service>.ts` + `index.ts` 汇总入口。

同名 DTO 现在有两道网，**都不依赖任何人的纪律**：

- 结构不同 → 方向 1 的闸门（上一轮会话）非零退出，不落盘；
- 结构完全相同 → 两份各自活在自己的文件里，桶文件漏写消解行就是 **TS2308 编译错误**。

验证：`typecheck --force` 零错误（0 cached）、lint 3/3、vitest 113 文件 / 2163 例、
codegen 护栏 14 条、契约生成幂等（旧扁平行与新分文件行的类型名集合逐一对账：
1461 vs 1461，零丢失零新增）。

## 结构

```
packages/api-contracts/src/
  contracts/{identity,catalog,channel,warehouse,trade,crm}.ts
  index.ts                    ← export * 各服务文件 + 每条重名显式绑定
  *.openapi.json              ← 位置不变
```

消费面 233 个文件、242 处 import 从 `.../src/catalog` 改为 `.../src/index`，纯路径字符串替换。

## 关键 trade-off

### 1. 桶文件叫 `index.ts` 而不是继续叫 `catalog.ts`

`catalog.ts` 这个名字本身就在制造误解：今天它装着 trade/warehouse/crm 的类型，
昨天它曾经只装 catalog 一个服务的类型。改名的 242 处成本是一次性的纯字符串替换，
换掉的是一个会持续骗人的名字。typecheck 对 233 个文件做了等价性证明。

### 2. 消解行取「SERVICES 顺序第一个」，不是「后跑的」

分文件之前的扁平合并是 `all_schemas.update()`，**后跑的赢**。分文件之后不存在覆盖，
真正的问题是「消费端 import 桶文件时看到哪一份」，所以取第一个服务（确定性优先）。
`describe()` 的措辞同步从「最终生效 / 合并后消失」改成「桶文件绑定 / 消费端拿不到」——
继续用「谁赢」的措辞会指向一个已经不存在的机制。

### 3. allowlist 语义升级：放行必须写 `winner`

方向 1 的 allowlist 只回答「放不放行」。分文件之后这个语义不够了：
放行但桶文件不绑定，`export *` 撞名会让**整个前端编译失败**。
所以 entry 现在必须带 `winner`，指明桶文件绑定哪一份，并且有一条测试盯着这件事。

### 4. 旧 `catalog.ts` 只警告不自动删

生成器发现 `src/catalog.ts` 还在时会打 `WARN`。不自动删是因为生成器不该替人做删除决策；
但必须喊出来 —— 留着它，走旧路径的 import 会拿到**过期类型且不报错**，
这正是本次要消灭的那类静默。实际已 `git rm`。

### 5. `.gitattributes` 改成 `src/**/*.ts`

跨服务的车道现在各改各的文件，本来就不冲突了；但**同服务内**的车道仍然追加到同一个
`contracts/<service>.ts`，驱动依旧要覆盖。覆盖面用 `git check-attr` 问过 git 本人，
`index.ts` 与 `contracts/*.ts` 都命中 `contract-union`，前端文件仍是 `unspecified`。

## 踩过的坑（三次都是我的探针错，不是代码错）

**1. 对照组根本没跑。** 想验证「无冲突时干净」，用了 `head -n -1` 抽掉注入 ——
macOS 的 BSD `head` 不支持负数行数，命令直接报错，注入原封不动，
后面那两行「无输出」其实是重复的错误输出。**差点把一个假对照当成已验证。**
换 python 过滤后重跑。

**2. 对照组第二次仍然没对准。** 抽掉注入后探针 import 的正是被删掉的 `ZzProbe`，
报 `TS2305 no exported member` —— 合理，但不是我要验的东西。要验的是「真实桶文件能干净编译」。

**3. 探针里编了个不存在的类型名**（`TradeOrderSaved`，真名 `TradeSaved`）。
值得注意的是 `Dimension` 与 `ProcessingDownstreamGap` 在同一次运行里**没有报错** ——
桶文件本身是好的，错的只有探针。

**4. 测试抓到生产代码的真 bug**：`legacy.relative_to(root)` 在 `--out-dir` 指向仓库外时抛
`ValueError` 直接崩 —— 而那正是该参数 documented 的用法（测试就靠它避免污染真实契约）。
已加 `_display()` 兜底。**测试抓到的和我的预期相反时，要先判是代码错还是断言错**：
这次前三次是我断言错，第四次是代码错，不能一概而论。

## 文件清单

| 文件                                                                  | 变更                                                                                           |
| --------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `tools/codegen/catalog-contracts.py`                                  | 分文件输出 + 桶文件；`describe`/`load_allowlist`/`gate` 语义随分文件调整；修 `_display()` 崩溃 |
| `tools/codegen/test_catalog_contracts.py`                             | 11 → 14 条；新增分文件/消歧/allowlist-winner/残留警告的断言                                    |
| `packages/api-contracts/src/contracts/*.ts`                           | 新增 6 个（生成物）                                                                            |
| `packages/api-contracts/src/index.ts`                                 | 新增桶文件（生成物）                                                                           |
| `packages/api-contracts/src/catalog.ts`                               | **删除**（旧扁平产物）                                                                         |
| `apps/web/src/**`                                                     | 233 文件 / 242 处 import 路径重写                                                              |
| `.gitattributes`                                                      | `src/*.ts` → `src/**/*.ts`                                                                     |
| `docs/backlog/.../2026-10-10-contract-flat-overwrite-and-nav-gaps.md` | 方向 2 标记完成                                                                                |

## 后续待办

- [ ] **建议方向 3**（新模块一律带服务前缀）单独用不够，已被证伪两次。
      闸门是纪律的替代品，可按需保留为降低噪音的约定。
- [ ] `Dimension` 在 warehouse / trade 两处定义完全相同，桶文件显式绑定 warehouse。
      现在改一边就会升级成字段级拦截；也可主动给其中一边加前缀收敛掉。
- [ ] 同一 backlog 文件里的另外两节**未动**：导航未注册
      （`/crm/knowledge`、`/aftersales/knowledge`）、catalog 既有 flaky 测试。
- [ ] `packages/api-contracts` 至今**没有 package.json**，全靠相对深路径 import
      （`../../../../../packages/...`）。本次只是把 `catalog` 换成 `index`，
      真正该做的是给它一个包名 + tsconfig path alias，让消费面不再穿越目录层级。
- [ ] 观察项：`pnpm format:check` 对 489 个文件报红（含本轮未触碰的文件），CI `lint` job 会跑它。
      属既有状态，未扩大也未修。
