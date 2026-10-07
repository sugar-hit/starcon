import { describe, expect, it } from 'vitest';
import type { ExportJobRow, ExportSourceInfo } from './export-model';
import {
  PII_HINT,
  QUOTE_BOUNDARY_HINT,
  TTL_HINT,
  availableFilters,
  byteText,
  conditionText,
  defaultFields,
  errorText,
  isRunning,
  jobFileUrl,
  localTime,
  requestProblem,
  rowCountText,
  sortOptions,
  statusLabel,
  statusTone,
  unavailableFilters,
} from './export-model';

const skuSource: ExportSourceInfo = {
  key: 'SKU',
  label: 'SKU 列表',
  upstreamModule: '3.1.2.1 SKU 列表',
  permission: 'catalog.sku.read',
  available: true,
  note: '',
  fields: [
    { key: 'CODE', label: 'SKU 编码', pii: false, note: '' },
    { key: 'NAME', label: 'SKU 名称', pii: false, note: '' },
    { key: 'BARCODE', label: '条码', pii: false, note: '' },
  ],
  sorts: [
    { key: 'updated_at', label: '更新时间' },
    { key: 'code', label: 'SKU 编码' },
  ],
  filters: [
    { key: 'q', label: '关键字', kind: 'TEXT', available: true, note: '' },
    { key: 'category', label: '分类', kind: 'TEXT', available: true, note: '' },
    {
      key: 'onHandMin',
      label: '在仓数量下限',
      kind: 'DECIMAL',
      available: false,
      note: '上游投影未接入：库存数量归 07 库存所有（阶段 D3），catalog 无权威数据',
    },
  ],
  defaultSortKey: 'updated_at',
  maxRows: 50000,
  downloadTtlHours: 24,
};

const contactSource: ExportSourceInfo = {
  ...skuSource,
  key: 'CONTACT',
  label: '往来单位联系人',
  fields: [
    { key: 'PARTY_CODE', label: '往来单位编码', pii: false, note: '' },
    { key: 'NAME', label: '联系人姓名', pii: false, note: '' },
    { key: 'EMAIL', label: '邮箱', pii: true, note: '敏感字段：导出需显式授权并填写用途' },
  ],
};

function row(patch: Partial<ExportJobRow>): ExportJobRow {
  return {
    id: '01234567-89ab-cdef-0123-456789abcdef',
    sourceKey: 'SKU',
    sourceLabel: 'SKU 列表',
    status: 'SUCCEEDED',
    statusNote: '已完成',
    fields: ['CODE'],
    headers: ['SKU 编码'],
    appliedFilters: [],
    sortKey: 'updated_at',
    sortOrder: 'desc',
    rowCount: 2,
    fileName: 'catalog-sku-20260101-abcd1234.csv',
    byteSize: 512,
    contentSha256: 'a'.repeat(64),
    includesPii: false,
    piiFields: [],
    piiPurpose: '',
    actorId: '11111111-2222-3333-4444-555555555555',
    departmentId: null,
    requestedAt: '2026-01-01T00:00:00Z',
    startedAt: '2026-01-01T00:00:01Z',
    finishedAt: '2026-01-01T00:00:02Z',
    expiresAt: '2026-01-02T00:00:02Z',
    downloadable: true,
    downloadNote: '在有效期内可下载',
    error: '',
    ...patch,
  };
}

describe('状态与行数显示', () => {
  it('运行中的任务不显示 0 行，而是显示生成中', () => {
    expect(rowCountText(row({ status: 'RUNNING', rowCount: 0 }))).toBe('生成中…');
    expect(rowCountText(row({ status: 'PENDING' }))).toBe('生成中…');
  });

  it('0 行是合法结果，不能与失败混淆', () => {
    expect(rowCountText(row({ rowCount: 0 }))).toBe('0 行');
    expect(statusLabel('FAILED')).toBe('失败');
    expect(statusTone('FAILED')).toBe('danger');
    // 语气必须落在 Badge 真实支持的 tone 联合里（返回类型已收窄）。
    expect(isRunning(row({ status: 'FAILED' }))).toBe(false);
  });

  it('状态标签与语气覆盖全部状态', () => {
    expect(statusLabel('PENDING')).toBe('排队中');
    expect(statusLabel('CANCELLED')).toBe('已作废');
    expect(statusLabel('EXPIRED')).toBe('已过期');
    expect(statusTone('EXPIRED')).toBe('neutral');
    expect(statusTone('CANCELLED')).toBe('neutral');
    expect(statusTone('SUCCEEDED')).toBe('success');
    expect(statusTone('RUNNING')).toBe('warning');
  });
});

describe('提交前本地校验', () => {
  it('一个字段都没选时拦下', () => {
    expect(requestProblem([], '', [skuSource], 'SKU')).toContain('字段');
  });

  it('没选数据源时拦下', () => {
    expect(requestProblem(['CODE'], '', [skuSource], null)).toContain('数据源');
  });

  it('勾了敏感字段却没写用途时拦下，并点名是哪些字段', () => {
    expect(requestProblem(['PARTY_CODE', 'EMAIL'], '', [contactSource], 'CONTACT')).toContain('邮箱');
    expect(requestProblem(['EMAIL'], '   ', [contactSource], 'CONTACT')).toContain('导出用途');
  });

  it('写了用途就放行；不含敏感字段时不需要理由', () => {
    expect(requestProblem(['PARTY_CODE', 'EMAIL'], '客服工单', [contactSource], 'CONTACT')).toBeNull();
    expect(requestProblem(['CODE'], '', [skuSource], 'SKU')).toBeNull();
  });

  it('未交付的数据源即使勾了字段也不放行', () => {
    const pending = { ...skuSource, available: false, note: '上游列表模块尚未交付' };
    expect(requestProblem(['CODE'], '', [pending], 'SKU')).toBe('上游列表模块尚未交付');
  });
});

describe('字段与筛选词表', () => {
  it('默认字段不带 PII，避免一进页面就触发授权流程', () => {
    expect(defaultFields(contactSource)).toEqual(['PARTY_CODE', 'NAME']);
    expect(defaultFields(contactSource)).not.toContain('EMAIL');
    expect(defaultFields(null)).toEqual([]);
  });

  it('可用与不可用筛选条件分开，后者保留归属说明', () => {
    expect(availableFilters(skuSource).map((f) => f.key)).toEqual(['q', 'category']);
    const blocked = unavailableFilters(skuSource);
    expect(blocked.map((f) => f.key)).toEqual(['onHandMin']);
    expect(blocked.at(0)?.note ?? '').toContain('07 库存');
    expect(sortOptions(skuSource)).toHaveLength(2);
    expect(sortOptions(null)).toEqual([]);
  });
});

describe('审计回显', () => {
  it('无条件时说明是导出全部可见数据，而不是空白', () => {
    expect(conditionText([])).toContain('全部数据');
  });

  it('有条件时用标签而不是键名回显', () => {
    expect(
      conditionText([{ key: 'spuId', label: '所属 SPU', value: 'abc' }]),
    ).toBe('所属 SPU=abc');
  });
});

describe('杂项', () => {
  it('字节数按量级显示', () => {
    expect(byteText(512)).toBe('512 B');
    expect(byteText(2048)).toBe('2.0 KB');
    expect(byteText(5 * 1024 * 1024)).toBe('5.0 MB');
    expect(byteText(null)).toBe('');
  });

  it('时间为空时显示占位符而不是 Invalid Date', () => {
    expect(localTime(null)).toBe('—');
    expect(localTime('2026-01-01T00:00:00Z')).not.toContain('Invalid');
  });

  it('下载地址走 /api 代理，不直接指向后端', () => {
    expect(jobFileUrl('abc')).toBe('/api/catalog/catalog/export-jobs/abc/file');
  });

  it('错误统一收成一句话', () => {
    expect(errorText(new Error('boom'))).toBe('boom');
    expect(errorText('x')).toBe('操作失败，请重试');
  });

  it('边界提示把「尚未交付」与「需授权」讲清楚', () => {
    expect(QUOTE_BOUNDARY_HINT).toContain('3.6.2.1');
    expect(PII_HINT).toContain('用途');
    expect(TTL_HINT).toContain('有效期');
  });
});
