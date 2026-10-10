import { api } from '../inventory/api';

/**
 * 10.4.2.1 海外仓连接前端模型。
 *
 * <p>蓝图关系段加粗句：**外部仓库存是观测值不是自有账本，必须与自营库存分开存储与对账，不能覆盖。**
 *
 * <p>这个约束在界面上不是一句提示语，而是一条**禁止出现的操作**：本模块
 * **不提供**任何编辑/删除观测值的入口。用户能做的只有「看」——
 * 看连接、看同步记录、看观测流水。没有「修改库存」按钮，是这条约束在 UI 上的直接体现；
 * 真加一个编辑框，就等于在产品层面承认观测值可以被改写，蓝图那条铁律当场失效。
 *
 * <p>所以这里的动作函数里只有两类：触发同步、登记服务商报送。
 * 观测值只有读出口。
 */

export interface WarehouseProvider {
  id: string;
  code: string;
  name: string;
  kind: string;
  supportsInventorySync: boolean;
  supportsForecast: boolean;
  supportsStatusCallback: boolean;
  supportsBilling: boolean;
}

export interface CredentialReference {
  provider: string;
  externalRef: string;
  tokenType: string;
  status: string;
  expiresAt?: string;
}

export interface WarehouseConnection {
  id: string;
  providerId: string;
  providerCode: string;
  providerName: string;
  authorizedAccount: string;
  displayName: string;
  apiEndpointRef: string;
  status: string;
  syncPaused: boolean;
  lastSyncAt?: string;
  lastError: string;
  version: number;
  credential?: CredentialReference;
  accountCount: number;
}

export interface WarehouseAccount {
  id: string;
  connectionId: string;
  connectionName: string;
  providerCode: string;
  externalAccountCode: string;
  name: string;
  countryCode: string;
  timezone: string;
  warehouseKind: string;
  status: string;
  version: number;
  lastSyncAt?: string;
  latestObservedAt?: string;
  observedSkuCount?: number;
}

export interface WarehouseSyncRun {
  id: string;
  connectionId: string;
  accountId?: string;
  accountName?: string;
  triggerSource: string;
  status: string;
  startedAt: string;
  finishedAt?: string;
  fetchedCount: number;
  observedCount: number;
  failedCount: number;
  errorDetail: string;
}

/**
 * 外部库存观测快照。
 *
 * <p>刻意**没有** available/onHand/committed 这类「当前余额」字段：
 * 观测是带时点的事实，余额是派生量。给了余额字段界面就会长出编辑框，
 * 而编辑框就是「可以覆盖」的第一颗螺丝。
 */
export interface ExternalStockObservation {
  id: string;
  accountId: string;
  accountName: string;
  externalSku: string;
  quantity: number;
  unit: string;
  source: string;
  observedAt: string;
}

export interface ExternalStockLatest {
  accountId: string;
  accountName: string;
  externalSku: string;
  quantity: number;
  unit: string;
  source: string;
  observedAt: string;
}

export interface InboundStockLineInput {
  externalSku: string;
  quantity: number;
  unit?: string;
  source: string;
  reportedAt: string;
}

// ---------------------------------------------------------------------------
// 读取出口
// ---------------------------------------------------------------------------

export function fetchProviders(): Promise<WarehouseProvider[]> {
  return api<WarehouseProvider[]>('channel/warehouse-providers');
}

export function fetchConnections(): Promise<WarehouseConnection[]> {
  return api<WarehouseConnection[]>('channel/warehouse-providers/connections');
}

export function fetchAccounts(connectionId?: string): Promise<WarehouseAccount[]> {
  const query = connectionId ? `?connectionId=${encodeURIComponent(connectionId)}` : '';
  return api<WarehouseAccount[]>(`channel/warehouse-providers/accounts${query}`);
}

export function fetchSyncRuns(connectionId?: string): Promise<WarehouseSyncRun[]> {
  const query = connectionId ? `?connectionId=${encodeURIComponent(connectionId)}` : '';
  return api<WarehouseSyncRun[]>(`channel/warehouse-providers/sync-runs${query}`);
}

export function fetchObservations(accountId: string): Promise<ExternalStockObservation[]> {
  return api<ExternalStockObservation[]>(
    `channel/warehouse-providers/accounts/${accountId}/observations`,
  );
}

export function fetchLatestObservations(accountId: string): Promise<ExternalStockLatest[]> {
  return api<ExternalStockLatest[]>(
    `channel/warehouse-providers/accounts/${accountId}/observations/latest`,
  );
}

// ---------------------------------------------------------------------------
// 写出口：只有「触发同步」与「登记报送」，没有「改观测值」
// ---------------------------------------------------------------------------

export function triggerSync(accountId: string): Promise<string> {
  return api<string>(`channel/warehouse-providers/accounts/${accountId}/sync`, 'POST');
}

export function recordInboundLine(
  accountId: string,
  input: InboundStockLineInput,
): Promise<string> {
  return api<string>(`channel/warehouse-providers/accounts/${accountId}/inbound-stock-lines`, 'POST', input);
}

export function setConnectionSyncPaused(connectionId: string, paused: boolean): Promise<void> {
  return api<void>(
    `channel/warehouse-providers/connections/${connectionId}/sync-paused?paused=${paused}`,
    'POST',
  );
}

// ---------------------------------------------------------------------------
// 展示派生
// ---------------------------------------------------------------------------

/** 观测新鲜度。观测陈旧而不知情，比没有观测更危险，所以这里显式给出时点。 */
export function freshness(observedAt?: string, now: number = Date.now()): {
  label: string;
  stale: boolean;
} {
  if (!observedAt) return { label: '尚无观测', stale: true };
  const hours = (now - new Date(observedAt).getTime()) / 3_600_000;
  if (hours < 0) return { label: '观测时点异常', stale: true };
  if (hours < 24) return { label: `${Math.floor(hours)} 小时前观测`, stale: hours > 12 };
  return { label: `${Math.floor(hours / 24)} 天前观测`, stale: true };
}

export function quantityText(quantity: number, unit: string): string {
  // 数量按 6 位小数对齐后去掉多余的 0：观测值要能读，但不该把 12 写成 12.000000。
  const text = quantity.toFixed(6).replace(/\.?0+$/, '');
  return `${text} ${unit}`;
}

/** 仓性质中文名，与后端 warehouse_kind 枚举对齐。 */
export function warehouseKindLabel(kind: string): string {
  switch (kind) {
    case 'OVERSEAS':
      return '海外仓';
    case 'PLATFORM_FBA':
      return '平台仓（FBA）';
    case 'THIRD_PARTY':
      return '第三方仓';
    default:
      return kind;
  }
}

export function connectionStatusLabel(connection: WarehouseConnection): string {
  if (connection.status === 'ACTIVE' && connection.syncPaused) return '已暂停同步';
  switch (connection.status) {
    case 'ACTIVE':
      return '正常';
    case 'EXPIRING':
      return '授权即将过期';
    case 'EXPIRED':
      return '授权已过期';
    case 'REVOKED':
      return '已吊销';
    case 'PAUSED':
      return '已停用';
    default:
      return connection.status;
  }
}

/** 同步是否处于需要注意的状态（失败或部分失败要能一眼看见）。 */
export function needsAttention(run: WarehouseSyncRun): boolean {
  return run.status === 'FAILED' || run.status === 'PARTIAL';
}