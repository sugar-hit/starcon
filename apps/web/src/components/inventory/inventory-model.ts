import type {
  BucketAction,
  BucketRow,
  DimensionCapability,
  DistributionRow,
  DistributionSpuRef,
  InventoryBucketDetail,
  InventoryLedgerDrill,
  InventoryLedgerPage,
  InventoryLedgerRow,
  InventoryOverview,
  InventoryPolicy,
  InventoryPolicyInput,
  InventoryPolicySaved,
  InventoryRefType,
  LegalEntityRef,
  LocationRef,
  LotRef,
  MultiWarehouseDistribution,
  OverviewGroup,
  OverviewRow,
  PlatformObservationInput,
  PlatformObservationSaved,
  QualityStatusRef,
  ReconciliationFindingPage,
  ReconciliationRequest,
  ReconciliationResult,
  ReconciliationRunPage,
  SkuRef,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 7.1.1.1 库存总览 + 7.1.1.2 库存明细前端模型。
 *
 * <p>界面上最容易出错的几处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**「未设阈值」不能画成「未超储」**。超储是拿在手和阈值比出来的，没有阈值就没有
 *       这个判断。两者含义不同（照 catalog `unregisteredSkus` 同一口径），所以行上与
 *       合计上都分开显示。
 *   <li>**在手 ≠ 可用**。在手含在途，可用不含 —— 未到货的货不能拿去发货。数字看着接近，
 *       但差的就是在途，界面上必须把在途单列一行。
 *   <li>**合计不受分页影响**。合计是全量的，翻页时它不变；否则"翻到第二页总数变小"
 *       会被当成数据丢了。
 *   <li>**明细的"未分解"不能算成"不平"**。库位明细页只覆盖已拆到库位的库存，
 *       还没拆的 balance 行是合法状态，要单独报出来而不是混进差异里。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export function fetchOverview(params: OverviewParams) {
  return api<InventoryOverview>(`warehouse/inventory/overview${query(params)}`);
}

/** 7.1.1.2 bucket 明细。筛选条件与总览同一套 query 拼装约定。 */
export function fetchBuckets(params: BucketParams) {
  return api<InventoryBucketDetail>(`warehouse/inventory/buckets${bucketQuery(params)}`);
}

export function fetchCapabilities() {
  return api<DimensionCapability[]>('warehouse/inventory/capabilities');
}

export function fetchWarehouses() {
  return api<WarehouseRef[]>('warehouse/warehouses');
}

export function fetchLegalEntities() {
  return api<LegalEntityRef[]>('warehouse/legal-entities');
}

export function fetchQualityStatuses() {
  return api<QualityStatusRef[]>('warehouse/quality-statuses');
}

export function fetchSkus() {
  return api<SkuRef[]>('warehouse/skus');
}

/** 库位下拉。按仓库过滤，让"先选仓、再选库位"的逐级下钻在界面上成立。 */
export function fetchLocations(warehouseId?: string) {
  return api<LocationRef[]>(
    `warehouse/locations${warehouseId ? `?warehouseId=${encodeURIComponent(warehouseId)}` : ''}`,
  );
}

/** 批次下拉。按 SKU 过滤。 */
export function fetchLots(skuId?: string) {
  return api<LotRef[]>(`warehouse/lots${skuId ? `?skuId=${encodeURIComponent(skuId)}` : ''}`);
}

export function fetchPolicies(scopeType: string) {
  return api<InventoryPolicy[]>(
    `warehouse/inventory/policies${scopeType ? `?scopeType=${encodeURIComponent(scopeType)}` : ''}`,
  );
}

export function savePolicy(id: string | null, input: InventoryPolicyInput) {
  return api<InventoryPolicySaved>(
    id ? `warehouse/inventory/policies/${id}` : 'warehouse/inventory/policies',
    id ? 'PUT' : 'POST',
    input,
  );
}

export function deletePolicy(id: string) {
  return api<{ deleted: boolean }>(`warehouse/inventory/policies/${id}`, 'DELETE');
}

// ------------------------------------------------------------ 7.1.2.1 库存流水台账

export function fetchLedger(params: LedgerParams) {
  return api<InventoryLedgerPage>(`warehouse/inventory/ledger${ledgerQuery(params)}`);
}

/** 分录下钻：一笔业务的全部带符号分录。 */
export function fetchLedgerDrill(transactionId: string) {
  return api<InventoryLedgerDrill>(
    `warehouse/inventory/ledger/${encodeURIComponent(transactionId)}`,
  );
}

export function fetchLedgerRefTypes() {
  return api<InventoryRefType[]>('warehouse/inventory/ledger/ref-types');
}

// ------------------------------------------------------------ 7.1.2.2 账实核对

export interface ReconciliationParams {
  from?: string;
  to?: string;
  warehouseId?: string;
  skuId?: string;
  legalEntityId?: string;
}

/**
 * 发起一次核对。
 *
 * <p>用 POST：核对会落一份不可变的结论（run + findings），是写操作而不是查询。
 * 时间串按带偏移的 ISO-8601 送（`datetime-local` 读到的是不带偏移的墙钟串，
 * 这里补上本机偏移量），后端不接受无偏移的时间串 —— 那样同一份请求在开发机与
 * K8s 里会核出不同区间。
 */
export function runReconciliation(params: ReconciliationParams) {
  const payload: ReconciliationRequest = {
    from: params.from ? withOffset(params.from) : null,
    to: params.to ? withOffset(params.to) : null,
    warehouseId: params.warehouseId || null,
    skuId: params.skuId || null,
    legalEntityId: params.legalEntityId || null,
  };
  return api<ReconciliationResult>('warehouse/inventory/reconciliation', 'POST', payload);
}

export function fetchReconciliationRuns(params: { status?: string; page?: number; size?: number }) {
  return api<ReconciliationRunPage>(`warehouse/inventory/reconciliation${query(params)}`);
}

export function fetchReconciliationRun(runId: string) {
  return api<ReconciliationResult>(
    `warehouse/inventory/reconciliation/${encodeURIComponent(runId)}`,
  );
}

/** 回查某次核对的发现。kind 决定翻的是差异明细还是可疑流水。 */
export function fetchReconciliationFindings(
  runId: string,
  params: { kind?: string; page?: number; size?: number },
) {
  return api<ReconciliationFindingPage>(
    `warehouse/inventory/reconciliation/${encodeURIComponent(runId)}/findings${query(params)}`,
  );
}

/** 给 `datetime-local` 的墙钟串补上本机偏移量：`2026-10-01T09:30` → `2026-10-01T09:30:00+08:00`。 */
export function withOffset(local: string): string {
  const date = new Date(local);
  if (Number.isNaN(date.getTime())) return local;
  const pad = (n: number) => String(n).padStart(2, '0');
  const sign = date.getTimezoneOffset() <= 0 ? '+' : '-';
  const offset = Math.abs(date.getTimezoneOffset());
  return `${local.length === 16 ? `${local}:00` : local}${sign}${pad(Math.floor(offset / 60))}:${pad(
    offset % 60,
  )}`;
}

/** 可疑类别的中文标签。与后端 SuspectCategory 一一对应，不认识的码原样显示而不是静默吞掉。 */
export const suspectCategoryLabels: Record<string, string> = {
  MISSING: '漏记',
  BACKFILL: '迟到补记',
  DUPLICATE: '重复记',
  LOST_UPDATE: '并发丢失',
};

export const suspectCategoryOptions = Object.entries(suspectCategoryLabels).map(
  ([value, label]) => ({ value, label }),
);

export const severityLabels: Record<string, string> = {
  INFO: '提示',
  WARN: '警告',
  HIGH: '严重',
};

export const statusLabels: Record<string, string> = {
  CONSISTENT: '账实一致',
  DISCREPANT: '存在差异',
};

export const scopeTypeLabels: Record<string, string> = {
  ALL: '全部余额行',
  WAREHOUSE: '仓库',
  SKU: 'SKU',
  LEGAL_ENTITY: '货权法人',
};

/**
 * 可疑区间的时间桶标题：`2026-10-03` 这种按天粒度直接可读，不必展示到时分。
 *
 * <p>刻意按**浏览器本地时区**取日期而不是 `toISOString()`：后者会把 `+08:00` 的
 * 凌晨折算成前一天的 UTC，桶标签和用户心里的"那几天"对不上。核对区间是按天分的，
 * 运营就是按本地日读账的。
 */
export function windowLabel(start: string, end: string | null): string {
  const from = new Date(start);
  const to = end ? new Date(end) : null;
  const day = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  if (Number.isNaN(from.getTime())) return start;
  if (!to || Number.isNaN(to.getTime()) || day(to) === day(from)) return day(from);
  return `${day(from)} ~ ${day(to)}`;
}

/** 差异为 0 时不能显示成空白或「—」：空白会被读成"没查到"。 */
export function diffText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '0.0000';
  return Number(value).toFixed(4);
}

// ------------------------------------------------------------ 查询参数

export interface OverviewParams {
  warehouseId?: string;
  legalEntityId?: string;
  keyword?: string;
  zeroStock?: boolean;
  overstock?: boolean;
  sort?: string;
  page?: number;
  size?: number;
}

/** 拼查询串。空值不拼进去：后端把"未传"和"传空串"当成两种含义处理。 */
export function query(params: OverviewParams) {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.legalEntityId) parts.push(`legalEntityId=${encodeURIComponent(params.legalEntityId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.zeroStock !== undefined) parts.push(`zeroStock=${params.zeroStock}`);
  if (params.overstock !== undefined) parts.push(`overstock=${params.overstock}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 7.1.1.2 明细

/** 7.1.1.2 的逐级下钻条件：法人 → 仓库 → 库位 → SKU → 批次。 */
export interface BucketParams {
  warehouseId?: string;
  legalEntityId?: string;
  locationId?: string;
  skuId?: string;
  lotId?: string;
  qualityStatusId?: string;
  keyword?: string;
  onlyPickable?: boolean;
  sort?: string;
  page?: number;
  size?: number;
}

/**
 * 总览行（7.1.1.1）下钻到明细（7.1.1.2）时携带的四个维度。
 *
 * <p>字段与 `OverviewRow.detailHref` 里的 query 参数一一对应 ——
 * 那个 href 是后端给的路由契约，前端按同样的参数名解析，两者不会各说各话。
 */
export interface BucketDrill {
  warehouseId: string;
  legalEntityId: string;
  skuId: string;
  qualityStatusId: string;
}

/**
 * 解析 7.1.1.1 行上的 `detailHref`，取出下钻维度。
 *
 * <p>刻意**解析后端给的 href** 而不是在前端另写一份参数拼法：href 由
 * `InventoryOverviewService.detailHref()` 生成，参数名只存在一处。
 * href 形状不认识时返回 null（调用方按"无下钻"处理），不抛异常 ——
 * 一个 href 格式变化不该让整个总览页白屏。
 */
export function parseDetailHref(href: string | null | undefined): BucketDrill | null {
  if (!href) return null;
  const [raw] = href.split('?');
  if (raw !== '/inventory') return null;
  const params = new URLSearchParams(href.split('?')[1] ?? '');
  const pick = (key: string) => params.get(key)?.trim() ?? '';
  const drill = {
    warehouseId: pick('warehouseId'),
    legalEntityId: pick('legalEntityId'),
    skuId: pick('skuId'),
    qualityStatusId: pick('qualityStatusId'),
  };
  // 四个维度缺一个就不是一次完整的行级下钻：宁可按"无下钻"处理，
  // 也不要只带一半条件让用户以为看的就是那一行。
  return Object.values(drill).every(Boolean) ? drill : null;
}

/**
 * 明细查询串。
 *
 * 与总览的 `query()` 分开而不是复用同一个函数：明细多了库位、批次、可拣三个条件，
 * 共用一个函数就得给两处都塞可选字段，读起来分不清哪条路径支持哪个条件。
 */
export function bucketQuery(params: BucketParams) {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.legalEntityId) parts.push(`legalEntityId=${encodeURIComponent(params.legalEntityId)}`);
  if (params.locationId) parts.push(`locationId=${encodeURIComponent(params.locationId)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.lotId) parts.push(`lotId=${encodeURIComponent(params.lotId)}`);
  if (params.qualityStatusId)
    parts.push(`qualityStatusId=${encodeURIComponent(params.qualityStatusId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  // 复选框只表达"只看"，不表达"不看"：后端把未传与 false 视为两种含义
  if (params.onlyPickable) parts.push('onlyPickable=true');
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 7.1.2.1 台账查询条件：时间范围 / SKU / 仓库 / 业务类型 / 来源单据类型。 */
export interface LedgerParams {
  /** 发生时间下界（含），ISO-8601 带时区偏移，例如 2026-10-01T00:00:00+08:00。 */
  from?: string;
  /** 发生时间上界（不含）。 */
  to?: string;
  skuId?: string;
  warehouseId?: string;
  kinds?: string[];
  refTypes?: string[];
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

/**
 * 台账查询串。
 *
 * <p>时间参数必须**带时区偏移**发出去：后端按 `timestamptz` 的绝对时刻筛选，
 * 发一个不带偏移的裸时间会被服务端按运行环境时区猜，同一个请求在开发机和 K8s 里
 * 会筛出不同区间，期间净变动就没法复现了。
 */
export function ledgerQuery(params: LedgerParams) {
  const parts: string[] = [];
  if (params.from) parts.push(`from=${encodeURIComponent(params.from)}`);
  if (params.to) parts.push(`to=${encodeURIComponent(params.to)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.kinds?.length) {
    // 同一参数名重复出现 = 多选；后端按 List<String> 接
    params.kinds.forEach((k) => parts.push(`kinds=${encodeURIComponent(k)}`));
  }
  if (params.refTypes?.length) {
    params.refTypes.forEach((r) => parts.push(`refTypes=${encodeURIComponent(r)}`));
  }
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/**
 * 7.1.1.2 行内「查看流水」入口带过来的定位参数。
 *
 * <p>契约里 href 带的是 skuId / warehouseId / locationId / lotId 四个 bucket 维度，
 * 但台账读的是 balance 口径 —— locationId 与 lotId 台账接不住（分录挂在 balance 行上，
 * 不在库位/批次上），所以这里只认 skuId 与 warehouseId，多出来的参数静默丢掉。
 * 前端把这份"哪些维度接得住"写死在这里，而不是把四个参数都原样转发后让用户对着一个
 * 没有库位筛选的台账页发问。
 */
export interface LedgerDrill {
  skuId: string;
  warehouseId: string;
  /**
   * 直接定位到某一笔业务的分录明细。
   *
   * <p>7.1.2.2 账实核对的「可疑流水定位」终点就是一笔具体的业务：从核对页点进来时
   * 必须直接落到那笔的分录上，而不是打开一个没筛选的台账让人自己再找一遍 ——
   * 那等于把定位成本又推回给人了。
   */
  transactionId: string;
}

export function parseLedgerHref(href: string | null | undefined): LedgerDrill | null {
  if (!href) return null;
  const [raw] = href.split('?');
  if (raw !== '/inventory/ledger') return null;
  const params = new URLSearchParams(href.split('?')[1] ?? '');
  const drill = {
    skuId: params.get('skuId')?.trim() ?? '',
    warehouseId: params.get('warehouseId')?.trim() ?? '',
    transactionId: params.get('transactionId')?.trim() ?? '',
  };
  return drill.skuId || drill.warehouseId || drill.transactionId ? drill : null;
}

/**
 * 业务类型标签。
 *
 * <p>蓝图列了「入库、出库、预占、释放、调整、盘点、组装、调拨」八类业务，
 * 而 `inventory_transaction.kind` 的 CHECK 约束只有八个码，盘点与组装当前由写入方
 * 记成 ADJUSTMENT（差异体现在备注与分录构成上）。所以这里**不给盘点/组装造标签** ——
 * 界面上的类型必须与库里真能筛出来的类型一一对应，否则会出现"有盘点单却筛不出盘点"。
 * 调整这一行显式写明它同时承载盘点与组装，免得用户以为盘点漏记了。
 */
export const ledgerKindLabels: Record<string, string> = {
  RECEIPT: '入库',
  SHIPMENT: '出库',
  ADJUSTMENT: '调整（含盘点 / 组装）',
  TRANSFER_OUT: '调拨出',
  TRANSFER_IN: '调拨入',
  RETURN: '退货',
  RESERVE: '预占',
  RELEASE: '释放',
};

/** 业务类型下拉的固定选项。与 ledgerKindLabels 同源，不在第二处再写一份。 */
export const ledgerKindOptions = Object.entries(ledgerKindLabels).map(([value, label]) => ({
  value,
  label,
}));

/**
 * 带符号数量。
 *
 * <p>台账的核心是**数量符号**（入库正、出库负）：这里显式渲染 +/− 而不是靠颜色区分，
 * 因为颜色在灰度打印与色觉障碍下不可用；颜色只是辅助。
 */
export function signed(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const text = Math.abs(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  if (value > 0) return `+${text}`;
  if (value < 0) return `−${text}`;
  return '0.00';
}

/** 数量符号对应的语义色 class：入库绿、出库红、预占琥珀（与 roleBadge 同一套观感）。 */
export function signedTone(value: number | null | undefined): string {
  if (value === null || value === undefined || value === 0) return 'text-muted-foreground';
  return value > 0 ? 'text-emerald-700 dark:text-emerald-300' : 'text-rose-700 dark:text-rose-300';
}

/**
 * 期间净变动的展示口径说明。
 *
 * <p>行上的量是「这笔业务整笔的净变动」，而汇总里同时给了分录口径。两个口径不等时
 * （通常是按 SKU/仓库筛过）差异是有解释的：那就是这笔业务在其它 SKU/仓库上的部分。
 * 界面上把这句写出来，用户才不会以为数字算错了。
 */
export function grainNote(filtered: boolean): string {
  return filtered
    ? '行上的数量是**整笔业务**的净变动；已按 SKU / 仓库筛选时它不等于该维度的量，差额见汇总里的分录口径。'
    : '行上的数量是**整笔业务**的净变动，与汇总的分录口径在无维度筛选时恒等。';
}

/**
 * 一行流水的定位说明。
 *
 * <p>蓝图要求「每笔含来源单据」：refType 是来源单据类型、refId 是那张单据的 ID。
 * 单据号在其它服务里，仓库服务只登记逻辑 ID，所以这里展示短 ID 并说明它的来源，
 * 而不是显示一个空白格让人以为没记录。
 */
export function refLabel(row: InventoryLedgerRow): string {
  return `${row.refType} · ${row.refId.slice(0, 8)}`;
}

/**
 * 分录"当前余额"的说明。
 *
 * <p>下钻里显示的 balance 是**现在**多少，不是这笔业务发生时的多少（流水只追加，
 * 要还原当时值得从更早的分录累加）。字段名带 Now，这里再补一句中文防止误读。
 */
export const balanceNowNote =
  '当前余额（不是这笔业务发生时的值：流水只追加，历史值需从更早分录累加）';

export const locationTypeLabels: Record<string, string> = {
  STORAGE: '存储',
  RECEIVING: '收货',
  SHIPPING: '发货',
  QUARANTINE: '待检',
  DEFECTIVE: '残次',
  FROZEN: '冻结',
};

export const lotStatusLabels: Record<string, string> = {
  ACTIVE: '在用',
  DEPLETED: '已耗尽',
  EXPIRED: '已过期',
};

/**
 * 批次的展示标签。
 *
 * 无批次不是"没填"，是一个真实取值（非批次管理品），所以显示成「无批次」而不是空白 ——
 * 空白在表里会被读成"数据缺失"。
 */
export function lotLabel(lotCode: string | null): string {
  return lotCode ?? '无批次';
}

/**
 * 库位 · 批次 · 质量状态：明细行的定位串。
 *
 * 蓝图要求界面上必须能看到"在哪个货位、什么批次、什么质量状态"，这三样合成一列放在
 * SKU 之后，任何一行都能一眼回答"这批货在哪、是什么批次"。
 */
export function bucketPlacement(row: BucketRow): string {
  return [
    row.locationCode,
    lotLabel(row.lotCode),
    roleLabels[row.qualityRole] ?? row.qualityStatusName,
  ].join(' · ');
}

/** 库位不可拣的原因说明：可售量计算（7.1.1.3）要把不可拣库位的量排除在可发货之外。 */
export function pickableNote(row: BucketRow): string {
  if (row.pickable) return '可拣，可发货';
  const reason = locationTypeLabels[row.locationType] ?? row.locationType;
  return `${reason}库位，不可发货`;
}

/**
 * 行内操作入口的可用性说明。
 *
 * 目标模块未交付时后端返回 available=false 与 note：界面上禁用按钮并显示原因，
 * 而不是给一个点下去 404 的入口。
 */
export function actionNote(action: BucketAction): string {
  return action.available ? `${action.label}（目标模块 ${action.ownerModule}）` : action.note;
}

// ------------------------------------------------------------ 展示口径

/**
 * 数量格式化。
 *
 * <p>固定两位小数：库存数量会出现 0.001 这种小数量（散装称重），
 * 但把 4 位精度全铺在表里会让人读不出量级。
 */
export function qty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export const roleLabels: Record<string, string> = {
  SELLABLE: '可售',
  PENDING_INSPECTION: '待检',
  DEFECTIVE: '残次',
};

export const roleBadge: Record<string, string> = {
  SELLABLE: 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
  PENDING_INSPECTION: 'bg-amber-500/10 text-amber-700 dark:text-amber-300',
  DEFECTIVE: 'bg-rose-500/10 text-rose-700 dark:text-rose-300',
};

export const scopeLabels: Record<string, string> = {
  SKU: 'SKU 级',
  CATEGORY: '类目级',
  WAREHOUSE: '仓库级',
  DEFAULT: '默认级',
};

/**
 * 行内"在手"的构成说明。
 *
 * <p>把在途单列而不是并进可用：运营判断能不能发货看的是可用，
 * 判断"货还在不在我手上"看的是在手。合成一个数就看不出调拨在途占了多少。
 */
export function rowComposition(row: OverviewRow): string {
  return `在仓 ${qty(row.onHand)} · 在途 ${qty(row.inTransit)}`;
}

export function groupByDimension(groups: OverviewGroup[], dimension: string): OverviewGroup[] {
  return groups.filter((g) => g.dimension === dimension);
}

/** 分组统计图的横轴标签。质量角色维度用角色中文名而不是行名（同一角色可能多行）。 */
export function groupLabel(group: OverviewGroup): string {
  if (group.dimension === 'QUALITY_ROLE') return roleLabels[group.label] ?? group.label;
  return group.label;
}

/**
 * 错误文案。
 *
 * <p>422（依赖未接入的筛选）要单独说清楚是"这个筛选现在查不了"，
 * 而不是笼统的"请求失败" —— 否则用户会以为自己填错了值，反复改一个改不动的条件。
 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 422) return error.message;
    if (error.status === 403) return '当前角色没有库存总览的查看权限';
    if (error.status === 0) return '正在连接仓库服务，请稍后重试';
    return error.message;
  }
  return '加载失败，请稍后重试';
}

// ================================================================ 7.1.3.1 多仓分布视图

/** 矩阵查询参数：skuId 与 spuId 互斥且必居其一（后端 400）。 */
export interface DistributionParams {
  skuId?: string;
  spuId?: string;
  legalEntityId?: string;
  lookbackDays?: number;
}

/**
 * 取多仓对照矩阵。
 *
 * <p>矩阵一次只查一个 SKU 或一个 SPU：后端不接受「全租户所有 SKU 的全仓分布」，
 * 那既不是矩阵也不是分页，所以这里不做「什么都不传」的兜底请求。
 */
export function fetchDistribution(params: DistributionParams) {
  return api<MultiWarehouseDistribution>(
    `warehouse/inventory/distribution${distributionQuery(params)}`,
  );
}

/**
 * 取 SPU 下拉。
 *
 * <p>只列出本租户登记过 spu_id 的 SPU。没有登记的 SKU 不在这里 —— 那个覆盖缺口由矩阵响应里的
 * unregisteredSpuCount 如实报出，不在下拉里假装「只有这些 SKU」。
 */
export function fetchDistributionSpus() {
  return api<DistributionSpuRef[]>('warehouse/inventory/distribution/spus');
}

/** 记录一条平台仓（FBA）观测。来源与采集时间必填 —— 后端会显式拒绝缺失。 */
export function savePlatformObservation(input: PlatformObservationInput) {
  return api<PlatformObservationSaved>('warehouse/inventory/platform-observations', 'POST', input);
}

function distributionQuery(params: DistributionParams): string {
  const q = new URLSearchParams();
  if (params.skuId) q.set('skuId', params.skuId);
  if (params.spuId) q.set('spuId', params.spuId);
  if (params.legalEntityId) q.set('legalEntityId', params.legalEntityId);
  if (params.lookbackDays !== undefined) q.set('lookbackDays', String(params.lookbackDays));
  const s = q.toString();
  return s ? `?${s}` : '';
}

/** 仓库性质标签。取值受 V55 的 CHECK 约束锁死，不是自由文本。 */
export const warehouseKindLabels: Record<string, string> = {
  LOCAL: '本地自营仓',
  OVERSEAS: '海外自营仓',
  PLATFORM_FBA: '平台仓（FBA）',
  THIRD_PARTY: '三方仓',
};

/** 平台仓的行判定：这类仓的数量来自观测，不是自有账本。 */
export function isPlatformRow(row: DistributionRow): boolean {
  return row.warehouseKind === 'PLATFORM_FBA';
}

/** 周转天数依据标签。两个口径的数不能直接比大小，界面上必须分开显示。 */
export const turnoverBasisLabels: Record<string, string> = {
  OWN_OUTBOUND: '自有发货速度',
  PLATFORM_OBSERVATION_DIFF: '平台观测差分',
  NONE: '不可计算',
};

/**
 * 周转天数的展示。
 *
 * <p>算不出时返回「—」并把原因放在 title 上，而不是显示 0：0 会被读成「瞬间卖完」，
 * 而空值 + 原因才是"没算出来"。返回结构带上 basis，让界面对两个口径做视觉区分。
 */
export function turnoverText(row: DistributionRow): {
  value: string;
  basis: string;
  title: string;
} {
  if (!row.turnoverComputable || row.turnoverDays === null) {
    return { value: '—', basis: turnoverBasisLabels.NONE ?? '不可计算', title: row.turnoverNote };
  }
  return {
    value: `${row.turnoverDays.toLocaleString('zh-CN', { maximumFractionDigits: 1 })} 天`,
    basis: turnoverBasisLabels[row.turnoverBasis] ?? row.turnoverBasis,
    title: row.turnoverNote,
  };
}

/**
 * 观测新鲜度文案。
 *
 * <p>平台数量是某时刻的快照，所以"多久之前采的"和数量本身一样重要。
 * 超过一天就标成"数据较旧"——调拨决策建在一个几小时前的快照上是常见事故。
 */
export function observationFreshness(row: DistributionRow, now = Date.now()): string {
  if (!row.collectedAt) return '—';
  const at = Date.parse(row.collectedAt);
  if (Number.isNaN(at)) return '—';
  const seconds = Math.max(0, Math.round((now - at) / 1000));
  const stamp = new Date(at).toLocaleString('zh-CN', { hour12: false });
  const age =
    seconds < 3600
      ? `${Math.floor(seconds / 60)} 分钟前`
      : seconds < 86400
        ? `${Math.floor(seconds / 3600)} 小时前`
        : `${Math.floor(seconds / 86400)} 天前`;
  return `${stamp}（${age}${seconds > 86400 ? '，数据较旧' : ''}）`;
}

/**
 * 矩阵行的来源标注。
 *
 * <p>自有仓与平台仓的量不能混在一个无差别求和里，界面上也必须让用户一眼看出
 * 「这一行的数字是账，还是某时刻的平台快照」。
 */
export function rowSourceNote(row: DistributionRow): string {
  if (isPlatformRow(row)) {
    return `平台观测值（来源 ${row.source ?? '未标注'}），不是自有账本`;
  }
  return '自有账本量';
}
