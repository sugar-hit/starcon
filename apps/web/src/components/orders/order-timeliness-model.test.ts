import { describe, expect, it } from 'vitest';
import {
  capabilityTone,
  formatDuration,
  formatRate,
  overdueKindLabel,
  promiseLabel,
  promiseTone,
  slaLabel,
  slaTone,
} from './order-timeliness-model';

/**
 * 9.5.1.1 时效分析前端呈现口径。
 *
 * <p>这些断言盯的都是**同一个东西的两种表现**：后端把「算不出来」和
 * 「算出来是 0」分得很清楚，前端一旦把它们画成同一个东西，报表就会
 * 开始说谎 —— 而时效是平台算法权重项，说谎的代价是自然流量。
 */
describe('9.5.1.1 时效分析 · 呈现口径', () => {
  describe('时长：null 绝不能变成 0', () => {
    it('未测出的时长渲染成「未测出」，不是「0 秒」', () => {
      expect(formatDuration(null)).toBe('未测出');
      expect(formatDuration(undefined)).toBe('未测出');
      expect(formatDuration(null)).not.toBe('0秒');
    });

    it('真实的 0 秒照原样显示：0 是算得出来的结论', () => {
      // 刻意区分 null 与 0：后者是「确实 0 秒」，前者是「没测出来」
      expect(formatDuration(0)).toBe('0秒');
    });

    it('秒 / 分 / 小时 / 天四档各自成立', () => {
      expect(formatDuration(45)).toBe('45秒');
      expect(formatDuration(125)).toBe('2分5秒');
      expect(formatDuration(3600)).toBe('1小时0分');
      expect(formatDuration(93600)).toBe('1天2小时');
      expect(formatDuration(172800)).toBe('2天0小时');
    });

    it('负秒数（提前到货）渲染成「提前」而不是负号', () => {
      expect(formatDuration(-86400)).toBe('提前 1天0小时');
      expect(formatDuration(-3600)).toContain('提前');
    });
  });

  describe('比率：分母为 0 是「无法判定」而不是 0%', () => {
    it('null 比率渲染成「无法判定」，绝不渲染成 0%', () => {
      expect(formatRate(null)).toBe('—（无法判定）');
      expect(formatRate(undefined)).toBe('—（无法判定）');
      expect(formatRate('')).toBe('—（无法判定）');
      expect(formatRate(null)).not.toBe('0%');
    });

    it('真实比率带百分号', () => {
      expect(formatRate('66.67')).toBe('66.67%');
      expect(formatRate(0)).toBe('0%');
    });
  });

  describe('承诺 vs 实际：四桶互不吞并', () => {
    it('四个桶各有各的说法', () => {
      expect(promiseLabel('MET')).toBe('达成承诺');
      expect(promiseLabel('LATE')).toBe('超出承诺');
      expect(promiseLabel('NO_PROMISE')).toBe('未登记承诺到货');
      expect(promiseLabel('NO_ACTUAL')).toBe('未登记实际到货');
    });

    it('两个未登记桶用中性色而不是警告色', () => {
      // 「还没登记」不是已确认的问题：画成警告会引人去查承运商，
      // 而真正该做的是把承诺 / 签收登记补上
      expect(promiseTone('NO_PROMISE')).toBe('neutral');
      expect(promiseTone('NO_ACTUAL')).toBe('neutral');
      expect(promiseTone('MET')).toBe('ok');
      expect(promiseTone('LATE')).toBe('bad');
    });

    it('未知状态原样返回而不是吞掉', () => {
      expect(promiseLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
      expect(promiseLabel('')).toBe('—');
      expect(promiseTone('SOMETHING_NEW')).toBe('neutral');
    });
  });

  describe('发货时限三桶', () => {
    it('UNSET 既不算准时也不算超时', () => {
      expect(slaLabel('ON_TIME')).toBe('按时发货');
      expect(slaLabel('OVERDUE')).toBe('超出发货时限');
      expect(slaLabel('UNSET')).toBe('未登记发货时限');
      expect(slaTone('UNSET')).toBe('neutral');
      expect(slaTone('OVERDUE')).toBe('bad');
      expect(slaTone('ON_TIME')).toBe('ok');
    });
  });

  describe('超时来源：发货时限与到货承诺不合并', () => {
    it('两个来源各有各的说法', () => {
      expect(overdueKindLabel('SHIP_SLA')).toBe('超出发货时限');
      expect(overdueKindLabel('PROMISE')).toBe('超出到货承诺');
      expect(overdueKindLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    });
  });

  describe('能力缺口', () => {
    it('available=false 用中性色：它不是失败，是「还没有数据源」', () => {
      expect(capabilityTone(false)).toBe('neutral');
      expect(capabilityTone(true)).toBe('ok');
    });
  });
});
