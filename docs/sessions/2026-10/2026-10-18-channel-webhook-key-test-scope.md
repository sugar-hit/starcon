# 2026-10-18: channel 回调主密钥改为测试作用域供给（2.6.1.1 修复车道）

## 摘要

模块 2.6.1.1 引入的 `WebhookService` 在构造器上带了一个**没有默认值的** `@Value("${CHANNEL_WEBHOOK_SIGNING_KEY}")`。
这个属性在 channel 的任何配置里都不存在，只有两个测试类各自用 `@SpringBootTest(properties=...)` /
`@DynamicPropertySource` 喂了它。于是其余每一个 channel 集成测试在加载 `ChannelApplication` 时，
`webhookService` bean 创建失败 → `PlaceholderResolutionException` → 整个 Spring 上下文起不来。

后果是全服务级联崩盘：**channel 322 个测试里 256 个 error**（Failures 0 / Errors 256），
只有自带属性的两个测试类侥幸通过。

修法：**只在测试作用域补上这个属性**，主代码一行不改。

## 决策 / 结论

1. **不给 `@Value` 加默认值，不加任何兜底。**
   这个 key 是 `WebhookSignature.seal/open` 用来 **AES-GCM 封装各租户 webhook 签名密钥的主密钥**。
   写成 `@Value("${CHANNEL_WEBHOOK_SIGNING_KEY:}")` 这种形式，等于给生产发一把**公开的、可预测的**主密钥：
   任何能读到仓库的人都能解开所有租户落库的签名密钥。这是安全回归，不是配置健壮性优化。
   主代码保持"缺了就启动失败"——缺主密钥时启动失败是正确行为，静默降级才是事故。

2. **用 `src/test/resources/application.properties`，不是 `application.yml`。**
   这是本次唯一有技术含量的取舍，详见下面「关键 trade-off」。

3. **沿用已有的固定测试值。**
   `MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=` 是 base64 的 `0123456789abcdef0123456789abcdef`，
   正好 32 字节，满足 `WebhookSignature.keyOf` 的 `key.length != 32` 校验（`WebhookSignature.java:120-124`）。
   它与 `OpenApiContractTest` / `WebhookReceiptIntegrationTest` 各自注册的值一致。
   该值只存在于 test classpath：不入库、不进任何生产配置、不是密钥材料。

4. **两个自带属性的测试类必须仍然绿**，已实测确认（见下）。

## 涉及文件

- 新增：`backend/services/channel/src/test/resources/application.properties` —— 本次唯一改动
- 未改动：`backend/services/channel/src/main/java/com/starcon/channel/WebhookService.java`
  （`@Value` 保持无默认值，`WebhookService.java:78`）
- 未改动：`WebhookSignature.java` / `src/main/resources/application.yml` / 任何 `pom.xml`

## 关键 trade-off / 讨论

**为什么不是 `application.yml`：同名文件会整个遮蔽主配置。**

channel 有 `src/main/resources/application.yml`（datasource / flyway / jooq / jackson / springdoc 全在里面）。
Maven surefire 的 classpath 顺序是 `target/test-classes` 在 `target/classes` 之前，
而 Spring Boot 解析 `classpath:/application.yml` 走的是 `ClassLoader.getResource()`——**只返回第一个匹配**，
不是合并。

于是按常规写法新建 `src/test/resources/application.yml` 并只写这一个 key，主配置会被整个顶掉，
实测表现为：

```
Caused by: ... HikariDataSource: Factory method 'dataSource' threw exception with message:
Failed to determine a suitable driver class
```

`spring.datasource.url: ${DB_URL}` 连同 jooq 方言、jackson 严格模式、springdoc 开关一起消失。

**改用 `.properties` 就干净了**：`.properties` 与 `.yml` 是 Spring Boot 针对同一 config location
支持的不同扩展名，两份会**一起加载**。测试目录放 `.properties`（新增一个 key），
`classes` 目录的 `application.yml` 仍然命中并生效，互不遮蔽。

> 踩坑记录：Maven 的 resources 插件只复制、不清理。删掉源码里的 `application.yml` 之后，
> `target/test-classes/application.yml` 仍会残留并继续遮蔽主配置，下一轮跑出来还是同样的
> driver class 报错。**改完 test resources 记得看一眼 `target/test-classes`，必要时手动删。**

## 验证方式与真实输出

### 修复前（复现）

```bash
cd <WORKTREE>/backend && ./mvnw -pl services/channel -am test
```

```
[ERROR] Tests run: 322, Failures: 0, Errors: 256, Skipped: 0
[INFO] BUILD FAILURE
```

第一条真实异常（不是后面连带的 `ApplicationContext failure threshold`）：

```
Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name
  'webhookService' ...: Unexpected exception during bean creation
Caused by: org.springframework.util.PlaceholderResolutionException: Could not resolve placeholder
  'CHANNEL_WEBHOOK_SIGNING_KEY' in value "${CHANNEL_WEBHOOK_SIGNING_KEY}"
```

### 修复后（L0 编译闸 + L1 channel 测试闸）

```bash
cd <WORKTREE>/backend && ./mvnw -q compile                      # L0
cd <WORKTREE>/backend && ./mvnw -pl services/channel -am test   # L1
```

```
L0_COMPILE_EXIT=0
L1_TEST_EXIT=0

[INFO] Tests run: 322, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

两个自带属性的测试类**仍然通过**，实测数字：

```
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.channel.WebhookReceiptIntegrationTest
[INFO] Tests run:  1, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.channel.OpenApiContractTest
```

它们的注册源（`@SpringBootTest(properties=...)` / `@DynamicPropertyRegistry`）优先级高于
`application.properties`，覆盖成立，两边值也一致。

`git diff --stat HEAD -- backend/services/channel/src/main` 输出为空，确认主代码零改动。

本次只涉及 Java 测试资源，未跑 `pnpm typecheck` / `pnpm vitest` / Next build。

## 后续待办

- ⚠️ **同类风险仍然存在**：backend 里任何一个服务新增带无默认值 `@Value` 的 bean，
  都会重演"全服务上下文起不来"。目前每个服务都没有 `src/test/resources`，
  这个"测试作用域补必需属性"的位置要等下一次踩坑时才自然长出来。
  可考虑在 `docs/operations/` 补一句：新 `@Value` 必需属性要在测试作用域有对应供给。
- 本次未加回归测试。缺陷本身是"缺属性导致上下文起不来"，任何测试都跑不起来，无法自证；
  它的守门人是 L1 全服务测试闸。

## 关联

- 模块：2.6.1.1 接收记录
- 相关代码：`backend/services/channel/src/main/java/com/starcon/channel/WebhookService.java:78`、
  `WebhookSignature.java:120`
- 前序会话语档：`docs/sessions/2026-10/2026-10-18-2-6-1-1-webhook-receipt.md`
