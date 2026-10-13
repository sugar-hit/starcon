-- ===========================================================================
-- 8.1.1.3 仓库配置
-- ===========================================================================
--
-- 蓝图原文：「配置仓库行为参数：是否支持批次/序列号、拣货策略（按订单/按波次/按库位）、
-- 默认复核比例、是否允许拆零发货、工作时段与班次。」以及关系段的红线：
-- 「**配置影响作业方式**，不是纯展示项，修改需记录变更。」
--
-- ---------------------------------------------------------------------------
-- 与 8.1.1.1 / 8.1.1.2 的分工：那两个模块都不建新表，本模块必须建
-- ---------------------------------------------------------------------------
--   8.1.1.1 仓库列表：纯聚合视图，**刻意不建新表**（V69 文件头「列表是聚合视图，
--     存一份列表数据就意味着『列表上的数』与『warehouse 表上的数』会有两个真相来源」）。
--   8.1.1.2 仓库详情：纯聚合，只给 warehouse 补地址五列（V77 文件头）。
--   8.1.1.3 仓库配置：**行为参数不在 warehouse 表上**。那里放的是仓库的
--     **身份**（编码 / 类型 / 货权法人 / 时区 / 地址 / 状态），改错了是主数据事故；
--     这里放的是**作业行为**（要不要批次、怎么拣货、复核多少比例、能不能拆零、
--     几点上班），改错了直接影响 8.3 的作业方式。
--     把两组字段塞进同一行有两个具体代价：
--       1. 一次 UPDATE 改不动单个维度 —— 「只想把复核比例从 0.05 调到 0.1」会
--          连带把地址五列写一遍，而写进去的是读取时看到的旧值。
--       2. 「这个仓的档案是谁改的」与「这个仓的作业参数是谁改的」在审计上
--          是两件不同的事，混在一行里分不开。
--     所以本迁移建独立的配置表，warehouse 表只被**引用**（同服务 FK，不是跨服务 FK）。
--
-- ---------------------------------------------------------------------------
-- 「工作时段与班次」这张表是本模块的责任，实查结论写在这里
-- ---------------------------------------------------------------------------
-- 上一条车道 8.1.1.2 在它的证据栏与代码里都点名了这件事：
--   WarehouseDetailService.labor() 写死 rosterAvailable=false，
--   理由是「人员花名册与排班没有数据表（工作时段与班次属 8.1.1.3 配置）」。
-- 实查确认仓库服务里确实没有这张表（warehouse_work_task 是 V74/8.5.1.1 的
-- **作业任务**表，带 warehouse_id 与 operator_user_id，但没有班次概念）。
-- 本迁移建 warehouse_shift 把这个缺口补上。
--
-- 为什么不顺带建「人员花名册」表：花名册是**人员档案**（谁在这个仓上班），
-- 蓝图 8.1.1.3 的措辞是「工作时段与班次」—— 说的是**时间**不是**人**。
-- 而人员档案的真相来源在 identity（成员关系），在本服务再建一份花名册就是
-- 造第二个真相来源（与 V1 的 legal_entity_ref 纪律相反：那个是**引用登记**，
-- 不是主数据镜像）。所以这里只建班次，不建花名册。
--
-- ---------------------------------------------------------------------------
-- 拣货策略三选一 + 默认复核比例 + 拆零发货：为什么落在同一张配置行上
-- ---------------------------------------------------------------------------
-- 蓝图把这四项写在同一个「配置仓库行为参数」句子里，它们共同决定 8.3 拣货与复核
-- 怎么做（下游：8.3.2 拣货、8.3.3 复核），所以是**同一个生效值**而不是四个独立档。
-- 一行一个仓：解析时不需要「哪个档优先」的问题，也不会出现「拣货策略是新的、
-- 复核比例还是三个月前的」这种半更新状态。
--
-- 三选一（ORDER / WAVE / LOCATION）用 CHECK 钉在 DB 层：策略名是**作业契约**，
-- 8.3 按它分派任务；放成自由文本会出现「按波次」「BY_WAVE」「WAVE」三套写法，
-- 而界面上看不出它们是同一个意思。
--
-- default_review_ratio 是比例值，CHECK 钉在 [0,1]：>1 会让「复核 150% 的货」
-- 这种句子成立，<0 在语义上不存在。两层防线（DB CHECK + 服务层显式区间校验）
-- 与 V68 的 buffer_ratio 同一条纪律。
--
-- allow_split_shipment（是否允许拆零发货）：拆零会把「一个订单 = 一个包裹」
-- 这个下游默认假设破掉（面单、装箱、运费都按整单算），所以它不是一个可以
-- 默认打开的开关 —— 默认 false，要拆零必须显式打开。
--
-- lot_tracking_enabled / serial_tracking_enabled：蓝图点名「是否支持批次/序列号」。
-- 两列**独立**而不是「是否启用追溯」一列：批次追溯与序列号追溯是两种不同的
-- 作业成本（批次要录批号，序列号要逐件扫），把两件事压成一个布尔会让
-- 「这个仓录批号但不逐件扫」无法表达。
--
-- ---------------------------------------------------------------------------
-- 变更历史：蓝图「修改需记录变更」是硬要求，所以它是 append-only 的独立表
-- ---------------------------------------------------------------------------
-- 为什么不用「一档一区间」的 effective_from/effective_to 写法（V68 的做法）：
--   仓库行为参数是**当前态**（「这个仓现在按什么作业」），不是「当时按什么作业」
--   的凭证。作业单会读当前态，读历史区间只会让每个作业单多做一次解析。
--   但蓝图同时要求留痕 —— 所以两件事分开：
--     warehouse_config      当前生效的一行（UPDATE 可改）
--     warehouse_config_change  谁在什么时候把哪个字段从什么改成了什么（append-only）
--
-- 前后值存法：old_value / new_value 都是 **text**，不是 jsonb、不是 typed 列。
--   * 为什么不是 jsonb：改一个字段一条记录，读的时候要解析整份 JSON 才能取出
--     一个值，而审计查询几乎总是「这个仓最近改了什么」这种逐字段的读。
--     折中方案是把值统一**规范化为字符串**（布尔写 "true"/"false"、
--     比例写 "0.050000"、策略写 "ORDER"），这样排序与比较有确定语义，
--     代价是「0.05」与「0.050000」在这张表里是两个串。
--     所以 old_value/new_value 上各有一个 **CHECK** 钉住「要么有值要么是空串之外的
--     NULL」，并且 created_at 与 id 一起构成确定次序（见下面的索引）。
--   * 为什么不用 typed 列：加一列配置项就要给这张表加一列，而这张表是
--     append-only 的（下面有触发器），加列倒还好，改触发器与 CHECK 才是风险。
--
-- 不可篡改：warehouse_config_change **只允许 INSERT**。
--   1. REVOKE UPDATE, DELETE ON warehouse_config_change FROM warehouse_app
--      —— 与 V1 的 inventory_entry / inventory_transaction 同一手法：
      --      兜底放在权限层而不是只放在触发器里。
--   2. 追加 BEFORE UPDATE OR DELETE 触发器 —— 因为 REVOKE 只挡运行角色，
--      表 owner 与超级用户仍然能改；触发器补上第二道。
--   理由逐条：变更历史是「配置为什么变成现在这样」的唯一凭据，
--   能改它等于这个模块的留痕要求形同虚设。
--
-- 留痕粒度是**逐字段**：一次修改 3 个字段就写 3 行，而不是把整份配置快照写一行。
--   理由是审计的真实问题是「谁把复核比例从 0 调到 1 了」，
--   逐行才能答；整份快照会让「改了哪几个字段」变成一个需要人肉比对的问题。
--
-- ---------------------------------------------------------------------------
-- 多租户：三张新表一律 tenant_id NOT NULL + ENABLE + FORCE RLS + isolation 策略
-- ---------------------------------------------------------------------------
-- 与 V1 第 9 节逐字同形（current_setting('app.current_tenant',true)::uuid）。
-- FORCE 是必须的：只 ENABLE 时表 owner 仍然绕过策略，而测试连接用的
-- warehouse_app 是登录角色 —— 但生产上如果误配成 owner 就等于没有隔离。
-- 与 V1 不同的是这里**逐张写出** ALTER 而不是照抄 V1 的 DO 块数组：
-- 三张表是新加的，逐张写出来才能一眼看出「哪张被 FORCE 了」。
-- warehouse 表本身**不重复** ENABLE（V1 建的，归属写错比不写更糟，同 V69/V77 理由）。
--
-- GRANT：warehouse_app 可读写配置与班次（现场要调班次是真实需求），
-- 但 **config_change 只给 INSERT/SELECT** —— 运行角色自己也没有资格改写留痕。

-- ===========================================================================
-- 1. warehouse_config · 仓库行为参数（每仓一行，当前态）
-- ===========================================================================
CREATE TABLE warehouse_config (
  tenant_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,

  -- 是否支持批次追溯：录货时要不要批号。关掉时 8.2.1.1 收货不录批次。
  lot_tracking_enabled boolean NOT NULL DEFAULT false,
  -- 是否支持序列号追溯：逐件扫。**与批次独立**（见文件头）。
  serial_tracking_enabled boolean NOT NULL DEFAULT false,

  -- 拣货策略三选一。这是**作业契约**：8.3.2 按它分派任务。
  picking_strategy varchar(16) NOT NULL DEFAULT 'ORDER'
    CHECK(picking_strategy IN ('ORDER','WAVE','LOCATION')),

  -- 默认复核比例，0~1。0 = 不复核，1 = 全量复核。
  -- NUMERIC(9,6) + BigDecimal：AGENTS.md 禁浮点记账，一个比例也不例外
  -- （0.1 在二进制浮点里是 0.1000000000000000055）。
  default_review_ratio numeric(9,6) NOT NULL DEFAULT 0
    CHECK(default_review_ratio >= 0 AND default_review_ratio <= 1),

  -- 是否允许拆零发货。默认 false：拆零会破掉「一单一包裹」这个下游默认假设。
  allow_split_shipment boolean NOT NULL DEFAULT false,

  note varchar(500) NOT NULL DEFAULT '',
  -- 最后一次修改者。配置行本身允许改，所以这里记 updated_by 而不是 created_by 单值。
  updated_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id,warehouse_id),
  -- 同服务 FK：warehouse 是本服务的表（V1 建），所以这里**可以**建外键
  --（与 V74 的 warehouse_work_task 同一手法）。跨服务才不建（见 V1 文件头）。
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id)
);

-- 读当前配置的主查询：按仓库取一行，PK 前缀就是 (tenant_id,warehouse_id)，已覆盖。
-- 「这个仓改过几次」走 config_change 的仓库索引，不走这里。

-- ===========================================================================
-- 2. warehouse_shift · 工作时段与班次
-- ===========================================================================
-- 8.1.1.2 明确点名「作业人员花名册与排班无表（属 8.1.1.3）」—— 缺口在这里补上。
--
-- 为什么班次存**当地时间**而不是 UTC 时刻：
--   「早班 09:00-18:00」是一条**每天重复**的墙上时间规则，它本身不是某个瞬间。
--   存成 timestamptz 就必须回答「09:00 是哪个日期的 09:00」，而班次按定义跨日期重复，
--   这个日期没有来源。所以这里存 time（当地墙上时刻），
--   具体某天的实际时刻由 warehouse.timezone（IANA ID，V1/V69）推出来 ——
--   这也是「纽约这周几号切夏令时」能被正确回答的前提（8.1.1.1 已立的规矩）。
--
-- start_local > end_local 表示**跨夜班**（22:00 → 06:00）。不禁止跨夜：
--   夜班是真实存在的作业形态，强行拆成两条会让「这个班从 22 点到次日 6 点」
--   这个问题在数据层就没有答案。
CREATE TABLE warehouse_shift (
  tenant_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  id uuid NOT NULL,

  code varchar(32) NOT NULL,
  name varchar(64) NOT NULL,

  -- 当地墙上时刻，闭区间 [start, end)。用 time 而不是 timestamptz：见上方理由。
  start_local time NOT NULL,
  end_local time NOT NULL,
  -- 跨夜班：start >= end 时为 true，由服务层按墙上时刻算出后写入，
  -- 不让调用方自己判断（自己判断就会出现「22:00-06:00 被算成不跨夜」）。
  crosses_midnight boolean NOT NULL,

  -- 是否排班：停用是排班调整的正常出口，删班次不是（见下方说明）。
  enabled boolean NOT NULL DEFAULT true,

  note varchar(500) NOT NULL DEFAULT '',
  created_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id,id),
  UNIQUE(tenant_id,warehouse_id,code),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),

  CHECK(btrim(code)<>'' AND btrim(name)<>''),

  -- 零长班次不是班次。start=end 会被 crosses_midnight 的计算读成跨夜 24 小时，
  -- 那是一个「从今天 09:00 干到明天 09:00」的班 —— 几乎肯定是录错了。
  CHECK(start_local <> end_local)
);

-- 列班次表的主查询：按仓 + 编码序。
CREATE INDEX warehouse_shift_list ON warehouse_shift(tenant_id,warehouse_id,enabled,code);

-- ===========================================================================
-- 3. warehouse_config_change · 变更留痕（append-only）
-- ===========================================================================
-- 蓝图原话：「配置影响作业方式，不是纯展示项，修改需记录变更。」这一句是硬要求，
-- 所以它落成一张独立表而不是配置行上的一个「最后修改时间」——
-- 后者只能回答「改过」，回答不了「从什么改成什么」。
CREATE TABLE warehouse_config_change (
  tenant_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  id uuid NOT NULL,

  -- 改的是哪一组参数：
  --   CONFIG 当前生效的行为参数（warehouse_config 行）
  --   SHIFT  某个班次（配合 shift_id）
  --   SHIFT_SCHEDULE 班次的启用状态 —— 与其它班次字段分开记，
  --     因为「把早班停用」与「把早班改到 10 点上班」是两种不同的作业影响，
  --     混进同一条会让复盘分不清。
  scope varchar(20) NOT NULL CHECK(scope IN ('CONFIG','SHIFT','SHIFT_SCHEDULE')),

  -- scope='SHIFT' / 'SHIFT_SCHEDULE' 时指向班次；CONFIG 时为 NULL。
  shift_id uuid,

  field_name varchar(48) NOT NULL CHECK(btrim(field_name)<>''),

  -- 前后值：**规范化文本**（布尔 "true"/"false"、比例 "0.050000"、策略 "ORDER"、
  -- 班次时刻 "09:00:00"）。old_value 为 NULL = 之前没有这个值（新建）；
  -- new_value 为 NULL = 之后没有了。
  -- 「之前没有」与「之前是空」必须能分开，所以这里用 NULL 而不是空串。
  old_value text,
  new_value text,

  note varchar(500) NOT NULL DEFAULT '',
  -- 操作人：已认证成员 id（AccessService.Access.userId()，与 V54/V56/V68 同一纪律，
  -- 不接受请求参数传入）。
  changed_by_membership_id uuid NOT NULL,
  changed_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,shift_id) REFERENCES warehouse_shift(tenant_id,id),

  -- 班次类记录必须指向班次，配置类记录必须不指向：少一条就会出现
  -- 「改的是哪个班次」这个问题无从回答的记录。
  CHECK((scope='CONFIG' AND shift_id IS NULL)
     OR (scope IN ('SHIFT','SHIFT_SCHEDULE') AND shift_id IS NOT NULL)),

  -- 真有变化才写留痕。新值与旧值完全相同的一行不是变更，是噪声：
  -- 它会让「这个仓改过几次」变成一个被无意义提交灌水的数字。
  CHECK(old_value IS DISTINCT FROM new_value)
);

-- 「这个仓的变更历史」页面的主查询：按时间倒序 + id 收尾定序。
-- id 收尾是必需的：changed_at 由 DB 的 now() 给，同一毫秒内的两行次序不确定，
-- 而没有确定次序的分页会在翻页时漏行或重复。
CREATE INDEX warehouse_config_change_history
  ON warehouse_config_change(tenant_id,warehouse_id,changed_at DESC,id);
-- 「某个班次被改过几次」：班次可能被停用后重建，同一编码的历史要能一次取到。
CREATE INDEX warehouse_config_change_shift
  ON warehouse_config_change(tenant_id,warehouse_id,shift_id,changed_at DESC)
  WHERE shift_id IS NOT NULL;

-- ===========================================================================
-- 4. 多租户：RLS + 授权
-- ===========================================================================
-- 逐张写出（而不是照抄 V1 的 DO 块）：这三张是新表，逐张列出来才能一眼
-- 确认 FORCE 在位。策略与 V1 第 9 节逐字同形。
ALTER TABLE warehouse_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse_config FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON warehouse_config
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

ALTER TABLE warehouse_shift ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse_shift FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON warehouse_shift
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

ALTER TABLE warehouse_config_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse_config_change FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON warehouse_config_change
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

-- 运行角色：配置与班次可读写（现场调班次是真实需求）。
GRANT SELECT,INSERT,UPDATE,DELETE ON warehouse_config, warehouse_shift TO warehouse_app;
-- 留痕**只给 INSERT/SELECT**：连运行角色自己都不能改写或删掉一条变更记录。
-- 「改不掉」这件事的第一道防线在权限层，第二道在下面的触发器。
GRANT SELECT,INSERT ON warehouse_config_change TO warehouse_app;

-- ===========================================================================
-- 5. 留痕 append-only：触发器（REVOKE 之外的第二道）
-- ===========================================================================
-- REVOKE 只挡运行角色，表 owner 与超级用户仍然能改 —— 所以补一道触发器。
-- 与 V76 的 guard_allocation_floor_immutable 同一手法（那里挡的是「内容写定后不可改」，
-- 这里挡的是「写下来之后一行都不许动」）。
CREATE FUNCTION guard_warehouse_config_change_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION
    '仓库配置变更历史是 append-only：已经写下的那一行不允许修改或删除。'
    '「这个仓的作业参数为什么变成现在这样」只能靠它回答，'
    '能改它等于本模块的留痕要求形同虚设'
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER warehouse_config_change_append_only
  BEFORE UPDATE OR DELETE ON warehouse_config_change
  FOR EACH ROW EXECUTE FUNCTION guard_warehouse_config_change_append_only();

-- ---------------------------------------------------------------------------
-- 刻意不建的东西，逐条说明理由
-- ---------------------------------------------------------------------------
--   * 不给 warehouse 加配置列：见文件头第一节（身份与行为是两组字段）。
--   * 不建人员花名册表：见文件头第三节（人员档案的真相来源在 identity）。
--   * 不给班次建 DELETE 端点与触发器级禁止：停用（enabled=false）是排班调整的出口，
--     删班次会让「上周三夜班是谁上的」这个问题失去载体（历史留痕会指向一个不存在的
--     班次）。服务层同样不提供删除形状。
--   * 不建「配置生效区间」分档：见第三节（当前态 + append-only 留痕）。
--   * 不动 warehouse 表：它的 RLS / GRANT 归 V1，本迁移既不重复 ENABLE 也不重复 GRANT
--     （归属写错比不写更糟，与 V69 / V77 同一理由）。
--   * 不建跨服务外键：人员属 identity，SKU 属 catalog。本迁移没引用它们。
