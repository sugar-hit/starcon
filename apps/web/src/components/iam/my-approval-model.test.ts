import { describe, expect, it } from 'vitest';

import {
  DWELL_FILTERS,
  amountLabel,
  batchSummary,
  buildQuery,
  dwellLabel,
  dwellTone,
  emptyFilters,
  isFiltered,
  isUuid,
  resultLabel,
  resultTone,
  retryableItems,
  summaryLabel,
  validateFilters,
  type InboxFilters,
} from './my-approval-model';

/**
 * 17.4.2.1 待我审批的前端纯函数。
 *
 * <p>覆盖三件容易做错的事：<b>停留时长的中文说法与语气</b>、<b>筛选拼装不把空值发出去</b>、
 * <b>批量结果的逐条呈现</b>。
 */

function filters(overrides: Partial<InboxFilters> = {}): InboxFilters {
  return { ...emptyFilters(), ...overrides };
}

describe('停留时长', () => {
  it('按秒/分/时/天逐级换算，不出现「0 天 0 小时」这种没信息量的说法', () => {
    expect(dwellLabel(0)).toBe('0 秒');
    expect(dwellLabel(59)).toBe('59 秒');
    expect(dwellLabel(60)).toBe('1 分钟');
    expect(dwellLabel(3599)).toBe('59 分钟');
    expect(dwellLabel(3600)).toBe('1 小时 0 分钟');
    expect(dwellLabel(3660)).toBe('1 小时 1 分钟');
    expect(dwellLabel(86399)).toBe('23 小时 59 分钟');
    expect(dwellLabel(86400)).toBe('1 天 0 小时');
    expect(dwellLabel(90000)).toBe('1 天 1 小时');
  });

  it('负数按 0 处理：时钟回拨或脏数据不该渲染出「负 3 天」', () => {
    expect(dwellLabel(-5)).toBe('0 秒');
    expect(dwellTone(-5)).toBe('neutral');
  });

  it('语气随压得越久越重，一小时是一个明显的台阶', () => {
    expect(dwellTone(0)).toBe('neutral');
    expect(dwellTone(3599)).toBe('neutral');
    expect(dwellTone(3600)).toBe('primary');
    expect(dwellTone(24 * 3600)).toBe('warning');
    expect(dwellTone(72 * 3600)).toBe('danger');
  });
});

describe('筛选拼装', () => {
  it('空值一律不进查询串：发出 ?minAmount= 会让后端按空字符串解析', () => {
    const q = buildQuery(filters());
    expect(q).toBe('?sort=WAITING&size=20');
    expect(q).not.toContain('minAmount=');
    expect(q).not.toContain('documentTypeCode=');
  });

  it('停留时长以秒下发，由后端换算区间', () => {
    expect(buildQuery(filters({ minDwellHours: 24 }))).toContain('minDwellSeconds=86400');
    expect(buildQuery(filters({ minDwellHours: 0 }))).toContain('minDwellSeconds=0');
  });

  it('币种统一大写，首尾空白被裁掉', () => {
    const q = buildQuery(
      filters({ currencyCode: '  usd  ', documentTypeCode: ' PURCHASE_ORDER ' }),
    );
    expect(q).toContain('currencyCode=USD');
    expect(q).toContain('documentTypeCode=PURCHASE_ORDER');
  });

  it('首页不带 page 参数，末页照带', () => {
    expect(buildQuery(filters({ page: 0 }))).not.toContain('page=');
    expect(buildQuery(filters({ page: 3 }))).toContain('page=3');
  });

  it('校验拦住格式错误与自相矛盾的区间', () => {
    expect(validateFilters(filters({ minAmount: 'abc' }))).toEqual([
      '金额下限需为非负数字，最多 6 位小数',
    ]);
    expect(validateFilters(filters({ minAmount: '100', maxAmount: '10' }))).toEqual([
      '金额下限不能大于上限',
    ]);
    expect(validateFilters(filters({ currencyCode: 'US' }))).toEqual([
      '币种需为三个字母，例如 USD',
    ]);
    expect(validateFilters(filters({ applicantId: 'not-a-uuid' }))).toEqual([
      '申请人需为成员 ID（UUID）',
    ]);
    expect(validateFilters(filters({ minAmount: '1000.123456' }))).toEqual([]);
    expect(validateFilters(filters())).toEqual([]);
  });

  it('isFiltered 区分「本来就没有」与「筛完没有」', () => {
    expect(isFiltered(filters())).toBe(false);
    expect(isFiltered(filters({ sort: 'NEWEST', page: 2 }))).toBe(false);
    expect(isFiltered(filters({ documentTypeCode: 'PURCHASE_ORDER' }))).toBe(true);
    expect(isFiltered(filters({ minDwellHours: 24 }))).toBe(true);
  });

  it('UUID 校验大小写不敏感', () => {
    expect(isUuid('0192f3c4-5d6e-7a8b-9c0d-1e2f3a4b5c6d')).toBe(true);
    expect(isUuid('0192F3C4-5D6E-7A8B-9C0D-1E2F3A4B5C6D')).toBe(true);
    expect(isUuid('0192f3c45d6e7a8b9c0d1e2f3a4b5c6d')).toBe(false);
  });

  it('停留时长档位里第一档是不限，null 不能被当成 0 小时', () => {
    expect(DWELL_FILTERS[0]!.hours).toBeNull();
    expect(buildQuery(filters({ minDwellHours: DWELL_FILTERS[0]!.hours }))).not.toContain(
      'minDwellSeconds',
    );
  });
});

describe('批量结果', () => {
  it('未知码原样显示而不是吞掉：后端加了新码时页面要说得出它是什么', () => {
    expect(resultLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(resultTone('SOMETHING_NEW')).toBe('neutral');
  });

  it('已知码映射成人话与语气', () => {
    expect(resultLabel('NOT_MINE')).toBe('不在你的待办里');
    expect(resultTone('REJECTED_BY_RULE')).toBe('danger');
    expect(resultTone('APPROVED')).toBe('success');
  });

  it('只在真失败时才提示失败数', () => {
    expect(batchSummary(3, 0)).toBe('全部 3 条处理成功');
    expect(batchSummary(2, 1)).toBe('2 条成功，1 条未处理');
    expect(batchSummary(0, 2)).toBe('0 条成功，2 条未处理');
  });

  it('不在待办里的条目不进重试清单：再点一次只会再失败一次', () => {
    const items = [
      { instanceId: 'a', ok: true, code: 'APPROVED' },
      { instanceId: 'b', ok: false, code: 'NOT_MINE' },
      { instanceId: 'c', ok: false, code: 'REJECTED_BY_RULE' },
    ];
    expect(retryableItems(items).map((i) => i.instanceId)).toEqual(['c']);
    expect(retryableItems(items)).toHaveLength(1);
  });
});

describe('展示口径', () => {
  it('表头把筛选后与总数分开讲', () => {
    expect(summaryLabel(5, 5)).toBe('共 5 条待审批');
    expect(summaryLabel(3, 17)).toBe('筛选出 3 条，共 17 条待审批');
  });

  it('没有金额时说清楚是「不适用金额审批」，不留空白让人猜是不是加载中', () => {
    expect(amountLabel(null, null)).toBe('不适用金额审批');
    expect(amountLabel(8800.5, 'USD')).toBe('USD 8800.5');
    expect(amountLabel(100, null)).toBe('100');
  });
});
