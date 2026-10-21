import { describe, expect, it } from 'vitest';
import {
  actionBlockedReason,
  APPEAL_RESULT_OPTIONS,
  APPEAL_STATUS_OPTIONS,
  coverageText,
  formatAmount,
  isTerminalStatus,
  SOURCE_MODULE_OPTIONS,
  sourceText,
  SUBMISSION_OUTCOME_OPTIONS,
  SUBMISSION_STATUS_OPTIONS,
  windowText,
  windowTone,
} from './platform-dispute-appeal-model';

/**
 * 11.4.1.2 证据与申诉的前端单元测试。
 *
 * <p>测的是**蓝图那条加粗句**「<b>举证材料必须来自系统记录</b>（轨迹、沟通日志），
 * 临时拼凑的证据在申诉中往往不被采信」在界面上是不是真的成立：
 *
 * <ul>
 *   <li><b>来源只有四种「系统记录」，没有「上传文件」这一档</b> ——
 *       取值域本身就是一条硬约束的落点。</li>
 *   <li><b>「能不能提交」只认后端判据</b>：界面上的窗口状态直接透传
 *       DB 判据函数的输出，**不自己算时限**（所以没有倒计时函数可测 ——
 *       那是刻意的：前端算一遍就多了一个可能与服务层漂移的口径）。</li>
 *   <li><b>「清单齐了」与「包里覆盖了」是两个数字</b>：分开显示，
 *       否则提交被拒时人不知道该补什么。</li>
 * </ul>
 */

describe('举证材料：只有系统记录，没有上传文件', () => {
  it('来源取值域只有四种系统记录', () => {
    expect(SOURCE_MODULE_OPTIONS.map((o) => o.code)).toEqual([
      'SHIPMENT_TRACKING_OBSERVATION',
      'AFTERSALES_MESSAGE',
      'AFTERSALES_ATTACHMENT',
      'QUALITY_INSPECTION_ORDER',
    ]);
  });

  it('没有一个来源叫「上传文件」或「任意附件」', () => {
    for (const o of SOURCE_MODULE_OPTIONS) {
      expect(o.code).not.toMatch(/UPLOAD|ARBITRARY|FILE_UPLOAD/i);
    }
  });

  it('来源展示带上单号与时刻（快照可复核）', () => {
    const text = sourceText({
      sourceModule: 'AFTERSALES_ATTACHMENT',
      sourceModuleLabel: '售后单附件（商品照片 / 凭证）',
      sourceRefNo: 'SRC-1',
      sourceLabel: 'photo.jpg',
      sourceOccurredAt: '2026-03-01T08:00:00+08:00',
    });
    expect(text).toContain('售后单附件');
    expect(text).toContain('SRC-1');
    expect(text).toContain('photo.jpg');
  });
});

describe('提交窗口：只认后端判据，不自己算', () => {
  it('窗口状态直接透传后端给的判据结果', () => {
    expect(windowText('OPEN', '窗口开着')).toBe('可以提交申诉');
    expect(windowTone('OPEN')).toBe('success');
    expect(windowText('EXPIRED', '举证时限已过')).toContain('举证时限已过');
    expect(windowTone('EXPIRED')).toBe('danger');
    expect(windowText('RELEASED', '举证已提交，时限解除')).toContain('时限解除');
    expect(windowText('CLOSED', '纠纷已终结')).toContain('纠纷已终结');
  });

  it('未知状态不炸，退回中性色调', () => {
    expect(windowTone('SOMETHING_NEW')).toBe('neutral');
    expect(windowText('SOMETHING_NEW', '未知')).toBe('未知');
    expect(windowText(null, null)).toBe('未知');
  });
});

describe('举证覆盖：两个数字分开显示', () => {
  const gate = {
    checklistItemCount: 3,
    missingRequiredCount: 1,
    coveredItemCount: 2,
    uncoveredRequiredCount: 0,
  };

  it('清单没列出来时先说清楚', () => {
    expect(
      coverageText({
        checklistItemCount: 0,
        missingRequiredCount: 0,
        coveredItemCount: 0,
        uncoveredRequiredCount: 0,
      }),
    ).toBe('上游还没列举证清单');
  });

  it('必举证缺项与未覆盖项分别报出来', () => {
    const text = coverageText(gate);
    expect(text).toContain('清单 3 项');
    expect(text).toContain('必举证缺 1 项');
    expect(text).not.toContain('已被材料覆盖');
  });

  it('齐了才说「已覆盖」', () => {
    const text = coverageText({
      checklistItemCount: 2,
      missingRequiredCount: 0,
      coveredItemCount: 2,
      uncoveredRequiredCount: 0,
    });
    expect(text).toContain('已覆盖 2 项');
    expect(text).not.toContain('缺');
  });

  it('清单说齐了但包里没有材料 ⇒ 单独报「未被材料覆盖」', () => {
    const text = coverageText({
      checklistItemCount: 2,
      missingRequiredCount: 0,
      coveredItemCount: 0,
      uncoveredRequiredCount: 2,
    });
    expect(text).toContain('未被材料覆盖 2 项');
    expect(text).not.toContain('已覆盖');
  });
});

describe('动作清单：每条都带「为什么不能做」', () => {
  const actions = [
    { code: 'SUBMIT', allowed: true, blockingReason: null },
    { code: 'ATTACH_MATERIAL', allowed: false, blockingReason: '申诉单已是「已提交平台」，材料包已冻结' },
  ];

  it('允许的动作没有阻塞原因', () => {
    expect(actionBlockedReason(actions, 'SUBMIT')).toBeNull();
  });

  it('不允许的动作给出后端给的原因', () => {
    expect(actionBlockedReason(actions, 'ATTACH_MATERIAL')).toContain('材料包已冻结');
  });

  it('动作清单里没有这个动作 ⇒ 也算有原因（不静默放行）', () => {
    expect(actionBlockedReason(actions, 'NOT_IN_LIST')).toContain('不在当前模块的动作清单里');
  });
});

describe('状态与取值域', () => {
  it('四个终态不可再改', () => {
    expect(isTerminalStatus('RESOLVED_WON')).toBe(true);
    expect(isTerminalStatus('RESOLVED_LOST')).toBe(true);
    expect(isTerminalStatus('RESOLVED_PARTIAL')).toBe(true);
    expect(isTerminalStatus('WITHDRAWN')).toBe(true);
    expect(isTerminalStatus('SUBMITTED')).toBe(false);
    expect(isTerminalStatus('DRAFT')).toBe(false);
  });

  it('结果三档是胜诉 / 败诉 / 部分', () => {
    expect(APPEAL_RESULT_OPTIONS.map((o) => o.code)).toEqual(['WON', 'LOST', 'PARTIAL']);
  });

  it('申诉状态含「部分支持」这一档（11.4.1.1 层没有）', () => {
    expect(APPEAL_STATUS_OPTIONS.map((o) => o.code)).toContain('RESOLVED_PARTIAL');
  });

  it('提交状态机的四档都在', () => {
    expect(SUBMISSION_STATUS_OPTIONS.map((o) => o.code)).toEqual([
      'PENDING',
      'SENT',
      'ACKNOWLEDGED',
      'FAILED',
    ]);
    expect(SUBMISSION_OUTCOME_OPTIONS.map((o) => o.code)).toEqual([
      'SENT',
      'FAILED',
      'ACKNOWLEDGED',
    ]);
  });
});

describe('金额', () => {
  it('金额带币种，缺失显示破折号', () => {
    expect(formatAmount(88.5, 'USD')).toBe('88.50 USD');
    expect(formatAmount(null, 'USD')).toBe('—');
    expect(formatAmount(undefined)).toBe('—');
  });
});
