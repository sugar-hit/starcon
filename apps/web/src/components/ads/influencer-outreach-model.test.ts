import { describe, expect, it } from 'vitest';
import {
  batchResultLabel,
  commissionScopeLabel,
  commissionTypeLabel,
  configStatusLabel,
  emptyInfluencerCommissionRuleInput,
  emptyInfluencerOutreachInput,
  emptyInfluencerOutreachSegmentInput,
  emptyInfluencerOutreachTemplateInput,
  emptyInfluencerSampleOrderInput,
  financeText,
  followupTypeLabel,
  outboundText,
  outreachCount,
  outreachMoney,
  outreachRatio,
  outreachStatusLabel,
  placeholdersOf,
  sampleOrderStatusLabel,
  shipmentStatusLabel,
  trackingText,
  validateInfluencerCommissionRule,
  validateInfluencerOutreach,
  validateInfluencerOutreachSegment,
  validateInfluencerOutreachTemplate,
  validateInfluencerSampleOrder,
} from './influencer-outreach-model';

/**
 * 14.5.1.2 建联与样品 前端模型单测。
 *
 * 🔴 钉住这一页最容易做丢的几件事：
 *
 * 1. **「没录」不是 0**：`null` 显示「未录入」，不是 0.00。
 * 2. **下游缺口说清归谁**：轨迹不可查归 10 物流、计提未入财务归 12 财务、
 *    仓库单号待 8 仓库交付后回填。
 * 3. **模板占位符是闭合词表**：未登记的占位符在提交前就被指出来。
 * 4. **混合佣金形态被拒**（先给 5% 再减 20 美元算不清）。
 * 5. **幂等键必填**（重放同一条不会发第二遍）。
 */
describe('14.5.1.2 建联与样品 · 前端模型', () => {
  describe('「没录」不是 0', () => {
    it('金额 null 显示「未录入」而不是 0.00', () => {
      expect(outreachMoney(null)).toBe('未录入');
      expect(outreachMoney(undefined)).toBe('未录入');
      expect(outreachMoney(0)).toBe('0.00');
      expect(outreachMoney(0, 'USD')).toBe('0.00 USD');
      expect(outreachMoney(12.5, 'USD')).toBe('12.50 USD');
    });

    it('比率 null 显示「未录入」而不是 0.00%', () => {
      expect(outreachRatio(null)).toBe('未录入');
      expect(outreachRatio(undefined)).toBe('未录入');
      expect(outreachRatio(0)).toBe('0.00%');
      expect(outreachRatio(0.1)).toBe('10.00%');
    });

    it('数量 null 显示「未录入」而不是 0', () => {
      expect(outreachCount(null)).toBe('未录入');
      expect(outreachCount(undefined)).toBe('未录入');
      expect(outreachCount(2)).toBe('2');
    });
  });

  describe('下游缺口说清归谁', () => {
    it('物流轨迹恒为「不可查」并标出责任模块', () => {
      expect(trackingText({ trackingAvailable: false, trackingOwnerModule: '10 物流' })).toBe(
        '轨迹不可查（归 10 物流 管）',
      );
      expect(trackingText({ trackingAvailable: true, trackingOwnerModule: '10 物流' })).toBe('轨迹可查');
    });

    it('财务恒为「未进费用归集」并标出责任模块', () => {
      expect(financeText({ financePosted: false, financeOwnerModule: '12 财务' })).toBe(
        '未进费用归集（归 12 财务 管）',
      );
      expect(financeText({ financePosted: true, financeOwnerModule: '12 财务' })).toBe('已入财务');
    });

    it('出库显示本地事实 + 仓库单号待回填', () => {
      expect(
        outboundText({
          outboundStatus: 'LOCAL_PENDING',
          outboundAt: null,
          warehouseOutboundRef: '',
          warehouseOwnerModule: '8 仓库',
        }),
      ).toBe('本地待出库 · 仓库单号待 8 仓库 交付后回填');
      expect(
        outboundText({
          outboundStatus: 'LOCAL_OUTBOUNDED',
          outboundAt: '2026-10-05T10:00:00Z',
          warehouseOutboundRef: 'WH-1',
          warehouseOwnerModule: '8 仓库',
        }),
      ).toContain('本地已记出库');
      expect(
        outboundText({
          outboundStatus: 'LOCAL_OUTBOUNDED',
          outboundAt: '2026-10-05T10:00:00Z',
          warehouseOutboundRef: 'WH-1',
          warehouseOwnerModule: '8 仓库',
        }),
      ).toContain('仓库单号 WH-1');
    });
  });

  describe('状态标签', () => {
    it('建联状态有中文说明', () => {
      expect(outreachStatusLabel('DRAFT')).toBe('草稿（还没发出去）');
      expect(outreachStatusLabel('SENT')).toBe('已发送');
      expect(outreachStatusLabel('CANCELLED')).toBe('已取消');
      expect(outreachStatusLabel(undefined)).toBe('—');
    });

    it('样品与包裹状态有中文说明', () => {
      expect(sampleOrderStatusLabel('REQUESTED')).toBe('已申请（待出库）');
      expect(sampleOrderStatusLabel('SIGNED')).toBe('已签收');
      expect(shipmentStatusLabel('LOST')).toBe('丢件');
      expect(shipmentStatusLabel('RETURNED')).toBe('已退回');
    });

    it('跟进方式 / 佣金形态 / 适用范围 / 配置状态有中文说明', () => {
      expect(followupTypeLabel('SAMPLE_FEEDBACK')).toBe('样品反馈');
      expect(commissionTypeLabel('RATE')).toBe('按比例');
      expect(commissionTypeLabel('FIXED')).toBe('固定额');
      expect(commissionScopeLabel('SEGMENT')).toBe('按分群');
      expect(configStatusLabel('PAUSED')).toBe('停用');
    });

    it('批量建联结果码有中文说明', () => {
      expect(batchResultLabel('CREATED')).toBe('已生成草稿');
      expect(batchResultLabel('SKIPPED_OPEN')).toBe('跳过（已有未走完的邀约）');
      expect(batchResultLabel('SKIPPED_DUPLICATE')).toBe('跳过（同批次已建过）');
    });
  });

  describe('模板占位符是闭合词表', () => {
    const variables = [
      { name: 'display_name', label: '达人名称', required: true },
      { name: 'handle', label: '达人账号', required: false },
    ];

    it('能抽出模板里的占位符', () => {
      expect(placeholdersOf('你好 {{display_name}}，@{{handle}}')).toEqual([
        'display_name',
        'handle',
      ]);
      expect(placeholdersOf('没有占位符')).toEqual([]);
    });

    it('未登记的占位符在提交前就被指出来', () => {
      const result = validateInfluencerOutreachTemplate(
        {
          templateCode: 'T1',
          templateName: '模板',
          platformId: 'p1',
          subject: '',
          body: '你好 {{达人名字}}',
          status: 'ACTIVE',
          note: '',
        },
        variables,
      );
      expect(result.ok).toBe(false);
      expect(result.errors.join()).toContain('未登记的占位符：达人名字');
    });

    it('闭合词表里的占位符通过校验', () => {
      const result = validateInfluencerOutreachTemplate(
        {
          templateCode: 'T1',
          templateName: '模板',
          platformId: 'p1',
          subject: '合作邀请',
          body: '你好 {{display_name}}',
          status: 'ACTIVE',
          note: '',
        },
        variables,
      );
      expect(result.ok).toBe(true);
      expect(result.errors).toEqual([]);
    });

    it('必填项缺失会被指出来', () => {
      const result = validateInfluencerOutreachTemplate(
        {
          templateCode: '',
          templateName: '',
          platformId: '',
          subject: '',
          body: '',
          status: 'ACTIVE',
          note: '',
        },
        variables,
      );
      expect(result.ok).toBe(false);
      expect(result.errors.join()).toContain('模板编码必填');
      expect(result.errors.join()).toContain('模板正文必填');
    });
  });

  describe('筛选条件校验', () => {
    it('编码与名称必填', () => {
      const result = validateInfluencerOutreachSegment(emptyInfluencerOutreachSegmentInput());
      expect(result.ok).toBe(false);
      expect(result.errors.join()).toContain('筛选条件编码必填');
    });

    it('互动率下限必须在 0 ~ 1 之间', () => {
      const result = validateInfluencerOutreachSegment({
        ...emptyInfluencerOutreachSegmentInput(),
        segmentCode: 'S1',
        segmentName: '分群',
        minEngagementRate: '1.5',
      });
      expect(result.ok).toBe(false);
      expect(result.errors.join()).toContain('互动率下限');
    });

    it('条件全对时通过', () => {
      const result = validateInfluencerOutreachSegment({
        ...emptyInfluencerOutreachSegmentInput(),
        segmentCode: 'S1',
        segmentName: '分群',
        minEngagementRate: '0.05',
        minGmv: '1000',
        minFollowerCount: '10000',
      });
      expect(result.ok).toBe(true);
    });
  });

  describe('建联记录校验', () => {
    it('幂等键必填', () => {
      const result = validateInfluencerOutreach(emptyInfluencerOutreachInput());
      expect(result.ok).toBe(false);
      expect(result.errors.join()).toContain('幂等键必填');
    });

    it('全填时通过', () => {
      const result = validateInfluencerOutreach({
        influencerId: 'i1',
        segmentId: 's1',
        templateId: 't1',
        idempotencyKey: 'k1',
        note: '',
      });
      expect(result.ok).toBe(true);
    });
  });

  describe('佣金规则校验', () => {
    it('混合形态被拒（先给 5% 再减 20 美元算不清）', () => {
      const result = validateInfluencerCommissionRule({
        ...emptyInfluencerCommissionRuleInput(),
        ruleCode: 'R1',
        ruleName: '规则',
        commissionType: 'RATE',
        commissionRate: '0.1',
        fixedAmount: '20',
      });
      expect(result.ok).toBe(false);
      expect(result.errors.join()).toContain('混合形态');
    });

    it('比例形态的固定额形态都要求对应字段', () => {
      const rateMissing = validateInfluencerCommissionRule({
        ...emptyInfluencerCommissionRuleInput(),
        ruleCode: 'R1',
        ruleName: '规则',
        commissionType: 'RATE',
        commissionRate: '',
      });
      expect(rateMissing.ok).toBe(false);
      expect(rateMissing.errors.join()).toContain('按比例时必须填佣金比例');

      const fixedMissing = validateInfluencerCommissionRule({
        ...emptyInfluencerCommissionRuleInput(),
        ruleCode: 'R1',
        ruleName: '规则',
        commissionType: 'FIXED',
        commissionRate: '',
        fixedAmount: '',
      });
      expect(fixedMissing.ok).toBe(false);
      expect(fixedMissing.errors.join()).toContain('固定额时必须填固定金额');
    });

    it('比例超范围被拒', () => {
      const result = validateInfluencerCommissionRule({
        ...emptyInfluencerCommissionRuleInput(),
        ruleCode: 'R1',
        ruleName: '规则',
        commissionType: 'RATE',
        commissionRate: '1.5',
      });
      expect(result.ok).toBe(false);
      expect(result.errors.join()).toContain('佣金比例必须在');
    });

    it('按分群 / 按达人时必须选对应对象', () => {
      const segment = validateInfluencerCommissionRule({
        ...emptyInfluencerCommissionRuleInput(),
        ruleCode: 'R1',
        ruleName: '规则',
        scopeType: 'SEGMENT',
        segmentId: '',
      });
      expect(segment.ok).toBe(false);
      expect(segment.errors.join()).toContain('按分群时必须选筛选条件');

      const influencer = validateInfluencerCommissionRule({
        ...emptyInfluencerCommissionRuleInput(),
        ruleCode: 'R1',
        ruleName: '规则',
        scopeType: 'INFLUENCER',
        influencerId: '',
      });
      expect(influencer.ok).toBe(false);
      expect(influencer.errors.join()).toContain('按达人时必须选达人');
    });
  });

  describe('样品单校验', () => {
    it('SKU 编码与建联记录必填', () => {
      const result = validateInfluencerSampleOrder(emptyInfluencerSampleOrderInput());
      expect(result.ok).toBe(false);
      expect(result.errors.join()).toContain('建联记录必填');
      expect(result.errors.join()).toContain('样品 SKU 编码必填');
    });

    it('数量必须大于 0，成本不能是负数', () => {
      const quantity = validateInfluencerSampleOrder({
        ...emptyInfluencerSampleOrderInput(),
        outreachId: 'o1',
        skuCode: 'SKU-1',
        quantity: '0',
      });
      expect(quantity.ok).toBe(false);
      expect(quantity.errors.join()).toContain('样品数量必须大于 0');

      const value = validateInfluencerSampleOrder({
        ...emptyInfluencerSampleOrderInput(),
        outreachId: 'o1',
        skuCode: 'SKU-1',
        sampleValue: '-1',
      });
      expect(value.ok).toBe(false);
      expect(value.errors.join()).toContain('样品成本不能是负数');
    });

    it('成本留空 = 未录入，不算错', () => {
      const result = validateInfluencerSampleOrder({
        ...emptyInfluencerSampleOrderInput(),
        outreachId: 'o1',
        skuCode: 'SKU-1',
        sampleValue: '',
      });
      expect(result.ok).toBe(true);
    });
  });

  describe('表单初值', () => {
    it('默认排除黑名单里的达人', () => {
      expect(emptyInfluencerOutreachSegmentInput().excludeBlacklisted).toBe(true);
    });

    it('模板与佣金规则初值可用', () => {
      expect(emptyInfluencerOutreachTemplateInput().status).toBe('ACTIVE');
      expect(emptyInfluencerCommissionRuleInput().commissionType).toBe('RATE');
    });
  });
});
