-- 3.4.1.2 图片处理：处理配方（目标平台规格）、异步处理任务、逐素材结果、产物版本。
--
-- 蓝图 3.4.1.2 的原话：
--   「对图片做刊登前的加工：白底、去背景、水印添加、尺寸裁剪到平台要求、格式转换、
--     生成多比例版本（如主图 1:1 + 缩略图）。」
--   「输入：原图 + 处理参数（目标平台规格）；批处理任务。」
--   「输出：处理后图片 + 处理任务记录（成功/失败原因）。」
--   「**处理是异步任务**，长批量不阻塞页面；处理结果不可逆覆盖原图，原图始终保留。」
--
-- ============================================================ 读原图的路径
--
-- 原图来自 3.4.1.1 的 media_asset / media_asset_version（V18），**本轮不改 V18 一行**。
-- 处理时读的是 media_asset_version 里那一版的 storage_key：不是 media_asset 上的当前
-- 存储键，也不是文件名——只有版本行是「这份字节到底是什么」的唯一真相。
--
-- ============================================================ 「原图始终保留」怎么在 DB 层成立
--
-- 蓝图说「处理结果不可逆覆盖原图，原图始终保留」。字面拆开是两件事：
--   (1) 覆盖 —— 处理产物**不回写**原对象的键，而是写新对象 + 插新版本行，
--       media_asset.current_version 由 V18 的 sync_media_asset_from_version 前进。
--       于是「当前这张图」变成处理后的图，而 v1 那张原图还在版本表里、对象还在存储里。
--   (2) 保留 —— 原图版本行与它的 storage_key 不能被删改。这两件事光靠服务层自觉是不够的：
--       一条运维 SQL、一次手滑的清理脚本就能绕过去，所以本文件加两个触发器把它钉在 DB 层：
--         guard_media_asset_version_immutable
--           版本行**只允许 INSERT**。任何 UPDATE 一律拒——包括只改 note 这种看起来无害的。
--           「不可逆覆盖」意味着连原地改一个字段都不给口子，否则"原图"能被慢慢改成别的东西。
--         refuse_media_process_source_version_delete
--           被处理记录引用过的版本行不可被**单独**删除。
--           判定要区分两种删除：素材整体删除时的 ON DELETE CASCADE 放行（那是删除素材，
--           已经由 V18 的 refuse_media_asset_delete 按引用保护过），而直接
--           `delete from media_asset_version` 撞下来——父行还在、且这版是处理来源或产物。
--
-- ============================================================ 二进制仍然不落库
--
-- 与 V18 同一纪律：处理产物走 MediaStorage 端口写对象，库里只存 storage_key。
-- 本文件五张表里没有 bytea / oid。处理把图片读进内存、改完、再写出去，
-- 峰值内存约等于「原图 + 产物」两份，与文件大小同阶，不随素材总量增长。
--
-- ============================================================ 1. 处理配方（目标平台规格）
--
-- 配方是**租户级字典**（与 media_tag / brand 同级），不是每个任务临时填的一堆参数：
-- 亚马逊主图一套规格、Shopify 缩略图一套规格，批处理时要反复用同一套，
-- 每次重填既容易错也让「这批图为什么被裁成这样」无法回溯。
--
-- 六个加工能力在这一行上全部有落点，没有一个是「以后再补」：
--   去背景   remove_background + background_tolerance
--   白底     white_background（去背景后合成到白底画布）
--   水印     watermark_text + watermark_position + watermark_opacity
--   尺寸裁剪 target_width_px / target_height_px + crop_mode
--   格式转换 target_format
--   多比例   见第 2 节 media_process_recipe_ratio（一个配方产出多行）
CREATE TABLE media_process_recipe (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(32) NOT NULL CHECK(btrim(code)<>''),
  name varchar(100) NOT NULL CHECK(btrim(name)<>''),
  note text NOT NULL DEFAULT '',
  -- 目标平台：自由文本而不是外键到 channel 服务的平台表。跨服务 FK 不可能（模块边界纪律），
  -- 而这里只需要「这批规格是给谁用的」这个说明；真正要联动校验平台规格时走 event / 授权 API。
  target_platform varchar(64) NOT NULL DEFAULT '',
  -- 格式转换：只能落在 JDK ImageIO 写得出的格式上。WEBP 不在内（JDK 没有 WEBP 写入器），
  -- 写成 'WEBP' 会得到一个声称成功、实则什么都没写的空产物，所以 DB 层先拒。
  target_format text NOT NULL CHECK(target_format IN ('png','jpeg','gif','bmp')),
  -- 去背景：从四角泛洪，颜色距离超过容差即视为背景（alpha 置 0）。
  -- 容差按 0~255 的每通道距离计：0=只吃完全同色，255=全图都算背景。
  remove_background boolean NOT NULL DEFAULT false,
  background_tolerance int NOT NULL DEFAULT 24 CHECK(background_tolerance BETWEEN 0 AND 255),
  -- 白底：去背景后合成到纯白画布（亚马逊/Shopify 主图要求 RGB 无透明通道）。
  -- 允许与 remove_background 同时为真——那正是标准流水线：先抠掉背景，再铺白底。
  -- 两者都为假时输出保留原有 alpha。
  white_background boolean NOT NULL DEFAULT false,
  -- 水印：文本水印。空文本等价于不加水印，但保留字段而不是复用 note，
  -- 因为「水印要跟着每个产物重画」这件事在 04 刊登侧还会被引用。
  watermark_text varchar(120) NOT NULL DEFAULT '',
  -- 位置：四角 + 平铺。平铺是防「裁掉角就绕过水印」的那一种。
  watermark_position text NOT NULL DEFAULT 'BOTTOM_RIGHT'
    CHECK(watermark_position IN ('TOP_LEFT','TOP_RIGHT','BOTTOM_LEFT','BOTTOM_RIGHT','CENTER','TILED')),
  -- 不透明度 0~100，与 ImageIO 的 AlphaComposite 同一口径（0=完全透明，100=完全不透明）。
  watermark_opacity int NOT NULL DEFAULT 30 CHECK(watermark_opacity BETWEEN 0 AND 100),
  -- 尺寸裁剪：目标框。两列都必填——只给一个宽高的「裁剪」含义不唯一。
  -- 留成 null 表示这一档**不缩放**，保留原尺寸（缩略图档常常用原图 + 水印）。
  target_width_px numeric(19,6),
  target_height_px numeric(19,6),
  -- 适配方式：FIT 等比缩放后补白边（不裁掉画面），CROP_FILL 填满后居中裁掉溢出部分。
  crop_mode text NOT NULL DEFAULT 'FIT' CHECK(crop_mode IN ('FIT','CROP_FILL')),
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code),
  -- 目标框要么两列都空（不缩放），要么两列都有值。少一列的语义是模糊的，
  -- 而这种行一旦落库，后面每个产物都会按一个说不清的尺寸裁。
  CHECK((target_width_px IS NULL AND target_height_px IS NULL)
     OR (target_width_px IS NOT NULL AND target_height_px IS NOT NULL)),
  CHECK(target_width_px IS NULL OR target_width_px>0),
  CHECK(target_height_px IS NULL OR target_height_px>0),
  -- 去背景但输出 jpeg/bmp 时白底必须开：**只有 png 有真正的 alpha 通道**。
  -- gif 只有 1 位透明（半透明会被二值化，抠出的柔边变成锯齿），bmp 与 jpeg 根本没有 alpha
  -- （透明像素会被渲染成黑或乱码）。这两种情况下若白底没开，API 会返回成功而产物是坏的，
  -- 所以在 DB 层先拒掉这种组合，而不是让用户传完 200 张图才看到。
  CHECK(NOT remove_background OR white_background OR target_format='png')
);

-- ============================================================ 2. 多比例版本
--
-- 「生成多比例版本（如主图 1:1 + 缩略图）」落在这里：一个配方挂 N 个比例档，
-- 每个档产出一行 media_process_output，也就是原素材的一个新版本。
-- 拆成子表而不是在配方上放一个数组：产物要按比例回溯（「这批缩略图是哪档出来的」），
-- 数组列查不了。
CREATE TABLE media_process_recipe_ratio (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, recipe_id uuid NOT NULL,
  label varchar(32) NOT NULL CHECK(btrim(label)<>''),
  width_px numeric(19,6) NOT NULL CHECK(width_px>0),
  height_px numeric(19,6) NOT NULL CHECK(height_px>0),
  crop_mode text NOT NULL DEFAULT 'FIT' CHECK(crop_mode IN ('FIT','CROP_FILL')),
  sort_no int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一配方内标签唯一：两档都叫 MAIN 时产物表里会出现两行无法区分的「主图」。
  UNIQUE(tenant_id,recipe_id,label),
  FOREIGN KEY(tenant_id,recipe_id) REFERENCES media_process_recipe(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 3. 处理任务（异步）
--
-- 蓝图：「**处理是异步任务**，长批量不阻塞页面」。
-- 一次处理几十张图要几十秒到几分钟，同步做会让上传页一直转圈直到网关超时，
-- 而失败时用户只知道「超时了」，不知道哪几张成功。
-- 所以提交只写任务与条目就返回（毫秒级），真正的像素加工在线程池里跑，
-- 进度从这张表查——成功/失败原因也是蓝图点名要的输出。
CREATE TABLE media_process_job (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, recipe_id uuid NOT NULL,
  -- 发起人：写审计与「谁点的处理」都要用。取自已认证成员关系，不接受客户端指定。
  requested_by uuid NOT NULL,
  status text NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','PARTIAL','FAILED','CANCELLED')),
  -- 条目总数在提交时就写死（当时选中了几个素材），后面 succeeded/failed 只增不减。
  -- 用一个常量列而不是每次 count：列表页要显示 N/N，而 count 会随处理中的行变动。
  asset_count int NOT NULL DEFAULT 0 CHECK(asset_count>=0),
  succeeded_count int NOT NULL DEFAULT 0 CHECK(succeeded_count>=0),
  failed_count int NOT NULL DEFAULT 0 CHECK(failed_count>=0),
  skipped_count int NOT NULL DEFAULT 0 CHECK(skipped_count>=0),
  -- 取消请求：处理在条目之间检查它。这里只存「请求」，真正落到 CANCELLED 是工作线程做的，
  -- 所以提交取消不必等任务停。
  cancel_requested boolean NOT NULL DEFAULT false,
  error text NOT NULL DEFAULT '',
  -- 业务时间与系统时间分开：created_at 是运营「几点点的处理」，
  -- started_at/finished_at 是系统「什么时候真的开始/结束加工」。长任务里两者能差很久。
  created_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  finished_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,recipe_id) REFERENCES media_process_recipe(tenant_id,id)
);

-- ------------------------------------------------------------ 逐素材条目
--
-- 一行 = 一个素材在这次处理里的完整结果：读了哪一版、成功还是失败、为什么失败。
-- 任务级只有计数，问「哪张失败了」必须能落到条目行上。
CREATE TABLE media_process_job_item (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, job_id uuid NOT NULL, asset_id uuid NOT NULL,
  -- 来源版本与来源键在提交时**快照**下来，而不是处理时再读 media_asset：
  -- 批量跑到一半素材被人上传了新版本时，处理的对象必须是提交那一刻定下来的那一版，
  -- 否则同一个任务里的产物来源会前后不一致，而任务记录再也解释不清。
  source_version int NOT NULL CHECK(source_version>0),
  source_storage_key varchar(500) NOT NULL CHECK(btrim(source_storage_key)<>''),
  source_sha256 varchar(64) NOT NULL CHECK(length(source_sha256)=64),
  status text NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','SKIPPED','CANCELLED')),
  produced_count int NOT NULL DEFAULT 0 CHECK(produced_count>=0),
  -- 失败原因：可执行的中文原因（为什么这张不行、该怎么改），不是异常类名。
  error text NOT NULL DEFAULT '',
  -- 系统时间：加工开始/结束的时刻。
  started_at timestamptz, finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一任务里同一个素材只出现一次：重复提交会让同一个 id 处理两遍并产出两套版本。
  UNIQUE(tenant_id,job_id,asset_id),
  FOREIGN KEY(tenant_id,job_id) REFERENCES media_process_job(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,asset_id) REFERENCES media_asset(tenant_id,id) ON DELETE CASCADE
);

-- ------------------------------------------------------------ 产物
--
-- 一行 = 一个比例档的一个产物。产物本体是 media_asset_version 里的一行新版本
-- （V18 的触发器负责把 media_asset 的当前版本推进过去），这张表负责回答
-- 「这个新版本是从哪个任务、哪一档、基于哪一版原图产出的」。
CREATE TABLE media_process_output (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  job_id uuid NOT NULL, item_id uuid NOT NULL, asset_id uuid NOT NULL,
  -- 比例档标签（MAIN / THUMBNAIL / ...，取自 media_process_recipe_ratio.label）。
  ratio_label varchar(32) NOT NULL CHECK(btrim(ratio_label)<>''),
  -- 产出前后的版本号：from=原图那一版，to=本次写入的新版本。
  -- 两者相等在 DB 层被拒——那意味着「处理结果覆盖了原图」，
  -- 而蓝图要求的是「写入新文件 + 新版本行，原图那一版原封不动」。
  from_version int NOT NULL CHECK(from_version>0),
  to_version int NOT NULL CHECK(to_version>0),
  storage_key varchar(500) NOT NULL CHECK(btrim(storage_key)<>''),
  file_format varchar(32) NOT NULL CHECK(btrim(file_format)<>''),
  mime_type varchar(120) NOT NULL CHECK(btrim(mime_type)<>''),
  byte_size numeric(19,6) NOT NULL CHECK(byte_size>0),
  width_px numeric(19,6) NOT NULL CHECK(width_px>0),
  height_px numeric(19,6) NOT NULL CHECK(height_px>0),
  content_sha256 varchar(64) NOT NULL CHECK(length(content_sha256)=64),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 「新版本」这个约束写成 CHECK：to_version 必须严格大于 from_version。
  -- 它把蓝图那句硬要求钉在数据里——产物不可能是原图自己。
  CHECK(to_version>from_version),
  -- 一个素材在一个任务里同一档只产出一件；同档重跑会撞这里。
  UNIQUE(tenant_id,job_id,asset_id,ratio_label),
  FOREIGN KEY(tenant_id,job_id) REFERENCES media_process_job(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,item_id) REFERENCES media_process_job_item(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,asset_id) REFERENCES media_asset(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 4. 版本行不可变 + 原图不可删
--
-- 见头部「「原图始终保留」怎么在 DB 层成立」。
--
-- BEFORE UPDATE 而不是 AFTER：BEFORE 阶段只要拒绝就一定拦住，
-- 而 AFTER 只能报错、事务照样可以先写后补。
CREATE FUNCTION guard_media_asset_version_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '媒体版本行不可修改（素材 % 的 v%）：版本是「某一时刻的那份字节」的事实，3.4.1.2 要求处理结果不可逆覆盖原图、原图始终保留，因此这里只允许新增版本，不允许改写已有版本',
   OLD.asset_id, OLD.version_no USING ERRCODE='23514';
END $$;
CREATE TRIGGER guard_media_asset_version_immutable BEFORE UPDATE ON media_asset_version
  FOR EACH ROW EXECUTE FUNCTION guard_media_asset_version_immutable();

-- 区分「素材整体删除的级联」与「直接删某个版本行」：
-- 级联发生时父行 media_asset 已经不在了，直接删版本行时父行还在。
-- 这个判据不是取巧——它是同一事务里可观察的事实，且让 V18 的删除素材路径保持原样可用。
CREATE FUNCTION refuse_media_process_source_version_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_exists boolean; referenced boolean; refs text;
BEGIN
 SELECT EXISTS(SELECT 1 FROM media_asset a WHERE a.tenant_id=OLD.tenant_id AND a.id=OLD.asset_id)
   INTO parent_exists;
 IF NOT parent_exists THEN RETURN OLD; END IF;  -- 素材整体删除的级联，放行
 SELECT EXISTS(
   SELECT 1 FROM media_process_job_item i
    WHERE i.tenant_id=OLD.tenant_id AND i.asset_id=OLD.asset_id AND i.source_version=OLD.version_no)
   OR EXISTS(
   SELECT 1 FROM media_process_output o
    WHERE o.tenant_id=OLD.tenant_id AND o.asset_id=OLD.asset_id
      AND (o.from_version=OLD.version_no OR o.to_version=OLD.version_no))
   INTO referenced;
 IF referenced THEN
  SELECT string_agg(DISTINCT ref, '、') INTO refs FROM (
    SELECT '处理来源 v'||i.source_version AS ref FROM media_process_job_item i
     WHERE i.tenant_id=OLD.tenant_id AND i.asset_id=OLD.asset_id AND i.source_version=OLD.version_no
    UNION ALL
    SELECT '产物版本 v'||o.to_version FROM media_process_output o
     WHERE o.tenant_id=OLD.tenant_id AND o.asset_id=OLD.asset_id AND o.to_version=OLD.version_no
    UNION ALL
    SELECT '处理基线 v'||o.from_version FROM media_process_output o
     WHERE o.tenant_id=OLD.tenant_id AND o.asset_id=OLD.asset_id AND o.from_version=OLD.version_no) x;
  RAISE EXCEPTION '媒体版本 v% 已被图片处理记录引用（%），不可删除：3.4.1.2 要求原图始终保留。要清理这个素材请删除素材本身（无引用时才允许）',
   OLD.version_no, refs USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_media_process_source_version_delete BEFORE DELETE ON media_asset_version
  FOR EACH ROW EXECUTE FUNCTION refuse_media_process_source_version_delete();

-- 配方被任务引用时不允许删（配置可以停用，被事实引用的历史不能被抹掉）。
-- 与 V18 的 refuse_media_tag_delete 同一纪律：删配方会级联删掉它的比例档，
-- 而比例档是历史产物的口径来源——产物表存的是 label 文本，档没了就无法解释产物。
CREATE FUNCTION refuse_media_process_recipe_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE jobs bigint;
BEGIN
 SELECT count(*) INTO jobs FROM media_process_job j
  WHERE j.tenant_id=OLD.tenant_id AND j.recipe_id=OLD.id;
 IF jobs>0 THEN
  RAISE EXCEPTION '处理配方「%」已被 % 个处理任务引用，不可删除：请改用停用（停用后不能再用它新建任务，历史任务与产物照常可读）',
   OLD.code, jobs USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_media_process_recipe_delete BEFORE DELETE ON media_process_recipe
  FOR EACH ROW EXECUTE FUNCTION refuse_media_process_recipe_delete();

-- ============================================================ 5. 索引
--
-- 任务列表页：按状态 + 最近提交排（页面上最常见的是「我刚点的那个跑到哪了」）。
CREATE INDEX media_process_job_list ON media_process_job(tenant_id, status, created_at DESC, id DESC);
-- 未完成态的部分索引：任务列表页「只看排队中/处理中」这个筛选走它。
-- 用部分索引是因为终态行远多于在跑的行——素材库跑上几个月后，
-- 全表索引里绝大多数条目都是再也不会被读的死条目。
CREATE INDEX media_process_job_pending ON media_process_job(tenant_id, created_at)
  WHERE status IN ('PENDING','RUNNING');
-- 任务详情：条目按状态分组展示。
CREATE INDEX media_process_job_item_by_job ON media_process_job_item(tenant_id, job_id, status, created_at);
-- 「这个素材被哪些任务处理过 / 处理出几版」：素材详情页的处理历史区。
CREATE INDEX media_process_job_item_by_asset ON media_process_job_item(tenant_id, asset_id, created_at DESC);
CREATE INDEX media_process_output_by_item ON media_process_output(tenant_id, item_id, created_at, id);
-- 版本删除守卫（refuse_media_process_source_version_delete）要按
-- (tenant_id, asset_id, to_version) 与 (tenant_id, asset_id, from_version) 两路查引用，
-- 所以把两列都放进索引：只建 to_version 那一路的话，from_version 那一路仍然是全表扫，
-- 而这条触发器在每次删版本行时都会走。
CREATE INDEX media_process_output_by_asset ON media_process_output(tenant_id, asset_id, to_version);
CREATE INDEX media_process_output_by_asset_from ON media_process_output(tenant_id, asset_id, from_version);
-- 配方字典列表与比例档读取。
CREATE INDEX media_process_recipe_list ON media_process_recipe(tenant_id, enabled, code);
CREATE INDEX media_process_recipe_ratio_by_recipe ON media_process_recipe_ratio(tenant_id, recipe_id, sort_no, id);

-- ============================================================ 6. RLS 与授权
--
-- 五张表全部租户级数据（素材库是租户共享资产库，不套 V3 的 owner_id/department_id 数据范围）：
-- 与 media_* 同一形状。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['media_process_recipe','media_process_recipe_ratio','media_process_job','media_process_job_item','media_process_output'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  media_process_recipe, media_process_recipe_ratio, media_process_job, media_process_job_item, media_process_output
  TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  media_process_recipe, media_process_recipe_ratio, media_process_job, media_process_job_item, media_process_output
  TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **04 刊登上传**：处理产物就是 media_asset 的新版本，刊登侧按 media_asset 读当前版本即可，
--     不需要认识本模块的表。要「某次处理产出了什么」的明细时再走授权 API。
--   * **转码 / 视频抽帧**：3.4.1.1 明确视频的宽高与时长不探测（需要解容器，JDK 没有），
--     本模块只处理图片（media_type='IMAGE'），视频处理不在 D1–D2 范围内。
--     配方的 target_format 只允许图片格式，不含任何视频容器。
--   * **目标平台规格联动**：target_platform 是自由文本。真正与 channel 服务的平台规格表联动时，
--     走 event / 授权 API，不在这里加跨库 FK。