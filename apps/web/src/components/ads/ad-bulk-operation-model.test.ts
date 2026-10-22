import { describe, expect, it } from 'vitest';
import {
  bulkGuardrailText,
  bulkItemResultLabel,
  bulkItemResultTone,
  bulkMoney,
  bulkOperationLabel,
  bulkTargetLabel,
  bulkTargetName,
  bulkTaskStatusLabel,
  bulkTaskStatusTone,
  bulkTimestamp,
  bulkVerdictLabel,
  bulkVerdictTone,
  emptyAdBulkFormInput,
  validateAdBulkForm,
} from './ad-bulk-operation-model';
import type { AdBulkGuardrails } from './ad-bulk-operation-model';

/**
 * 14.1.1.3 批量操作 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的几件事**：
 *
 * 1. **「已生效」不能渲染成「平台侧已生效」**。平台回写队列未交付 ⇒ 本地改了、平台没改。
 * 2. **超上限是整批拒绝**，标签上必须能看出来它与「这一条被业务规则拒」不是一回事。
 * 3. **竞价落在广告组上**：标签与落点不许写成活动级。
 * 4. **表单校验挡住不存在的组合**（竞价配活动落点、ADJUST_BID 同时给目标和增减额）。
 * 5. **护栏上限要能显示成一句话**：界面上要写明「本次最多 N 个」，而不是让人撞墙才知道。
 */

const guardrails: AdBulkGuardrails = {
  maxSingleBudgetDelta: 5000,
  maxTotalBudgetDelta: 20000,
  maxCampaignBudget: 100000,
  maxBid: 1000,
  maxSingleBidDelta: 200,
  maxTotalBidDelta: 20000,
  maxCampaignsPerTask: 200,
  maxGroupsPerTask: 500,
  maxSelectablePerFilter: 1000,
  maxPreviewItems: 100,
  note: '护栏说明',
};

describe('14.1.1.3 批量操作 · 操作与落点标签', () => {
  it('六种操作都有中文标签', () => {
    expect(bulkOperationLabel('ADJUST_BUDGET')).toBe('预算增减');
    expect(bulkOperationLabel('SET_BUDGET')).toBe('设为预算');
    expect(bulkOperationLabel('PAUSE')).toBe('暂停');
    expect(bulkOperationLabel('ENABLE')).toBe('启用');
    expect(bulkOperationLabel('SET_END_AT')).toBe('设置结束时间');
    expect(bulkOperationLabel('ADJUST_BID')).toBe('调整竞价');
  });

  it('🔴 竞价的落点是广告组 —— 标签不许写成活动', () => {
    expect(bulkTargetLabel('AD_GROUP')).toBe('广告组');
    expect(bulkTargetLabel('CAMPAIGN')).toBe('活动');
  });

  it('🔴 广告组落点的目标名要带组名（只显示活动名会让人以为改的是活动）', () => {
    expect(
      bulkTargetName({ targetType: 'AD_GROUP', campaignName: '活动 A', groupName: '组 1' }),
    ).toBe('活动 A / 组 1');
    expect(bulkTargetName({ targetType: 'CAMPAIGN', campaignName: '活动 A' })).toBe('活动 A');
  });
});

describe('14.1.1.3 批量操作 · 任务状态与结果', () => {
  it('🔴 REJECTED 的说法是「整批被拒（一条都没执行）」，不能与「部分完成」混为一谈', () => {
    expect(bulkTaskStatusLabel('REJECTED')).toContain('整批被拒');
    expect(bulkTaskStatusLabel('PARTIAL')).toContain('部分完成');
    expect(bulkTaskStatusTone('REJECTED')).toBe('text-destructive');
    expect(bulkTaskStatusTone('FAILED')).toBe('text-destructive');
    expect(bulkTaskStatusTone('DONE')).toBe('text-emerald-600');
  });

  it('逐条结果三种说法分开', () => {
    expect(bulkItemResultLabel('APPLIED')).toBe('已生效');
    expect(bulkItemResultLabel('REJECTED')).toBe('被拒');
    expect(bulkItemResultLabel('SKIPPED')).toBe('跳过');
    expect(bulkItemResultTone('REJECTED')).toBe('text-destructive');
  });

  it('🔴 预览里「越上限」与「这一条被业务规则拒」是两件事，标签与语气都要分开', () => {
    expect(bulkVerdictLabel('REJECT_LIMIT')).toContain('整批拒绝');
    expect(bulkVerdictLabel('REJECT')).toBe('这一条会被拒');
    expect(bulkVerdictTone('REJECT_LIMIT')).toBe('text-destructive');
    expect(bulkVerdictTone('APPLY')).toBe('text-emerald-600');
  });
});

describe('14.1.1.3 批量操作 · 数值与时间展示', () => {
  it('金额为空（null）显示「—」，不是 0', () => {
    expect(bulkMoney(null)).toBe('—');
    expect(bulkMoney(undefined)).toBe('—');
    expect(bulkMoney(123.5, 'USD')).toBe('123.50 USD');
  });

  it('时间为空显示「—」', () => {
    expect(bulkTimestamp(null)).toBe('—');
    expect(bulkTimestamp('')).toBe('—');
    expect(bulkTimestamp('2026-10-01T09:00:00+08:00')).not.toBe('—');
  });
});

describe('14.1.1.3 批量操作 · 护栏上限要能显示成一句话', () => {
  it('🔴 界面上要能写明「本次最多 N 个」', () => {
    const text = bulkGuardrailText(guardrails);
    expect(text).toContain('200');
    expect(text).toContain('500');
    expect(text).toContain('1000');
    expect(text).toContain('20000');
  });

  it('护栏还没加载时返回空串而不是抛错', () => {
    expect(bulkGuardrailText(null)).toBe('');
  });
});

describe('14.1.1.3 批量操作 · 表单校验', () => {
  it('默认表单是预算增减 + 活动落点', () => {
    expect(emptyAdBulkFormInput.operation).toBe('ADJUST_BUDGET');
    expect(emptyAdBulkFormInput.targetType).toBe('CAMPAIGN');
    expect(validateAdBulkForm(emptyAdBulkFormInput).ok).toBe(false);
  });

  it('🔴 竞价的落点必须是广告组', () => {
    const result = validateAdBulkForm({
      ...emptyAdBulkFormInput,
      operation: 'ADJUST_BID',
      targetType: 'CAMPAIGN',
      bid: '3',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join('；')).toContain('广告组');
  });

  it('🔴 ADJUST_BID 的目标竞价与增减额二选一', () => {
    const both = validateAdBulkForm({
      ...emptyAdBulkFormInput,
      operation: 'ADJUST_BID',
      targetType: 'AD_GROUP',
      bid: '3',
      bidDelta: '1',
    });
    expect(both.ok).toBe(false);
    expect(both.errors.join('；')).toContain('二选一');

    const onlyBid = validateAdBulkForm({
      ...emptyAdBulkFormInput,
      operation: 'ADJUST_BID',
      targetType: 'AD_GROUP',
      bid: '3',
    });
    expect(onlyBid.ok).toBe(true);
  });

  it('广告组落点只用于竞价调整', () => {
    const result = validateAdBulkForm({
      ...emptyAdBulkFormInput,
      operation: 'PAUSE',
      targetType: 'AD_GROUP',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join('；')).toContain('广告组落点只用于竞价调整');
  });

  it('预算增减额不能是 0，目标预算不能为负', () => {
    expect(
      validateAdBulkForm({ ...emptyAdBulkFormInput, operation: 'ADJUST_BUDGET', delta: '0' }).ok,
    ).toBe(false);
    expect(
      validateAdBulkForm({ ...emptyAdBulkFormInput, operation: 'SET_BUDGET', budget: '-1' }).ok,
    ).toBe(false);
  });

  it('结束时间要么给时刻，要么勾清空', () => {
    expect(validateAdBulkForm({ ...emptyAdBulkFormInput, operation: 'SET_END_AT' }).ok).toBe(false);
    expect(
      validateAdBulkForm({
        ...emptyAdBulkFormInput,
        operation: 'SET_END_AT',
        clearEndAt: true,
      }).ok,
    ).toBe(true);
    expect(
      validateAdBulkForm({
        ...emptyAdBulkFormInput,
        operation: 'SET_END_AT',
        endAt: '2026-12-31T23:59:59+08:00',
      }).ok,
    ).toBe(true);
  });

  it('暂停 / 启用不需要任何数值参数', () => {
    expect(validateAdBulkForm({ ...emptyAdBulkFormInput, operation: 'PAUSE' }).ok).toBe(true);
    expect(validateAdBulkForm({ ...emptyAdBulkFormInput, operation: 'ENABLE' }).ok).toBe(true);
  });
});
