import { describe, expect, it } from 'vitest';
import type {
  ExternalAdapterAvailability,
  ExternalSyncRecordRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  ADAPTERS,
  CATEGORIES,
  adaptersForCategory,
  categoryForAdapter,
  directSyncAvailable,
  emptyHint,
  failureClassLabel,
  formatCount,
  formatDateTime,
  healthLabel,
  healthTone,
  providerNoticeText,
  syncStatusLabel,
  syncStatusTone,
  taskTypesForCategory,
  unavailableHint,
  unnotified,
  validateConnection,
  validateExternalRecord,
  validateSyncTask,
} from './external-integration-model';

/**
 * 18.2.3.1「其他对接」前端模型单测。
 *
 * <p>🔴 重点盯四件**最容易被做丢**的事：
 * <ol>
 *   <li>「未接入」**不能**显示成红色失败（那是「我们还没接上」，不是「外部系统挂了」）；</li>
 *   <li>「本地台账成功」**必须**同时显示「未与外部系统核对过」；</li>
 *   <li>适配器与对接类别**不相容**时表单必须挡住（给海外仓配货源同步 = 永远跑不通）；</li>
 *   <li>直连按钮**只在**适配器真的接通时才出现。</li>
 * </ol>
 */

function availability(overrides: Partial<ExternalAdapterAvailability> = {}) {
  return {
    adapterCode: 'ALIBABA_1688',
    adapterLabel: '1688 开放平台',
    category: 'SUPPLY_SOURCE',
    categoryLabel: '1688 / 淘宝货源平台',
    connected: false,
    reason: '本仓目前没有任何真实的外部系统适配器',
    ownerModule: '18.2.3',
    ...overrides,
  } satisfies ExternalAdapterAvailability;
}

function record(overrides: Partial<ExternalSyncRecordRow> = {}): ExternalSyncRecordRow {
  return {
    id: '00000000-0000-4000-8000-000000000000',
    connectionId: '00000000-0000-4000-8000-000000000001',
    connectionName: '1688 主货源',
    taskId: '00000000-0000-4000-8000-000000000002',
    taskName: '货源清单同步',
    runNo: 1,
    status: 'SUCCEEDED',
    statusLabel: '成功',
    source: 'EXTERNAL',
    sourceLabel: '外部侧已完成 · 人工登记',
    recordsTotal: 120,
    recordsSucceeded: 118,
    recordsFailed: 2,
    externalRef: '1688-BATCH-20261031',
    failureReason: '',
    failureClass: '',
    providerNotified: false,
    providerNotice: '外部侧已完成，人工登记进本系统台账；本系统**未**与外部系统核对过',
    startedAt: '2026-10-31T00:00:00Z',
    finishedAt: '2026-10-31T00:05:00Z',
    createdBy: '00000000-0000-4000-8000-000000000003',
    createdAt: '2026-10-31T00:05:00Z',
    ...overrides,
  };
}

describe('18.2.3.1 其他对接 · 健康状态', () => {
  it('未接入是灰的不是红的 —— 我们没接上，不等于外部系统挂了', () => {
    expect(healthTone('NOT_CONNECTED')).toBe('muted');
    expect(healthLabel('NOT_CONNECTED')).toBe('未接入');
  });

  it('健康 / 亚健康 / 不健康三档语气分明', () => {
    expect(healthTone('HEALTHY')).toBe('ok');
    expect(healthTone('DEGRADED')).toBe('warn');
    expect(healthTone('UNHEALTHY')).toBe('danger');
    expect(healthTone('UNKNOWN')).toBe('muted');
    expect(healthLabel('DEGRADED')).toBe('亚健康');
    expect(healthLabel('UNHEALTHY')).toBe('不健康');
    expect(healthLabel('UNKNOWN')).toBe('未验证');
  });
});

describe('18.2.3.1 其他对接 · 同步记录', () => {
  it('超时不显示成失败 —— 结果未知与失败不是一回事', () => {
    expect(syncStatusTone('TIMEOUT')).toBe('warn');
    expect(syncStatusTone('FAILED')).toBe('danger');
    expect(syncStatusTone('SUCCEEDED')).toBe('ok');
    expect(syncStatusTone('NOT_CONNECTED')).toBe('muted');
    expect(syncStatusLabel('NOT_CONNECTED')).toBe('外部通道未接入');
  });

  it('失败分类有中文说法', () => {
    expect(failureClassLabel('REJECTED')).toBe('外部系统明确拒绝');
    expect(failureClassLabel('NOT_CONFIGURED')).toBe('未接入');
    expect(failureClassLabel('')).toBe('');
  });

  it('🔴 本地台账成功必须同时显示「未与外部系统核对过」', () => {
    const row = record();
    expect(unnotified(row)).toBe(true);
    expect(providerNoticeText(row)).toContain('未');
    expect(providerNoticeText(row)).not.toContain('已登记');
    // 真核对过的才说核对过。
    expect(providerNoticeText(record({ providerNotified: true }))).toBe('已与外部系统核对过');
  });
});

describe('18.2.3.1 其他对接 · 类别与适配器', () => {
  it('适配器与类别一一对应（货源平台有两个）', () => {
    expect(categoryForAdapter('ALIBABA_1688')).toBe('SUPPLY_SOURCE');
    expect(categoryForAdapter('TAOBAO')).toBe('SUPPLY_SOURCE');
    expect(categoryForAdapter('OVERSEAS_WAREHOUSE')).toBe('OVERSEAS_WAREHOUSE');
    expect(categoryForAdapter('SELF_HOSTED_BI')).toBe('SELF_HOSTED_BI');
    expect(categoryForAdapter('NOT_A_REAL_ADAPTER')).toBeNull();
  });

  it('每个类别名下都有可用的适配器与同步类型', () => {
    for (const category of CATEGORIES) {
      expect(adaptersForCategory(category.code).length).toBeGreaterThan(0);
      expect(taskTypesForCategory(category.code).length).toBeGreaterThan(0);
    }
    // 六个适配器，一个不多一个不少。
    expect(ADAPTERS).toHaveLength(6);
    expect(new Set(ADAPTERS.map((a) => a.code)).size).toBe(6);
  });

  it('同步类型只属于一个类别 —— 不会给海外仓配出货源同步', () => {
    const warehouseTasks = taskTypesForCategory('OVERSEAS_WAREHOUSE');
    expect(warehouseTasks.map((t) => t.code)).toEqual(['INVENTORY_SYNC']);
    expect(warehouseTasks.map((t) => t.code)).not.toContain('CATALOG_SYNC');
  });
});

describe('18.2.3.1 其他对接 · 直连按钮的可见性', () => {
  it('适配器未接入时不提供「发起同步」按钮', () => {
    expect(directSyncAvailable([availability()], 'ALIBABA_1688')).toBe(false);
    expect(directSyncAvailable([], 'ALIBABA_1688')).toBe(false);
    expect(directSyncAvailable(undefined, 'ALIBABA_1688')).toBe(false);
  });

  it('适配器真的接通时才提供按钮', () => {
    expect(directSyncAvailable([availability({ connected: true })], 'ALIBABA_1688')).toBe(true);
  });

  it('没接通时逐个写清缺什么、归谁', () => {
    const hint = unavailableHint([
      availability(),
      availability({ adapterCode: 'TAOBAO', adapterLabel: '淘宝开放平台' }),
    ]);
    expect(hint).toContain('1688 开放平台');
    expect(hint).toContain('淘宝开放平台');
    expect(unavailableHint([availability({ connected: true })])).toBe('');
  });
});

describe('18.2.3.1 其他对接 · 表单校验', () => {
  const base = {
    category: 'SUPPLY_SOURCE',
    adapterCode: 'ALIBABA_1688',
    name: '1688 主货源',
    baseUrl: 'https://example.invalid/api',
    credentialRef: 'secret://external/1688/main',
    credentialHint: '9f2a',
    channelPlatformCode: '',
    channelShopId: '',
    status: 'ACTIVE',
    timeoutSeconds: '15',
    maxAttempts: '3',
    remark: '',
  };

  it('类别与适配器不相容时挡住', () => {
    expect(validateConnection({ ...base, category: 'OVERSEAS_WAREHOUSE' })).toContain('不相容');
    expect(validateConnection(base)).toBeNull();
  });

  it('密钥引用与末位提示必须成对', () => {
    expect(validateConnection({ ...base, credentialHint: '' })).toContain('同时填');
    expect(validateConnection({ ...base, credentialRef: '' })).toContain('同时填');
    expect(validateConnection({ ...base, credentialRef: '', credentialHint: '' })).toBeNull();
  });

  it('接口地址必须是 http/https 形状', () => {
    expect(validateConnection({ ...base, baseUrl: 'ftp://x' })).toContain('http');
    expect(validateConnection({ ...base, baseUrl: '' })).toBeNull();
  });

  it('超时秒数与重试次数有界', () => {
    expect(validateConnection({ ...base, timeoutSeconds: '0' })).toContain('超时秒数');
    expect(validateConnection({ ...base, maxAttempts: '99' })).toContain('重试次数');
  });

  it('同步任务：选了固定间隔就必须给间隔，选了 cron 就必须给表达式', () => {
    const task = {
      connectionId: 'c-1',
      taskType: 'CATALOG_SYNC',
      direction: 'PULL',
      name: '货源清单同步',
      resourceRef: 'SHOP-1',
      scheduleKind: 'MANUAL',
      scheduleExpression: '',
      intervalSeconds: '',
      enabled: true,
      remark: '',
    };
    expect(validateSyncTask(task)).toBeNull();
    expect(validateSyncTask({ ...task, scheduleKind: 'INTERVAL' })).toContain('间隔秒数');
    expect(validateSyncTask({ ...task, scheduleKind: 'CRON' })).toContain('cron');
  });

  it('人工登记：条数不能超过处理条数，失败必须写原因', () => {
    const form = {
      taskId: 't-1',
      status: 'SUCCEEDED',
      recordsTotal: '10',
      recordsSucceeded: '8',
      recordsFailed: '2',
      externalRef: 'BATCH-1',
      failureReason: '',
      failureClass: '',
      startedAt: '',
      finishedAt: '',
    };
    expect(validateExternalRecord(form)).toBeNull();
    expect(validateExternalRecord({ ...form, recordsSucceeded: '11' })).toContain('成功条数');
    expect(validateExternalRecord({ ...form, recordsFailed: '11' })).toContain('失败条数');
    expect(validateExternalRecord({ ...form, status: 'FAILED', failureReason: '' })).toContain(
      '失败原因',
    );
  });

  it('人工登记：结束时间不能早于开始时间', () => {
    expect(
      validateExternalRecord({
        taskId: 't-1',
        status: 'SUCCEEDED',
        recordsTotal: '1',
        recordsSucceeded: '1',
        recordsFailed: '0',
        externalRef: '',
        failureReason: '',
        failureClass: '',
        startedAt: '2026-10-31T00:05:00Z',
        finishedAt: '2026-10-31T00:00:00Z',
      }),
    ).toContain('结束时间');
  });
});

describe('18.2.3.1 其他对接 · 展示', () => {
  it('条数与时间的空值显示成「—」', () => {
    expect(formatCount(null)).toBe('—');
    expect(formatCount(120)).toBe('120');
    expect(formatDateTime(null)).toBe('—');
    expect(formatDateTime('not-a-date')).toBe('—');
    expect(formatDateTime('2026-10-31T00:00:00Z')).not.toBe('—');
  });

  it('空态区分「还没登记过」与「筛选无结果」', () => {
    expect(emptyHint('connections', false)).toContain('还没有登记');
    expect(emptyHint('connections', true)).toContain('筛选');
    expect(emptyHint('tasks', false)).toContain('还没有登记');
    expect(emptyHint('records', false)).toContain('还没有任何同步记录');
  });
});
