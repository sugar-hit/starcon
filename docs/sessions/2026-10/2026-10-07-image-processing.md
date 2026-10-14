# 3.4.1.2 图片处理

## 决策摘要

交付卷 03 商品主数据系统 3.4.1.2「图片处理」，分支 `feat/3-4-1-2-image-processing`，
基线 `feat/3-4-1-1-media-library`（tip `ca36d9b`）。

蓝图六条能力全部做完（白底 / 去背景 / 水印添加 / 尺寸裁剪到平台要求 / 格式转换 / 生成多比例版本），
异步任务真的在线程池里跑，进度是库里可查的事实。四条核心决策：

1. **「原图始终保留」靠三个 DB 触发器成立，不靠服务层自觉。**
   蓝图原话「处理结果不可逆覆盖原图，原图始终保留」拆开是两件事：
   **覆盖**指产物写新对象 + 插新版本行、`media_asset.current_version` 由 V18 的
   `sync_media_asset_from_version` 推进（沿用上游既有写入路径，不另开一条）；
   **保留**指来源版本行与它的 storage_key 不能被删改。后者光靠服务层判是不够的——
   一条运维 SQL 就能绕过去，所以：
   - `guard_media_asset_version_immutable`（BEFORE UPDATE）：版本行**只允许 INSERT**，
     连改 `note` 这种看起来无害的也拒。否则「不可逆」可以被一次次小改动慢慢绕过。
   - `refuse_media_process_source_version_delete`（BEFORE DELETE）：被处理记录引用过的
     版本不可被**单独**删除。关键是它要区分两种删除——素材整体删除时的 `ON DELETE CASCADE`
     放行（那是删素材，已由 V18 的 `refuse_media_asset_delete` 按引用保护过），
     直接 `delete from media_asset_version` 撞下来。判据是**父 `media_asset` 行是否还存在**：
     级联时父行已不在，同事务内可观察，这样 V18 的删素材路径保持原样可用。
   - `refuse_media_process_recipe_delete`：被任务引用的配方只能停用不能删
     （删配方会级联删比例档，而档是历史产物的口径来源——产物表存的是 label 文本）。
     另有 `CHECK(to_version > from_version)` 把「产物不可能是原图自己」钉进数据。

2. **多比例版本 = 每个比例档各产出一个新版本，而不是一个资产拆成多个。**
   蓝图写「生成多比例版本（如主图 1:1 + 缩略图）」，落在 `media_process_recipe_ratio`
   子表：每个档一行 `media_process_output`，也就是原素材的一个新版本。
   拆子表而不是在配方上放数组，是因为产物要按比例回溯（「这批缩略图是哪档出来的」），
   数组列查不了。比例档用**整体替换**语义：档位集合是「这批产物长什么样」的完整口径，
   增删式会让「当前有哪些档」这个问题有两个答案。

3. **提交只写元数据，像素加工在线程池里跑；进度落库而不是放内存。**
   蓝图要求「处理是异步任务，长批量不阻塞页面」。`submit()` 只做四件很快的事
   （查配方、逐素材快照来源版本、写任务行、写条目行），毫秒级返回；
   `ImageProcessExecutor` 在请求线程之外消费。进度每处理完一个素材就更新条目与任务计数，
   所以它是**库里可查的事实**，换进程/重启后不丢。
   提交瞬间在**事务内**快照 `source_version`/`source_storage_key`/`source_sha256`：
   批量跑到一半素材被人追加新版本时，处理对象必须仍是提交那一刻的字节，
   否则同一任务的产物来源前后不一致，而任务记录再也解释不清。

4. **并行度取 2 而不是 CPU 核数；一个任务同时只有一个工作线程在跑。**
   像素加工是内存与 GC 密集的：一张 4000×3000 的 ARGB 位图就是 48MB，
   并行度一高就会把堆打满、让所有任务一起变慢。「单任务单主人」靠
   `markRunning` 的 `where status='PENDING'` 原子抢占实现——**这不是性能优化，
   是正确性要求**，详见下面的「实测修掉的缺陷」第 1 条。

## 关键 trade-off

### 去背景用四角泛洪而不是语义分割

没有引入 ML 依赖（JDK 没有，引入就等于给 catalog 服务挂一个模型运行时）。
实现是「从四角泛洪 + 颜色距离超容差即视为背景」——对纯色/渐变背景（摄影棚白底、
纯色布景）效果好，背景里若有大面积与主体同色的区域会被一起抠掉。
容差就是留给用户调的旋钮，界面上写明了它的失效模式而不是假装「总是正确」。
代价明确：这不是抠图级的抠图。换成模型方案时，`ImageProcessor.removeBackground`
是唯一需要替换的方法，签名不变。

### 格式转换按「格式」而不是按「像素」决定是否换色彩模型

第一版只在 `hasTransparency()` 为真时才换色彩模型，结果全不透明的 ARGB 写 jpeg 直接失败。
实测 JDK 的 JPEG/BMP 写入器遇到 `TYPE_INT_ARGB` 就返回 `false`，
即使每个像素 alpha 都是 255（它的理由是「这张图有能力存 alpha」，而 JPEG/BMP 没有）。
所以现在按格式判定。另有 V19 层的 CHECK 兜住组合：
只有 png 有真正的 alpha 通道，gif 只有 1 位透明（抠出的柔边会被二值化成锯齿），
jpeg/bmp 根本没有 alpha——所以「去背景 + 非 png 目标格式」必须同时开白底，
否则接口会返回一个「成功」的坏产物（透明被渲染成黑块）。

### WEBP 明确拒收，但理由要说清

3.4.1.1 已经因为「JDK 无 WEBP 读取器」拒收 WEBP 上传，并在错误文案里写了
「3.4.1.2 交付后可直接上传并自动转码」。本轮口径要与那句话对齐：
`target_format` 只允许 png/jpeg/gif/bmp，错误文案说明是 ImageIO 写入器缺失。
**转码入口本身仍然没有做**——WEBP 解码需要第三方库，属于下一个明确的增量，
不在这轮「六项能力」的范围内。这里选择把理由写清而不是写一个
「转 WEBP」却什么都不做的下拉项。

### 配方的 `target_platform` 是自由文本，不建跨服务外键

channel 服务的平台表在别的库。建一个指向别库不存在表的列就是硬编码的假引用
（V16 头部否掉的方案 B）。真正要联动校验平台规格时走 event / 授权 API。

## 实测修掉的缺陷（本轮开发中发现，均为真缺陷不是测试写法问题）

1. **两线程互相等对方让出连接 → 死锁，整个测试挂住。**
   早期版本让工作线程与测试的同步驱动同时推进同一个任务，各自「等对方把条目处理完」
   才能收尾，而每一轮等待都要开事务拿连接。集成测试把连接池固定成 1 条连接
   （为了证明归还后不串租户），于是两边互相等对方让出唯一连接，直接死锁。
   修法是**单任务单主人**（`markRunning` 原子抢占），把并发从「需要互相等待」
   变成「后者直接退出」。这条纪律留在代码注释里，因为它看起来像可以「优化掉」的东西。

2. **取消时把条目卡在 RUNNING，任务永远不落终态。**
   取消检查原本在「领取条目」之后。判定为取消而 break 时，那条已被标成 RUNNING 的条目
   没人再碰：既不会被处理，也不会被 `cancelRemaining` 落成 CANCELLED（它只处理 PENDING）。
   结果 `finish()` 永远看到一条 RUNNING、拒绝落终态，任务卡死在「处理中」。
   修法是把取消检查移到领取之前。

3. **`cancelRequested` 写死 `false`。**
   `cancel()` 成功后回读任务时仍报告「没取消」，页面上的「正在取消」提示永远不出现。
   这不是显示问题——运维据此判断要不要重跑。

4. **泛洪种子漏置 alpha。** `seed()` 只把角像素标 visited 并入队，
   置透明只发生在 `offer()` 里，于是四个角永远留着不透明的一小块原背景色。

5. **不透明 ARGB 写 jpeg/bmp 失败**（见上「按格式决定色彩模型」）。

6. **afterCommit 里查 tenant_id 拿到 null。**
   `set_config('app.current_tenant', ..., true)` 的 `true` 表示「只在本次事务内有效」，
   而 `afterCommit` 恰好在事务结束**之后**执行——那时连接上没有租户上下文，
   FORCE RLS 直接把行藏起来。修法是在事务内把 tenant_id 捕获成局部变量传出去。
   这条同时说明：**工作线程不能靠「请求上下文」拿租户**，必须按任务行里记录的 tenant_id 开事务。

7. **`media_process_job` 没有 `updated_at` 列。**
   它的时间轴是 `created_at`（业务：几点点的）与 `started_at`/`finished_at`
   （系统：什么时候真的开始/结束）。取消请求两者都不是，所以只推进 version 乐观锁。
   另有一处 SQL 缺逗号。

8. **`SELECT_SOURCE` 已带 `where a.tenant_id=?` 又拼 `where a.id=?`。**

## 涉及文件

**迁移（catalog 服务）**

- `backend/services/catalog/src/main/resources/db/migration/V19__image_processing.sql`

**后端**

- `backend/services/catalog/src/main/java/com/starcon/catalog/ImageProcessRules.java`（纯规则层）
- `backend/services/catalog/src/main/java/com/starcon/catalog/ImageProcessor.java`（像素加工引擎）
- `backend/services/catalog/src/main/java/com/starcon/catalog/ImageProcessService.java`（配方 / 任务 / 进度）
- `backend/services/catalog/src/main/java/com/starcon/catalog/ImageProcessExecutor.java`（异步执行器）
- `backend/services/catalog/src/main/java/com/starcon/catalog/ImageProcessController.java`（端点）
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogModels.java`（13 个 DTO）

**测试**

- `backend/services/catalog/src/test/java/com/starcon/catalog/ImageProcessingIntegrationTest.java`（12 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/ImageProcessorTest.java`（9 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/ImageProcessRulesTest.java`（10 例）

**前端**

- `apps/web/src/components/catalog/image-process-workspace.tsx`
- `apps/web/src/components/catalog/image-process-model.ts`
- `apps/web/src/components/catalog/image-process-model.test.ts`（20 例）
- `apps/web/src/app/products/media/processing/page.tsx`
- `apps/web/src/lib/api-proxy.ts`（放行 processing 路径）
- `packages/api-contracts/src/catalog.ts` + `catalog.openapi.json`（codegen 产物，未手改）

**台账与会话**

- `docs/product/capability-blueprint/module-progress.md`（仅 3.4.1.2 那一行）
- `docs/sessions/2026-10/2026-10-07-image-processing.md`

## 验证方式

```bash
cd backend && ./mvnw verify
# BUILD SUCCESS；catalog 449 + channel 66 + warehouse 31，0 失败
# 含 ImageProcessingIntegrationTest 12、ImageProcessorTest 9、ImageProcessRulesTest 10
# 以及 3.4.1.1 的 MediaIntegrationTest 16（未受影响）

cd .. && pnpm typecheck && pnpm lint && pnpm test
# typecheck 全绿；lint 只有 variant-combination-workspace.tsx 的既有 warning；
# 31 个测试文件 / 396 例通过

NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build
# Compiled successfully；/products/media 与 /products/media/processing 均已产出

node tools/module-progress/sync.mjs --check
# ✓ 台账与蓝图一致
```

集成测试用真实 PG16（Testcontainers）+ 真实文件系统 + 真实线程池，无任何内存替身。
租户隔离三断言齐跑：两租户互不可见、伪造 `X-Tenant-Id` 无效、连接池单连接反复交替不串。

## 后续待办

1. **WEBP 解码/转码入口未做。** `target_format` 只允许 png/jpeg/gif/bmp，
   3.4.1.1 错误文案里「3.4.1.2 交付后可直接上传 WEBP 并自动转码」这句目前**不成立**，
   需要第三方解码库（twelve-images 之类）。要么引入依赖，要么回头修正 3.4.1.1 那句文案。
   **这是本轮唯一未覆盖的相邻能力**，不在蓝图点名的六项之内，但文案口径需要有人决定。

2. **视频转码 / 抽帧不在本模块。** 3.4.1.1 明确视频宽高与时长不探测（需要解容器，JDK 没有），
   本轮处理只接受 `media_type='IMAGE'`，非图片素材在提交时就被跳过并给出原因。
   视频要做转码需要先解容器，属于另一个增量。

3. **3.4.1.1 的 api-proxy 有个既有缺口（本轮未改）。**
   `apps/web/src/lib/api-proxy.ts` 里 `media` 那条正则的 `{id}` 分支缺一个前导 `/`，
   导致 `media/{uuid}/versions`、`media/{uuid}/references`、`media/tag/{uuid}/enabled`
   这些**已交付的 3.4.1.1 路径实际匹配不上**（在 HEAD 上用原正则复现过，不是本轮引入）。
   本轮新增的 `media/processing/...` 全部带显式 `\/`，匹配正常。
   修它要改另一个模块已交付的行为，因此留给 3.4.1.1 的 owner 决定。

4. **处理任务的跨进程恢复未做。** 任务在提交事务提交后入队线程池；
   若进程在入队前宕机，那条 PENDING 任务会留在库里等人重新提交。
   catalog 库里没有 tenants 表（租户目录在 identity 服务），因此无法在 RLS 下做跨租户扫描，
   要做需要独立的调度角色或由 identity 提供租户列表。本轮用「进度可见 + 可重新提交」
   作为可接受的边界，没有静默丢任务。
