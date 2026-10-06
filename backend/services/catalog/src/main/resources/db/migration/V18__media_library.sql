-- 3.4.1.1 媒体库：媒体对象元数据 + 分类标签 + 引用计数 + 版本 + 失效检测 + 删除保护。
--
-- 蓝图 3.4.1.1 的原话：
--   「集中管理图片与视频：上传、分类标签、引用计数（被哪些 SPU/SKU/listing 使用）、版本、
--     失效检测。支撑批量刊登时的"素材库复用"。」
--   「输出：媒体对象元数据（存储键、尺寸、格式、哈希）；删除保护（有引用不可删）。」
--   「媒体用**对象存储 + 元数据本地管理**的组合，不把二进制存业务库。」
--
-- ============================================================ 为什么本轮不 drop product_asset
--
-- V1 建过一张 `product_asset`，它是 `LIKE product_research INCLUDING ALL` 的同构复制：
-- 没有文件键、没有 sku_id、没有任何图片/视频字段。台账里 3.4.1.1 标「阻塞」指的就是它。
-- V6 的注释也写了「修这张表是 3.4.1.1 的专门任务」。
--
-- 但把它 drop 掉会立刻打断**两个已交付模块**（实测，不是推测）：
--
--   * `CatalogService.table()` 把 `assets` 映射到 product_asset，
--     `/catalog/records/assets` 是 3.1.x 通用记录面的一路，`/products/assets` 页面在读它，
--     CatalogIntegrationTest 对 research / compliance / assets 三种 kind 都做了断言；
--   * `SkuDetailService.media()` 读它的 title/notes/reference 给 SKU 详情页的媒体区，
--     SkuDetailIntegrationTest 断言 `d.media()` 里能取到 title。
--
-- 那条记录的形状（title / notes / reference / status / locale / target_country / expires_on）
-- 与媒体元数据（storage_key / media_type / byte_size / width / sha256）**没有任何交集**。
-- 把它们塞进同一张表，或者为拆表去重写两个已交付模块的写入所有权与测试，
-- 都是 V9 / V11 / V13 / V14 / V16 / V17 反复强调的那件事：**重开旧账**。
--
-- 所以本轮的口径是：
--   * 媒体库**不复用 product_asset**，也**不在它上面加字段硬凑**——它拿的是全新的六张表；
--   * product_asset 原样保留，继续服务 3.1.x 的通用记录面（那是它们的写入所有权，不是本模块的）；
--   * 3.4.1.1 的下游复用（3.4.1.2 图片处理、04 批量上传、刊登选图）一律读 media_* 这条新路径。
-- 删掉 product_asset 是 3.1.x 那条线自己的决定，不在本模块范围内。
--
-- ============================================================ 二进制不落业务库
--
-- 六张表全部只存**元数据**与一个存储键（storage_key），没有一个 bytea / oid 列：
-- 蓝图写死了「对象存储 + 元数据本地管理」，把二进制塞进 PG 会让备份、TOAST、
-- 与 04 批量上传的并发写全部退化。实际字节的落盘与读取走
-- `MediaStorage` 端口（catalog 服务内），当前实现是本地文件系统，根目录由
-- `MEDIA_STORAGE_ROOT` 配置，换成 S3 / OSS 只需换一个实现类。
--
-- ============================================================ 1. 媒体对象
--
-- 一行 = 一个素材。它**同时**是「当前版本」和「素材身份」：
-- 当前版本的元数据冗余在本表上（storage_key / sha256 / 字节数 / 尺寸），
-- 历史版本在 media_asset_version 里。
--
-- 为什么当前版本要冗余而不是 join 出来：
--   列表页每行都要显示「现在这个素材是哪张图、多大、多宽」，按版本表取就得再 join 一次
--   或者做一次窗口函数。冗余换来的代价由下面两个触发器兜住，它们合起来保证
--   **版本表是唯一真相来源、media_asset 上的当前版本字段只是被强制的派生缓存**：
--     (1) sync_media_asset_from_version —— 每写入一条版本行，就把素材本体同步成那一版的元数据。
--         写入所有权只有这一条路径，不存在「谁改了哪一列」的歧义。
--     (2) guard_media_asset_current_version —— 直接 UPDATE media_asset 绕过 (1) 时撞下来：
--         current_version 指的版本行必须存在，且字节数/哈希/存储键逐字一致。
--   注意 (1) 是 AFTER INSERT 而不是 BEFORE：素材行必须先存在（版本行有指向它的外键），
--   版本行才写得进去。这两个方向不能同时用 BEFORE，否则是先有鸡还是先有蛋的死锁。
CREATE TABLE media_asset (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 显示名：运营自己起的素材名（「白底主图-正面」），与原始文件名分开。
  -- 批量刊登选图时运营看的是这个名字，不是 object key。
  name varchar(200) NOT NULL,
  description text NOT NULL DEFAULT '',
  -- 媒体类型：IMAGE / VIDEO。枚举化而不是自由文本，因为下游 3.4.1.2 要按它选处理管线
  -- （去背景只对图片有意义），而刊登侧按它决定主图字段能不能填。
  media_type text NOT NULL CHECK(media_type IN ('IMAGE','VIDEO')),
  -- 用途：主图 / 附图 / 详情图 / 视频 / 其他。刊登按用途取图（主图位不能塞详情图）。
  purpose text NOT NULL CHECK(purpose IN ('PRIMARY','GALLERY','DETAIL','VIDEO','OTHER')),
  -- 格式与 MIME：**从字节探测得出**，不是调用方填的（见 MediaRules.probe 的说明）。
  file_format varchar(32) NOT NULL CHECK(btrim(file_format)<>''),
  mime_type varchar(120) NOT NULL CHECK(btrim(mime_type)<>''),
  -- 字节大小：NUMERIC 而非 bigint，与仓库里 sku_package 的 numeric(19,6) 同一口径。
  -- 禁浮点记账是硬要求，这里虽然没有金额，但统一成 NUMERIC(19,6) 让宽度/时长/大小
  -- 三组「量」共用一套精度约定，前端也不必为两种类型写两套格式化。
  byte_size numeric(19,6) NOT NULL CHECK(byte_size>0),
  -- 宽高（像素）与时长（秒）：图片必有宽高，视频宽高不探测（留空），
  -- 视频时长只对 MP4 家族探测得到，其他容器留空——**不猜、不填假值**。
  width_px numeric(19,6),
  height_px numeric(19,6),
  duration_seconds numeric(19,6),
  -- 内容哈希：内容寻址的锚点。同一租户内相同内容只允许一个素材行
  -- （UNIQUE(tenant_id,content_sha256)），这就是「素材库复用」的字面实现：
  -- 重复上传同一张图返回既有素材，而不是复制一份。
  content_sha256 varchar(64) NOT NULL CHECK(length(content_sha256)=64),
  -- 存储键：对象存储里的定位符，**不是**可直接访问的 URL（对外 URL 由读取端点现签）。
  storage_key varchar(500) NOT NULL CHECK(btrim(storage_key)<>''),
  -- 原始文件名与替代文本：刊登详情页要用（亚马逊等平台强制 alt text）。
  original_filename varchar(255) NOT NULL CHECK(btrim(original_filename)<>''),
  alt_text varchar(500),
  -- ------------------------------------------------------------ 失效检测
  -- 元数据在 PG、二进制在对象存储，两边会漂移：文件被运维删了、被覆盖了、
  -- 或者对象存储迁移后 key 指到别的东西。元数据本身查不出来，只能验。
  --   UNKNOWN   从未验过（刚上传）
  --   VERIFIED  验过且字节数与哈希都对得上
  --   MISSING   存储里已经找不到这个键（最常见：文件被删或迁移）
  --   SIZE_MISMATCH     键在，但字节数变了
  --   CONTENT_MISMATCH  键在、大小一样，但哈希不同（被覆盖成另一个同尺寸文件）
  integrity_status text NOT NULL DEFAULT 'UNKNOWN'
    CHECK(integrity_status IN ('UNKNOWN','VERIFIED','MISSING','SIZE_MISMATCH','CONTENT_MISMATCH')),
  -- 系统时间（什么时候验的）与业务时间分开：integrity_checked_at 是运维动作时间，
  -- created_at/updated_at 是素材的业务生命周期时间，两者不该互相覆盖。
  integrity_checked_at timestamptz,
  integrity_detail text NOT NULL DEFAULT '',
  -- 当前版本号：必须指向 media_asset_version 里真实存在的一行（触发器保证）。
  current_version int NOT NULL DEFAULT 1 CHECK(current_version>0),
  -- 停用 = 不许新建引用，历史引用与版本照常可读（与 brand.enabled 同一口径）。
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 存储键租户内唯一：两个素材指向同一个 key 意味着删一个会毁掉另一个。
  UNIQUE(tenant_id,storage_key),
  -- 内容寻址：同租户内同一份内容只有一行。
  UNIQUE(tenant_id,content_sha256),
  CHECK(btrim(name)<>''),
  -- 图片必须探测出宽高：探测不出宽高的「图片」是坏文件，不该进素材库。
  -- 这是探测失败时在 DB 层再挡一次（应用层已经先拒了）。
  CHECK(media_type<>'IMAGE' OR (width_px IS NOT NULL AND height_px IS NOT NULL)),
  CHECK(width_px IS NULL OR width_px>0),
  CHECK(height_px IS NULL OR height_px>0),
  CHECK(duration_seconds IS NULL OR duration_seconds>=0)
);

-- ------------------------------------------------------------ 2. 版本历史
--
-- 蓝图 3.4.1.1 要求「版本」，而 3.4.1.2 又写死了「处理结果不可逆覆盖原图，
-- **原图始终保留**」——两条合起来只有一个形状能同时满足：每次上传落一个版本行，
-- 旧版本永远不删（除非整个素材被删）。当前版本由 media_asset.current_version 指，
-- 历史版本随时可回读，也让「这个素材上一版长什么样」可回答。
CREATE TABLE media_asset_version (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, asset_id uuid NOT NULL,
  version_no int NOT NULL CHECK(version_no>0),
  storage_key varchar(500) NOT NULL CHECK(btrim(storage_key)<>''),
  original_filename varchar(255) NOT NULL CHECK(btrim(original_filename)<>''),
  file_format varchar(32) NOT NULL,
  mime_type varchar(120) NOT NULL,
  byte_size numeric(19,6) NOT NULL CHECK(byte_size>0),
  width_px numeric(19,6),
  height_px numeric(19,6),
  duration_seconds numeric(19,6),
  content_sha256 varchar(64) NOT NULL CHECK(length(content_sha256)=64),
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一素材内版本号唯一：不允许两个 v2，回滚/回填时不会出现「当前是哪一个 v2」。
  UNIQUE(tenant_id,asset_id,version_no),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES media_asset(tenant_id,id) ON DELETE CASCADE
);

-- ------------------------------------------------------------ 当前版本同步（写入路径）
--
-- 写入顺序固定为：先插 media_asset 行，再插 media_asset_version 行，由下面的 AFTER INSERT
-- 把素材本体同步成那一版的元数据并推进 current_version。
-- 反过来（先版本行）写不进去，因为版本行有指向素材行的外键。
CREATE FUNCTION sync_media_asset_from_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE media_asset SET
   storage_key=NEW.storage_key, original_filename=NEW.original_filename,
   file_format=NEW.file_format, mime_type=NEW.mime_type,
   byte_size=NEW.byte_size, width_px=NEW.width_px, height_px=NEW.height_px,
   duration_seconds=NEW.duration_seconds, content_sha256=NEW.content_sha256,
   current_version=NEW.version_no,
   -- 只有推进到更新的版本时才动业务时间轴与乐观锁：
   -- 回填一条 v1（补录历史版本）不应该让素材看起来"刚被改过"，
   -- 那会让列表页的"最近更新"排序给出误导性的结果。
   updated_at=CASE WHEN NEW.version_no>=current_version THEN now() ELSE updated_at END,
   version=CASE WHEN NEW.version_no>=current_version THEN version+1 ELSE version END
 WHERE tenant_id=NEW.tenant_id AND id=NEW.asset_id;
 RETURN NEW;
END $$;
CREATE TRIGGER sync_media_asset_from_version AFTER INSERT ON media_asset_version
  FOR EACH ROW EXECUTE FUNCTION sync_media_asset_from_version();

-- 绕过写入路径直接改 media_asset 的媒体元数据时（脚本、运维 SQL、未来的另一个服务），
-- 必须撞上「当前版本字段与版本表一致」的检查。
--
-- 用 **AFTER UPDATE 行级**触发器：BEFORE 阶段看到的还是 OLD 行，而同步路径
-- （sync_media_asset_from_version）改的正是 current_version 与 byte_size，
-- BEFORE 拿到旧值会误判。AFTER UPDATE 时 NEW 是改完的行，且那条版本行
-- （本事务里更早的一条 INSERT）已经可见，两边比对的是同一时刻的事实。
--
-- 只挂 UPDATE 不挂 INSERT：素材行必须先于版本行存在（版本行有指向它的外键），
-- INSERT 那一刻本来就还没有版本行可比，挂了也是空转。
--
-- 一行一检查，不是全表扫：素材库上千行时，全表扫会让每次写入都退化成 O(n)。
CREATE FUNCTION guard_media_asset_current_version() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE consistent boolean;
BEGIN
 SELECT EXISTS(SELECT 1 FROM media_asset_version v
   WHERE v.tenant_id=NEW.tenant_id AND v.asset_id=NEW.id AND v.version_no=NEW.current_version
     AND v.byte_size=NEW.byte_size AND v.content_sha256=NEW.content_sha256
     AND v.storage_key=NEW.storage_key)
  INTO consistent;
 IF NOT consistent THEN
  RAISE EXCEPTION '媒体「%」的当前版本字段与版本表不一致：storage_key / byte_size / content_sha256 只能由 media_asset_version 同步而来（当前版本 %）',
   NEW.name, NEW.current_version USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_media_asset_current_version
  AFTER UPDATE ON media_asset
  FOR EACH ROW EXECUTE FUNCTION guard_media_asset_current_version();

-- ------------------------------------------------------------ 3. 分类标签
--
-- 标签是**租户级字典**（与 brand / unit_of_measure / product_category 同级），
-- 不是每行自由文本：批量刊登选素材时要「按用途标签筛出可复用的那批」，
-- 自由文本标签只能全表扫。同一批标签要在多个素材上保持同一含义，否则筛选无意义。
CREATE TABLE media_tag (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(32) NOT NULL CHECK(btrim(code)<>''),
  name varchar(100) NOT NULL CHECK(btrim(name)<>''),
  note text NOT NULL DEFAULT '',
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code),
  CHECK(btrim(code)<>'' AND btrim(name)<'')
);

-- 素材与标签的多对多。一个标签在一个素材上只能有一条。
CREATE TABLE media_asset_tag (
  tenant_id uuid NOT NULL, asset_id uuid NOT NULL, tag_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,asset_id,tag_id),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES media_asset(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,tag_id) REFERENCES media_tag(tenant_id,id) ON DELETE CASCADE
);

-- ------------------------------------------------------------ 4. 引用计数（被哪些 SPU/SKU 使用）
--
-- 蓝图点名「引用计数（被哪些 SPU/SKU/listing 使用）」与「删除保护（有引用不可删）」。
-- 引用是一张可枚举的表，而不是 media_asset 上的一个整数字段：
-- 引用计数要能回答「**被谁**引用」，只有数字答不出来；而删除保护要在 DB 层生效，
-- 那就需要在删除那一刻能查到引用行。
--
-- **owner_type 只有 SPU 与 SKU 两个取值**，listing 引用刻意不做：
--   04 刊登（listing）在本仓库里是**另一个服务**（services/channel）尚未交付的模块，
--   在这里建一个指向不存在表的引用列就是 V16 头部否掉的方案 (B)「硬编码的假引用」——
--   比缺功能更糟。04 落地后按模块边界走 event / 授权 API 往这张表写引用行，
--   那时再加 owner_type='LISTING' 与对应的无外键列（listing 在别的库，跨库 FK 不可能）。
--   这一点在结尾「留给下游的口子」里写清。
CREATE TABLE media_reference (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, asset_id uuid NOT NULL,
  owner_type text NOT NULL CHECK(owner_type IN ('SPU','SKU')),
  spu_id uuid,
  sku_id uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES media_asset(tenant_id,id) ON DELETE CASCADE,
  -- 复合外键带 tenant_id：跨租户引用在 DB 层不可能发生（与 V16 brand_category 同一手法）。
  -- spu 与 sku 都有 UNIQUE(tenant_id,id)，所以这两条外键能建。
  FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id),
  -- 形状约束：SPU 引用必须给 spu_id 且不许给 sku_id，反之亦然。
  -- 少了它，一条 owner_type='SPU' 而 sku_id 非空的行会让「这个素材被哪个 SKU 用着」
  -- 在两个列里各有一份答案。
  CHECK((owner_type='SPU' AND spu_id IS NOT NULL AND sku_id IS NULL)
     OR (owner_type='SKU' AND sku_id IS NOT NULL AND spu_id IS NULL))
);

-- 同一素材对同一对象只允许一条引用。
-- 不能直接写 UNIQUE(tenant_id,asset_id,owner_type,spu_id,sku_id)：PG 的 UNIQUE 把 NULL 视作互不相等，
-- 于是「两条都指向同一个 SPU（sku_id 为 NULL）」这种重复引用照样能插进来，
-- 引用计数会翻倍，而删除保护看到的是一个比真实情况大的数字。
-- 用 coalesce 把 NULL 折叠成哨兵值，表达式唯一索引才有去重效果（与 V16/V17 的部分索引同一手法）。
CREATE UNIQUE INDEX media_reference_dedupe ON media_reference(
  tenant_id, asset_id, owner_type,
  coalesce(spu_id,'00000000-0000-0000-0000-000000000000'::uuid),
  coalesce(sku_id,'00000000-0000-0000-0000-000000000000'::uuid));

-- ------------------------------------------------------------ 5. 失效检测记录
--
-- 每次验完整性都留一行，理由和 V8 的 sku_unit_conversion_snapshot 一样：
-- 「什么时候发现文件不见了」是排查素材库故障时要回溯的事实，
-- 只在 media_asset 上覆盖一个 integrity_status 会把这段历史抹掉。
-- 哈希只在真的重算时才存（MISSING 时没有实际值，存 null 而不是留上一个成功值冒充本次结果）。
CREATE TABLE media_integrity_check (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, asset_id uuid NOT NULL,
  integrity_status text NOT NULL
    CHECK(integrity_status IN ('VERIFIED','MISSING','SIZE_MISMATCH','CONTENT_MISMATCH')),
  byte_size_expected numeric(19,6),
  byte_size_actual numeric(19,6),
  sha256_expected varchar(64),
  sha256_actual varchar(64),
  detail text NOT NULL DEFAULT '',
  -- 系统时间：这是运维动作的发生时间，不是业务时间。
  checked_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES media_asset(tenant_id,id) ON DELETE CASCADE
);

-- ------------------------------------------------------------ 6. 删除保护
--
-- 「有引用不可删」必须在 DB 层兜住，不能只靠服务层判一下：
-- 应用层判的是「请求这一刻」，而这条素材可能在判完之后、删除之前被刊登侧挂上引用。
-- 触发器把窗口关掉，并给出可读原因（23503 外键冲突，与仓库既有做法一致）。
CREATE FUNCTION refuse_media_asset_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE refs bigint; refs_spu bigint; refs_sku bigint;
BEGIN
 SELECT count(*), count(*) FILTER (WHERE owner_type='SPU'), count(*) FILTER (WHERE owner_type='SKU')
   INTO refs, refs_spu, refs_sku
   FROM media_reference r WHERE r.tenant_id=OLD.tenant_id AND r.asset_id=OLD.id;
 IF refs>0 THEN
  RAISE EXCEPTION '媒体「%」仍被引用（SPU % 处、SKU % 处），不可删除：请先在对应商品上解除引用',
   OLD.name, refs_spu, refs_sku USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_media_asset_delete BEFORE DELETE ON media_asset
  FOR EACH ROW EXECUTE FUNCTION refuse_media_asset_delete();

-- 标签字典的删除保护：标签被素材用着时不允许删，只允许停用。
-- 与 media_asset 的删除保护同一条纪律（配置可以停用，历史事实不能被抹掉），
-- 但这里**不级联删** media_asset_tag：删标签会静默清掉一批素材的分类，
-- 而运营停用标签时同样想要的效果（不再出现在筛选里）已经由 enabled=false 提供。
CREATE FUNCTION refuse_media_tag_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE used bigint;
BEGIN
 SELECT count(*) INTO used FROM media_asset_tag t
  WHERE t.tenant_id=OLD.tenant_id AND t.tag_id=OLD.id;
 IF used>0 THEN
  RAISE EXCEPTION '标签「%」仍被 % 个素材使用，不可删除：请改用停用（停用后不再出现在新建筛选里，历史关联保留）',
   OLD.code, used USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_media_tag_delete BEFORE DELETE ON media_tag
  FOR EACH ROW EXECUTE FUNCTION refuse_media_tag_delete();

-- ------------------------------------------------------------ 7. 索引
--
-- 列表页主查询：媒体类型 + 用途 + 启用状态，按最近更新排序（上传后立刻能看到）。
CREATE INDEX media_asset_list ON media_asset(tenant_id, media_type, purpose, enabled, updated_at DESC, id DESC);
-- 「现在有哪些素材验不过」——素材库巡检与概览里的治理缺口指标。
CREATE INDEX media_asset_integrity ON media_asset(tenant_id, integrity_status, updated_at DESC)
  WHERE integrity_status<>'VERIFIED';
-- 内容寻址去重的命中路径（重复上传同一份字节时走这里）。
CREATE INDEX media_asset_sha ON media_asset(tenant_id, content_sha256);
-- 版本历史：「这个素材上一版是什么」与回读。
CREATE INDEX media_asset_version_by_asset ON media_asset_version(tenant_id, asset_id, version_no DESC);
-- 标签筛选：「打了这个标签的素材」（批量刊登选素材的主路径）。
CREATE INDEX media_asset_tag_by_tag ON media_asset_tag(tenant_id, tag_id, asset_id);
-- 引用计数与删除保护的主查询。
CREATE INDEX media_reference_by_asset ON media_reference(tenant_id, asset_id, owner_type);
-- 「这个 SPU / 这个 SKU 用了哪些素材」：商品详情页的媒体区与刊登侧都走它。
CREATE INDEX media_reference_by_spu ON media_reference(tenant_id, spu_id, asset_id) WHERE spu_id IS NOT NULL;
CREATE INDEX media_reference_by_sku ON media_reference(tenant_id, sku_id, asset_id) WHERE sku_id IS NOT NULL;
-- 失效检测历史按时间倒序回读。
CREATE INDEX media_integrity_check_by_asset ON media_integrity_check(tenant_id, asset_id, checked_at DESC, id DESC);
-- 标签字典列表。
CREATE INDEX media_tag_list ON media_tag(tenant_id, enabled, code);

-- ------------------------------------------------------------ 8. RLS 与授权
--
-- 六张表全部是租户级数据（与 product_asset / brand / unit_group 同级）：只有租户隔离，
-- 可见性由端点层权限把关。数据范围（owner_id / department_id）那套是给
-- 「个人负责的业务单据」用的（V3 的 spu / product_research / product_asset），
-- 素材库是租户共享资产库——同一个运营要在别人的素材上复用，不套个人数据范围。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['media_asset','media_asset_version','media_tag','media_asset_tag','media_reference','media_integrity_check'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  media_asset, media_asset_version, media_tag, media_asset_tag, media_reference, media_integrity_check
  TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  media_asset, media_asset_version, media_tag, media_asset_tag, media_reference, media_integrity_check
  TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **listing 引用要等 04 刊登**：04 在 services/channel 里、尚未交付，
--     这里放一个指向别库不存在表的引用列就是硬编码的假引用（V16 头部否掉的方案 (B)）。
--     04 落地后由 04 通过 event / 授权 API 往 media_reference 写引用行，
--     届时给 media_reference 加 owner_type='LISTING' 与无外键的 listing 标识列（跨库 FK 不可能）。
--   * **3.4.1.2 图片处理从 media_asset_version 取原图**，处理结果落成新版本行，
--     原图版本永不覆盖（V18 第 2 节的版本表就是为它准备的）。
--   * **product_asset 继续存在**，服务 3.1.x 的通用记录面（`/catalog/records/assets` 与
--     `/products/assets`）。它不是媒体表、也不参与引用计数，见头部「为什么本轮不 drop」。
--   * **失效检测只覆盖对象存储这一侧**。3.4.1.2 的处理结果是否已被下游刊登采纳
--     属于刊登侧的事实，本模块不猜。