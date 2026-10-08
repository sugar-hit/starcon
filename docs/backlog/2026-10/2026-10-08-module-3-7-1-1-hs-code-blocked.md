# 3.7.1.1 HS 编码与申报要素 —— 阻塞拆表与半落地残留

日期：2026-10-08 21:30　来源：18:00 硬停机后接管排查　模块状态：台账 `未开发`

## 两个必须知道的坑

### 1. 台账与代码不一致：`CustomsAttributeController.java` 已进集成头

`backend/services/catalog/.../CustomsAttributeController.java` **已被提交并合入 `integration/module-dev`**，
但台账 `3.7.1.1` 仍标 `未开发`。

**后果**：任何未来车道若按「未开发 = 代码不存在」的前提去排 3.7.1.1，会撞上已存在的 Controller，
可能重复创建端点导致路由冲突。**接手前先 grep 该 Controller 的 `@RequestMapping`，再决定怎么接。**

### 2. Service / Rules / Models + V94 从未提交，已 stash

那轮尝试的其余 4 个文件**从未进入任何提交**，是纯未跟踪残留：

```
backend/services/catalog/src/main/java/com/starcon/catalog/CustomsAttributeModels.java   (239 行)
backend/services/catalog/src/main/java/com/starcon/catalog/CustomsAttributeRules.java    (298 行)
backend/services/catalog/src/main/java/com/starcon/catalog/CustomsAttributeService.java  (664 行)
backend/services/catalog/src/main/resources/db/migration/V94__customs_hs_declaration_elements.sql (449 行)
```

因它们让主工作区 `git status --porcelain` 非空，而 `lane.mjs merge-ready` 第 882–886 行
**脏工作区直接 `exit(1)`**，等于**堵死整条并入通道**（每轮 tick 都失败），已 stash：

```
git stash push -u -m "3.7.1.1 HS编码 失败尝试残留：..."
# stash@{0}
```

恢复：`git stash pop`（或 `git stash show -p stash@{0}` 先看）。

## ⚠️ 恢复前必须先改版本号

`V94` **已被重新分配给模块 `11.4.2.2`「知识库与话术」**（2026-10-08 21:30 由
`lane.mjs plan` 分配，该车道的 dry-run 实测输出 `mig=V94`）。

版本号分配器扫的是**工作区文件**，不是 git 历史 —— 所以残留的 V94 一旦恢复，
会和 11.4.2.2 的已落库 V94 **同号**，Flyway 直接炸。

**恢复时必须改成一个未占用的新号**，且不要用「找最大版本号 +1」的旧办法 ——
并行车道会同时提交迁移，撞号会让另一条车道的 Flyway 炸掉。

## 真正的阻塞原因

台账证据列已写明（不是推测）：

> 阻塞：`product_compliance` 同为 `LIKE product_research INCLUDING ALL` 复制，
> 装不了 HS 编码与认证要素。**须先拆表。**

即 `product_compliance` 是 `product_research` 的整表克隆，列结构不匹配
HS 编码 + 申报要素 + 认证要素这三类字段。**不先拆表，这个模块怎么写都装不下。**

所以正确顺序是：

1. **先做「拆表」这个前置任务**（把 `product_compliance` 拆成合规要求 / HS 编码 / 认证要素三张，
   带数据搬迁 + 集成测试）
2. 拆表落地并合入集成头后，再排 `3.7.1.1` 的车道
3. 那条车道要同时处理「Controller 已存在」这个事实

**在拆表完成前，不要指望调度器把这个模块排出来** —— 它被正确地排除在外，不是调度器的 bug。