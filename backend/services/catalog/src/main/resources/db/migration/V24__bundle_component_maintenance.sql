-- 3.5.1.2 组件维护：在 V23 的 bundle_component 之上做组件工作台与三条校验。
--
-- 蓝图 3.5.1.2 的原话：
--   「职责：维护套装组件 SKU 与数量，校验不出现循环引用（套装 A 含套装 B 且 B 含 A）
--     与组件数量必须大于 0。」
--   「输入：组件增删改；bundle_component 记录；循环检测。」
--   「输出：组件清单；校验结果（循环、数量非法、组件不存在均拒绝保存）。」
--   「关系：上游 = 3.5.1.1；下游 = 07 库存（预占组件）、09 订单（order_line_component
--     冻结展开结果）。**已发布版本的组件不可改**，只能新建版本 —— 历史订单冻结的是当时版本。」
--
-- ============================================================ 一、本模块在 V23 之上补的到底是什么
--
-- 3.5.1.1 交付的是「随草稿版本编辑组件清单 + 发布时校验组件齐备」。它把
-- `bundle_component` 的**形状**建对了（行挂版本、数量 NUMERIC(19,6)>0），
-- 但把两件事明确留给了本模块（V23 文件头第四节逐字写着）：
--
--   1. **循环引用检测**；
--   2. **独立的组件工作台**（跨版本看组件清单、逐行增删改、拿到结构化校验结果）。
--
-- 「已发布版本的组件不可改」这条纪律本身在 V23 已经用触发器钉死了
-- （guard_bundle_component_published），本模块不需要重复实现，
-- 但服务层要给出**可执行的中文原因**而不是让人撞 500。
--
-- ============================================================ 二、为什么必须有 bundle_sku（循环检测的前提）
--
-- 蓝图把循环引用写成「套装 A 含套装 B 且 B 含 A」。但 V23 的 bundle_component
-- **只引用 sku，不引用 bundle**（刻意保持一条干净的 SKU 外键）。于是有一个绕不开的问题：
--
--   **在只有 bundle_component.sku_id 的前提下，「套装 A 含套装 B」根本无法表达。**
--
-- 蓝图自己给出了表达方式（3.5.1.1 关系栏「下游 = 3.1.2.2（套装作为可售 SKU）」）：
-- **套装自己先成为一个可售 SKU 行，再被别的套装当组件引用。** 也就是说
--
--   套装 A 含套装 B  ==  A 的某个版本的组件里有 S，而 S 是一条「代表套装 B」的可售 SKU。
--
-- 所以「SKU ↔ 套装」的指向关系必须存在，循环才有地方发生。本文件建 `bundle_sku`
-- 承载这条指向，并规定一个 SKU 至多代表一个套装（UNIQUE(tenant_id, sku_id)）——
-- 一个 SKU 同时代表两个套装时，「这个组件展开成什么」有两个答案。
--
-- 为什么不把 bundle_id 直接加到 bundle_component 上（多态列 / 直接引用套装）：
--   * 组件**绝大多数**不是套装（普通 SKU），多一列可空的外键会让 99% 的行带着一个空值，
--     而空值与「指向套装 0」分不开；
--   * 已发布版本冻结的是「当时那一份 SKU 清单」，09 订单按 SKU 展开；
--     套装指向是**间接**的、可变的（SKU 重新代表别的套装），把它摊平进组件行
--     就会让历史订单冻结的展开结果随 SKU 的重新指向一起变。
--   * V23 文件头已经把这个选择写死了，本模块沿用，不推翻上游已交付的形状。
--
-- 本表由本模块写（而不是 3.1.2.2）：循环检测是 3.5.1.2 的核心职责，
-- 而「哪些 SKU 代表套装」正是让循环可判定的输入。归属写在第六节。
--
-- ============================================================ 三、循环检测的规则（本文件最关键的一段）
--
-- 蓝图标了「校验不出现循环引用」，但没有规定怎么算「循环」。本文件钉死口径：
--
--   **规则 R6（成环的定义）**：把套装看成图上的点，把「套装 X 的某个版本里有一个
--     代表套装 Y 的组件 SKU」看成一条有向边 X → Y。
--     写入一条组件（或改一条 SKU 的套装指向）之后，若从**新指向的套装**出发
--     能沿这些边走回**写入方所属的套装**，就是成环。
--
--   **规则 R7（遍历范围含草稿）**：边来自**该套装的全部版本**（草稿与已发布都算）。
--     为什么不只看已发布：草稿的组件行是真实存在的一行数据，而它马上就要被发布；
--     只看已发布的话，A 的草稿里塞进 S_B（B 已发布且含 S_A）在保存时不报，
--     等 A 的草稿一发布就成环，而发布那一刻已经晚了。宁可现在拦。
--
--   **规则 R8（自引用就是环）**：S_B 就是 A 自己的可售 SKU 时，
--     「A 含 A」是长度为 1 的环，展开时直接无限递归。与其在展开侧加深度截断
--     （截断等于给一个错误答案编造一个「到这里就够了」的解释），不如在写入侧拒绝。
--
--   **规则 R9（拒绝而不是自动拆环）**：成环时拒绝写入并把**整条环路**（套装编码链）
--     写进报错。自动拆环需要替运营决定「断哪一条」，而运营比我们清楚哪条是笔误；
--     给出环路让他自己改，是唯一不编造的做法。
--
-- 判定同时落在三层，两层必须给出同一个答案：
--   1. 服务层（BundleComponentService）：给可执行的中文原因 + 完整环路；
--   2. **触发器**（本文件第四节）：绕过应用层直连数据库写也一样被拦 ——
--      V23 已经论证过 CHECK 可被绕过而触发器不行；
--   3. 规则层（BundleComponentRules）：纯函数，把「可达性 → 是否成环」写成可单测的判定。
--
-- ============================================================ 1. SKU ↔ 套装指向（循环的载体）
--
-- 一行 = 「这个 SKU 是那个套装的可售面」。允许一个套装有 0..n 个可售 SKU
-- （不同站点/包装可以是不同 SKU），但一个 SKU 至多代表一个套装。
CREATE TABLE bundle_sku (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  bundle_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一个 SKU 至多代表一个套装：两行时「这个组件展开成什么」有两个答案，
  -- 而 09 订单冻结展开结果时只能取一条。
  UNIQUE(tenant_id,sku_id),
  FOREIGN KEY(tenant_id,bundle_id) REFERENCES bundle(tenant_id,id) ON DELETE CASCADE,
  -- RESTRICT 而不是 CASCADE：删一个「代表套装的 SKU」等于让所有把它当组件的
  -- 套装在展开时静默降级成普通 SKU，而已发布版本里的那些组件行是冻结的历史。
  -- 要删请先显式解开指向。
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE RESTRICT
);

-- ============================================================ 2. 组件行的乐观锁
--
-- 3.5.1.1 的组件清单是**整体替换**（PUT /versions/{id}/components），没有单行版本号。
-- 3.5.1.2 的组件工作台要**逐行增删改**，逐行改必须有乐观锁：
-- 两个人同时打开同一个版本的组件清单，A 改了数量、B 改了备注，
-- 没有 version 列时后写的会静默覆盖先写的，而组件清单是要冻结进订单的东西。
ALTER TABLE bundle_component ADD COLUMN version bigint NOT NULL DEFAULT 0;

-- ============================================================ 3. 可达性查询（纯函数，规则 R6 的实现）
--
-- 从 p_from 出发沿边走到 p_to 是否可达，返回**一条**路径（uuid[]，首尾都是套装 id）；
-- 不可达返回 NULL。调用方据此判定是否成环。
--
-- 两条实现细节，都是为了「有环时不会把数据库转爆」：
--   * NOT (... = ANY(path))：路径里出现过的套装不再展开。这既剪掉了与目标无关的死循环，
--     又保证递归一定终止。
--   * depth < 32：深度硬上限。理论上「可达」的判定不需要它（路径去重已经保证终止），
--     但一个 32 层深的套装链在业务上没有任何意义，让它在第 33 层停住比继续走更便宜。
--     上限只影响「极深的环」是否报出完整路径，不影响「是否成环」的结论 ——
--     因为 p_to 一旦被找到就立刻返回。
CREATE FUNCTION find_bundle_cycle_path(p_tenant uuid, p_from uuid, p_to uuid) RETURNS uuid[]
LANGUAGE plpgsql STABLE AS $$
DECLARE
  found uuid[];
BEGIN
  -- 自引用：套装含自己，长度 1 的环（规则 R8）。
  IF p_from = p_to THEN RETURN ARRAY[p_from]; END IF;
  WITH RECURSIVE chain(bundle_id, depth, path) AS (
    SELECT p_from, 0, ARRAY[p_from]
    UNION ALL
    SELECT nxt.bundle_id, ch.depth + 1, ch.path || nxt.bundle_id
    FROM chain ch
    JOIN LATERAL (
      -- 边：套装 ch.bundle_id 的**任一版本**里，代表某个套装的可售 SKU（规则 R7）。
      SELECT DISTINCT bs.bundle_id
        FROM bundle_version v
        JOIN bundle_component c ON c.tenant_id = v.tenant_id AND c.version_id = v.id
        JOIN bundle_sku bs ON bs.tenant_id = c.tenant_id AND bs.sku_id = c.sku_id
       WHERE v.tenant_id = p_tenant
         AND v.bundle_id = ch.bundle_id
         AND NOT (bs.bundle_id = ANY(ch.path))
    ) nxt ON true
    WHERE ch.depth < 32
  )
  SELECT ch.path INTO found FROM chain ch WHERE ch.bundle_id = p_to LIMIT 1;
  RETURN found;
END $$;

-- 把 uuid[] 路径转成「A → B → A」这样的编码链，用于报错文案。
CREATE FUNCTION bundle_cycle_codes(p_tenant uuid, p_path uuid[]) RETURNS text
LANGUAGE sql STABLE AS $$
  SELECT coalesce(string_agg(b.code, ' → ' ORDER BY t.ord), '')
    FROM unnest(p_path) WITH ORDINALITY AS t(id, ord)
    JOIN bundle b ON b.tenant_id = p_tenant AND b.id = t.id;
$$;

-- ============================================================ 4. 触发器（成环的纵深防线）
--
-- 与 V23 的 guard_bundle_component_published 叠加在同一个写入路径上：
-- 已发布版本先被拦（V23），草稿版本再被本节拦（成环）。
--
-- 4.1 组件写入：组件 SKU 代表套装 B 时，B 能走回本组件所属的套装 A 就是环。
CREATE FUNCTION guard_bundle_component_cycle() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  parent uuid; linked uuid; cyc uuid[];
BEGIN
  SELECT v.bundle_id INTO parent FROM bundle_version v
   WHERE v.tenant_id = NEW.tenant_id AND v.id = NEW.version_id;
  IF parent IS NULL THEN RETURN NEW; END IF;

  -- 不是套装可售 SKU 的普通组件不参与成环判定（套装之间的边根本不存在）。
  SELECT bs.bundle_id INTO linked FROM bundle_sku bs
   WHERE bs.tenant_id = NEW.tenant_id AND bs.sku_id = NEW.sku_id;
  IF linked IS NULL THEN RETURN NEW; END IF;

  cyc := find_bundle_cycle_path(NEW.tenant_id, linked, parent);
  IF cyc IS NOT NULL THEN
   -- 环要按「写入方 → 组件指向的套装 → … → 写入方」读，所以把 parent 放在**前面**：
   -- 路径 cyc 本身就是 [linked, …, parent]，前面补上 parent 才是一条闭合的环
   -- （BND-B → BND-C → BND-B），而不是把 parent 重复贴在末尾。
   RAISE EXCEPTION '组件会形成循环引用（%）：套装之间已经存在一条包含链，再把这个组件放进来就闭合成环。09 订单按组件逐层展开时会在环上无限递归，而订单要冻结的是有限的展开结果。请换一个不在这条链上的组件，或先解开链上某一处指向。',
     bundle_cycle_codes(NEW.tenant_id, ARRAY[parent] || cyc) USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_bundle_component_cycle
  BEFORE INSERT ON bundle_component
  FOR EACH ROW EXECUTE FUNCTION guard_bundle_component_cycle();

-- 4.2 指向变更：改一个 SKU 代表哪个套装，等于改所有把它当组件的套装的出边。
--     所以这一侧也必须查环 —— 否则「先建好 A→B，再把 B 的可售 SKU 改指回 A」
--     是一条绕过 4.1 的成环路径。
CREATE FUNCTION guard_bundle_sku_cycle() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  holder uuid; cyc uuid[]; target_code text; holder_code text; codes text;
BEGIN
  SELECT b.code INTO target_code FROM bundle b
   WHERE b.tenant_id = NEW.tenant_id AND b.id = NEW.bundle_id;

  -- 所有把这个 SKU 当组件的套装都是「出边持有者」，逐个查它会不会被连回新指向的套装。
  --
  -- 这里**不**跳过 holder = NEW.bundle_id（也就是「这个套装自己用自己」）：
  -- 「自己用自己」是不是环，取决于**组件行此刻是否已经存在**，而不是取决于套装 id 是否相同。
  --   * 先建指向、再加组件 —— 建指向这一刻还没有组件行，holder 集合是空的，放行；
  --     真正写下那行组件时由 4.1 判成环并拒绝。
  --   * 先加组件、再建指向（或改指向）—— holder 集合里有它自己，立刻判成环并拒绝。
  -- 两个顺序里「自己用自己」都被拦住，只是拦在不同的那一步；按套装 id 相等去跳过
  -- 反而会让第二个顺序漏过去。
  FOR holder IN
    SELECT DISTINCT v.bundle_id
      FROM bundle_version v
      JOIN bundle_component c ON c.tenant_id = v.tenant_id AND c.version_id = v.id
     WHERE v.tenant_id = NEW.tenant_id AND c.sku_id = NEW.sku_id
  LOOP
    cyc := find_bundle_cycle_path(NEW.tenant_id, NEW.bundle_id, holder);
    IF cyc IS NOT NULL THEN
      SELECT b.code INTO holder_code FROM bundle b
       WHERE b.tenant_id = NEW.tenant_id AND b.id = holder;
      -- 同上：holder 是「把它当组件的那个套装」，放在前面才是闭合的环。
      codes := bundle_cycle_codes(NEW.tenant_id, ARRAY[holder] || cyc);
      RAISE EXCEPTION '这个指向会形成循环引用（% → %）：SKU「%」现在被套装「%」当作组件，重新代表套装「%」后两个套装就互相包含了。09 订单按组件逐层展开时会在环上无限递归。请解开链上某一处指向，或换一个 SKU 来代表这个套装。',
        codes, holder_code,
        (SELECT s.code FROM sku s WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.sku_id),
        holder_code, coalesce(target_code, '（未知套装）') USING ERRCODE='23514';
    END IF;
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_bundle_sku_cycle
  BEFORE INSERT OR UPDATE OF bundle_id, sku_id ON bundle_sku
  FOR EACH ROW EXECUTE FUNCTION guard_bundle_sku_cycle();

-- ============================================================ 5. 索引
--
-- 成环查询的主形状：某个套装是哪些 SKU 的代表（bundle_sku_by_bundle）。
CREATE INDEX bundle_sku_by_bundle ON bundle_sku(tenant_id, bundle_id, sku_id);
-- 组件工作台的「跨版本看组件清单」：按 SKU 反查（V23 已建 bundle_component_by_sku），
-- 这里补的是「按代表套装反查哪些组件行指向了它」——成环判定的内层连接形状。
CREATE INDEX bundle_component_by_linked_sku ON bundle_component(tenant_id, sku_id, version_id);
-- 单行组件编辑的定位形状（组件 id 是主键，这里是按 sku 改数量时的覆盖索引）。
CREATE INDEX bundle_component_row_version ON bundle_component(tenant_id, id, version);

-- ============================================================ 6. RLS 与授权
--
-- bundle_sku 与 bundle_component 同口径：租户级数据，不套 V3 的 owner_id/department_id
-- （套装是租户共享的商品定义）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['bundle_sku'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON bundle_sku TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON bundle_sku TO starcon_app;

-- ============================================================ 7. 边界与归属（不是本模块的缺口）
--
--   * **已发布版本的组件不可改**：V23 的 guard_bundle_component_published 已经钉死，
--     本模块的服务层只负责把原因说成人话，不重复实现纪律。
--   * **3.5.2.x BOM**（3.5.2.1/3.5.2.2）：生产/加工用，产生实物成品、持有自己的库存；
--     套装是销售用的虚拟组合，不持有独立库存、扣组件库存（实体目录 §3.4）。
--     两者语义不同，**不合并表**，本模块不碰 BOM。
--   * **07 库存 / 09 订单**：读 `GET /catalog/versions/{id}/components`（3.5.1.1 已有）
--     与本模块的跨版本组件清单。09 下单时冻结展开结果，本模块不写订单表。
--   * **bundle_sku 的写入所有权在本模块**：它存在的唯一理由是让循环可判定，
--     而循环检测是 3.5.1.2 的核心职责。3.1.2.2（SKU 详情）只读它来展示
--     「这个 SKU 代表哪个套装」，不写。
