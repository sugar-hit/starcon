# 3.4.1.1 媒体库

## 决策摘要

交付卷 03 商品主数据系统 3.4.1.1「媒体库」（D1–D2，路由 `/products/media`），
分支 `feat/3-4-1-1-media-library`，基线 `feat/3-3-3-1-measurement-units`（tip `7b99507`）。

蓝图 3.4.1.1 原文：「集中管理图片与视频：上传、分类标签、引用计数（被哪些 SPU/SKU/listing
使用）、版本、失效检测。支撑批量刊登时的"素材库复用"。」并写死
「媒体用**对象存储 + 元数据本地管理**的组合，不把二进制存业务库」。

台账里这一行原本标「阻塞」，根因是 V1 的 `product_asset` 是
`LIKE product_research INCLUDING ALL` 的同构复制：没有文件键、没有 `sku_id`、
没有任何图片/视频字段。六条核心决策：

1. **没有 drop `product_asset`——这是本轮最需要解释的一条。**
   任务书建议「用新迁移 drop 掉这个错表并重建」。实测发现它有两个**已交付模块**的活读者：
   `CatalogService.table()` 把 `assets` 映射到它，`/catalog/records/assets` 与
   `/products/assets` 页面在读，`CatalogIntegrationTest` 对 research/compliance/assets
   三种 kind 都有断言；`SkuDetailService.media()` 读它的 title/notes/reference 给 SKU 详情页
   媒体区，`SkuDetailIntegrationTest` 断言 `d.media()` 里能取到 title。
   而那条记录的形状（title / notes / reference / status / locale / target_country / expires_on）
   与媒体元数据（storage_key / media_type / byte_size / width / sha256）**没有任何交集**。
   塞进同一张表、或为拆表去重写两个已交付模块的写入所有权与测试，
   都是 V9 / V11 / V13 / V14 / V16 / V17 反复强调的**重开旧账**。
   故：媒体库走**全新六表**，不复用、不在 `product_asset` 上加字段；它原样保留，
   继续服务 3.1.x 的通用记录面。删它由 3.1.x 那条线自己决定。
   阻塞因此**不是靠删表解决的**，是靠给媒体库自己的权威数据解决的。

2. **元数据一律从字节探测，不接受调用方填写。**
   格式按**字节签名**判定而不是文件扩展名（扩展名正是要避免的错误来源），
   宽高用 `ImageIO` 从字节读，SHA-256 实算。
   理由是这些是「这份文件是什么」的客观事实：让调用方填就等于允许它声称
   「一个 1KB 的 jpg 有 4000×3000」，而刊登侧要拿这个尺寸去校验平台规格（3.4.1.2 的职责），
   错的尺寸会**静默**导致刊登失败。数据库层还加了
   `CHECK(media_type<>'IMAGE' OR (width_px IS NOT NULL AND height_px IS NOT NULL))` 兜一道。

3. **视频宽高与时长不探测；WEBP 明确拒收。**
   时长要解容器（MP4 的 moov/mvhd 或 EBML Timecode），JDK 不提供。
   与其写一个只覆盖 MP4 的半吊子解析器（对 WEBM 会给出错的秒数，而刊登侧会拿它算
   视频长度限制），不如**留空**——留空的语义是「未知」，不是 0 秒。前端
   `dimensionText` / `durationText` 相应地把空值显示成「未探测」而不是 0：
   显示成 0 会让运营以为「这个视频时长 0 秒」而做出错误判断。
   WEBP 同样明确拒收并给出可执行原因（先转 PNG/JPEG；3.4.1.2 交付后可自动转码），
   而不是让 `ImageIO.read` 静默返回 null 后报一句看不懂的错。

4. **同内容复用是 DB 层的唯一性，不是应用层的「查一下再说」。**
   `UNIQUE(tenant_id, content_sha256)`：同租户内相同字节只有一行素材，
   重复上传直接返回既有素材并带 `deduplicated=true`，既不新建行也不写第二份对象。
   这就是蓝图那句「素材库复用」的字面实现。
   **跨租户不复用**：素材是租户级资产，B 租户不能拿到 A 租户的存储键。

5. **当前版本字段由 DB 触发器同步，服务层不回写。**
   素材本体上冗余了当前版本的 storage_key / byte_size / content_sha256，
   换列表页不必再 join 版本表。但冗余必须**被强制**：
   - `sync_media_asset_from_version`（AFTER INSERT ON media_asset_version）是**唯一写入路径**，
     把素材本体同步成那一版的元数据并推进 current_version；
   - `guard_media_asset_current_version`（AFTER UPDATE 行级 ON media_asset）在绕过写入路径
     直接改本体时撞下来。
   两处各写一遍就有两个写入路径，而触发器只挡得住其中一个。

6. **失效检测真的把对象读回来算哈希；读失败 ≠ 文件缺失。**
   不信任元数据里那行 `content_sha256`——它记录的是**上传当时**的字节，
   文件之后被删 / 被覆盖 / 被迁移只有读回来才知道。
   五态：`UNKNOWN` / `VERIFIED` / `MISSING` / `SIZE_MISMATCH` / `CONTENT_MISMATCH`，
   每次结果落一行历史（不覆盖，「什么时候发现文件不见了」是要回溯的事实）。
   存储**读失败**单独报 503 而不是记成 MISSING：读失败可能是存储临时不可用或权限问题，
   报成 MISSING 会让运维去清理一个其实还在的文件——比误报更危险的方向。

## 关键 trade-off

- **引用表 vs 引用计数列**：蓝图要点名「被哪些 SPU/SKU/listing 使用」。
  只有整数字段答不出「被谁」，而删除保护要在 DB 层生效就需要删除那一刻能查到引用行。
  选可枚举的 `media_reference` 表。
  `owner_type` **只做 SPU 与 SKU**：04 刊登在 `services/channel` 且尚未交付，
  放一个指向别库不存在表的引用列就是 V16 头部否掉的方案 (B)「硬编码的假引用」。
  04 落地后走 event / 授权 API 往这张表写引用行。
- **引用去重用表达式索引**：直接 `UNIQUE(tenant_id,asset_id,owner_type,spu_id,sku_id)` 挡不住
  「两条都指向同一 SPU」——PG 的 UNIQUE 把 NULL 视作互不相等。
  用 `coalesce(spu_id,'000...'::uuid)` 折叠成哨兵值的表达式唯一索引才有去重效果。
  不去重的后果不是「多一行脏数据」，而是引用计数翻倍、删除保护看到一个比真实情况大的数字。
- **删除保护双层**：应用层先判并给出「被引用 N 处」这种可执行信息
  （触发器抛的 23503 只有约束名，对运营不可执行）；
  DB 触发器堵「判完之后、删除之前被刊登侧挂上引用」的并发窗口。
  删除时**所有版本的对象一并清掉**，只删当前版本会留下一堆没人知道是什么的孤儿文件。
- **权限沿用 `catalog.assets.*`**（V3 已有的素材域词表），不新造——
  新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
- **不套 V3 的 owner_id/department_id 数据范围**：素材库是租户共享资产库，
  同一个运营要在别人的素材上复用，套个人数据范围会让「素材库」失去意义。

## 开发中实测发现并修掉的真缺陷

这些都不是读代码能看出来的，只有真跑 PostgreSQL 16 + 真写文件系统才会暴露：

1. **`media_tag` 表多写了一条 CHECK 导致任何标签名都插不进去。**
   列上已有 `CHECK(btrim(name)<>')`，表级又重复写了
   `CHECK(btrim(code)<>'' AND btrim(name)<>'')`。后者里 `btrim(name)<>''` 的 `''`
   被 PG 按 `varchar(100)` 解析成**100 个空格的字面量**，于是约束变成
   `btrim(name) < '（100空格）'`——除了全空字符串，**任何名字都通不过**。
   实测报错是 `new row for relation "media_tag" violates check constraint "media_tag_check"`，
   完全指不到真因。
2. **版本一致性触发器与版本行外键构成循环依赖。** 最初写的是
   `BEFORE INSERT ON media_asset` 上校验「当前版本行已存在」，但版本行有指向素材行的外键，
   素材行必须先存在——于是素材行要求版本行先存在、版本行又要求素材行先存在，谁也写不进去。
   改成「素材行先插 → 版本行 AFTER INSERT 同步本体 → 素材行 AFTER UPDATE 行级守卫」。
3. **守卫最初写成语句级全表扫**，素材上千行时每次写入都退化成 O(n)。改行级。
4. **存储健康探针用固定键 `media/health/probe`**，所有租户共用一个键，
   两个租户同时点概览会互相删掉对方的探针对象、随机报「存储不可写」——
   一个只在多租户下偶发、极难定位的假故障。改成按租户前缀生成。
5. **`MediaService.assetRow` 用 `select *`**，取不到 `reference_count` / `version_count`
   两个派生列，`detail()` 抛 `Field "version_count" is not contained in row type`
   这种指不到真因的错。
6. **测试角色只给 `catalog.assets.*` 时读不到被引用的 SPU**：V3 的 `data_scope` 策略在
   `spu` 上是 RESTRICTIVE，操作是 `catalog.assets.*` 时要求 grant 里含 `catalog.spu.read`
   （那条策略把 assets 域映射到 spu 的数据范围）。症状是「商品不存在或不属于当前租户」，
   真正原因是授权词表缺了 spu 那一段。已写进测试注释。

## 文件清单

- 迁移：`backend/services/catalog/src/main/resources/db/migration/V18__media_library.sql`
- 后端：`MediaStorage.java` / `LocalMediaStorage.java` / `MediaRules.java` /
  `MediaService.java` / `MediaController.java` + `CatalogModels.java`（追加 14 个 record）
- 测试：`MediaIntegrationTest.java`（16 例）/ `MediaRulesTest.java`（10 例）
- 前端：`apps/web/src/app/products/media/page.tsx` /
  `components/catalog/media-model.ts` / `media-model.test.ts`（24 例）/ `media-workspace.tsx`
- 契约：`packages/api-contracts/src/catalog.ts` + `catalog.openapi.json`
  （由 `pnpm contracts:generate` 从真实 Spring spec 重新生成，18 个 Media* DTO）
- 代理白名单：`apps/web/src/lib/api-proxy.ts`
- 台账：`docs/product/capability-blueprint/module-progress.md` 第 3.4.1.1 行

## 后续待办

- **3.4.1.2 图片处理**（未开发，本模块是它的上游）：从 `media_asset_version` 取原图，
  处理结果落成新版本行，原图版本永不覆盖——版本表就是为它准备的。
  它交付后 WEBP 可直接上传并自动转码，届时删掉 MediaRules 里那段拒收。
- **04 刊登的 listing 引用**：`media_reference.owner_type` 需要加 `'LISTING'`
  与对应的无外键标识列（listing 在 `services/channel`，跨库 FK 不可能），
  写入路径走 event / 授权 API，不做跨库读写。
- **对象存储**：当前实现是本地文件系统（`MEDIA_STORAGE_ROOT`）。
  上生产前换 S3 / OSS 实现类，`MediaStorage` 端口已经为此留好边界。
- **3.1.x 决定 `product_asset` 的去留**：它现在只服务 `/catalog/records/assets`
  与 SKU 详情页媒体区；媒体库已不再读它。