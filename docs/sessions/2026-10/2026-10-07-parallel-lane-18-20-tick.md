# 18:20 tick —— 集成分支是红的，而且 `land` 根本不查测试

17.5.1.1 已 land（7 commits，`{"ok":true,"head":"dc951ac"}`）。但本轮更重要的是：
**在我准备回收它的时候，发现集成头 17.3.2.1(V47) 把 V45 的入职写入路径打断了，
而修复还以未提交状态躺在主工作区里。**已由本轮代为验证并提交。

## 一、集成分支当时是红的

18:10 那个 tick 实例合并了 17.3.2.1：

```
6bd0b19 merge(17.3.2.1): 角色分配并入集成头
409ff79 fix(identity/17.3.2.1): 消除 RoleAssignmentInput 撞名，契约重新生成
```

合完之后主工作区里躺着两个**未提交**的文件：

- `?? V50__member_onboarding_role_assignment_scope.sql`
- `M  MemberOnboardingIntegrationTest.java`（+124 行）

18:23 我接手时它们还在，且当时**调度闸被另一个实例持有**（`held-by-other`）——
说明 18:10 那个 tick 当时正在跑。

### 根因（静态即可证明，不需要跑测试）

V47 给 `role_assignment` 加了两道硬要求，V45 的 `onboard_member` 两条都没跟上：

|     | V47 的要求                                                                    | V45 的 `onboard_member`                                                                           |
| --- | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| 1   | `ALTER TABLE role_assignment ALTER COLUMN assigned_by SET NOT NULL`（V47:41） | `INSERT INTO role_assignment(tenant_id,membership_id,role_id,scope_mode)` —— **没有 assigned_by** |
| 2   | 「有角色必有范围」延迟约束触发器：非 ALL/SELF 必须挂 `role_assignment_scope`  | 只写 `scope_mode`，**从不写范围行**                                                               |

我特意排除了两种「可能不炸」的掩护：

- `assigned_by uuid` 加列时**没有 DEFAULT**（V47:27），回填只作用于存量行（V47:35）；
- `role_assignment` 上**没有 BEFORE INSERT 触发器**（唯一的触发器建在
  `role_assignment_scope` 上，V47:105）。

所以每一次带角色的入职都必然撞 NOT NULL 违约，DEPARTMENT 分配必然被约束拒绝。

### 更值得记的是：V45 的测试本来是**绿的**

`MemberOnboardingIntegrationTest` 此前全绿 —— 因为那两道约束**当时还不存在**。
落库的是「声明了 DEPARTMENT 范围却没有任何范围对象」的残缺记录。
V47 把这个假通过挡住了，暴露的是本来就残缺的写入路径。

**这不是「V47 写错了」，是「V47 终于让一个一直存在的 bug 显形」。**
测试绿 ≠ 写入路径对，只在约束是完备的前提下才成立。

## 二、结构性缺口：`land` 不查测试

```js
// tools/module-progress/lane.mjs:581
const dirty = git(['status','--porcelain'], lane.worktree);  // 闸1：工作区干净
if (sha === lane.baseSha || !sha) { ... }                    // 闸3：有新提交
```

三道闸全是 **git 状态**：`工作区干净` / `无未跟踪文件` / `HEAD 偏离基线`。
**没有任何一处读测试结果。**所以一条测试红的车道可以完全正常地 `land`，
`merge-ready` 也会照单并入 —— 红了要等**下一个**碰巧跑全量 verify 的人发现。

17.3.2.1 的 worker 在自己分支上跑过 verify 也未必能兜住：
它验的是「自己那条车道」，而**跨车道的破坏只有在两条都合进同一个头之后才存在**。
这次就是 V47（新）与 V45（早已合并）合到一起才炸。

**建议（未实施，属于改编排脚本，需人决策）**：`land` 增加第四道闸 ——
要求车道在被标记 ready 之前，在**最新集成头**上重跑受影响服务的 verify；
或至少 `merge-ready` 在并入后跑一次冒烟。代价是每次回收多花几分钟，
收益是把「红头存活一个 tick」变成「当场拦下」。

## 三、本轮实际动作

1. **验证再提交**，没有直接捡起别人的未提交改动就 commit：
   - 静态证明前提成立（NOT NULL 无 DEFAULT、无兜底触发器）
   - `./mvnw verify -pl services/identity -am` → **Tests run: 118, Failures: 0, Errors: 0，BUILD SUCCESS**
   - 确认 V50 修法最小且正确：INSERT 补 `assigned_by=actor`；
     DEPARTMENT 分支按入参 `dept` **派生**范围行（不加范围对象入参 ——
     加了只会多出一条能被填错的路径，还要连带改 DTO 与重新生成契约）
2. `bd599cf` 提交代码修复，`77b403f` 提交台账证据更新。**只显式 add 这两个文件**，
   没有用 `git commit -a`（避免再次发生 17:50 那次「提交了旧暂存区」的事故）。
3. **没有强行抢闸**：`gate acquire` 返回 `held-by-other`（exit 3），
   按纪律不做任何改动、不 force。**17.5.1.1 本轮未并入**，留给持闸的实例回收。

## 四、给下一轮

1. **回收 17.5.1.1 之前先跑一次全量 verify**。它是在 `185298c` 上开发的，
   集成头现在多了 V47 与 V50；虽然验证时 118 项已含 V50 的影响面，
   但前端/契约侧没在合并后的头上验过。
2. 合并后按纪律核对台账没被回退：
   `comm -23` 比对已交付集合 + 「合并后已交付数 = 合并前 + 本次新增」算术校验。
   当前基线：**已交付 76 / 465**，无重复行。
3. **两个 tick 实例并发写主工作区**是本轮观察到的新事实。
   18:10 实例与本轮同时在主工作区活动（闸在它手里、台账在它写到一半）。
   后果是「谁的未提交改动」归属不清 —— 本轮只能靠内容判断
   （V50 注释写得极其自证，直接读出根因与修法）。**派发前提醒 worker 不要碰主工作区
   这条纪律是有效的，但两个调度者自己也要注意**。
