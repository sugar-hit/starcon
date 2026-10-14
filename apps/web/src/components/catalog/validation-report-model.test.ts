import { describe, expect, it } from 'vitest';
import type {
  ImportValidationIssueLine,
  ImportValidationReportRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  CATEGORIES,
  SEVERITY_FILTERS,
  blockingNotice,
  categoryIssueCount,
  distinctFieldCount,
  exportFileUrl,
  filterLabel,
  groupIssuesByRow,
  importablePercent,
  kindLabel,
  problemColumns,
  reportJobId,
  severityTone,
} from './validation-report-model';

function issue(over: Partial<ImportValidationIssueLine> = {}): ImportValidationIssueLine {
  return {
    id: 'i1',
    reportId: 'r1',
    rowNo: 3,
    fieldKey: 'spu_code',
    fieldLabel: '商品编码',
    code: 'REQUIRED',
    category: 'REQUIRED_MISSING',
    categoryLabel: '必填缺失',
    severity: 'ERROR',
    message: '「spu_code」是必填列，这一行没有值',
    rawValue: '',
    rowOutcome: 'INVALID',
    rowOutcomeLabel: '校验不通过',
    rowBlocking: true,
    ...over,
  };
}

function report(over: Partial<ImportValidationReportRow> = {}): ImportValidationReportRow {
  return {
    id: 'r1',
    jobId: 'j1',
    sourcePrevalidatedAt: '2026-10-07T02:00:00Z',
    sourceStatus: 'PREVALIDATED',
    sourceStatusLabel: '预校验完成',
    totalRowCount: 4,
    blockingRowCount: 2,
    skippableRowCount: 1,
    cleanRowCount: 1,
    issueRowCount: 3,
    errorCount: 3,
    warningCount: 1,
    stale: false,
    generatedAt: '2026-10-07T02:05:00Z',
    actorId: 'u1',
    ...over,
  };
}

describe('校验报告模型', () => {
  it('按行分组并把行级阻断算成任一 ERROR', () => {
    const groups = groupIssuesByRow([
      issue({ rowNo: 3 }),
      issue({ rowNo: 5, code: 'INVALID_ENUM', category: 'FORMAT_INVALID', severity: 'ERROR' }),
      issue({ rowNo: 4, code: 'EXISTS_IN_CREATE_MODE', severity: 'WARNING', rowBlocking: false }),
    ]);
    expect(groups.map((g) => g.rowNo)).toEqual([3, 4, 5]);
    // 可跳过的那一行不算阻断
    expect(groups.find((g) => g.rowNo === 4)?.blocking).toBe(false);
    expect(groups.find((g) => g.rowNo === 3)?.blocking).toBe(true);
  });

  it('同一行的「要改几处」按字段去重，整行级问题单独算一处', () => {
    expect(
      distinctFieldCount([
        issue({ fieldKey: 'spu_code', code: 'REQUIRED' }),
        issue({ fieldKey: 'spu_code', code: 'MATCH_KEY_REQUIRED' }),
        issue({ fieldKey: 'spu_name', code: 'TOO_LONG' }),
      ]),
    ).toBe(2);
    // 编码重复这类整行级问题（fieldKey 空串）也算一处
    expect(
      distinctFieldCount([
        issue({ fieldKey: '', fieldLabel: '', code: 'DUPLICATE_IN_FILE' }),
        issue({ fieldKey: 'spu_name', code: 'TOO_LONG' }),
      ]),
    ).toBe(2);
  });

  it('可落库百分比按行算而不是按条', () => {
    expect(importablePercent(report())).toBe(50);
    expect(importablePercent(report({ totalRowCount: 0, blockingRowCount: 0 }))).toBe(0);
    expect(
      importablePercent(report({ totalRowCount: 3, blockingRowCount: 1, skippableRowCount: 2 })),
    ).toBe(67);
  });

  it('必须修正的行被说成「不会落库」而不是「有问题」', () => {
    // 蓝图：校验失败的行不落库，不允许尽力导入产生半截数据
    expect(blockingNotice(report())).toContain('不会落库');
    expect(blockingNotice(report())).toContain('2 行必须修正');
    // 没有阻断行时不说教
    const clean = blockingNotice(
      report({ blockingRowCount: 0, skippableRowCount: 0, cleanRowCount: 4 }),
    );
    expect(clean).toContain('全部 4 行都可落库');
    expect(clean).not.toContain('不会落库');
  });

  it('严重级别与筛选的显示名各自成对', () => {
    expect(severityTone('ERROR')).toBe('danger');
    expect(severityTone('WARNING')).toBe('warning');
    expect(filterLabel(null)).toBe('全部问题');
    expect(filterLabel('ERROR')).toBe('必须修正');
    expect(filterLabel('NOPE')).toBe('NOPE');
    expect(kindLabel('FIXED_TEMPLATE')).toBe('修正版模板');
    // 六个类别都要有中文标签（报告页筛选下拉直接用它）
    expect(CATEGORIES).toHaveLength(6);
    expect(CATEGORIES.every((c) => c.label.length > 0)).toBe(true);
    expect(SEVERITY_FILTERS.map((f) => f.value)).toEqual(['ALL', 'ERROR', 'WARNING']);
  });

  it('分类计数与问题列过滤', () => {
    expect(
      categoryIssueCount({ category: 'X', label: 'x', rowCount: 1, errorCount: 2, warningCount: 1 }),
    ).toBe(3);
    // 「哪一列错得最多」只给真的有问题的列，0 条的列不该占版面
    expect(
      problemColumns([
        { fieldKey: 'a', fieldLabel: 'A', rowCount: 2, errorCount: 2, warningCount: 0 },
        { fieldKey: 'b', fieldLabel: 'B', rowCount: 0, errorCount: 0, warningCount: 0 },
      ]).map((c) => c.fieldKey),
    ).toEqual(['a']);
  });

  it('下载链接与 jobId 解析都走 /api/catalog 前缀且不带租户参数', () => {
    const url = exportFileUrl('j1', 'e1');
    expect(url).toBe('/api/catalog/catalog/import-jobs/j1/report/exports/e1/file');
    // 租户绝不进 URL：它只来自服务端 cookie
    expect(url).not.toContain('tenant');
    expect(reportJobId('?jobId=j1&x=2')).toBe('j1');
    expect(reportJobId('')).toBeNull();
  });
});