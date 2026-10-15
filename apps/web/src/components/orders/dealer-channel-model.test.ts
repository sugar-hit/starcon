import { describe, expect, it } from 'vitest';
import {
  attainmentTone,
  breachLabel,
  complianceLabel,
  dealerAlertQuery,
  dealerAttainmentQuery,
  dealerAuthorizationQuery,
  dealerProfileQuery,
  dealerSaleQuery,
  formatAttainment,
  rebateBasisLabel,
  validityLabel,
} from './dealer-channel-model';

/**
 * 9.7.1.2 经销与分销管理前端模型。
 *
 * <p>本文件钉死的是**那些「编一个默认值就会说谎」的展示规则** ——
 * 完成率的无定义、合规的未校验、返点的未确认口径、期限的区间语义。
 * 这些在后端是 null / UNVERIFIED / false，前端一旦把它们渲染成 0% / 通过 / 已确认，
 * 页面就会把「没接通」显示成「结论已定」，而所有静态检查依然全绿。
 */

describe('完成率', () => {
  it('目标为 0（不限配额）时显示「不考核」而不是 0%', () => {
    // null 是后端 nullif(target,0) 的结果，含义是「无定义」。
    expect(formatAttainment(null)).toBe('不考核');
    expect(formatAttainment(undefined)).toBe('不考核');
    // 0% 会被读成「一个都没完成」，100% 会被读成「超额完成」—— 都是编造的结论。
    expect(formatAttainment(null)).not.toBe('0.00%');
    expect(formatAttainment(null)).not.toBe('100.00%');
  });

  it('有目标时按后端给的比率换算，不在前端重算', () => {
    expect(formatAttainment(0.033333)).toBe('3.33%');
    expect(formatAttainment(1)).toBe('100.00%');
    expect(formatAttainment(1.25)).toBe('125.00%');
    expect(formatAttainment(0)).toBe('0.00%');
  });

  it('分档只用于着色，无定义单独一档（tone 取 @starcon/ui 的词表）', () => {
    expect(attainmentTone(null)).toBe('neutral');
    expect(attainmentTone(0)).toBe('danger');
    expect(attainmentTone(0.49)).toBe('danger');
    expect(attainmentTone(0.5)).toBe('warning');
    expect(attainmentTone(0.999999)).toBe('warning');
    expect(attainmentTone(1)).toBe('success');
    expect(attainmentTone(2)).toBe('success');
  });
});

describe('返点口径', () => {
  it('未与财务确认是常态，标签必须说出来', () => {
    expect(rebateBasisLabel({ financeConfirmed: false })).toBe('本模块口径 · 未与财务确认');
  });

  it('只有真的确认过才显示「已确认」', () => {
    expect(rebateBasisLabel({ financeConfirmed: true })).toBe('财务已确认口径');
    // 两个标签必须不同，否则「没确认」会被渲染成「已确认」而不易察觉。
    expect(rebateBasisLabel({ financeConfirmed: true })).not.toBe(
      rebateBasisLabel({ financeConfirmed: false }),
    );
  });
});

describe('合规状态', () => {
  it('UNVERIFIED 显示「未校验（16 合规未交付）」，不是留空也不是「通过」', () => {
    expect(complianceLabel('UNVERIFIED')).toBe('未校验（16 合规未交付）');
    expect(complianceLabel('UNVERIFIED')).not.toBe('');
    expect(complianceLabel('UNVERIFIED')).not.toContain('通过');
  });

  it('三个状态各有各的说法', () => {
    expect(complianceLabel('VERIFIED')).toBe('已通过合规校验');
    expect(complianceLabel('REJECTED')).toBe('合规否决');
    // 不可达状态回落到「未校验」而不是乐观地显示「通过」。
    expect(complianceLabel('UNKNOWN' as never)).toBe('未校验（16 合规未交付）');
  });
});

describe('授权期限', () => {
  it('显示后端返回的两个日期区间，不做推算', () => {
    expect(validityLabel({ validFrom: '2026-01-01', validTo: '2026-12-31' })).toBe(
      '2026-01-01 ~ 2026-12-31',
    );
    // 补录的历史授权期限不一定从今天算起，所以界面上不能出现任何「还有 N 天」。
    expect(validityLabel({ validFrom: '2020-03-01', validTo: '2020-12-31' })).not.toContain('天');
  });
});

describe('越界维度', () => {
  it('三种越界各有说法，BOTH 要把两件事都说出来', () => {
    expect(breachLabel('REGION')).toBe('跨区域');
    expect(breachLabel('PRODUCT_LINE')).toBe('跨产品线');
    expect(breachLabel('BOTH')).toBe('跨区域 + 跨产品线');
  });
});

describe('查询串', () => {
  it('授权台账：状态 / 经销商 / 关键词 / 分页', () => {
    expect(dealerAuthorizationQuery({ status: 'ACTIVE', page: 1, size: 50 })).toBe(
      '?status=ACTIVE&page=1&size=50',
    );
    expect(dealerAuthorizationQuery({ keyword: ' 甲 ' })).toBe('?keyword=%E7%94%B2');
    expect(dealerAuthorizationQuery({})).toBe('');
  });

  it('完成率：期间与经销商', () => {
    expect(
      dealerAttainmentQuery({ periodStart: '2026-03-01', periodEnd: '2026-03-31', size: 10 }),
    ).toBe('?periodStart=2026-03-01&periodEnd=2026-03-31&size=10');
  });

  it('销售：授权 / 区域 / 期间', () => {
    expect(dealerSaleQuery({ authorizationId: 'a', regionCode: 'EAST' })).toBe(
      '?authorizationId=a&regionCode=EAST',
    );
  });

  it('预警：状态 / 维度 / 经销商', () => {
    expect(dealerAlertQuery({ alertState: 'OPEN', breachKind: 'BOTH' })).toBe(
      '?alertState=OPEN&breachKind=BOTH',
    );
  });

  it('档案：关键词与分页', () => {
    expect(dealerProfileQuery({ keyword: '经销商甲', page: 0 })).toBe(
      '?keyword=%E7%BB%8F%E9%94%80%E5%95%86%E7%94%B2&page=0',
    );
  });

  it('空条件不产生裸问号', () => {
    expect(dealerAuthorizationQuery({ keyword: '   ' })).toBe('');
    expect(dealerAttainmentQuery({})).toBe('');
    expect(dealerAlertQuery({})).toBe('');
  });
});
