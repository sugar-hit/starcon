import { describe, expect, it } from 'vitest';
import {
  aftersalesIntakeQuery,
  eligibilityBadgeClass,
  eligibilityPresentation,
  humanizeHours,
  mustRefundBadgeClass,
  mustRefundBasisText,
  mustRefundPresentation,
  orderAnchorText,
  ownerModeText,
  replyDueBasisText,
  windowMarginText,
} from './aftersales-intake-model';

/**
 * 11.1.1.3 售后受理前端模型的展示口径测试。
 *
 * <p>与同目录 `aftersales-case-model.test.ts` 同一手法：**逐个钉住那些「判错也不会报错」的分支**。
 * 本模块的判定全部来自后端冻结凭据，前端只负责呈现，所以前端真正的风险是
 * 「把一件严重的事显示成一件不起眼的事」——
 * 必退超时被显示成普通、超窗被显示成合规、没配 SLA 被显示成「正常」。
 */

describe('必退语气（蓝图加粗句的 UI 落点）', () => {
  it('必退且已超时是 rank 0 —— 打开页面第一眼就该是它', () => {
    const p = mustRefundPresentation(true, true);
    expect(p.rank).toBe(0);
    expect(p.tone).toBe('must-overdue');
    expect(p.badge).toBe('必退超时');
  });

  it('必退未超时与必退超时是**两个不同的 tone**，不能只改文字', () => {
    expect(mustRefundPresentation(true, false).tone).toBe('must');
    expect(mustRefundPresentation(true, true).tone).not.toBe(
      mustRefundPresentation(true, false).tone,
    );
    expect(mustRefundBadgeClass(true, true)).not.toBe(mustRefundBadgeClass(true, false));
  });

  it('非必退 rank 排在必退之后', () => {
    expect(mustRefundPresentation(false, false).rank).toBeGreaterThan(
      mustRefundPresentation(true, false).rank,
    );
  });

  it('判定未知单独一档，且 rank 在必退之后、合规之前', () => {
    const unknown = mustRefundPresentation(null, false);
    expect(unknown.rank).toBeGreaterThan(mustRefundPresentation(true, false).rank);
    expect(unknown.rank).toBeLessThan(mustRefundPresentation(false, false).rank);
    expect(mustRefundPresentation(undefined, false).rank).toBe(unknown.rank);
  });
});

describe('必退依据必须说清来源与可推翻性', () => {
  it('平台声明与原因分类命中是两种不同的说法', () => {
    expect(mustRefundBasisText('PLATFORM_DECLARED')).toContain('不可在本地降级');
    expect(mustRefundBasisText('REASON_MAPPED', 'QUALITY_DEFECT')).toContain('QUALITY_DEFECT');
  });

  it('不命中与未知口径都不给一个含糊的「是」', () => {
    expect(mustRefundBasisText('NONE')).toBe('不命中任何必退情形');
    expect(mustRefundBasisText(null)).toBe('—');
  });
});

describe('受理结论语气', () => {
  it('四个结论各自分得开，且必退优先展示', () => {
    expect(eligibilityPresentation('ACCEPTED_MUST_REFUND').rank).toBe(0);
    expect(eligibilityPresentation('OUT_OF_WINDOW_PENDING').rank).toBe(1);
    expect(eligibilityPresentation('ACCEPTED').rank).toBe(2);
    expect(eligibilityPresentation('REJECTED_OUT_OF_WINDOW').rank).toBe(3);
  });

  it('⚠️ 认不出的结论回落到「超窗待裁定」而不是「符合条件」', () => {
    // 后端加了新口径时，按「通过」处理会直接放过一张本该拒的单。
    expect(eligibilityPresentation('SOME_NEW_VERDICT').tone).toBe('pending');
    expect(eligibilityPresentation(null).tone).toBe('pending');
    expect(eligibilityBadgeClass('SOME_NEW_VERDICT')).toBe(eligibilityBadgeClass('OUT_OF_WINDOW_PENDING'));
  });
});

describe('售后期余量文本', () => {
  it('窗口内说「余」，超窗说「已超窗」—— 一个是余量一个是欠账', () => {
    expect(windowMarginText(true, 48)).toBe('窗口内，余 2 天');
    expect(windowMarginText(false, -30)).toBe('已超窗 1 天 6 小时');
  });

  it('余量为 null 时不编一个「0」出来', () => {
    expect(windowMarginText(true, null)).toBe('—');
    expect(windowMarginText(false, undefined)).toBe('—');
  });

  it('小时数不足 1 小时时给「不足 1 小时」而不是「0 小时」', () => {
    expect(humanizeHours(0)).toBe('不足 1 小时');
    expect(humanizeHours(0.5)).toBe('不足 1 小时');
  });

  it('负数输入按绝对值渲染（超窗文案自己已经带了「已超窗」前缀）', () => {
    expect(humanizeHours(-24)).toBe('1 天');
  });
});

describe('锚点与时限来源', () => {
  it('锚点用建单时刻时必须说出来 —— 它意味着窗口短算了一截', () => {
    expect(orderAnchorText('SUBMITTED_AT')).toContain('提交时刻');
    expect(orderAnchorText('CREATED_AT')).toContain('尚无提交时刻');
  });

  it('回复时限的两个来源分别说清，且都带小时数口径', () => {
    expect(replyDueBasisText('PLATFORM_GIVEN')).toContain('平台');
    expect(replyDueBasisText('POLICY_SLA', 24)).toContain('24');
    expect(replyDueBasisText(null)).toBe('—');
  });

  it('责任人三种分配方式都有一句人话，「待认领」必须显式', () => {
    expect(ownerModeText('UNASSIGNED')).toContain('待认领');
    expect(ownerModeText('ROSTER_ROUND_ROBIN')).toContain('自动派单');
    expect(ownerModeText('OPERATOR_SPECIFIED')).toContain('指定');
  });
});

describe('列表查询串', () => {
  it('空条件不产生裸问号', () => {
    expect(aftersalesIntakeQuery({})).toBe('');
  });

  it('布尔值以 true/false 形式带上，而不是被当成「没填」丢掉', () => {
    // mustRefund=false 与「不筛选」在语义上完全不同，丢掉它界面就筛不出非必退的单。
    expect(aftersalesIntakeQuery({ mustRefund: false })).toBe('?mustRefund=false');
    expect(aftersalesIntakeQuery({ currentOnly: true })).toBe('?currentOnly=true');
  });

  it('分页与筛选拼在一起，顺序稳定', () => {
    expect(aftersalesIntakeQuery({ eligibility: 'ACCEPTED', mustRefund: true, page: 2, size: 20 })).toBe(
      '?eligibility=ACCEPTED&mustRefund=true&page=2&size=20',
    );
  });

  it('关键字做 URL 编码，避免空格与中文把查询串拆坏', () => {
    expect(aftersalesIntakeQuery({ keyword: 'AS 100' })).toBe('?keyword=AS%20100');
  });
});
