import { describe, expect, it } from 'vitest';
import {
  availableDomains,
  directionLabel,
  downloadable,
  errorCodeLabel,
  errorCodeText,
  errorReportUrl,
  expiryText,
  progressPercent,
  resultFileUrl,
  severityTone,
  taskStatusLabel,
  taskStatusTone,
  templateFileUrl,
  unavailableCapabilities,
  unavailableDomains,
} from './io-task-model';
import type {
  ImportExportCapabilities,
  ImportExportDomainRow,
  ImportExportTaskRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.1.3.1「导入导出任务」前端模型的单元测试。
 *
 * 🔴 这里钉住的是**页面上最容易做丢的判断**：
 * 「已受理 ≠ 已完成」「没接通要显式摆出来」「过期了不能再下载」
 * 「失败行要有原因码」「读不到 ≠ 没有」。
 * 这些判断一旦做丢，界面会在**全绿**的情况下骗人。
 */

function task(overrides: Partial<ImportExportTaskRow> = {}): ImportExportTaskRow {
  return {
    id: '00000000-0000-4000-8000-000000000000',
    taskNo: 'IO-00000000-00000001',
    direction: 'EXPORT',
    domainCode: 'PLATFORM_APP',
    domainLabel: '平台应用与凭证',
    format: 'CSV',
    status: 'SUCCEEDED',
    scope: {},
    totalRows: 10,
    processedRows: 10,
    succeededRows: 10,
    failedRows: 0,
    resultFileId: 'file-1',
    errorFileId: '',
    upstreamService: '',
    upstreamJobId: '',
    upstreamJobRef: '',
    error: '',
    actorId: 'user-1',
    requestedAt: '2026-10-31T08:00:00Z',
    startedAt: '2026-10-31T08:00:01Z',
    finishedAt: '2026-10-31T08:00:02Z',
    expiresAt: '2026-11-01T08:00:02Z',
    createdAt: '2026-10-31T08:00:00Z',
    ...overrides,
  };
}

function domain(overrides: Partial<ImportExportDomainRow> = {}): ImportExportDomainRow {
  return {
    code: 'PLATFORM_APP',
    label: '平台应用与凭证',
    direction: 'EXPORT',
    available: true,
    source: 'platform 自有表',
    ownerService: 'platform',
    ownerModule: '18.1.1.1',
    columns: [],
    reason: '',
    limitation: '',
    ...overrides,
  };
}

describe('任务状态口径', () => {
  it('每个状态都有中文口径', () => {
    expect(taskStatusLabel('PENDING')).toBe('待执行');
    expect(taskStatusLabel('RUNNING')).toBe('执行中');
    expect(taskStatusLabel('SUCCEEDED')).toBe('已完成');
    expect(taskStatusLabel('FAILED')).toBe('已失败');
    expect(taskStatusLabel('CANCELLED')).toBe('已作废');
    expect(taskStatusLabel('EXPIRED')).toBe('已过期');
    expect(taskStatusLabel('WHAT')).toBe('WHAT');
    expect(taskStatusLabel('')).toBe('—');
  });

  it('已过期是中性而不是错误 —— 文件到期是正常完成的', () => {
    expect(taskStatusTone('SUCCEEDED')).toBe('ok');
    expect(taskStatusTone('RUNNING')).toBe('warn');
    expect(taskStatusTone('FAILED')).toBe('danger');
    expect(taskStatusTone('EXPIRED')).toBe('muted');
    expect(taskStatusTone('CANCELLED')).toBe('muted');
  });

  it('方向口径', () => {
    expect(directionLabel('EXPORT')).toBe('导出');
    expect(directionLabel('IMPORT')).toBe('导入');
    expect(directionLabel('')).toBe('—');
  });

  it('失败级别：ERROR 是错误，WARNING 是警告', () => {
    expect(severityTone('ERROR')).toBe('danger');
    expect(severityTone('WARNING')).toBe('warn');
  });
});

describe('进度', () => {
  it('没有总行数时不编一个百分比出来', () => {
    expect(progressPercent(task({ totalRows: 0, processedRows: 0 }))).toBeNull();
    expect(progressPercent(null)).toBeNull();
  });

  it('已完成的���务是 100%', () => {
    expect(progressPercent(task())).toBe(100);
  });

  it('按已处理行数算，且不超过 100', () => {
    expect(progressPercent(task({ status: 'RUNNING', processedRows: 3, totalRows: 12 }))).toBe(25);
    expect(progressPercent(task({ status: 'RUNNING', processedRows: 30, totalRows: 12 }))).toBe(
      100,
    );
  });
});

describe('下载有效期', () => {
  it('已成功且未过期 ⇒ 可下载', () => {
    const future = new Date(Date.now() + 3_600_000).toISOString();
    expect(downloadable(task({ expiresAt: future }))).toBe(true);
  });

  it('过期 ⇒ 不可下载', () => {
    const past = new Date(Date.now() - 3_600_000).toISOString();
    expect(downloadable(task({ expiresAt: past }))).toBe(false);
  });

  it('没成功 ⇒ 不可下载（还没生成文件）', () => {
    const future = new Date(Date.now() + 3_600_000).toISOString();
    expect(downloadable(task({ status: 'RUNNING', expiresAt: future }))).toBe(false);
    expect(downloadable(task({ status: 'FAILED', expiresAt: future }))).toBe(false);
  });

  it('有效期文案区分已过期 / 不到一小时 / 若干小时 / 若干天', () => {
    const hours = (n: number) => new Date(Date.now() + n * 3_600_000).toISOString();
    expect(expiryText(task({ expiresAt: hours(-1) }))).toBe('已过下载有效期');
    expect(expiryText(task({ expiresAt: hours(0.5) }))).toBe('不到 1 小时后过期');
    // 🔴 取整边界：实现的剩余毫秒是「构造 expiresAt 的那次 Date.now()」减
    // 「expiryText 内部的 Date.now()」。两次取时之间只要跨过一个毫秒，
    // Math.floor(剩余/1 小时) 就会掉一档（5 小时变 4 小时、72 小时变 71 小时
    // 进而 3 天变 2 天）—— 这是**毫秒边界竞态**，与被测逻辑无关。
    // ⇒ 取带半小时余量的值，让断言离取整边界有 30 分钟的余量。
    expect(expiryText(task({ expiresAt: hours(5.5) }))).toBe('还剩 5 小时过期');
    expect(expiryText(task({ expiresAt: hours(72.5) }))).toBe('还剩 3 天过期');
    expect(expiryText(task({ expiresAt: '' }))).toBe('');
  });
});

describe('数据域目录', () => {
  const domains = [
    domain({ code: 'PLATFORM_APP', direction: 'EXPORT', available: true }),
    domain({
      code: 'PLATFORM_APP',
      direction: 'IMPORT',
      available: false,
      reason: '应用档案导入未接通',
    }),
    domain({
      code: 'CUSTOMER',
      label: '客户与联系人',
      direction: 'EXPORT',
      available: false,
      reason: 'crm 没有面向平台的客户档案列表授权端点',
      ownerModule: '15.1',
    }),
    domain({ code: 'PRODUCT', label: '商品与 SKU', direction: 'EXPORT', available: true }),
  ];

  it('可用域只列该方向上真的能用的', () => {
    expect(availableDomains(domains, 'EXPORT').map((d) => d.code)).toEqual([
      'PLATFORM_APP',
      'PRODUCT',
    ]);
    expect(availableDomains(domains, 'IMPORT')).toHaveLength(0);
  });

  it('不可用域照样出现在清单里（不能从清单里悄悄删掉）', () => {
    const unavailable = unavailableDomains(domains, 'EXPORT');
    expect(unavailable.map((d) => d.code)).toEqual(['CUSTOMER']);
    expect(unavailable[0]?.reason).toContain('crm');
    expect(unavailableDomains(domains, 'IMPORT')[0]?.reason).toBe('应用档案导入未接通');
  });

  it('空目录不炸', () => {
    expect(availableDomains(null, 'EXPORT')).toEqual([]);
    expect(unavailableDomains(undefined, 'IMPORT')).toEqual([]);
  });
});

describe('能力声明', () => {
  const capabilities: ImportExportCapabilities = {
    capabilities: [
      {
        module: '18.1.3.1',
        name: '任务创建',
        available: true,
        basis: 'platform_io_task 表',
        ownerModule: '',
        limitation: '',
      },
      {
        module: '18.1.3.1',
        name: '导入：订单 / 库存 / 凭证',
        available: false,
        basis: '属主服务没有面向通道的文件型导入作业端点',
        ownerModule: '9.1',
        limitation: '',
      },
    ],
    gaps: [],
  };

  it('不可用的那几格被单独挑出来', () => {
    expect(unavailableCapabilities(capabilities).map((c) => c.name)).toEqual([
      '导入：订单 / 库存 / 凭证',
    ]);
    expect(unavailableCapabilities(null)).toEqual([]);
  });
});

describe('失败原因码', () => {
  it('已知原因码有中文口径', () => {
    expect(errorCodeLabel('APP_NOT_FOUND')).toBe('应用不存在');
    expect(errorCodeLabel('INVALID_TARGET_URL')).toBe('端点地址格式不对');
    expect(errorCodeLabel('EVENT_TYPE_UNKNOWN')).toBe('事件类型未登记');
    expect(errorCodeLabel('')).toBe('—');
  });

  it('未知原因码原样返回（不编一个假口径）', () => {
    expect(errorCodeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(errorCodeText('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(errorCodeText('APP_NOT_FOUND')).toBe('应用不存在');
  });
});

describe('下载链接', () => {
  it('走同源代理路径（代理用会话 cookie 补 Authorization 头）', () => {
    const id = '00000000-0000-4000-8000-000000000000';
    expect(resultFileUrl(id)).toBe(`/api/platform/io/tasks/${id}/file`);
    expect(errorReportUrl(id)).toBe(`/api/platform/io/tasks/${id}/error-report`);
    expect(templateFileUrl(id)).toBe(`/api/platform/io/templates/${id}/file`);
  });
});
