-- 3.1.3.2 组合唯一性校验：同一 SPU 下的规格值组合唯一性 + 生成 SKU 矩阵前的预校验落点 + 待建 SKU 草稿。
--
-- 蓝图原文（本迁移的技术兑现口径）：
--   * **唯一性约束在数据库层也要有**，应用层校验只用于友好提示，不能替代 DB 约束。
--     下面 `spu_variant_combination` 的 UNIQUE(tenant_id,spu_id,signature) 就是这个约束本体，
--     另有两条触发器补齐"跨表"和"签名与轴值一致"两处应用层管不到的洞。
--   * 输出 = 冲突清单（重复组合）；通过则生成**待建 SKU 草稿**（草稿不是 SKU 本身）。
--
-- 边界（沿 V9 结尾注释继续，不重开已交付的账）：
--   * **不建 SKU 行**。本模块只做到"草稿"为止：真正落 SKU 是下游 3.1.2.1 的职责，
--     它同时要分配 sku.code 与 barcode（V7 的编码方案），本模块不抢那条链路。
--   * **`sku` 表已由 V1 建好且带 UNIQUE(tenant_id,spu_id,variant_signature)**：
--     已兑现的真实 SKU 那一侧的唯一性是**既有**事实，本迁移不改 `sku` 的列，只与之互不打架。
--   * **签名格式约定**：按"轴编码升序、`编码=取值`、用 `|` 连接"生成。取值字符集来自 V9 的
--     attribute_option 值源（`[\p{IsHan}A-Za-z0-9 _\-./]`），**不含 `=` 与 `|`**，
--     所以这两个分隔符不可能出现在取值里，拼接结果不会被内容本身截断或产生歧义。
--     Java 侧 `CombinationRules` 用同一格式，两边同口径。
--   * **本表不写入"读到的"既有 SKU 组合**：读路径不做写。既有 SKU 的组合在查询时实时解析
--     （attribute_value_item 上的轴值），因此不依赖任何一次性的回填，也不会有回填漏掉的窗口。

-- 建表顺序（外键依赖，不能倒）：
--   sku_matrix_draft（只依赖 spu）→ spu_variant_combination（依赖 spu + 草稿）
--   → sku_matrix_draft_item（依赖草稿 + 组合）→ spu_variant_combination_value（依赖组合 + 规格选项）。

-- ------------------------------------------------------------ 矩阵草稿（待建 SKU 草稿）
CREATE TABLE sku_matrix_draft (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, spu_id uuid NOT NULL,
 -- MATRIX = 按各轴启用选项的笛卡尔积展开；EXPLICIT = 调用方显式提交组合集合。
 mode text NOT NULL CHECK(mode IN ('MATRIX','EXPLICIT')),
 -- PENDING 等待下游 3.1.2.1 兑现；DISCARDED 已作废（草稿行保留作留痕，占用已释放）。
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','DISCARDED')),
 axis_count int NOT NULL CHECK(axis_count>0),
 combination_count int NOT NULL CHECK(combination_count>0),
 notes text NOT NULL DEFAULT '',
 actor_id uuid NOT NULL,
 -- occurred_at 是业务时间，created_at/discarded_at 是系统时间，两者分列（与 V5 同口径）。
 occurred_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 discarded_at timestamptz,
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE,
 -- 作废必须有作废时间，未作废的草稿不许带时间戳：草稿到底怎么了要能一眼查出来。
 CHECK((status='DISCARDED')=(discarded_at IS NOT NULL))
);

-- ------------------------------------------------------------ 组合台账（唯一性约束本体）
-- 一行 = "这个 SPU 的这个规格值组合已经被占了"。矩阵草稿生成时占用，作废时释放。
-- 刻意**不保留"已释放"行**：留一行会占住唯一索引，让同一个组合永远无法再次生成——
-- 释放必须是物理删除。台账只记"已占用"这一种事实，不做状态机。
CREATE TABLE spu_variant_combination (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, spu_id uuid NOT NULL,
 -- 规范化签名：轴编码升序，`编码=取值`，`|` 连接。同一个组合无论提交顺序如何都得到同一串，
 -- 所以它才是能拿去当唯一键的那个值（可读文本而不是哈希——冲突时要能直接念给人听）。
 signature text NOT NULL,
 -- 轴数必须等于明细行数，由延迟约束触发器在提交前复核（见 guard_variant_combination_lines）。
 axis_count int NOT NULL CHECK(axis_count>0),
 -- 占用它的矩阵草稿：草稿作废时按这条链路释放占用，同时让"这行是谁留下的"可追溯。
 draft_id uuid NOT NULL,
 actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 -- 【硬约束·本体】同一 SPU 下不允许出现重复组合。绕过应用层直接写库也撞在这里（23505）。
 UNIQUE(tenant_id,spu_id,signature),
 FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,draft_id) REFERENCES sku_matrix_draft(tenant_id,id) ON DELETE CASCADE,
 CHECK(btrim(signature)<>'')
);

-- 草稿里的每个组合。指向组合台账：草稿与占用是同一份事实的两种视图，
-- 不把 signature 在这里复制一份当第二事实来源（复制会让两边各说各话）。
CREATE TABLE sku_matrix_draft_item (
 tenant_id uuid NOT NULL, draft_id uuid NOT NULL, combination_id uuid NOT NULL,
 position int NOT NULL DEFAULT 0 CHECK(position>=0),
 -- 主键即"同一份草稿里同一个组合只能出现一次"：应用层已查过重复，这是 DB 侧兜底。
 PRIMARY KEY(tenant_id,draft_id,combination_id),
 FOREIGN KEY(tenant_id,draft_id) REFERENCES sku_matrix_draft(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,combination_id) REFERENCES spu_variant_combination(tenant_id,id) ON DELETE CASCADE
);

-- ------------------------------------------------------------ 组合的轴值明细
-- 规范化成"一行一个轴上的取值"，而不是把整个组合塞进一个 JSON 字段：
-- 只有拆成行才能回答"哪些组合用了取值『红色』"这类治理问题，
-- 也才能在 DB 上表达"一个组合里同一条轴不能出现两次"。
CREATE TABLE spu_variant_combination_value (
 tenant_id uuid NOT NULL, combination_id uuid NOT NULL,
 spec_axis_id uuid NOT NULL, axis_code varchar(64) NOT NULL, option_value varchar(100) NOT NULL,
 position int NOT NULL DEFAULT 0 CHECK(position>=0),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,combination_id,spec_axis_id),
 -- 同一组合里轴编码不能重复。V1 的 attribute_definition 已按 (tenant_id,spu_id,code) 唯一，
 -- 且 V9 强制轴编码与承载属性同 code，所以同一 SPU 上两个轴不可能同 code；
 -- 这条约束把这层推理在 DB 上显式钉住——"按轴编码排序即全序"这件事要有依据。
 UNIQUE(tenant_id,combination_id,axis_code),
 FOREIGN KEY(tenant_id,combination_id) REFERENCES spu_variant_combination(tenant_id,id) ON DELETE CASCADE,
 -- 取值必须真实存在于该轴的规格选项里（值源由 V9 担保），这里再钉一层，
 -- 让明细行不可能指向一个没登记过的取值。
 FOREIGN KEY(tenant_id,spec_axis_id,option_value) REFERENCES spec_axis_option(tenant_id,spec_axis_id,value),
 CHECK(btrim(axis_code)<>'' AND btrim(option_value)<>'')
);

-- ------------------------------------------------------------ 明细行守卫（结构合法性）
-- 【硬约束】写入明细时逐条核对，任一条不成立就 23514 拒掉：
--   1) 这条轴必须**绑在这个 SPU 上**（挡住"混用别的 SPU 的规格轴"）；
--   2) 该轴本身必须启用（停用轴不产生新组合，历史组合照常可读）；
--   3) 取值必须已登记（外键兜底）且**处于启用状态**（停用取值挡新建）；
--   4) axis_code 由 spec_axis 推导，不接受调用方自报——自报会让签名对不上真实结构。
CREATE FUNCTION guard_variant_combination_value() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE spu_of uuid; axis_enabled boolean; axis_code text; option_enabled boolean;
BEGIN
 SELECT c.spu_id INTO STRICT spu_of FROM spu_variant_combination c
  WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.combination_id;
 IF NOT EXISTS(SELECT 1 FROM spu_spec_axis b
    WHERE b.tenant_id=NEW.tenant_id AND b.spu_id=spu_of AND b.spec_axis_id=NEW.spec_axis_id) THEN
  RAISE EXCEPTION '规格轴不属于该 SPU，不能出现在它的组合里' USING ERRCODE='23514';
 END IF;
 SELECT a.enabled,a.code INTO STRICT axis_enabled,axis_code FROM spec_axis a
  WHERE a.tenant_id=NEW.tenant_id AND a.id=NEW.spec_axis_id;
 IF NOT axis_enabled THEN
  RAISE EXCEPTION '规格轴「%」已停用，不能生成新组合：停用只挡新建，历史组合照常可读', axis_code
   USING ERRCODE='23514';
 END IF;
 IF axis_code<>NEW.axis_code THEN
  RAISE EXCEPTION '轴编码「%」与规格轴实际编码「%」不一致：轴编码由规格轴决定，不接受调用方自报',
   NEW.axis_code, axis_code USING ERRCODE='23514';
 END IF;
 SELECT o.enabled INTO STRICT option_enabled FROM spec_axis_option o
  WHERE o.tenant_id=NEW.tenant_id AND o.spec_axis_id=NEW.spec_axis_id AND o.value=NEW.option_value;
 IF NOT option_enabled THEN
  RAISE EXCEPTION '规格选项「%」在规格轴「%」上已停用，不能用于生成新组合：停用只挡新建，历史组合照常可读',
   NEW.option_value, axis_code USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_variant_combination_value BEFORE INSERT OR UPDATE ON spu_variant_combination_value
 FOR EACH ROW EXECUTE FUNCTION guard_variant_combination_value();

-- ------------------------------------------------------------ 签名一致性（延迟约束触发器）
-- 【硬约束】提交前复核：明细行数 == axis_count，且按轴编码升序拼出的签名 == 写入的 signature。
-- 为什么用 DEFERRABLE INITIALLY DEFERRED：应用层必须先写表头（要占唯一索引）再写明细行，
-- 若用普通 BEFORE 触发器，在明细还没写的那一刻根本无法判定签名对不对。
-- 延迟到提交时明细已齐，这时"签名是不是真的"才有意义。
-- 没有它，可以写 signature='COLOR=红色|SIZE=大' 而明细只放 COLOR=红色——
-- 唯一索引会为这串不存在的组合挡住另一个真实的"红色/大"，唯一性就名存实亡。
CREATE FUNCTION guard_variant_combination_lines() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lines int; recomputed text;
BEGIN
 SELECT count(*) INTO lines FROM spu_variant_combination_value v
  WHERE v.tenant_id=NEW.tenant_id AND v.combination_id=NEW.id;
 IF lines<>NEW.axis_count THEN
  RAISE EXCEPTION '组合的轴值明细有 % 行，与声明的轴数 % 不一致：组合必须每条轴恰好一个取值',
   lines, NEW.axis_count USING ERRCODE='23514';
 END IF;
 -- COLLATE "C" = 字节序，与 Java 的 String.compareTo 逐字节比较同序。
 -- 轴编码是 [A-Z0-9_-] 纯 ASCII，两种序法不会分叉。
 SELECT string_agg(v.axis_code||'='||v.option_value,'|' ORDER BY v.axis_code COLLATE "C")
  INTO recomputed FROM spu_variant_combination_value v
  WHERE v.tenant_id=NEW.tenant_id AND v.combination_id=NEW.id;
 IF recomputed IS DISTINCT FROM NEW.signature THEN
  RAISE EXCEPTION '组合签名「%」与实际轴值拼出的签名「%」不一致：签名必须由轴值推导，不接受自报',
   NEW.signature, recomputed USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER guard_variant_combination_lines
 AFTER INSERT OR UPDATE ON spu_variant_combination
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION guard_variant_combination_lines();

-- ------------------------------------------------------------ 跨表唯一性：与既有真实 SKU 不打架
-- 【硬约束】已存在的真实 SKU 是蓝图「输入」里明说的那一半：既有 SKU 的组合同样不能再被占用一次。
-- V1 的 UNIQUE(tenant_id,spu_id,variant_signature) 只管 sku 表内部，挡不住
-- "占用表里有一条 COLOR=红色|SIZE=大，同时 sku 表里又来一条同签名"这种两侧并存的重复。
-- 这里在占用侧补一刀，签名文本逐字相同即拒（23505）。
-- 只做**逐字**比较是刻意的：历史 SKU 的 variant_signature 由 3.1.1/3.1.2 写入，格式不归本模块管，
-- 逐字相同一定是重复；不同则交给应用层的按轴值精确比对去判，不能靠猜。
CREATE FUNCTION guard_variant_combination_against_sku() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE existing_sku uuid;
BEGIN
 SELECT k.id INTO existing_sku FROM sku k
  WHERE k.tenant_id=NEW.tenant_id AND k.spu_id=NEW.spu_id AND k.variant_signature=NEW.signature
  LIMIT 1;
 IF existing_sku IS NOT NULL THEN
  RAISE EXCEPTION '该 SPU 已存在签名相同的 SKU（%），不能重复占用同一组合（蓝图：唯一性由 DB 层保证）',
   existing_sku USING ERRCODE='23505';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_variant_combination_against_sku BEFORE INSERT OR UPDATE ON spu_variant_combination
 FOR EACH ROW EXECUTE FUNCTION guard_variant_combination_against_sku();

-- ------------------------------------------------------------ 索引
-- 唯一索引本身服务"这个 SPU 已占用了哪些组合"，是重复检测的热读路径。
CREATE INDEX spu_variant_combination_order ON spu_variant_combination(tenant_id,spu_id,created_at DESC,id DESC);
-- 草稿作废时按 draft_id 释放占用。
CREATE INDEX spu_variant_combination_draft ON spu_variant_combination(tenant_id,draft_id);
-- 反查治理面："取值『红色』被哪些组合用了"。
CREATE INDEX spu_variant_combination_value_lookup ON spu_variant_combination_value(tenant_id,spec_axis_id,option_value);
-- 按 SPU 列草稿，默认按创建时间倒序。
CREATE INDEX sku_matrix_draft_order ON sku_matrix_draft(tenant_id,spu_id,created_at DESC,id DESC);
-- 反查：一个组合被哪些草稿引用过。
CREATE INDEX sku_matrix_draft_item_combination ON sku_matrix_draft_item(tenant_id,combination_id);

DO $$ DECLARE tbl text; BEGIN
 -- 台账、明细、草稿项都带 spu 侧的语义或直接指向 SPU 档案：可见性跟随 SPU，
 -- 不能出现"看不到这个 SPU 却能读出它占用了哪些组合"这种绕过页面权限的读法。
 FOREACH tbl IN ARRAY ARRAY['sku_matrix_draft','spu_variant_combination','spu_variant_combination_value','sku_matrix_draft_item'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
 -- 明细跟随其组合可见。
 CREATE POLICY data_scope ON spu_variant_combination_value AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM spu_variant_combination c
    WHERE c.tenant_id=spu_variant_combination_value.tenant_id AND c.id=spu_variant_combination_value.combination_id))
  WITH CHECK (EXISTS(SELECT 1 FROM spu_variant_combination c
    WHERE c.tenant_id=spu_variant_combination_value.tenant_id AND c.id=spu_variant_combination_value.combination_id));
 -- 草稿项跟随其组合可见：看不到组合就读不到它被哪些草稿引用。
 CREATE POLICY data_scope ON sku_matrix_draft_item AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM spu_variant_combination c
    WHERE c.tenant_id=sku_matrix_draft_item.tenant_id AND c.id=sku_matrix_draft_item.combination_id))
  WITH CHECK (EXISTS(SELECT 1 FROM spu_variant_combination c
    WHERE c.tenant_id=sku_matrix_draft_item.tenant_id AND c.id=sku_matrix_draft_item.combination_id));
 -- 组合台账跟随 SPU 可见（列组合与草稿作废都走这条）。
 CREATE POLICY data_scope ON spu_variant_combination AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM spu p WHERE p.tenant_id=spu_variant_combination.tenant_id AND p.id=spu_variant_combination.spu_id))
  WITH CHECK (EXISTS(SELECT 1 FROM spu p WHERE p.tenant_id=spu_variant_combination.tenant_id AND p.id=spu_variant_combination.spu_id));
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON spu_variant_combination, spu_variant_combination_value, sku_matrix_draft, sku_matrix_draft_item TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON spu_variant_combination, spu_variant_combination_value, sku_matrix_draft, sku_matrix_draft_item TO starcon_app;