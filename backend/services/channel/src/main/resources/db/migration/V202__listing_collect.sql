-- ===========================================================================
-- 4.4.1.1 链接采集（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.4.1.1 链接采集
--   - 职责：粘贴外部商品链接（1688/淘宝/供应商站/同行 listing），抓取标题、主图、规格、属性、
--           描述等，生成采集草稿供认领为内部商品。
--   - 输入：商品 URL（支持平台白名单）；抓取任务。
--   - 输出：采集到的商品草稿（图片下载入媒体库）；来源 URL 与抓取时间。
--   - 关系：上游 = 外部页面；下游 = 4.4.1.3 采集箱、3.1.1.1（认领为 SPU）。
--           **采集只产生草稿**，进入正式商品库必须走 03 的建档流程并补全属性与合规信息。
--
-- ---------------------------------------------------------------------------
-- 零、🔴 归属 channel 而不是 catalog 的理由
-- ---------------------------------------------------------------------------
-- 采集的输入是**外部页面 URL**，输出是**采集草稿**。这条链路上没有任何一张
-- catalog 的事实表被写：
--   * 采集草稿不是 SPU / SKU —— 建档是 03 的 3.1.1.1 流程，认领是 4.4.1.3 采集箱。
--   * 采集草稿不是 listing —— listing 是"已在平台上架的商品"（V171），采集品还没上架。
--   * 图片**不入媒体库** —— 媒体库归 03 catalog，AGENTS.md 禁止跨服务数据库访问，
--     本模块**只记录图片 URL**（见下"三"）。
-- ⇒ 结论：本模块的产物是 channel 自己的事实（抓取任务 + 采集草稿），零跨服务写入。
--
-- 本迁移**纯新建三张表、零 ALTER 任何既有表**（与 V171 / V177 / V183 / V187 / V190 同一纪律）：
--   * V171 `listing` / `listing_variant` —— 平台商品，**本模块不碰**
--   * V175 `listing_content_snapshot` —— 平台内容快照，**本模块不碰**
--   * V190 `listing_claim` / `listing_claim_field` —— 4.4.2.1 商品认领（跨店搬家），
--     语义完全不同（把 A 店的 listing 认领到 B 店，不是从外部链接采集），**本模块不碰**
--   * V193 `listing_migration_*` —— 4.4.2.2 批量搬迁，**本模块不碰**
--
-- ---------------------------------------------------------------------------
-- 一、🔴 边界一：采集**只产生草稿**，不产生正式商品
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「**采集只产生草稿**」。这条不能只靠服务层自觉，要让它在结构上就长不出来：
--
--   (a) 本迁移**不建**任何 SPU / SKU 表，也**不建**指向 catalog 的外键（跨服务 FK 禁止）。
--   (b) 采集草稿表**没有** `spu_id` / `sku_id` / `internal_sku_id` 列 ——
--       一列都没有，就不可能"顺手续建一个商品"。
--   (c) 采集草稿表**没有** `status='PUBLISHED'` 之类的"已入库"状态；它的状态只有
--       抓取本身的成败（见 listing_collect_task.state），没有商品生命周期。
--   (d) 认领（草稿 → 内部商品）由 4.4.1.3 采集箱 → 3.1.1.1 建档流程完成，
--       那条链路的写所有权**不在本模块**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 边界二：图片**只记 URL**，不入媒体库
-- ---------------------------------------------------------------------------
-- 蓝图写「图片下载入媒体库」，但媒体库是 03 catalog 的事实表，AGENTS.md 明令
-- **禁止跨服务数据库访问**。两个模块同时交付会互相打脸，所以本模块的取舍是：
--
--   * 采集草稿记录 **图片 URL 列表**（主图 + 附图），**不下载二进制、不写媒体表**。
--   * 「下载入媒体库」这一步由 03 catalog 侧的媒体服务承担；本模块在能力声明里
--     把这一维显式标成 available=false + ownerModule=03，并写明影响。
--
-- 这不是"少做一半"，而是把跨服务写的那一半交还给它的所有者；本模块交付的是
-- 「图片的来源 URL 列表」这件**本模块自己能独立验证**的事。
--
-- ---------------------------------------------------------------------------
-- 三、白名单（🔴 抓取面收窄到配置内的来源域名）
-- ---------------------------------------------------------------------------
-- 蓝图输入句「商品 URL（**支持平台白名单**）」。白名单是**全局码表**：
-- 它是 starcon 允许去抓的**平台**清单（1688 / 淘宝 / 天猫 / 供应商站 / 同行站），
-- 与租户无关 —— 与 V1 的 `platform` / `marketplace` 全局码表同一先例：
--   * 不带 tenant_id、不启用 RLS、**只授 SELECT**（channel_app 可读，starcon_app 无权）。
--   * 租户**不能**自行加白名单域名 ⇒ 抓取面不会被单个租户撑大（SSRF 面的关键收窄）。
--   * 运维改白名单 = 改这张码表（迁移 / 运维流程），不经过任何业务端点。
--
-- 匹配规则（服务层 `ListingCollectService` 实现，DB 用 CHECK 钉住形状）：
--   * 请求 URL 的 **host（去端口）** 等于 `host`，**或**以 `.` + `host` 结尾
--     （⇒ `detail.1688.com` 命中 `1688.com`，而 `evil-1688.com` **不**命中）。
--   * 只允许 http / https 两种 scheme（file: / javascript: 等一律拒）。
--   * `enabled=false` 的行不参与匹配（停用某个平台不需要改代码）。
--
-- ---------------------------------------------------------------------------
-- 四、限频与去重（蓝图 4.4.1.2 原文：「抓取必须有频率限制与去重」）
-- ---------------------------------------------------------------------------
-- 本模块是 4.4.1 的**第一个**模块 ⇒ 限频与去重必须**从这一层就长出来**，
-- 否则 4.4.1.2 的批量采集建在流沙上。
--
--   * **限频**：同一租户 + 同一 host 的两次抓取之间必须有最小间隔
--     （`listing_collect_source.min_interval_seconds`，按平台配置）。
--     间隔的基准是 `listing_collect_task` 里**该租户该 host 最近一次抓取**的时间 ——
--     限频状态**不另建一张表**：它是抓取历史本身的一个投影，
--     另建表就会出现"限频表说没抓过、抓取历史说抓过"的两份事实。
--     超限时返回 429 并在报文里写明还需等待多少秒。
--
--   * **去重**：同一 URL 不重复抓。规范化 URL（去 utm 之类跟踪参数、归一 host、
--     排序查询参数、去掉 fragment）后若**已有成功记录**，直接返回既有草稿，
--     **不再发第二次 HTTP 请求**。规范化结果存在 `listing_collect_task.normalized_url`，
--     草稿表上 `UNIQUE(tenant_id, normalized_url)` 从 DB 层钉死"一个规范化 URL 一份草稿"。
--     失败的抓取**不**占去重名额（否则一次网络抖动就把这个 URL 永久锁死）。
--
-- ---------------------------------------------------------------------------
-- 五、金额与数量纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 本表**唯一的金额列**是 `source_price_amount numeric(19,4)`：
--   * 它是**外部页面原文标价**（来自 JSON-LD `offers.price` 或 `product:price:amount`），
--     **不是**我方售价 —— 售价归 05 定价（整卷未交付），本模块**不产出任何我方售价**。
--   * 因此它的列名带 `source_` 前缀，能力声明里也把"我方售价"标成 available=false。
--   * 用 BigDecimal 绑定，DB 用 NUMERIC —— **无 float / double 一列**。
--
-- 数量列：没有"我方库存数量"（内部可售量归 07 库存），只有计数（图片数 / 规格项数）
-- 与整数的 `attempt_no` / `http_status`。
--
-- ---------------------------------------------------------------------------
-- 六、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 所有时间列为 `timestamptz`。服务层用 OffsetDateTime 绑定，values 位置写
-- `?::timestamptz` —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
-- 集成测试**断言写回的值逐字相等**（`captured_at` / `started_at` / `finished_at`）。
--
-- ===========================================================================
-- listing_collect_source · 平台白名单（全局码表，不带 tenant_id）
-- ===========================================================================
CREATE TABLE listing_collect_source (
  id                uuid PRIMARY KEY,
  code              varchar(32) NOT NULL UNIQUE,
  name              varchar(100) NOT NULL,
  -- 允许抓取的域名（不含端口）。匹配规则：host 等于它，或 host 以 '.' + 它结尾。
  host              varchar(255) NOT NULL,
  -- 平台类别：PLATFORM 平台 / SUPPLIER 供应商站 / PEER 同行站
  kind              text NOT NULL CHECK(kind IN ('PLATFORM','SUPPLIER','PEER')),
  -- 🔴 该平台两次抓取之间的最小间隔（秒）。限频基准写在任务表，不另建限频表。
  min_interval_seconds integer NOT NULL DEFAULT 30 CHECK(min_interval_seconds>=0),
  enabled           boolean NOT NULL DEFAULT true,
  position          int NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(host),
  CHECK(btrim(code)<>'' AND btrim(name)<>'' AND btrim(host)<>'')
);
COMMENT ON TABLE listing_collect_source IS
  '4.4.1.1 · 平台白名单（全局码表，发布时随代码更新）。只授 SELECT：租户不能自行加白名单域名，'
  '抓取面因此不会因为单个租户而变大。';

-- 白名单种子：真实存在、公开可访问的平台站点。
INSERT INTO listing_collect_source (id,code,name,host,kind,min_interval_seconds,enabled,position) VALUES
  ('6c1d0001-0000-4000-8000-000000000001','ALI_1688','1688 平台','1688.com','PLATFORM',30,true,10),
  ('6c1d0001-0000-4000-8000-000000000002','TAOBAO','淘宝','taobao.com','PLATFORM',30,true,20),
  ('6c1d0001-0000-4000-8000-000000000003','TMALL','天猫','tmall.com','PLATFORM',30,true,30),
  ('6c1d0001-0000-4000-8000-000000000004','ALIBABA','阿里巴巴国际站（供应商批发）','alibaba.com','SUPPLIER',60,true,40),
  ('6c1d0001-0000-4000-8000-000000000005','MADE_IN_CHINA','中国制造网（供应商站）','made-in-china.com','SUPPLIER',60,true,50),
  ('6c1d0001-0000-4000-8000-000000000006','DHGATE','敦煌网（同行/供应商站）','dhgate.com','PEER',60,true,60);

-- ===========================================================================
-- listing_collect_task · 一次链接采集的抓取任务
-- ===========================================================================
CREATE TABLE listing_collect_task (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 命中的白名单行（🔴 恒非空：不在白名单的 URL 根本不会被抓，不会留下任务）
  source_id         uuid NOT NULL REFERENCES listing_collect_source(id),
  -- 用户粘进来的原始 URL（原文照抄，不改写）
  source_url        text NOT NULL,
  -- 规范化 URL（去跟踪参数、归一 host、排序查询参数、去 fragment）—— **去重键**
  normalized_url    text NOT NULL,
  -- 跟随重定向后的最终 URL（没有重定向时等于 source_url）
  final_url         text NOT NULL,
  -- 请求 host（去端口）—— **限频键的一部分**
  host              varchar(255) NOT NULL,
  -- 抓取结果：SUCCEEDED 成功 / FAILED 失败。
  -- 🔴 这里**没有**商品生命周期状态（草稿 / 已发布 / 已下架…）——
  --    采集草稿还没有进入商品库，"已发布"这件事在本模块里根本不存在。
  state             text NOT NULL CHECK(state IN ('SUCCEEDED','FAILED')),
  -- 失败原因（原文，成功时为空串）
  error_message     text NOT NULL DEFAULT '',
  -- 外部站点的 HTTP 状态码（网络层失败时为 0）
  http_status       int NOT NULL DEFAULT 0,
  -- 第几次尝试同一个规范化 URL（1 = 首次）
  attempt_no        int NOT NULL DEFAULT 1 CHECK(attempt_no>=1),
  actor_id          uuid,
  -- 负责人 / 部门：可见性随它收敛（与 V1 shop / V171 listing 同一纪律）
  owner_id          uuid,
  department_id     uuid,
  started_at        timestamptz NOT NULL,
  finished_at       timestamptz NOT NULL,
  -- 抓取时刻（外部页面被读到的时刻）—— 蓝图输出里的"抓取时间"
  captured_at       timestamptz NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- 🔴 去重的 DB 级钉死：**同一个规范化 URL 在同一租户下只能有一条成功抓取**。
-- 注意这里是**部分唯一索引**而不是整表 UNIQUE ——
-- 失败的抓取必须能重试（网络抖动不该把这个 URL 永久锁死），
-- 所以唯一性只覆盖 state='SUCCEEDED' 的行：
--   * 重复成功 ⇒ 23505 ⇒ 不会静默产生两份"来源相同"的草稿；
--   * 失败可以留多条 ⇒ 抓取历史如实记录"试了几次、每次为什么失败"。
-- 草稿表上的 UNIQUE(tenant_id, normalized_url) 是同一件事的第二道防线。
CREATE UNIQUE INDEX listing_collect_task_one_success
  ON listing_collect_task(tenant_id, normalized_url) WHERE state='SUCCEEDED';

-- 抓取历史（列表页默认排序：时间倒序）
CREATE INDEX listing_collect_task_recent   ON listing_collect_task(tenant_id, created_at DESC, id);
-- 限频基准：该租户该 host 最近一次抓取
CREATE INDEX listing_collect_task_host     ON listing_collect_task(tenant_id, host, started_at DESC);
-- 去重查找：同租户 + 规范化 URL
CREATE INDEX listing_collect_task_url      ON listing_collect_task(tenant_id, normalized_url);
-- 状态筛选
CREATE INDEX listing_collect_task_state    ON listing_collect_task(tenant_id, state);
-- 来源平台筛选
CREATE INDEX listing_collect_task_source   ON listing_collect_task(tenant_id, source_id);

-- ===========================================================================
-- listing_collect_draft · 采集草稿（🔴 不是商品，不是 listing）
-- ===========================================================================
CREATE TABLE listing_collect_draft (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  task_id           uuid NOT NULL,
  -- 命中的白名单行
  source_id         uuid NOT NULL REFERENCES listing_collect_source(id),
  -- 来源 URL 原文 + 规范化 URL（去重键）
  source_url        text NOT NULL,
  normalized_url    text NOT NULL,
  -- 抓到的标题（JSON-LD Product.name → og:title → <title>，按此优先级）
  title             text NOT NULL DEFAULT '',
  -- 🔴 主图 URL（**只记 URL，不下载二进制**）
  main_image_url    text NOT NULL DEFAULT '',
  -- 🔴 附图 URL 列表（**只记 URL，不下载二进制**）
  image_urls        jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- 规格（JSON-LD additionalProperty + 商品页规格表，键值对）
  specs             jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 属性（品牌 / 货号 / 类目等键值对）
  attributes        jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 商品描述（纯文本，标签已剥离）
  description       text NOT NULL DEFAULT '',
  -- 🔴 外部页面原文标价（**不是我方售价**；售价归 05 定价）
  source_price_amount   numeric(19,4),
  source_price_currency varchar(16),
  -- 抓取时刻（与任务行的 captured_at 逐字相等）
  captured_at       timestamptz NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 一个规范化 URL 在同一租户下只有一份草稿（去重的 DB 级钉死）
  UNIQUE(tenant_id, normalized_url),
  FOREIGN KEY (tenant_id, task_id) REFERENCES listing_collect_task(tenant_id, id) ON DELETE CASCADE,
  -- jsonb 结构纪律：图片列表必须是数组，规格 / 属性必须是对象。
  -- 存成字符串的那些会让界面只能显示一行 JSON 原文，等于没有结构化对照。
  CHECK(jsonb_typeof(image_urls) = 'array'),
  CHECK(jsonb_typeof(specs) = 'object'),
  CHECK(jsonb_typeof(attributes) = 'object'),
  -- 🔴 草稿不能没有标题：没有标题的草稿在采集箱里认领时无从判断认领的是什么
  CHECK(btrim(title) <> '')
);
COMMENT ON TABLE listing_collect_draft IS
  '4.4.1.1 · 采集草稿。🔴 不是 SPU / SKU / listing：本表**没有**任何商品主数据列，'
  '认领为内部商品走 4.4.1.3 采集箱 → 3.1.1.1 建档。图片**只记 URL**，媒体库归 03 catalog。';

-- 采集箱按来源分组（4.4.1.3 未交付，但索引先按真实查询维度建好）
CREATE INDEX listing_collect_draft_source  ON listing_collect_draft(tenant_id, source_id, created_at DESC);
CREATE INDEX listing_collect_draft_task    ON listing_collect_draft(tenant_id, task_id);
CREATE INDEX listing_collect_draft_recent  ON listing_collect_draft(tenant_id, created_at DESC, id);

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V169 / V170 / V171 / V175 / V183 / V187 / V190 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['listing_collect_task','listing_collect_draft'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 数据范围：采集任务 / 采集草稿按**负责人 / 部门**收敛
-- ---------------------------------------------------------------------------
-- 与 V171 `listing` / V1 `shop` 同一纪律（channel_scope 读 app.grants 的 scopeMode）：
-- ALL 全租户可见 / SELF 只看自己抓的 / DEPARTMENT 看本部门的。
-- 注意 SELF 语义要求 owner_id 非空 ⇒ 抓取时必须写入 owner_id（app.actor）。
CREATE POLICY data_scope ON listing_collect_task AS RESTRICTIVE
  USING (channel_scope(channel_operation(),owner_id,department_id))
  WITH CHECK (channel_scope(channel_operation(),owner_id,department_id));

-- 草稿的可见性随它所属的抓取任务收敛
CREATE POLICY data_scope ON listing_collect_draft AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_collect_task t WHERE t.tenant_id=listing_collect_draft.tenant_id
                 AND t.id=listing_collect_draft.task_id
                 AND channel_scope(channel_operation(),t.owner_id,t.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_collect_task t WHERE t.tenant_id=listing_collect_draft.tenant_id
                 AND t.id=listing_collect_draft.task_id
                 AND channel_scope(channel_operation(),t.owner_id,t.department_id)));

-- ---------------------------------------------------------------------------
-- 抓取历史不可删除：它是"这次抓取发生过（成功或失败）"的留痕
-- ---------------------------------------------------------------------------
CREATE FUNCTION refuse_listing_collect_task_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'A listing collect task is an audit fact and must not be deleted' USING ERRCODE='23514';
END $$;
CREATE TRIGGER listing_collect_task_no_delete BEFORE DELETE ON listing_collect_task
  FOR EACH ROW EXECUTE FUNCTION refuse_listing_collect_task_delete();

-- 采集草稿**允许**删除：草稿是工作副本，清理权归 4.4.1.3 采集箱（批量删除）。
-- 本模块不暴露删除端点，但保留 DB 权限给下游模块。

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1..V199 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

-- 白名单是全局只读码表：channel_app 可读；starcon_app 是跨服务只读角色，
-- 拿不到任何业务表（与 V1 / V171 / V183 同口径）。
GRANT SELECT ON listing_collect_source TO channel_app;

-- 🔴 刻意**不授 DELETE**（任务是不可删除的留痕；草稿的删除权留给 4.4.1.3 采集箱）。
GRANT SELECT,INSERT,UPDATE ON listing_collect_task TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON listing_collect_draft TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表。
REVOKE ALL ON listing_collect_task, listing_collect_draft FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：复用 identity 已注册的 channel.operation.*（identity 服务零改动）
-- ---------------------------------------------------------------------------
-- 链接采集属于「平台操作」语义，与 4.1.1.1 列表、4.4.2.1 商品认领、4.3.1.2 批量发布同族，
-- 因此 identity 服务零改动。
