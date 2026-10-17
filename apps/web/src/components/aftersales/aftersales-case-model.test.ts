import { describe, expect, it } from 'vitest';
import {
  aftersalesListQuery,
  authorizationStateLabel,
  bulkItemText,
  bulkSummaryText,
  bulkMaxSize,
  countdownText,
  dimensionByField,
  formatAmount,
  formatQuantity,
  formatTime,
  humanizeDuration,
  ownerText,
  platformSourceLabel,
  timelinessBadgeClass,
  timelinessTone,
  unavailableCell,
  summaryLine,
} from './aftersales-case-model';
import type {
  AftersalesBulkItemResult,
  AftersalesBulkResult,
  AftersalesCaseCapabilities,
  AftersalesCasePage,
  AftersalesCaseRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 11.1.1.1 售后单列表前端模型。
 *
 * <p>这些断言守的是**界面纪律**，不是渲染细节：
 *
 * <ul>
 *   <li><b>时效四档必须真的分得开</b>（label / rank / 语气）—— 超时的代价是账号绩效与
 *       资金冻结，把 DUE_SOON 和 ON_TRACK 画成同一个颜色等于提醒不存在。</li>
 *   <li><b>倒计时文案的三态不能混</b>：已超时显示「已超时 X」、未超时显示「还剩 X」、
 *       <b>null 显示明确的中文说明</b>而不是「0 秒」或「已超时」——
 *       null 的两种含义是「已处理」与「平台没给时限」，两者都不是超时。</li>
 *   <li><b>未知状态不得回落成绿色</b>：回落 NO_DEADLINE（安全）而不是 ON_TRACK（掩盖超时）。</li>
 *   <li><b>金额与数量分币种 / 不跨币种相加</b>。</li>
 *   <li><b>不可得维度显示「—」并带原因</b>，而不是空白。</li>
 * </ul>
 */

function row(patch: Partial<AftersalesCaseRow> = {}): AftersalesCaseRow {
  return {
    id: 'c-1',
    caseNo: 'AS1',
    orderId: 'o-1',
    orderNo: 'SO-1',
    platformSource: 'AMAZON',
    platformShopCode: null,
    caseType: 'RETURN_REFUND',
    caseTypeLabel: '退货退款',
    status: 'RECEIVED',
    statusLabel: '已受理待处理',
    reasonCode: 'QUALITY_DEFECT',
    reasonLabel: '质量问题',
    reasonNote: null,
    requestedAmount: 100,
    currency: 'USD',
    requestedQuantity: 2,
    timelinessState: 'ON_TRACK',
    timelinessLabel: '正常',
    replyDueAt: '2026-03-05T00:00:00Z',
    resolveDueAt: null,
    effectiveDueAt: '2026-03-05T00:00:00Z',
    remainingSeconds: 3600,
    ownerRef: 'u-1',
    ownerDisplayName: '客服小林',
    ownerRoleLabel: '售后专员',
    ownerSource: 'TENANT_MAINTAINED',
    ownerUpstreamDelivered: false,
    platformCaseNo: null,
    authorizationState: 'NONE',
    ticketNo: null,
    noteCount: 0,
    receivedAt: '2026-02-20T10:00:00Z',
    resolvedAt: null,
    ...patch,
  };
}

describe('时效语气：四档必须视觉上分得开', () => {
  it('五个状态各有独立语气、标签与排序权重', () => {
    const tones = ['OVERDUE', 'DUE_SOON', 'ON_TRACK', 'NO_DEADLINE', 'RESOLVED'].map((s) =>
      timelinessTone(s),
    );
    expect(new Set(tones.map((t) => t.tone)).size, '语气不能重名').toBe(5);
    expect(new Set(tones.map((t) => t.label)).size, '标签不能重名').toBe(5);
    // 超时最紧急，已处理排最后 —— 界面上的默认排序靠它兜底。
    expect(tones.map((t) => t.rank)).toEqual([0, 1, 2, 3, 4]);
  });

  it('CSS 类名每个状态都不同', () => {
    const classes = ['OVERDUE', 'DUE_SOON', 'ON_TRACK', 'NO_DEADLINE', 'RESOLVED'].map((s) =>
      timelinessBadgeClass(s),
    );
    expect(new Set(classes).size).toBe(5);
    expect(timelinessBadgeClass('OVERDUE')).toContain('overdue');
  });

  it('未知状态回落 NO_DEADLINE 而不是 ON_TRACK', () => {
    // 认不出的状态 = 后端加了本界面还没接的口径。此时显示「正常」会直接掩盖一次超时。
    expect(timelinessTone('SOMETHING_NEW').tone).toBe('no-deadline');
    expect(timelinessTone(null).tone).toBe('no-deadline');
    expect(timelinessTone(undefined).tone).toBe('no-deadline');
    expect(timelinessTone('SOMETHING_NEW').tone).not.toBe('on-track');
  });
});

describe('倒计时文案', () => {
  it('未超时显示「还剩」，超时显示「已超时」', () => {
    expect(countdownText('ON_TRACK', 3 * 3600 + 12 * 60)).toBe('还剩 3 小时 12 分');
    expect(countdownText('OVERDUE', -3 * 86400)).toBe('已超时 3 天');
  });

  it('null 绝不显示成 0 秒或已超时 —— 那两种含义都不是超时', () => {
    expect(countdownText('RESOLVED', null)).toBe('倒计时已停止');
    expect(countdownText('NO_DEADLINE', null)).toBe('平台未给时限');
    expect(countdownText('ON_TRACK', null)).not.toContain('超时');
    expect(countdownText('ON_TRACK', null)).not.toContain('0 秒');
  });

  it('不足一分钟不给「0 分 0 秒」', () => {
    expect(humanizeDuration(30)).toBe('不足 1 分钟');
    expect(humanizeDuration(59)).toBe('不足 1 分钟');
    expect(humanizeDuration(60)).toBe('1 分钟');
    expect(humanizeDuration(7200)).toBe('2 小时');
    expect(humanizeDuration(90000)).toBe('1 天 1 小时');
    expect(humanizeDuration(86400)).toBe('1 天');
  });
});

describe('不可得维度诚实显示', () => {
  it('店铺为空时显示「—」并带归属模块与原因', () => {
    const cell = unavailableCell(null, {
      label: '平台店铺',
      ownerModule: '2 平台与店铺',
      reason: 'sales_order 没有 shop_code 列',
    });
    expect(cell.text).toBe('—');
    expect(cell.title).toContain('平台店铺');
    expect(cell.title).toContain('2 平台与店铺');
    expect(cell.title).toContain('shop_code');
  });

  it('有值时原样返回，不加任何解释', () => {
    expect(unavailableCell('AMAZON', { label: 'x', ownerModule: 'y', reason: 'z' }).title).toBe('');
  });

  it('从能力声明里取维度说明；取不到就返回 undefined，不编一条原因', () => {
    const caps = {
      unavailableDimensions: [
        {
          field: 'platformShopCode',
          label: '平台店铺',
          available: false,
          ownerModule: '2 平台与店铺',
          reason: '没有 shop_code 列',
        },
      ],
    } as unknown as AftersalesCaseCapabilities;
    expect(dimensionByField(caps, 'platformShopCode')?.ownerModule).toBe('2 平台与店铺');
    expect(dimensionByField(caps, 'notThere')).toBeUndefined();
    expect(dimensionByField(null, 'platformShopCode')).toBeUndefined();
  });
});

describe('筛选与查询串', () => {
  it('空值不进查询串', () => {
    expect(aftersalesListQuery({})).toBe('');
    expect(aftersalesListQuery({ keyword: '   ' })).toBe('');
  });

  it('多维筛选全部带得上，且 URL 编码', () => {
    const q = aftersalesListQuery({
      caseType: 'RETURN_REFUND',
      timelinessState: 'OVERDUE',
      minAmount: '100.0000',
      maxAmount: '200.0000',
      platformSource: 'AMAZON',
      keyword: 'A B',
      page: 0,
      size: 20,
    });
    expect(q).toContain('caseType=RETURN_REFUND');
    expect(q).toContain('timelinessState=OVERDUE');
    expect(q).toContain('minAmount=100.0000');
    expect(q).toContain('maxAmount=200.0000');
    expect(q).toContain('platformSource=AMAZON');
    expect(q).toContain('keyword=A%20B');
    expect(q).toContain('page=0');
    expect(q).toContain('size=20');
  });
});

describe('汇总条', () => {
  const page = (amounts: { currency: string; amount: number; caseCount: number }[]) =>
    ({
      rows: [],
      page: 0,
      size: 20,
      total: 5,
      evaluatedAt: '2026-02-20T10:00:00Z',
      amountRangeSemantics: '闭区间',
      timelinessThresholdNote: '临期阈值 24 小时',
      dataScope: 'ALL',
      dataScopeNote: '',
      summary: {
        totalCount: 5,
        openCount: 4,
        overdueCount: 2,
        dueSoonCount: 1,
        onTrackCount: 1,
        noDeadlineCount: 1,
        resolvedCount: 1,
        ticketCount: 0,
        amountsByCurrency: amounts,
        countsByType: [],
        amountsNote: '',
      },
    }) as AftersalesCasePage;

  it('超时数排在最前', () => {
    const text = summaryLine(page([]));
    expect(text.indexOf('超时 2')).toBeLessThan(text.indexOf('临期 1'));
  });

  it('金额按币种分列，不跨币种相加', () => {
    const text = summaryLine(
      page([
        { currency: 'USD', amount: 100, caseCount: 2 },
        { currency: 'EUR', amount: 50, caseCount: 1 },
      ]),
    );
    expect(text).toContain('100.00 USD（2 张）');
    expect(text).toContain('50.00 EUR（1 张）');
    expect(text).not.toContain('150.00');
  });

  it('批量上限由能力声明给出，不在前端写死', () => {
    const caps = {
      bulkOperations: [{ maxBatchSize: 100 }],
    } as unknown as AftersalesCaseCapabilities;
    expect(bulkMaxSize(caps, 50)).toBe(100);
    expect(bulkMaxSize(null, 50)).toBe(50);
    expect(bulkMaxSize({ bulkOperations: [] } as unknown as AftersalesCaseCapabilities, 50)).toBe(50);
  });
});

describe('批量结果逐条回报', () => {
  const result = (items: AftersalesBulkResult['items']) =>
    ({
      operation: 'AUTHORIZE',
      operationLabel: '授权',
      requested: items.length,
      succeeded: items.filter((i) => i.success).length,
      failed: items.filter((i) => !i.success).length,
      items,
      batchId: 'b-1',
      dataScope: 'ALL',
      note: '',
    }) as AftersalesBulkResult;

  it('成功数 / 失败数分开说，不压成一个布尔值', () => {
    const text = bulkSummaryText(
      result([
        { caseId: '1', caseNo: 'AS1', success: true, outcome: 'AUTHORIZED', detail: '已批准', reference: 'x' },
        { caseId: '2', caseNo: 'AS2', success: false, outcome: 'AMOUNT_EXCEEDS', detail: '授权金额超过申请金额', reference: null },
      ]),
    );
    expect(text).toContain('成功 1 / 2');
    expect(text).toContain('失败 1');
  });

  it('失败项给人话原因，成功项带产物引用', () => {
    const ok: AftersalesBulkItemResult = {
      caseId: '1', caseNo: 'AS1', success: true, outcome: 'AUTHORIZED', detail: '已批准', reference: 'auth-1',
    };
    const ko: AftersalesBulkItemResult = {
      caseId: '2', caseNo: 'AS2', success: false, outcome: 'ALREADY_TICKETED', detail: '已转过工单（工单号 TK1）', reference: null,
    };
    expect(bulkItemText(ok)).toContain('auth-1');
    expect(bulkItemText(ko)).toContain('TK1');
  });
});

describe('格式化', () => {
  it('金额带币种；空值显示「—」', () => {
    expect(formatAmount(100, 'USD')).toBe('100.00 USD');
    expect(formatAmount('12.5', 'EUR')).toBe('12.50 EUR');
    expect(formatAmount(null, 'USD')).toBe('—');
    expect(formatAmount('abc')).toBe('abc');
  });

  it('数量去掉多余尾零但保留至少一位小数', () => {
    expect(formatQuantity(2)).toBe('2');
    expect(formatQuantity(1.5)).toBe('1.5');
    expect(formatQuantity(null)).toBe('—');
  });

  it('时间解析失败时原样返回，不显示 Invalid Date', () => {
    expect(formatTime('2026-02-20T10:00:00Z')).not.toBe('Invalid Date');
    expect(formatTime('不是时间')).toBe('不是时间');
    expect(formatTime(null)).toBe('—');
  });

  it('授权状态里 NONE 是「未授权」，不是第四种决定', () => {
    expect(authorizationStateLabel('NONE')).toBe('未授权');
    expect(authorizationStateLabel('APPROVED')).toBe('已批准');
    expect(authorizationStateLabel('PARTIALLY_APPROVED')).toBe('部分批准');
    expect(authorizationStateLabel('REJECTED')).toBe('已拒绝');
    expect(authorizationStateLabel(null)).toBe('—');
  });

  it('责任人没登记时说「未分配责任人」，不显示空白', () => {
    expect(ownerText(row({ ownerDisplayName: '客服小林' }))).toBe('客服小林');
    expect(ownerText(row({ ownerDisplayName: null, ownerRef: 'u-9' }))).toBe('u-9');
    expect(ownerText(row({ ownerDisplayName: null, ownerRef: null }))).toBe('未分配责任人');
  });

  it('平台来源是平台级，店铺维度不在这里出现', () => {
    expect(platformSourceLabel('AMAZON')).toBe('Amazon');
    expect(platformSourceLabel('TIKTOK_SHOP')).toBe('TikTok Shop');
    expect(platformSourceLabel(null)).toBe('—');
  });
});
