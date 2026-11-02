import { describe, expect, it } from 'vitest';
import type {
  CrmCustomerDetailAmount,
  CrmCustomerDetailIdentity,
  CrmCustomerDetailProfile,
  CrmCustomerDetailSourceState,
  CrmCustomerDetailTimelineEntry,
  CrmCustomerDetailTimelineRun,
} from '../../../../../packages/api-contracts/src/index';
import {
  customerDetailAmountText,
  customerDetailCandidateText,
  customerDetailContactIsPlain,
  customerDetailContactText,
  customerDetailEntryText,
  customerDetailIdentityText,
  customerDetailOptOutStateLabel,
  customerDetailRefundRateText,
  customerDetailRiskFlagLabel,
  customerDetailRunText,
  customerDetailSourceLabel,
  customerDetailSourceText,
  customerDetailTimelineEmptyText,
  customerDetailUnavailableText,
  customerDetailValueTierLabel,
} from './customer-detail-model';

/**
 * 15.3.1.2 客户详情前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的几件事**钉死：
 *
 * <ol>
 *   <li><b>「未接入」与「0 条」是两句话</b>：把读不到画成 0 条，
 *       等于替上游说「这个人从来没下过单」—— 那是编数据。</li>
 *   <li><b>「线索」不是「合并」</b>：页面文案里出现「已识别为同一人」，
 *       就是在替 15.3.1.3 下它还没下的结论。</li>
 *   <li><b>「部分来源读不到」不能画成「完成」</b>：PARTIAL 与 SUCCEEDED
 *       必须是两句话。</li>
 *   <li><b>空态分三种</b>：「还没建立」「这个来源本来就没有」「建立过但这次没产出」
 *       要的行动完全不同。</li>
 *   <li><b>金额按币种分行</b>、<b>前端不做算术</b>。</li>
 * </ol>
 */
describe('15.3.1.2 客户详情前端模型', () => {
  describe('时间线来源的可读名', () => {
    it('五个来源各有各的名字', () => {
      expect(customerDetailSourceLabel('ORDER')).toBe('订单');
      expect(customerDetailSourceLabel('AFTERSALES')).toBe('售后');
      expect(customerDetailSourceLabel('MESSAGE')).toBe('消息');
      expect(customerDetailSourceLabel('REVIEW')).toBe('评价');
      expect(customerDetailSourceLabel('CONTACT')).toBe('接触记录');
    });

    it('未知来源原样返回（不吞掉）', () => {
      expect(customerDetailSourceLabel('WHATEVER')).toBe('WHATEVER');
      expect(customerDetailSourceLabel(undefined)).toBe('');
    });
  });

  describe('退款率的显示', () => {
    it('分母为 0 时是「不可判定」，**不是** 0%', () => {
      expect(customerDetailRefundRateText(null)).toBe('不可判定');
      expect(customerDetailRefundRateText(undefined)).toBe('不可判定');
      expect(customerDetailRefundRateText('')).toBe('不可判定');
      expect(customerDetailRefundRateText(null)).not.toContain('0');
    });

    it('有值时原样显示（前端不做任何算术）', () => {
      expect(customerDetailRefundRateText('0.333333')).toBe('0.333333');
      expect(customerDetailRefundRateText('1.000000')).toBe('1.000000');
    });
  });

  describe('风险标记与分层', () => {
    it('「样本不足」与「无风险」是**两句话**，不能混', () => {
      expect(customerDetailRiskFlagLabel('INSUFFICIENT_SAMPLE')).toBe('样本不足，不可判定');
      expect(customerDetailRiskFlagLabel('NONE')).toBe('无风险标记');
      expect(customerDetailRiskFlagLabel('INSUFFICIENT_SAMPLE')).not.toBe(
        customerDetailRiskFlagLabel('NONE'),
      );
    });

    it('价值分层与退订状态各有各的话', () => {
      expect(customerDetailValueTierLabel('VIP')).toBe('高价值');
      expect(customerDetailValueTierLabel('LOW')).toBe('无有效消费');
      expect(customerDetailOptOutStateLabel('ACTIVE')).toBe('退订生效中');
    });
  });

  describe('金额按币种分行', () => {
    const amounts: CrmCustomerDetailAmount[] = [
      { currency: 'USD', totalAmount: 315.6, orderCount: 3, avgOrderAmount: 105.2 },
      { currency: 'EUR', totalAmount: 12, orderCount: 1, avgOrderAmount: 12 },
    ];

    it('两个币种分行显示，**不合并**成一个数', () => {
      const text = customerDetailAmountText(amounts);
      expect(text).toContain('USD 315.6');
      expect(text).toContain('EUR 12');
      expect(text).not.toBe('327.6');
    });

    it('客单价不可判定时是「—」，不是 0', () => {
      const text = customerDetailAmountText([
        { currency: 'USD', totalAmount: 0, orderCount: 0, avgOrderAmount: null },
      ]);
      expect(text).toContain('客单价 —');
    });

    it('没有金额行时是「无消费记录」', () => {
      expect(customerDetailAmountText([])).toBe('无消费记录');
      expect(customerDetailAmountText(undefined)).toBe('无消费记录');
    });
  });

  describe('联系方式', () => {
    const masked: CrmCustomerDetailProfile = {
      id: 'c1',
      customerRef: 'CUST-A',
      sourceShop: 'AMAZON',
      customerName: '张三',
      customerPartyId: null,
      firstOrderAt: null,
      lastOrderAt: null,
      orderCount: 0,
      refundCaseCount: 0,
      refundRate: null,
      valueTier: 'NORMAL',
      valueTierLabel: '常规',
      riskFlag: 'NONE',
      riskFlagLabel: '无风险标记',
      riskReason: '',
      contactEmail: 'z***@***.com',
      contactPhone: '138****1234',
      contactMasked: true,
      contactSource: 'MANUAL',
      contactSourceLabel: '人工登记',
      optOutState: 'NONE',
      optOutStateLabel: '无退订记录',
      windowFrom: null,
      windowTo: null,
      syncedAt: null,
    };

    it('默认脱敏：原样显示后端给的脱敏值', () => {
      expect(customerDetailContactText(masked)).toBe('z***@***.com / 138****1234');
      expect(customerDetailContactIsPlain(masked)).toBe(false);
    });

    it('明文时标志位为 false（页面据此提示「本次已展示明文」）', () => {
      expect(customerDetailContactIsPlain({ ...masked, contactMasked: false })).toBe(true);
    });
  });

  describe('跨店铺身份关系', () => {
    const identity: CrmCustomerDetailIdentity = {
      mergeState: 'NOT_MERGED',
      mergeStateLabel: '未合并',
      ownerModule: '15.3.1.3',
      note: '线索不是合并结论',
      candidates: [
        {
          customerId: 'c2',
          customerRef: 'CUST-B',
          sourceShop: 'SHOPIFY',
          customerName: '张三',
          clue: '联系方式指纹一致',
          maskedContact: 'z***@***.com',
          sameShop: false,
        },
      ],
    };

    it('必须写明「未合并」与归属模块', () => {
      const text = customerDetailIdentityText(identity);
      expect(text).toContain('未合并');
      expect(text).toContain('15.3.1.3');
      expect(text).toContain('线索');
    });

    it('🔴 绝不出现「已识别为同一人 / 已合并」这种替别人下结论的话', () => {
      const text = customerDetailIdentityText(identity);
      expect(text).not.toContain('已识别');
      expect(text).not.toContain('已合并');
      for (const candidate of identity.candidates) {
        expect(customerDetailCandidateText(candidate)).toContain('线索');
      }
    });
  });

  describe('逐段接入状态', () => {
    const source = (over: Partial<CrmCustomerDetailSourceState>): CrmCustomerDetailSourceState => ({
      source: 'ORDER',
      sourceLabel: '订单',
      available: true,
      reason: '',
      ownerModule: '9 订单',
      count: 3,
      ...over,
    });

    it('可用时给出条数', () => {
      expect(customerDetailSourceText(source({}))).toContain('3 条');
    });

    it('🔴 不可用时必须带原因 —— 没有原因就是让用户以为「本来就没有」', () => {
      const text = customerDetailSourceText(
        source({ available: false, reason: '上游读不到', count: 0 }),
      );
      expect(text).toContain('不可用');
      expect(text).toContain('上游读不到');
      expect(text).not.toContain('0 条');
    });

    it('汇总说明把读不到的来源逐条列出', () => {
      const text = customerDetailUnavailableText([
        source({}),
        source({ source: 'MESSAGE', sourceLabel: '消息', available: false, reason: '上游未接入' }),
      ]);
      expect(text).toContain('消息：上游未接入');
      expect(text).not.toContain('订单：');
    });

    it('全部可读时一句话说明', () => {
      expect(customerDetailUnavailableText([source({})])).toBe('五个来源全部可读。');
    });
  });

  describe('时间线空态', () => {
    it('还没建立 ⇒ 提示建立（并说明需要哪个权限）', () => {
      const text = customerDetailTimelineEmptyText(true, true, false);
      expect(text.title).toContain('还没有建立');
      expect(text.description).toContain('建立');
    });

    it('没有建立权限 ⇒ 说清缺哪个权限，而不是让人干等', () => {
      const text = customerDetailTimelineEmptyText(true, false, false);
      expect(text.description).toContain('crm.customers.update');
    });

    it('建立过但这个来源没有记录 ⇒ 与「还没建立」区分开', () => {
      const text = customerDetailTimelineEmptyText(false, true, true);
      expect(text.title).toContain('这个来源没有记录');
      expect(text.title).not.toContain('还没有建立');
    });
  });

  describe('重建结果', () => {
    const run = (over: Partial<CrmCustomerDetailTimelineRun>): CrmCustomerDetailTimelineRun => ({
      id: 'r1',
      runNo: 'TL-20261102-000001',
      status: 'SUCCEEDED',
      entryCount: 5,
      orderCount: 1,
      aftersalesCount: 1,
      messageCount: 1,
      reviewCount: 1,
      contactCount: 1,
      unavailableSources: {},
      truncated: false,
      failureReason: '',
      startedAt: '2026-11-02T20:00:00Z',
      finishedAt: '2026-11-02T20:00:01Z',
      ...over,
    });

    it('🔴 PARTIAL 与 SUCCEEDED 是两句话，不能都画成「完成」', () => {
      expect(customerDetailRunText(run({}))).toContain('五个来源全部读到了');
      const partial = customerDetailRunText(
        run({ status: 'PARTIAL', unavailableSources: { ORDER: '上游读不到' } }),
      );
      expect(partial).toContain('部分来源没读到');
      expect(partial).toContain('上游读不到');
      expect(partial).not.toContain('五个来源全部读到了');
    });

    it('截断必须说出来（时间线不是全量）', () => {
      expect(customerDetailRunText(run({ truncated: true }))).toContain('不是全量');
    });

    it('还没建立过时不是空串，而是一句人话', () => {
      expect(customerDetailRunText(undefined)).toContain('还没有建立过');
    });
  });

  describe('时间线条目', () => {
    const entry: CrmCustomerDetailTimelineEntry = {
      id: 'e1',
      sourceKind: 'ORDER',
      sourceKindLabel: '订单',
      sourceRef: 'o1',
      sourceModule: '9',
      occurredAt: '2026-11-01T10:00:00+08:00',
      title: '订单 SO-1',
      summary: 'AMAZON · 100.00 USD',
      status: 'CONFIRMED',
      category: 'AMAZON',
      amount: 100,
      currency: 'USD',
      quantity: 2,
      payload: {},
    };

    it('标题与摘要合成一段人话', () => {
      expect(customerDetailEntryText(entry)).toContain('订单 SO-1');
      expect(customerDetailEntryText(entry)).toContain('100.00 USD');
    });

    it('没有摘要时退回来源名，不留空白标题', () => {
      expect(customerDetailEntryText({ ...entry, summary: '' })).toContain('订单');
    });
  });
});
