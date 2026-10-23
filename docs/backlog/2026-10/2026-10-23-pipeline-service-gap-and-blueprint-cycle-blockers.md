# 编排器的三个结构性阻塞：微服务缺口 / 蓝图循环依赖 / 兜底分支死代码

- 创建: 2026-10-23
- Owner: TBD（蓝图语义部分需人工确认）
- 优先级: **P0**（阻塞 106 个未开发模块里的 105 个 ⇒ 停机条件「全部 465 已交付」在当前状态下不可能达成）
- 关联: `tools/module-progress/lane.mjs`（`pickModules` / `serviceGate`）、`docs/product/capability-blueprint/12-finance.md`、`13-profit-and-analytics.md`、`18-integration-and-automation.md`、`05-pricing.md`、`15-crm-and-service.md`
- 发现于: batch-77（`docs/sessions/2026-10/2026-10-23-batch-77-identity-four-and-finance-bootstrap.md`）

## 背景

batch-77 的 `lane.mjs plan` 返回 `newlyPlanned: []`（排不出任何车道）。逐模块复算后，106 个未开发模块的阻塞原因分成四类：

| 类别 | 模块数 | 根因 |
| --- | --- | --- |
| 微服务不存在 | **79** | 卷 12 finance(32) + 卷 13 reporting(23) + 卷 18 platform(24) 的主责服务不在集成头 |
| 上游未交付（传递自服务缺口） | 14 | 卷 14(4) 依赖卷 13 的 L4；卷 16(3) 依赖卷 12 的 L4；卷 01(7) 依赖卷 12/13/18 |
| 蓝图自身循环依赖 | 10 | 卷 05 与卷 15 各有一对互为上游的模块 |
| 编排器死代码导致永不可排 | 1 | 证据列标「阻塞」的模块在编排器眼里永远排不出来 |

三件事互相独立，但都卡在同一个地方：**编排器只排蓝图里的 L4 模块，而「服务不存在」「蓝图写错依赖」这两类缺口本身不是蓝图模块 ⇒ 没有任何机制会自动修复它们。**

## 待办内容

### ① 微服务缺口（79 个模块）

- [ ] **每批只引导一个服务**（batch-77 已验证的约束）：三个服务引导都要改 `backend/pom.xml`、`deploy/docker/catalog-init/01-databases.sh`、`deploy/docker/docker-compose.catalog.yml`、`tools/codegen/catalog-contracts.py`、`.github/workflows/ci.yml` —— **同批并行会直接撞车**。
- [x] finance（卷 12，32 个模块）—— batch-77 已派车道 12.1.1.1 引导，照 crm 的形状（先例：`87bd7591 feat(15.4.2.1): CRM 服务引导`）
- [ ] reporting（卷 13，23 个模块）—— **服务形态与 finance 不同**：卷 13 卷首写的是「基于事件构建的**可重建**投影，不是业务写入来源」；ADR-0004 也写「reporting 只消费投影」。需要先定「事件 → 投影」的管道形状（消费哪个 envelope、投影怎么重建），**不是照抄 crm 就能了**。
- [ ] platform（卷 18，24 个模块）—— 「`platform` 公共能力 + 各服务内的事件/outbox/inbox」，同样需要单独设计。
- [ ] 引导完成后**每批可排 4 条卷 12 车道**（12.1.1.1 / 12.1.2.1 / 12.2.1.1 / 12.3.1.1 …）

⚠️ **引导新服务时 `tools/codegen/catalog-contracts.py` 的 `SERVICES` 与新服务的 `OpenApiContractTest` 必须在同一个 commit** —— `load_specs()` 会无条件读 `backend/target/openapi/<service>.openapi.json`，缺文件会让**别的车道**跑 codegen 时直接崩。

### ② 蓝图循环依赖（10 个模块）

蓝图的「关系」字段里写出了**互为上游**的一对模块：

| 卷 | A | B | 原文 |
| --- | --- | --- | --- |
| 05 | 5.1.1.1 价目表列表 | 5.1.2.1 优先级与冲突检测 | A：「上游 = 5.1.2.1」／B：「上游 = 5.1.1.1」 |
| 15 | 15.3.1.1 客户列表 | 15.3.1.3 跨店合并 | A：「上游 = 9 订单、15.3.1.3 合并结果」／B：「上游 = 15.3.1.1」 |

⇒ 编排器的 `upstreamReady` 是 `every(u => delivered.has(u))`，**这两个 L3 各有一对永远互锁的模块，谁都排不出来**。

- [ ] **人工确认**后修改蓝图「关系」字段里的上游声明（这是**蓝图语义变更**，不是编排器可以自己决定的事；协议明确禁止调度者「自己改锚点绕开」）
- [ ] 建议方向：把**被依赖方的上游声明去掉**（5.1.1.1 不必等 5.1.2.1；15.3.1.1 不必等 15.3.1.3）—— 两对都是「列表/守卫」与「合并」的关系，后者在业务上并不构成前者的前置。

### ③ `lane.mjs` 兜底分支是死代码（1 个模块 + 未来同类）

`pickModules()` 的兜底分支：

```js
if (picked.length < count) {
  tryTake(unfinished.filter((r) => /^阻塞[：:]/.test(r.evidence)));
}
```

—— 而 `tryTake()` 内部**自己就会跳过** `^阻塞：` 的行：

```js
if (/^阻塞[：:]/.test(r.evidence)) continue;
```

⇒ **这个兜底分支永远选不出任何模块。** 后果：证据列标了「阻塞：」的模块，在编排器眼里是**永久不可排**的，除非人工清掉标记。batch-77 就撞上了这个（11.2.3.2 的标记解除条件已满足，但只要标记还在就排不出来）。

- [ ] 修 `pickModules()`：让兜底路径能选中被标记的模块（例如给 `tryTake` 加一个 `allowBlocked` 参数），**或**明确删掉这个死分支并在文档里写明「证据列的阻塞标记只能由人工清除」
- [ ] 补一条回归测试（`tools/module-progress/lane-plan-*.itest.mjs` 已有同类用例的形状可抄）

## 影响面

- `tools/module-progress/lane.mjs`：`pickModules()` / `serviceGate()`
- `docs/product/capability-blueprint/`：卷 05 / 12 / 13 / 15 / 18 的「关系」字段与卷首「后端归属」
- `backend/services/`：finance / reporting / platform 三个服务的引导
- `deploy/docker/`、`tools/codegen/catalog-contracts.py`、`.github/workflows/ci.yml`：新服务的共享文件（**同批只能改一个服务**）
- 编排吞吐：当前每轮只能排 2 条（11.2.3.2 + 12.1.1.1），解完 ①② 后每轮可排 4 条

## 完成标准

- [ ] `backend/services/finance/`、`reporting/`、`platform/` 三个服务都在集成头里
- [ ] 卷 05 / 卷 15 的循环依赖在蓝图里被解开，`plan` 能排出这两个卷的模块
- [ ] `lane.mjs` 的兜底分支要么可用、要么被删掉，且有回归测试锁住
- [ ] `plan --lanes 4` 在服务齐备后能稳定返回 4 条
