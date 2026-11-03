-- 5.1.1.2 价目项批量维护：价目项（`price_list_item`）+ Excel 导入批次与导入明细行。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：在价目表内维护 SKU 价格，支持按分类/品牌/SKU 范围批量设价、阶梯价（按数量门槛）、
--             Excel 导入导出，保存前预览影响范围。
--   **输入**：价目项录入/批量操作（固定值、按百分比、按类目统一）；`price_list_item` 记录。
--   **输出**：价目项记录（SKU、单价、货币、数量门槛、生效区间）；导入校验报告。
--   **关系**：上游 = 5.1.1.1、3.1.2.1 SKU；下游 = 5.3 测算、5.4 调价。
--             **同优先级多条有效价目必须报错让用户选，不随机取**（实体目录 catalog 价格规则）。
--
-- ============================================================ 第一节：这张表归谁
--
-- 蓝图 05 卷首写：「`catalog` 服务拥有 `price_list` `price_list_item`」。
-- V431（5.1.1.1）建了 `price_list` 与 `price_list_scope`，并**刻意不建** `price_list_item`
-- （第五节原话：「价目项是 5.1.1.2 的写入所有权」）。
-- ⇒ 本迁移是 `price_list_item` 的**唯一写入所有者**。
--
-- 本迁移建**三张**表：
--   * `price_list_item`       —— 一条价目项（SKU × 单价 × 数量门槛 × 生效区间）。
--   * `price_list_import_batch`  —— 一次 Excel 导入的**批次**（先报后改的两步留痕）。
--   * `price_list_import_row`    —— 批次里的**一行**及其校验结论（导入校验报告的落库形态）。
--
-- 🔴 **零 ALTER 任何既有表**（`price_list` / `price_list_scope` / `sku` / `spu` 一律不动），
--    **零外键**（理由见第六节：外键会打破既有测试的 TRUNCATE 依赖图）。
--
-- ============================================================ 第二节：🔴 价目项**没有**币种列
--
-- V431 第三节给本模块定了三条接口约定，本迁移逐条兑现：
--   1. ✅ `price_list_item` **没有** `currency` 列 —— 币种只能来自 `price_list.currency`。
--      蓝图加粗句「一价目一币种是硬约束」在下游的落法就是这一条：
--      **价目项在结构上无法跨币种**（同一张价目表里的所有价目项共享同一个币种），
--      硬约束不靠服务层记得检查，靠表结构本身。
--   2. ✅ `price_list_item.price_list_id` 指向 `price_list.id`（稳定主键，改名不改引用）。
--   3. ✅ 建 `AFTER INSERT/UPDATE/DELETE` 触发器调用 V431 提供的
--      `price_list_sync_item_count()`，把价目项数量写回 `price_list.item_count`。
--
-- 🔴 Excel 导入文件里**允许**有「币种」列（运营从别的表里导出来的表头就带它），
--    但那一列只用于**校验**：与服务端这张表的币种不一致 ⇒ 该行判为错误、整批不落库。
--    价目项行本身**仍然不存**币种 —— 校验完就把这一列丢掉。
--
-- ============================================================ 第三节：🔴 阶梯价 = 数量门槛
--
-- 蓝图：「阶梯价（按数量门槛）」。本迁移的落法：**一条价目项行 = 一个档位**。
--   * `min_quantity numeric(19,6) NOT NULL DEFAULT 0` —— 该档的**起始数量门槛**，
--     `0` = 基础档（无门槛，从 1 件起适用）。
--   * 同一个 SKU 在同一张表里可以有多行，`min_quantity` 递增 —— 这就是阶梯价。
--   * 「数量 100 以上适用哪一档」的判定规则：**取 `min_quantity <= 数量` 的行里
--     `min_quantity` 最大的那一档**（见 `PriceListItemRules`）。
--
-- 🔴 **阶梯不是冲突**：同一个 SKU 同时有两档生效是**正常业务**，
-- 所以冲突判定必须把「同 SKU」细化成「同 SKU **且同数量门槛**」——
-- 否则阶梯价一上线就会被自己的冲突报告判成冲突。
--
-- ============================================================ 第四节：🔴 同优先级多条有效价目 ⇒ 报错让用户选
--
-- 蓝图加粗句：「**同优先级多条有效价目必须报错让用户选，不随机取**」。
-- 本迁移提供这条规则能成立的三块结构：
--   1. `priority integer NOT NULL DEFAULT 0` —— 价目项的优先级。
--      **数字越小优先级越高**（与 3.6.2.1 供应商报价的 `priority` 同一约定，
--      不在本仓另造一套「越大越优先」的反向语义）。
--   2. 多个价目项命中同一个 SKU 的同一档位时，取 `priority` 最小的那条；
--      **若最小的 priority 命中多条 ⇒ 判定为歧义**，
--      解析出口抛 409 并把候选清单逐条带出来，**绝不随便挑一条**。
--   3. 🔴 **本表不提供「随机取一条」的兜底路径**：
--      `priority` 列 NOT NULL、`min_quantity` 列 NOT NULL，
--      「多条并列」这个状态在结构上无法被压平成一条。
--
-- 🔴 **本表只管「同一张价目表内部」的歧义**。
--    跨价目表的优先级与冲突（「同 SKU + 同店铺 + 同站点 + 同时段生效的多个价目」）
--    是 **5.1.2.1** 的职责（它是本表的下游守门员）。本迁移**不**建跨表的优先级结构。
--
-- ============================================================ 第五节：金额与数量一律十进制
--
--   * `unit_price numeric(19,6)` —— 单价。**禁浮点**（AGENTS.md）。
--     列宽 19.6 = 13 位整数 + 6 位小数：单价到百万级、精度到 0.000001，
--     足够覆盖「按百分比算完四舍五入到 6 位」的结果（`PriceListItemRules` 的口径）。
--   * `min_quantity numeric(19,6)` —— 数量门槛。**它也是数量**，所以也是 NUMERIC(19,6)，
--     不是 INTEGER：0.5 件起订、0.001 公斤起订是真实存在的业务场景。
--   * 全表没有任何 `float` / `double precision` 列。
--
-- ============================================================ 第六节：引用校验用触发器，不用外键
--
-- 沿用 V431 第七节的同一手法（上一批 batch-37 踩过的坑）：
-- 给**既有表**加 FOREIGN KEY 会打破同服务既有测试的 TRUNCATE ⇒ 整批用例 Errors。
-- 本迁移因此**零个外键**，引用完整性由 BEFORE INSERT/UPDATE 触发器保证
-- （判据与外键等价：引用不存在 ⇒ 报 23514，但**不参与 TRUNCATE 的依赖图**）。
--

-- ============================================================ 第七节：表结构

-- 5.1 价目项
CREATE TABLE price_list_item (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 所属价目表。**不建外键**（第六节），引用完整性由触发器 guard 保证。
  -- 稳定主键：改名不改引用（V431 第三节的第 2 条约定）。
  price_list_id uuid NOT NULL,

  -- SKU（3.1.2.1，catalog 卷的商品中心）。同服务 ⇒ 同一张库，但仍然**不建外键**（第六节）。
  sku_id uuid NOT NULL,

  -- 🔴 单价：NUMERIC(19,6)，**禁浮点**（第五节）。允许 0（赠品 / 清仓），不允许负数。
  unit_price numeric(19,6) NOT NULL CHECK(unit_price >= 0),

  -- 🔴 数量门槛（阶梯价）：该档的起始数量。0 = 基础档（第三节）。
  min_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(min_quantity >= 0),

  -- 🔴 优先级：**数字越小优先级越高**（第四节）。默认 0。
  priority integer NOT NULL DEFAULT 0,

  -- 生效区间：**半开区间** [valid_from, valid_to)，与 5.1.1.1 的价目表同一约定
  -- （起点含、终点不含；终点为空 = 长期有效）。
  valid_from timestamptz NOT NULL,
  valid_to timestamptz,

  -- 停用 = 这一条不参与定价（不删区间，历史价目项仍然引用它）。
  enabled boolean NOT NULL DEFAULT true,

  note text NOT NULL DEFAULT '',
  -- 乐观锁版本（与 5.1.1.1 的 price_list.version 同一手法：两个人同时改同一条价目项时，
  -- 后提交的那个会拿到 409，而不是把前一个人的改动悄悄盖掉）。
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一个 SKU 的同一档位、同一生效起点，只能有一条 ——
  -- 「同一个瞬间、同一档、两条价目」在结构上就是歧义（第四节），不该被允许写进来。
  UNIQUE(tenant_id, price_list_id, sku_id, min_quantity, valid_from),

  -- 半开区间必须非空且起点早于终点。
  CHECK(valid_to IS NULL OR valid_to > valid_from)
);

-- 台账页（默认按最近改过的排序）。
CREATE INDEX price_list_item_by_updated ON price_list_item(tenant_id, price_list_id, updated_at DESC, id);
-- 「这张表里这个 SKU 的全部档位」（解析与冲突检测的主查询路径）。
CREATE INDEX price_list_item_by_sku ON price_list_item(tenant_id, price_list_id, sku_id, min_quantity);
-- 「此刻生效的价目项」。
CREATE INDEX price_list_item_by_validity ON price_list_item(tenant_id, price_list_id, valid_from, valid_to);

-- 5.2 导入批次（先报后改的两步留痕）
--
-- 🔴 为什么要有批次表：蓝图要求「导入校验报告」是本模块的**输出**，
-- 而「有校验错误就不许落库（先报后改）」意味着**校验与落库是两次动作**。
-- 把第一次（校验）落在批次表上，运营改完文件**重新上传**即可 ——
-- 不必在浏览器里暂存一份可能被人篡改的文件字节。
CREATE TABLE price_list_import_batch (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  price_list_id uuid NOT NULL,

  -- 原始文件名（运营认得出自己传的是哪份文件）。
  file_name varchar(255) NOT NULL,

  -- PREVIEW = 只校验过、**还没落库**；APPLIED = 已落库；REJECTED = 校验有错误、整批被拒。
  status text NOT NULL CHECK(status IN ('PREVIEW','APPLIED','REJECTED')),

  total_rows integer NOT NULL CHECK(total_rows >= 0),
  -- 三种行数：ok = 可落库、error = 有错（整批不落库）、warning = 合法但会影响现有数据。
  ok_rows integer NOT NULL DEFAULT 0 CHECK(ok_rows >= 0),
  error_rows integer NOT NULL DEFAULT 0 CHECK(error_rows >= 0),
  warning_rows integer NOT NULL DEFAULT 0 CHECK(warning_rows >= 0),
  applied_rows integer NOT NULL DEFAULT 0 CHECK(applied_rows >= 0),

  actor_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  applied_at timestamptz,

  UNIQUE(tenant_id, id),
  CHECK(btrim(file_name) <> ''),
  -- 已落库的批次必须有落库时刻；未落库的批次必须没有。
  CHECK((status = 'APPLIED') = (applied_at IS NOT NULL))
);

CREATE INDEX price_list_import_batch_by_list
  ON price_list_import_batch(tenant_id, price_list_id, created_at DESC, id);

-- 5.3 导入明细行（= 导入校验报告的落库形态）
--
-- 🔴 这一行是「**这一格 Excel 解析出来的值**」+「**这一行的问题**」。
--    校验通过后落库时写的就是这里读出来的值 ——
--    **不重新解析文件**（重新解析就可能落库到与刚才校验过的不是同一批数据）。
CREATE TABLE price_list_import_row (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  batch_id uuid NOT NULL,

  -- Excel 里的行号（1 = 表头之后的第一行）。运营按这个行号改文件。
  row_no integer NOT NULL CHECK(row_no > 0),

  -- 原样保留文件里的取值（出错时运营看得见自己写的是什么）。
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- 🔴 单价与数量门槛都是 NUMERIC(19,6)（第五节）；解析失败时为 NULL（那是「解析不出来」，
  --    与「解析出来是 0」是两件事 —— 所以这里允许 NULL 而不是拿 0 顶替）。
  unit_price numeric(19,6),
  min_quantity numeric(19,6) NOT NULL DEFAULT 0,
  priority integer NOT NULL DEFAULT 0,
  valid_from timestamptz,
  valid_to timestamptz,
  -- 文件里声明的币种（**只用于校验**，不落到价目项上 —— 第二节）。
  declared_currency varchar(16),
  enabled boolean NOT NULL DEFAULT true,
  note text NOT NULL DEFAULT '',

  -- OK = 可落库；ERROR = 有错（**整批不落库**）。
  status text NOT NULL CHECK(status IN ('OK','ERROR')),
  -- 这一行的问题清单（JSON 数组，每条一句话说明为什么）。空数组 = 没问题。
  messages jsonb NOT NULL DEFAULT '[]'::jsonb,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, batch_id, row_no)
);

CREATE INDEX price_list_import_row_by_batch ON price_list_import_row(tenant_id, batch_id, row_no);

-- ============================================================ 第八节：触发器

-- 9.1 引用校验（零外键的等价替代，第六节）
CREATE FUNCTION guard_price_list_item() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM price_list p
     WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.price_list_id) THEN
    RAISE EXCEPTION 'Price list item % must reference an existing price list',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM sku s
     WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.sku_id) THEN
    RAISE EXCEPTION 'Price list item % must reference an existing sku',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER price_list_item_guard
  BEFORE INSERT OR UPDATE ON price_list_item
  FOR EACH ROW EXECUTE FUNCTION guard_price_list_item();

-- 9.2 表内价目项数量维护（V431 第三节的第 3 条约定）
--
-- 🔴 触发器建在**本表**上，函数用 V431 提供的那个（V431 第三节原话：
--    「本迁移只提供函数，不建触发器：触发器必须建在 5.1.1.2 自己的表上」）。
-- 建好之后，5.1.1.1 的 `price_list.item_count` 才会真的跟着本表的写入变化。
CREATE TRIGGER price_list_item_sync_item_count
  AFTER INSERT OR UPDATE OR DELETE ON price_list_item
  FOR EACH ROW EXECUTE FUNCTION price_list_sync_item_count();

-- 9.3 导入批次的引用校验
CREATE FUNCTION guard_price_list_import_batch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM price_list p
     WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.price_list_id) THEN
    RAISE EXCEPTION 'Price list import batch % must reference an existing price list',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER price_list_import_batch_guard
  BEFORE INSERT OR UPDATE ON price_list_import_batch
  FOR EACH ROW EXECUTE FUNCTION guard_price_list_import_batch();

-- 9.4 导入明细行的引用校验
CREATE FUNCTION guard_price_list_import_row() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM price_list_import_batch b
     WHERE b.tenant_id = NEW.tenant_id AND b.id = NEW.batch_id) THEN
    RAISE EXCEPTION 'Price list import row % must reference an existing import batch',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER price_list_import_row_guard
  BEFORE INSERT OR UPDATE ON price_list_import_row
  FOR EACH ROW EXECUTE FUNCTION guard_price_list_import_row();

-- ============================================================ 第九节：RLS 与授权
--
-- 三张表都是租户级业务表（价目项是这个租户自己的价格）：
-- `tenant_id uuid NOT NULL` + `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`
-- + isolation 策略（照抄 V431 第八节的 `DO $$ FOREACH` 写法，逐字相同）。
--
-- **FORCE 不能漏**：漏掉 FORCE 之后表 owner（迁移角色）仍能绕过策略读别人的价目项 ——
-- 那是一条看不见的越权面（别人的零售价与阶梯门槛）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'price_list_item',
    'price_list_import_batch',
    'price_list_import_row'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON price_list_item TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_list_item TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_list_import_batch TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_list_import_batch TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_list_import_row TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_list_import_row TO starcon_app;
