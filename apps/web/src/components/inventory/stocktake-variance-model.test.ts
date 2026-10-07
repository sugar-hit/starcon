import { describe, expect, it } from 'vitest';
import {
  adjustmentStatusLabels,
  adjustmentTone,
  attributionProblem,
  createAdjustmentProblem,
  decisionLabels,
  dispositionText,
  lineFilterLabels,
  progressPercent,
  progressText,
  qty,
  rateText,
  rateTone,
  reasonLabels,
  varianceText,
} from './stocktake-variance-model';

/**
 * 7.4.2.2 盘点差异处理前端纯函数。
 *
 * <p>重点钉住三件「错了会误导人」的事：
 *
 * <ul>
 *   <li>**率的分母为 0 不能显示成 0%**。0% 会被读成「没有差异」，而真实含义是
 *       「账面合计为 0，这一盘盘不出率」。后台返回的是 null，前端必须把它当第三种状态。
 *   <li>**忽略/复盘不是「已解决」**。处置文案要让处理人一眼看出这条差异到底被怎么了。
 *   <li>**忽略必须写够长的理由**。界面上先拦住，用户就不用等一个 422。
 * </ul>
 */
describe('率的展示：null 是第三种状态，不是 0%', () => {
  it('分母为 0 时说「不可计算」而不是 0.00%', () => {
    expect(rateText(null)).toBe('不可计算（分母为 0）');
    expect(rateText(undefined)).toBe('不可计算（分母为 0）');
    expect(rateText(null)).not.toContain('0%');
  });

  it('真的有率时带方向与小数位', () => {
    expect(rateText(66.6667)).toBe('66.6667%');
    expect(rateText(2.4242)).toBe('2.4242%');
    expect(rateText(0)).toBe('0.00%');
  });

  it('色调：null 是中性，0% 才是最值得警惕的', () => {
    expect(rateTone(null)).toBe('neutral');
    expect(rateTone(0)).toBe('warn');
    expect(rateTone(3.5)).toBe('bad');
  });
});

describe('差异与处置文案', () => {
  it('未录入显示破折号而不是 0', () => {
    expect(qty(null)).toBe('—');
    expect(varianceText(null)).toBe('未录入');
  });

  it('差异带方向', () => {
    expect(varianceText(4)).toBe('盘盈 4');
    expect(varianceText(-4)).toBe('盘亏 4');
    expect(varianceText(0)).toBe('账实相符');
  });

  it('处置状态区分未归因/待建单/调整中/已入账/已复盘/已忽略', () => {
    expect(dispositionText({ varianceDecision: null })).toBe('未归因');
    expect(dispositionText({ varianceDecision: 'ADJUST' })).toBe('待生成调整单');
    expect(
      dispositionText({
        varianceDecision: 'ADJUST',
        adjustmentStatus: 'PENDING_APPROVAL',
      }),
    ).toBe('调整单 待审批');
    expect(
      dispositionText({ varianceDecision: 'ADJUST', adjustmentStatus: 'DRAFT', postedAt: '2026-10-07T10:00:00Z' }),
    ).toBe('已入账');
    expect(dispositionText({ varianceDecision: 'RECOUNT' })).toBe('已复盘（待重新盘）');
    expect(dispositionText({ varianceDecision: 'IGNORE' })).toBe('已忽略（留痕）');
  });
});

describe('归因输入校验', () => {
  it('原因与决定必选', () => {
    expect(attributionProblem('', 'ADJUST', '说明')).toContain('必须选择差异原因');
    expect(attributionProblem('SHRINKAGE', '', '说明')).toContain('必须选择处理决定');
  });

  it('说明必填，且忽略至少 4 个字', () => {
    expect(attributionProblem('SHRINKAGE', 'ADJUST', '  ')).toContain('判断依据');
    expect(attributionProblem('TIMING', 'IGNORE', '  ')).toContain('忽略必须写理由');
    expect(attributionProblem('TIMING', 'IGNORE', '无')).toContain('至少 4 个字');
    // 复盘不要求 4 个字，但它也必须写理由。
    expect(attributionProblem('MISSHIP', 'RECOUNT', '重盘')).toBeNull();
  });

  it('超长说明被拦下', () => {
    expect(attributionProblem('SHRINKAGE', 'ADJUST', 'x'.repeat(501))).toContain('500');
  });

  it('合法组合通过', () => {
    expect(attributionProblem('SHRINKAGE', 'ADJUST', '搬运破损两件')).toBeNull();
    expect(attributionProblem('TIMING', 'IGNORE', '冻结之后正常发运走了，不是损耗')).toBeNull();
  });
});

describe('生成调整单', () => {
  it('一行都没选且没勾「全部」时被拦下', () => {
    expect(createAdjustmentProblem(false, [])).toContain('请至少选一行差异');
    expect(createAdjustmentProblem(false, ['a'])).toBeNull();
    expect(createAdjustmentProblem(true, [])).toBeNull();
  });
});

describe('枚举与单据状态', () => {
  it('五类原因、三种决定、五种筛选都在', () => {
    expect(Object.keys(reasonLabels)).toHaveLength(5);
    expect(Object.keys(decisionLabels)).toHaveLength(3);
    expect(Object.keys(lineFilterLabels)).toHaveLength(5);
  });

  it('待审批与已批待入账是最需要注意的中间态', () => {
    expect(adjustmentStatusLabels.PENDING_APPROVAL).toBe('待审批');
    expect(adjustmentStatusLabels.APPROVED).toBe('已批待入账');
    expect(adjustmentTone('PENDING_APPROVAL')).toBe('warn');
    expect(adjustmentTone('APPROVED')).toBe('warn');
    expect(adjustmentTone('REJECTED')).toBe('bad');
    expect(adjustmentTone('POSTED')).toBe('ok');
  });

  it('进度在总量为 0 时不显示成 100%', () => {
    expect(progressText(0, 0)).toBe('0 / 0');
    expect(progressPercent(0, 0)).toBe(0);
    expect(progressPercent(1, 3)).toBe(33);
  });
});
