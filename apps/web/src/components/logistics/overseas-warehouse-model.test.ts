import { describe, expect, it } from 'vitest';
import type { WarehouseConnection, WarehouseSyncRun } from './overseas-warehouse-model';
import {
  connectionStatusLabel,
  freshness,
  needsAttention,
  quantityText,
  warehouseKindLabel,
} from './overseas-warehouse-model';

function conn(over: Partial<WarehouseConnection> = {}): WarehouseConnection {
  return {
    id: 'c1',
    providerId: 'p1',
    providerCode: 'GENERIC_3PL',
    providerName: '通用第三方仓',
    authorizedAccount: 'acct-1',
    displayName: 'US East',
    apiEndpointRef: '',
    status: 'ACTIVE',
    syncPaused: false,
    lastError: '',
    version: 0,
    accountCount: 2,
    ...over,
  };
}

function run(over: Partial<WarehouseSyncRun> = {}): WarehouseSyncRun {
  return {
    id: 'r1',
    connectionId: 'c1',
    accountId: 'a1',
    accountName: 'US East',
    triggerSource: 'MANUAL',
    status: 'SUCCEEDED',
    startedAt: '2026-10-01T10:00:00Z',
    fetchedCount: 2,
    observedCount: 2,
    failedCount: 0,
    errorDetail: '',
    ...over,
  };
}

describe('10.4.2.1 海外仓连接展示派生', () => {
  it('数量文本去掉多余的 0，但小数精度不丢', () => {
    expect(quantityText(12, 'PCS')).toBe('12 PCS');
    expect(quantityText(12.25, 'PCS')).toBe('12.25 PCS');
    // 小数件商品的精度必须看得见，不能被四舍五入成整数
    expect(quantityText(0.000001, 'KG')).toBe('0.000001 KG');
  });

  it('陈旧观测必须显式标记：不知道多久之前的数，等于没有数', () => {
    const now = Date.parse('2026-10-02T10:00:00Z');
    expect(freshness(undefined, now).stale).toBe(true);
    expect(freshness('2026-10-02T09:00:00Z', now)).toEqual({ label: '1 小时前观测', stale: false });
    expect(freshness('2026-10-01T10:00:00Z', now)).toEqual({ label: '1 天前观测', stale: true });
    // 观测时点在未来说明时区或数据有问题，不能当成新鲜
    expect(freshness('2026-10-03T10:00:00Z', now).stale).toBe(true);
  });

  it('连接状态：暂停同步与连接失效是两件事，标签要分开', () => {
    expect(connectionStatusLabel(conn())).toBe('正常');
    expect(connectionStatusLabel(conn({ syncPaused: true }))).toBe('已暂停同步');
    expect(connectionStatusLabel(conn({ status: 'EXPIRED' }))).toBe('授权已过期');
    // 失效优先于暂停：两个都成立时，必须先说失效
    expect(connectionStatusLabel(conn({ status: 'REVOKED', syncPaused: true }))).toBe('已吊销');
  });

  it('失败的同步要能被一眼看见', () => {
    expect(needsAttention(run())).toBe(false);
    expect(needsAttention(run({ status: 'FAILED' }))).toBe(true);
    expect(needsAttention(run({ status: 'PARTIAL' }))).toBe(true);
  });

  it('仓性质标签覆盖后端枚举', () => {
    expect(warehouseKindLabel('OVERSEAS')).toBe('海外仓');
    expect(warehouseKindLabel('PLATFORM_FBA')).toBe('平台仓（FBA）');
    expect(warehouseKindLabel('THIRD_PARTY')).toBe('第三方仓');
  });
});
