import { describe, expect, it } from 'vitest';
import {
  actionLabel,
  actionTone,
  decisionLabel,
  emptyState,
  riskLevelLabel,
  riskLevelTone,
  scopeCovers,
  scopeLabel,
  scoreText,
  statusLabel,
  statusTone,
} from './buyer-blacklist-model';

/**
 * 16.5.1.1 黑名单 前端模型的纯函数断言。
 *
 * 🔴 这个页面上最容易做丢的事，前端这一层再挡一道：
 * **「建议拦截」显示成「建议拦截」而不是「已拦截」**、
 * **「读不到 / 查无此人」不显示成「没风险」**、
 * **风险评分没评过时显示「未知」而不是 0 分**、
 * **三态空态文案各不相同**。
 */

describe('生效范围', () => {
  it('全店铺范围覆盖任何店铺（含「没给店铺」的情形）', () => {
    expect(scopeCovers('ALL_SHOPS', '', 'SHOP_A')).toBe(true);
    expect(scopeCovers('ALL_SHOPS', '', '')).toBe(true);
  });

  it('仅本店铺范围只覆盖那一家店 —— 别的店不命中', () => {
    expect(scopeCovers('SHOP_ONLY', 'SHOP_A', 'SHOP_A')).toBe(true);
    expect(scopeCovers('SHOP_ONLY', 'SHOP_A', 'SHOP_B')).toBe(false);
  });

  it('仅本店铺范围在「没给店铺」时不命中（不能靠猜）', () => {
    expect(scopeCovers('SHOP_ONLY', 'SHOP_A', '')).toBe(false);
  });

  it('标签与后端值域一致', () => {
    expect(scopeLabel('SHOP_ONLY')).toBe('仅本店铺');
    expect(scopeLabel('ALL_SHOPS')).toBe('全店铺');
  });
});

describe('风险等级与建议动作', () => {
  it('等级标签齐全', () => {
    expect(riskLevelLabel('LOW')).toBe('低');
    expect(riskLevelLabel('MEDIUM')).toBe('中');
    expect(riskLevelLabel('HIGH')).toBe('高');
    expect(riskLevelLabel('CRITICAL')).toBe('极高');
  });

  it('等级越高色调越重', () => {
    expect(riskLevelTone('LOW')).toBe('neutral');
    expect(riskLevelTone('MEDIUM')).toBe('warning');
    expect(riskLevelTone('HIGH')).toBe('danger');
    expect(riskLevelTone('CRITICAL')).toBe('danger');
  });

  it('🔴 建议动作一律带「建议」二字 —— 不许显示成「已拦截」', () => {
    expect(actionLabel('BLOCK')).toBe('建议拦截');
    expect(actionLabel('REVIEW')).toBe('建议人工复核');
    expect(actionLabel('WATCH')).toBe('建议观察');
    expect(actionLabel('BLOCK')).not.toBe('已拦截');
  });

  it('建议动作的色调：拦截是危险色，观察是中性色', () => {
    expect(actionTone('BLOCK')).toBe('danger');
    expect(actionTone('REVIEW')).toBe('warning');
    expect(actionTone('WATCH')).toBe('neutral');
  });
});

describe('状态与申诉裁决', () => {
  it('状态标签区分「生效中」与「已解除」', () => {
    expect(statusLabel('ACTIVE')).toBe('生效中');
    expect(statusLabel('RELEASED')).toBe('已解除');
  });

  it('只有生效中才是成功色', () => {
    expect(statusTone('ACTIVE')).toBe('success');
    expect(statusTone('RELEASED')).toBe('neutral');
  });

  it('申诉裁决三种状态文案不同', () => {
    expect(decisionLabel('PENDING')).toBe('待裁决');
    expect(decisionLabel('APPROVED')).toBe('已通过（并解除）');
    expect(decisionLabel('REJECTED')).toBe('已驳回（维持）');
  });
});

describe('风险评分展示', () => {
  it('🔴 没评过时显示「未知」，绝不显示 0', () => {
    expect(scoreText(null)).toBe('未知');
    expect(scoreText(undefined as unknown as number | null)).toBe('未知');
    expect(scoreText(0)).toBe('0.00');
  });
});

describe('空态文案各不相同', () => {
  it('四种空态给出四段不同的文案', () => {
    const noEntry = emptyState('NO_ENTRY');
    const noFlag = emptyState('NO_FLAG');
    const notHit = emptyState('NOT_HIT');
    const upstream = emptyState('UPSTREAM_UNKNOWN');

    const titles = [noEntry.title, noFlag.title, notHit.title, upstream.title];
    expect(new Set(titles).size).toBe(4);
  });

  it('🔴「没命中」要说明这不代表没有风险', () => {
    expect(emptyState('NOT_HIT').description).toContain('不代表他没有任何风险');
  });

  it('🔴「查无此人」要说明这是「未知」不是「干净」', () => {
    expect(emptyState('UPSTREAM_UNKNOWN').description).toContain('未知');
    expect(emptyState('UPSTREAM_UNKNOWN').description).toContain('不是');
  });
});
