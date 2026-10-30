import { describe, expect, it } from 'vitest';
import {
  deliveryEmptyText,
  deliveryNote,
  deliveryStatusLabel,
  deliverySucceeded,
  dispatchResultText,
  downloadState,
  downloadStateText,
  exportByteSizeText,
  exportColumnKindLabel,
  exportDownloadUrl,
  exportFormatLabel,
  exportJobSummary,
  exportSelectionText,
  exportStatusLabel,
  isRunningStatus,
  isTerminalStatus,
  jobListEmptyText,
  jobListState,
} from './export-model';
import type {
  ExportSubscriptionColumnInfo,
  ExportSubscriptionDeliveryView,
  ExportSubscriptionDispatchResult,
  ExportSubscriptionJobPage,
  ExportSubscriptionJobView,
} from './export-model';

/**
 * 13.6.2.1 导出与订阅 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的几件事**：
 *
 * 1. **「受理」不是「完成」**。PENDING / RUNNING 的任务**不能**显示成可下载。
 * 2. **「已投递」不是「排上队了」**。PENDING 绝不能被说成「已发送」，
 *    FAILED 必须把原因摆出来。
 * 3. **过期就是过期**。过了有效期显示过期态，不给「点了就 410」的下载链接。
 * 4. **前端不做业务算术**。字节数只做单位换算，不重算行数 / 金额。
 * 5. **「读不到」与「没有数据」分开**。
 */

function job(overrides: Partial<ExportSubscriptionJobView> = {}): ExportSubscriptionJobView {
  return {
    jobId: '11111111-1111-1111-1111-111111111111',
    reportId: '22222222-2222-2222-2222-222222222222',
    reportCode: 'R-1',
    reportName: '报表一',
    format: 'CSV',
    status: 'SUCCEEDED',
    statusLabel: '已完成',
    selectedFields: ['DIM:SHOP'],
    fieldHeaders: ['店铺（uuid）'],
    rowCount: 4,
    fileName: 'R-1-20261001.csv',
    byteSize: 1024,
    contentSha256: 'a'.repeat(64),
    actorId: '33333333-3333-3333-3333-333333333333',
    requestedAt: '2026-10-01T09:00:00Z',
    startedAt: '2026-10-01T09:00:01Z',
    finishedAt: '2026-10-01T09:00:02Z',
    expiresAt: '2026-10-02T09:00:02Z',
    downloadable: true,
    downloadNote: '',
    error: '',
    ...overrides,
  };
}

function delivery(
  overrides: Partial<ExportSubscriptionDeliveryView> = {},
): ExportSubscriptionDeliveryView {
  return {
    deliveryId: '44444444-4444-4444-4444-444444444444',
    reportId: '22222222-2222-2222-2222-222222222222',
    reportCode: 'R-1',
    reportName: '报表一',
    subscriptionId: '55555555-5555-5555-5555-555555555555',
    channel: 'EMAIL',
    target: 'ops@example.com',
    scheduledFor: '2026-10-01T09:00:00Z',
    status: 'PENDING',
    statusLabel: '待投递',
    attemptCount: 0,
    lastError: '',
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
    note: '已排队，等待外部调度触发投递。',
    ...overrides,
  };
}

describe('导出任务状态', () => {
  it('受理后是排队中，不是已完成', () => {
    expect(exportStatusLabel('PENDING')).toBe('排队中');
    expect(exportStatusLabel('RUNNING')).toBe('生成中');
    expect(isRunningStatus('PENDING')).toBe(true);
    expect(isRunningStatus('RUNNING')).toBe(true);
    expect(isRunningStatus('SUCCEEDED')).toBe(false);
  });

  it('终态不再轮询', () => {
    expect(isTerminalStatus('SUCCEEDED')).toBe(true);
    expect(isTerminalStatus('FAILED')).toBe(true);
    expect(isTerminalStatus('CANCELLED')).toBe(true);
    expect(isTerminalStatus('EXPIRED')).toBe(true);
    expect(isTerminalStatus('RUNNING')).toBe(false);
  });

  it('未完成的任务不能下载', () => {
    const pending = job({ status: 'PENDING', downloadable: false, fileName: null });
    expect(downloadState(pending)).toBe('pending');
    expect(downloadStateText(pending)).toContain('尚未生成完成');
  });

  it('过了有效期显示过期态而不是给下载链接', () => {
    const expired = job({ downloadable: false, downloadNote: '已过下载有效期' });
    expect(downloadState(expired)).toBe('expired');
    expect(downloadStateText(expired)).toContain('已过下载有效期');
  });

  it('已作废与失败各说各的', () => {
    expect(downloadState(job({ status: 'CANCELLED', downloadable: false }))).toBe('cancelled');
    const failed = job({ status: 'FAILED', downloadable: false, error: '导出结果集超过行数上限' });
    expect(downloadState(failed)).toBe('failed');
    expect(downloadStateText(failed)).toContain('导出结果集超过行数上限');
  });

  it('就绪时给出有效期与到期时刻', () => {
    expect(downloadState(job())).toBe('ready');
    expect(downloadStateText(job())).toContain('2026-10-02T09:00:02Z');
  });
});

describe('导出摘要', () => {
  it('摘要里有格式、状态与行数', () => {
    const text = exportJobSummary(job());
    expect(text).toContain('CSV');
    expect(text).toContain('已完成');
    expect(text).toContain('4 行');
  });

  it('格式标签认不出来就原样显示', () => {
    expect(exportFormatLabel('XLSX')).toContain('Excel');
    expect(exportFormatLabel('PDF')).toBe('PDF');
    expect(exportFormatLabel('???')).toBe('???');
  });

  it('字节数只做单位换算', () => {
    expect(exportByteSizeText(job({ byteSize: 512 }))).toBe('512 B');
    expect(exportByteSizeText(job({ byteSize: 2048 }))).toBe('2.0 KB');
    expect(exportByteSizeText(job({ byteSize: null }))).toBe('—');
  });

  it('下载链接指向真文件端点', () => {
    expect(exportDownloadUrl('abc')).toBe(
      '/api/reporting/reporting/custom-report/export/jobs/abc/file',
    );
  });

  it('列的分类与摘要', () => {
    const columns: ExportSubscriptionColumnInfo[] = [
      { key: 'DIM:SHOP', label: '店铺（uuid）', kind: 'DIMENSION', note: '维度取值' },
      { key: 'MEASURE:SALES_AMOUNT', label: '销售额（金额）', kind: 'MEASURE', note: '口径' },
    ];
    expect(exportColumnKindLabel('DIMENSION')).toBe('维度');
    expect(exportColumnKindLabel('MEASURE')).toBe('度量');
    expect(exportSelectionText(columns)).toContain('销售额（金额）');
    expect(exportSelectionText([])).toBe('未选列');
  });
});

describe('投递状态', () => {
  it('待投递不是已发送', () => {
    expect(deliveryStatusLabel('PENDING')).toBe('待投递');
    expect(deliverySucceeded(delivery())).toBe(false);
    expect(deliverySucceeded(delivery({ status: 'DELIVERED' }))).toBe(true);
  });

  it('失败必须把原因摆出来', () => {
    const failed = delivery({ status: 'FAILED', lastError: '未配置邮件通道（REPORTING_SMTP_HOST 为空）' });
    expect(deliveryNote(failed)).toContain('REPORTING_SMTP_HOST');
  });

  it('成功与待投递的说明各说各的', () => {
    expect(deliveryNote(delivery())).toContain('等待外部调度');
    expect(
      deliveryNote(
        delivery({ status: 'DELIVERED', note: '传输层回报已成功送达。' }),
      ),
    ).toContain('已成功送达');
  });

  it('一轮投递的结果说清成功与失败各几条', () => {
    const result: ExportSubscriptionDispatchResult = {
      attempted: 3,
      delivered: 1,
      failed: 2,
      rows: [],
      note: '本轮尝试投递 3 条',
    };
    expect(dispatchResultText(result)).toContain('尝试 3 条');
    expect(dispatchResultText(result)).toContain('成功 1 条');
    expect(dispatchResultText(result)).toContain('失败 2 条');
  });
});

describe('页面状态', () => {
  const page: ExportSubscriptionJobPage = {
    rows: [job()],
    total: 1,
    page: 0,
    size: 20,
    statusFilter: '',
    formatFilter: '',
    keyword: '',
  };

  it('读不到是错误态，不是空态', () => {
    expect(jobListState(null, '服务不可用')).toBe('error');
    expect(jobListState(null, '')).toBe('loading');
  });

  it('读到了但一条都没有是空态', () => {
    expect(jobListState({ ...page, rows: [], total: 0 }, '')).toBe('empty');
    expect(jobListState(page, '')).toBe('ready');
  });

  it('空态文案区分「筛选无结果」与「还没导出过」', () => {
    expect(jobListEmptyText({ ...page, rows: [], total: 0 })).toContain('还没有发起过导出');
    expect(jobListEmptyText({ ...page, rows: [], total: 0, keyword: 'R-1' })).toContain(
      '没有导出任务匹配当前筛选条件',
    );
  });

  it('投递台账的空态文案说清是谁落的记录', () => {
    expect(deliveryEmptyText()).toContain('外部调度');
  });
});
