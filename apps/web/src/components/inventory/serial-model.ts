import type {
  SerialImportRequest,
  SerialMoveRequest,
  SerialIssue,
  SerialMovementPage,
  SerialOperationPage,
  SerialPage,
  SerialProfile,
  SerialRegisterRequest,
  SerialStats,
  SerialUpdateRequest,
  SerialValidationResult,
  SerialValidateRequest,
  SerialVocabularies,
  WarehouseRef,
  SkuRef,
  LocationRef,
  LotRef,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from './api';

// 枚举码值：springdoc 不为 List<Enum> 单独出 schema，契约里是内联字符串联合。
// 从真实 DTO 上取而不是自己写一份字面量 —— 迁移里加第 6 个状态时，
// 只有这样才会让这里编译失败，而不是悄悄少一个选项。
export type SerialStatusCode = SerialVocabularies['statuses'][number];
export type SerialDirectionCode = SerialVocabularies['directions'][number];
export type SerialMovementKindCode = SerialVocabularies['movementKinds'][number];
export type SerialIssueCode = SerialIssue['code'];

/**
 * 7.3.2.1 序列号前端模型。
 *
 * <p>界面上最容易出错的三处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**「数量」与「序列号条数」在提交前就要能看出对不上**。蓝图说「不允许大概齐」，
 *       而收货现场的人是在提交之前才知道自己少扫了两台。把这条约束做成提交前的一次本地比对，
 *       比等后端回一条错误再让他重扫一遍现实得多。后端仍然会独立校验 —— 前端这道只是提前说。
 *   <li>**扫描枪吐出来的是一段文本，不是一个数组**。整箱扫一次会得到几十行，
 *       有的设备还会带分隔符。解析规则必须写在代码里并被测试覆盖，
 *       不能靠 trim 了事 —— 那样第 2 行开始就会带着换行进库。
 *   <li>**校验错误按号归因**。一次出库上百个号时，「校验不通过」这四个字没有任何用；
 *       必须能指出是**哪个号**、**为什么**，并且把能自动挑出来的号直接列出来。
 * </ul>
 */

// ------------------------------------------------------------ 查询与列表

export interface SerialParams {
  skuId?: string;
  warehouseId?: string;
  locationId?: string;
  lotId?: string;
  status?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

/** 筛选条件的 query 串。未传的维度不出现 —— 出现成空串会被后端当成「按空值筛」。 */
export function serialQuery(params: SerialParams): string {
  const parts: string[] = [];
  const push = (key: string, value: string | undefined) => {
    if (value && value.trim()) parts.push(`${key}=${encodeURIComponent(value.trim())}`);
  };
  push('skuId', params.skuId);
  push('warehouseId', params.warehouseId);
  push('locationId', params.locationId);
  push('lotId', params.lotId);
  push('status', params.status);
  push('keyword', params.keyword);
  push('sort', params.sort);
  if (params.page !== undefined) parts.push(`page=${Math.max(0, params.page)}`);
  if (params.size !== undefined) parts.push(`size=${Math.max(1, params.size)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchSerials(params: SerialParams) {
  return api<SerialPage>(`warehouse/inventory/serials${serialQuery(params)}`);
}

export function fetchSerialStats(params: SerialParams) {
  // 统计不看分页：把 page/size 逐个摘掉而不是塞 undefined
  // （本仓开了 exactOptionalPropertyTypes，显式 undefined 与不传是两回事）。
  const { page: _page, size: _size, ...filters } = params;
  return api<SerialStats>(`warehouse/inventory/serials/stats${serialQuery(filters)}`);
}

export function fetchSerial(id: string) {
  return api<SerialProfile>(`warehouse/inventory/serials/${encodeURIComponent(id)}`);
}

export function fetchSerialVocabularies() {
  return api<SerialVocabularies>('warehouse/inventory/serial-vocabularies');
}

export interface SerialMovementParams {
  serialId?: string;
  operationId?: string;
  skuId?: string;
  kind?: string;
  refType?: string;
  refId?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export function fetchSerialMovements(params: SerialMovementParams) {
  const parts: string[] = [];
  const push = (key: string, value: string | undefined) => {
    if (value && value.trim()) parts.push(`${key}=${encodeURIComponent(value.trim())}`);
  };
  push('serialId', params.serialId);
  push('operationId', params.operationId);
  push('skuId', params.skuId);
  push('kind', params.kind);
  push('refType', params.refType);
  push('refId', params.refId);
  push('keyword', params.keyword);
  if (params.page !== undefined) parts.push(`page=${Math.max(0, params.page)}`);
  if (params.size !== undefined) parts.push(`size=${Math.max(1, params.size)}`);
  return api<SerialMovementPage>(`warehouse/inventory/serial-movements?${parts.join('&')}`);
}

export function fetchSerialOperations(params: {
  direction?: string;
  skuId?: string;
  refType?: string;
  refId?: string;
  page?: number;
  size?: number;
}) {
  const parts: string[] = [];
  const push = (key: string, value: string | undefined) => {
    if (value && value.trim()) parts.push(`${key}=${encodeURIComponent(value.trim())}`);
  };
  push('direction', params.direction);
  push('skuId', params.skuId);
  push('refType', params.refType);
  push('refId', params.refId);
  if (params.page !== undefined) parts.push(`page=${Math.max(0, params.page)}`);
  if (params.size !== undefined) parts.push(`size=${Math.max(1, params.size)}`);
  return api<SerialOperationPage>(`warehouse/inventory/serial-operations?${parts.join('&')}`);
}

// ------------------------------------------------------------ 写入

export function registerSerial(input: SerialRegisterRequest) {
  return api<SerialValidationResult>('warehouse/inventory/serials', 'POST', input);
}

export function importSerials(input: SerialImportRequest) {
  return api<SerialValidationResult>('warehouse/inventory/serials/import', 'POST', input);
}

export function updateSerial(id: string, input: SerialUpdateRequest) {
  return api<SerialProfile>(`warehouse/inventory/serials/${encodeURIComponent(id)}`, 'PUT', input);
}

export function deleteSerial(id: string) {
  return api<{ deleted?: boolean }>(
    `warehouse/inventory/serials/${encodeURIComponent(id)}`,
    'DELETE',
  );
}

export function validateMove(input: SerialValidateRequest) {
  return api<SerialValidationResult>(
    'warehouse/inventory/serials/validate-movement',
    'POST',
    input,
  );
}

export function moveSerials(input: SerialMoveRequest) {
  return api<SerialValidationResult>('warehouse/inventory/serials/movements', 'POST', input);
}

// ------------------------------------------------------------ 扫描输入解析

/**
 * 解析扫描枪 / 批量粘贴的文本 → 序列号数组。
 *
 * <p>三种来源都要认：每行一个（多数扫描枪的默认）、逗号/分号分隔、空白分隔。
 * 只做 trim 是不够的 —— 一箱扫下来第 2 行开始就带着换行进了库。
 *
 * <p>分隔符同时认全角与半角：运营用中文输入法敲出来的顿号/分号（`、`、`；`）
 * 和设备吐出来的半角逗号在真实粘贴文本里混着出现，只认一半就会把
 * `SN-1、SN-2；SN-3` 当成两个号，其中一个还是错的。
 *
 * <p>大小写**不做**归一：序列号是设备铭牌上的字面量，`SN-001` 与 `sn-001`
 * 在不同厂商那里是两回事，归一了就会把两台不同的机器合并成一台。
 *
 * @returns 去重后的序列号（保持首次出现的顺序），空串已被丢弃
 */
export function parseScanInput(raw: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const piece of raw.split(/[\r\n,;\u3001\uFF1B\t]+|\s{2,}/)) {
    const value = piece.trim();
    if (!value || seen.has(value)) continue;
    seen.add(value);
    out.push(value);
  }
  return out;
}

// ------------------------------------------------------------ 提交前的一致性预检

/**
 * 提交前的本地预检：把「数量与序列号条数」这条硬约束在**按下提交之前**就说清。
 *
 * <p>后端一定会独立校验（V58 的 CHECK 与延迟约束触发器各兜一层）。前端这道不重复业务规则，
 * 它只做一件后端做不到的事：在收货现场的人还没离开货架时就告诉他少扫了两台。
 *
 * @returns 一句人话 + 能不能提交。返回 `null` 表示本地这一关过了。
 */
export function localQuantityGuard(
  declaredQuantity: string,
  serials: string[],
): { message: string; blocking: boolean } | null {
  const trimmed = declaredQuantity.trim();
  if (!trimmed) {
    return {
      message: '必须填写数量：序列号是逐件管理的，没有「大概多少台」这种数',
      blocking: true,
    };
  }
  const quantity = Number(trimmed);
  if (!Number.isFinite(quantity)) {
    return { message: `数量不是数字：${trimmed}`, blocking: true };
  }
  if (!Number.isInteger(quantity)) {
    return { message: `序列号是离散件，数量不能带小数：${trimmed}`, blocking: true };
  }
  if (quantity < 0) {
    return { message: '数量不能为负', blocking: true };
  }
  if (quantity !== serials.length) {
    return {
      message: `数量写了 ${quantity}，扫到 ${serials.length} 个序列号：出入库数量必须与序列号条数完全一致`,
      blocking: true,
    };
  }
  if (serials.length === 0) {
    return { message: '一个序列号都没有，请先扫码', blocking: true };
  }
  return null;
}

// ------------------------------------------------------------ 方向与展示口径

/** 方向 → 这次操作会产生哪一类流转。界面上用它说明「这一步会发生什么」。 */
export function movementKindsOf(direction: string): SerialMovementKindCode[] {
  switch (direction) {
    case 'INBOUND':
      return ['INBOUND'];
    case 'OUTBOUND':
      return ['OUTBOUND'];
    case 'RELOCATION':
      return ['RELOCATION'];
    case 'STATUS_CHANGE':
      return ['STATUS_CHANGE'];
    case 'REBIND':
      return ['REBIND'];
    default:
      return [];
  }
}

/**
 * 每个方向允许的目标状态 —— 与后端 {@code resolvePlan} 同一套规则。
 *
 * <p>前端照抄一份是刻意的：不是为了替代后端校验（那永远以服务端为准），
 * 而是为了不让操作员填一个**注定**被拒的组合 —— 报错发生在提交之后没有任何意义。
 * 后端返回的错误仍然是最终裁决。
 */
export function allowedTargetStatuses(direction: string): SerialStatusCode[] {
  switch (direction) {
    case 'INBOUND':
      return ['IN_STOCK', 'REPAIR'];
    case 'OUTBOUND':
      return ['SOLD', 'IN_TRANSIT', 'SCRAPPED'];
    case 'RELOCATION':
      return ['IN_STOCK', 'REPAIR'];
    case 'STATUS_CHANGE':
      return ['IN_STOCK', 'REPAIR'];
    case 'REBIND':
      return [];
    default:
      return [];
  }
}

/** 目标状态是否必填。出库与状态流转必须说清去向，其余可空（空 = 保持原状态）。 */
export function targetStatusRequired(direction: string): boolean {
  return direction === 'OUTBOUND' || direction === 'STATUS_CHANGE';
}

/** 目标库位是否必填。入库与库位转移要落位，其余方向不填。 */
export function targetLocationRequired(direction: string): boolean {
  return direction === 'INBOUND' || direction === 'RELOCATION';
}

export const statusLabels: Record<SerialStatusCode, string> = {
  IN_STOCK: '在库',
  SOLD: '已售',
  IN_TRANSIT: '在途',
  REPAIR: '维修',
  SCRAPPED: '报废',
};

export const statusTones: Record<SerialStatusCode, string> = {
  IN_STOCK: 'bg-primary-soft text-primary',
  SOLD: 'bg-muted text-muted-foreground',
  IN_TRANSIT: 'bg-warning-soft text-warning',
  REPAIR: 'bg-warning-soft text-warning',
  SCRAPPED: 'bg-destructive-soft text-destructive',
};

export const directionLabels: Record<SerialDirectionCode, string> = {
  INBOUND: '入库',
  OUTBOUND: '出库',
  RELOCATION: '库位转移',
  STATUS_CHANGE: '状态流转',
  REBIND: '批次改绑',
};

export const movementKindLabels: Record<SerialMovementKindCode, string> = {
  INBOUND: '入库',
  OUTBOUND: '出库',
  RELOCATION: '库位转移',
  STATUS_CHANGE: '状态流转',
  REBIND: '批次改绑',
};

/** 来源库位 → 目标库位的一行读法。空的那一侧要显式写成「不在任何库位」，不然读的人要猜。 */
export function transitionText(
  fromLocation: string | null,
  toLocation: string | null,
  toStatus: SerialStatusCode,
): string {
  const place = (code: string | null) => (!code || code === '—' ? '不在任何库位' : code);
  return `${place(fromLocation)} → ${place(toLocation)}　${statusLabels[toStatus] ?? toStatus}`;
}

/** 状态流转行上是否真的换了状态 —— 没换就不该显示成一次状态变更。 */
export function isStatusChange(fromStatus: SerialStatusCode, toStatus: SerialStatusCode): boolean {
  return fromStatus !== toStatus;
}

/**
 * 把校验错误按序列号归因，并挑出「可以直接退回重扫」的号。
 *
 * <p>「整单退回」与「挑出个别号单独处理」必须能分开：前者是操作员少扫了两台，
 * 后者是这两个号被别人锁在维修库里了。把它们混在一张红框里，两种情况的正确处置完全不同。
 */
export function groupIssues(issues: SerialIssue[]): {
  global: SerialIssue[];
  bySerial: { serialNumber: string; issues: SerialIssue[] }[];
  duplicates: string[];
} {
  const global: SerialIssue[] = [];
  const byNumber = new Map<string, SerialIssue[]>();
  const duplicates = new Set<string>();
  for (const issue of issues ?? []) {
    if (!issue.serialNumber) {
      global.push(issue);
      continue;
    }
    if (issue.code === 'SERIAL_DUPLICATED') duplicates.add(issue.serialNumber);
    const bucket = byNumber.get(issue.serialNumber);
    if (bucket) bucket.push(issue);
    else byNumber.set(issue.serialNumber, [issue]);
  }
  return {
    global,
    bySerial: [...byNumber.entries()].map(([serialNumber, list]) => ({
      serialNumber,
      issues: list,
    })),
    duplicates: [...duplicates],
  };
}

/**
 * 状态分布守恒校验。
 *
 * <p>五个分档加起来必须等于总数 —— 不相等说明后端聚合口径漂了，
 * 那种情况下页面上**每个单独的数字都不可信**，所以要显式警告而不是照画。
 */
export function statsBalance(stats: SerialStats | null): {
  ok: boolean;
  sum: number;
  message: string;
} {
  if (!stats) return { ok: true, sum: 0, message: '' };
  const sum = stats.inStock + stats.sold + stats.inTransit + stats.repair + stats.scrapped;
  return {
    ok: sum === stats.total && stats.sumMatches,
    sum,
    message:
      sum === stats.total && stats.sumMatches
        ? ''
        : `五档合计 ${sum} 与总数 ${stats.total} 不一致，聚合口径可能已漂移，下方数字仅供参考`,
  };
}

/** 汇总下拉选项，避免每处都写一遍「先请求再映射」。 */
export async function loadSerialLookups() {
  const [warehouses, skus] = await Promise.all([
    api<WarehouseRef[]>('warehouse/warehouses'),
    api<SkuRef[]>('warehouse/skus'),
  ]);
  return { warehouses, skus };
}

export async function loadSerialLocations(warehouseId?: string) {
  return api<LocationRef[]>(
    `warehouse/locations${warehouseId ? `?warehouseId=${encodeURIComponent(warehouseId)}` : ''}`,
  );
}

export async function loadSerialLots(skuId?: string) {
  return api<LotRef[]>(`warehouse/lots${skuId ? `?skuId=${encodeURIComponent(skuId)}` : ''}`);
}

/** 把校验结果里「能直接照抄重扫」的号拼成一段文本：省掉操作员手工抄两百个号。 */
export function retryableScanText(result: SerialValidationResult | null): string {
  if (!result) return '';
  const { bySerial } = groupIssues(result.issues ?? []);
  return bySerial.map((entry) => entry.serialNumber).join('\n');
}
