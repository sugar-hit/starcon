import { describe, expect, it } from 'vitest';
import type { CandidateSet, ExcludedOrder } from '../../../../../packages/api-contracts/src/index';
import {
  blockedReason,
  canConsolidate,
  crossShopText,
  errorText,
  excludedText,
  freightSavingText,
  isoText,
  money,
  platformText,
  sizeText,
} from './order-consolidation-model';

/**
 * 9.3.1.2 合单前端模型的行为断言。
 *
 * <p>重点不在「函数能不能跑」，而在**界面会不会撒谎**：
 * 「未报价」与「省了 0」、「不需回传」与「回传失败」、
 * 「没进候选」与「系统漏单」这几组，在页面上必须是不同的两句话。
 */
describe('9.3.1.2 合单前端模型', () => {
  describe('运费节省：未报价不是 0', () => {
    it('没给合并运费报价时显示「未报价」，绝不显示 0', () => {
      expect(
        freightSavingText({
          freightSavingAmount: null,
          consolidatedShippingAmount: null,
        }),
      ).toContain('未报价');
      expect(
        freightSavingText({
          freightSavingAmount: null,
          consolidatedShippingAmount: null,
        }),
      ).not.toMatch(/^0/);
    });

    it('报价了但省到 0 时显示 0，与「未报价」是两句不同的话', () => {
      const text = freightSavingText({
        freightSavingAmount: 0,
        consolidatedShippingAmount: 17,
      });
      expect(text).toBe('0');
      expect(text).not.toContain('未报价');
    });

    it('报价并省到钱时显示实算差值', () => {
      expect(
        freightSavingText({
          freightSavingAmount: 5.75,
          consolidatedShippingAmount: 11.25,
        }),
      ).toBe('5.75');
    });
  });

  describe('金额与时间展示不改值', () => {
    it('金额只做格式化，空值显示破折号而不是 0', () => {
      expect(money(17)).toBe('17');
      expect(money(5.75)).toBe('5.75');
      expect(money(0)).toBe('0');
      expect(money(null)).toBe('—');
      expect(money(undefined)).toBe('—');
    });

    it('时间原样展示 ISO 值，不做时区换算；空值显示破折号', () => {
      expect(isoText('2026-03-01T10:45:00Z')).toBe('2026-03-01T10:45:00Z');
      expect(isoText(null)).toBe('—');
      expect(isoText('')).toBe('—');
    });
  });

  describe('跨店铺识别（蓝图加粗句）', () => {
    it('非跨店铺返回 null，界面不渲染徽标', () => {
      expect(
        crossShopText({
          crossShop: false,
          distinctPlatformCount: 1,
          distinctShopCount: 1,
        }),
      ).toBeNull();
    });

    it('跨店铺时给出平台数与店铺数', () => {
      expect(
        crossShopText({
          crossShop: true,
          distinctPlatformCount: 2,
          distinctShopCount: 3,
        }),
      ).toBe('跨 2 个平台 / 3 个店铺');
    });

    it('D5 未交付导致店铺数为 0 时只报平台数，不编一个店铺数', () => {
      const text = crossShopText({
        crossShop: true,
        distinctPlatformCount: 2,
        distinctShopCount: 0,
      });
      expect(text).toBe('跨 2 个平台');
      expect(text).not.toContain('个店铺');
    });
  });

  describe('平台归属：非平台单不编哨兵值', () => {
    it('平台单给出 平台 / 店铺 / 平台单号', () => {
      expect(
        platformText({
          platformCode: 'AMAZON',
          shopCode: 'SHOP-1',
          platformOrderRef: 'AMZ-1',
        }),
      ).toBe('AMAZON / SHOP-1 / AMZ-1');
    });

    it('店铺为空时只给平台与平台单号', () => {
      expect(
        platformText({
          platformCode: 'SHOPIFY',
          shopCode: '',
          platformOrderRef: 'SH-9',
        }),
      ).toBe('SHOPIFY / SH-9');
    });

    it('非平台单返回 null（手工单没有平台可回传）', () => {
      expect(
        platformText({
          platformCode: '',
          shopCode: '',
          platformOrderRef: 'MAN-1',
        }),
      ).toBeNull();
    });
  });

  describe('能否落合并', () => {
    // 契约把可空字段生成成非空 string，但后端确实会返回 null（甚至整字段缺省），
    // 所以夹具用一个显式的 cast helper 表达「运行时可能是 null」，
    // 而不是把断言写成「后端永远返回字符串」那种自欺。
    const set = (over: Partial<Record<string, unknown>>) =>
      ({
        candidates: [],
        excluded: [],
        buyerFingerprint: 'P:x',
        addressFingerprint: 'A:y',
        candidateCount: 3,
        candidateQty: 6,
        separateShippingTotal: 17,
        ruleConfigured: true,
        ruleBlockedReason: '',
        ...over,
      }) as unknown as CandidateSet;

    const base = set({});

    it('规则未配置时一律不可合 —— 不给隐含默认值', () => {
      expect(canConsolidate(set({ ruleConfigured: false }), 3)).toBe(false);
      expect(blockedReason(set({ ruleConfigured: false }))).not.toBeNull();
    });

    it('被规则阻断时不可合，并把阻断理由透出来', () => {
      const blocked = set({ ruleBlockedReason: '候选有 3 笔，超过本租户规则的单批上限 2 笔' });
      expect(canConsolidate(blocked, 3)).toBe(false);
      expect(blockedReason(blocked)).toContain('单批上限');
    });

    it('少于两笔不可合：本模块是 N→1', () => {
      expect(canConsolidate(base, 1)).toBe(false);
    });

    it('规则齐备且勾选两笔以上即可合', () => {
      expect(canConsolidate(base, 2)).toBe(true);
      expect(blockedReason(base)).toBeNull();
    });

    it('候选集为 null 时不可合，也不给阻断理由', () => {
      expect(canConsolidate(null, 5)).toBe(false);
      expect(blockedReason(null)).toBeNull();
    });
  });

  describe('排除项与规模展示', () => {
    it('排除项优先用后端给的中文说明，缺省时兜底成原因码本身', () => {
      expect(
        excludedText({
          orderId: 'o1',
          orderNo: 'SO-1',
          reason: 'BUYER_MISMATCH',
          reasonLabel: '同一地址下的另一位买家',
        }),
      ).toBe('同一地址下的另一位买家');
      // 后端序列化可能整个省略 reasonLabel（NON_NULL 包含策略），
      // 这时界面必须退回原因码，而不是显示空白。
      expect(
        excludedText({ orderId: 'o1', orderNo: 'SO-1', reason: 'BUYER_MISMATCH' } as ExcludedOrder),
      ).toBe('BUYER_MISMATCH');
    });

    it('规模同时报订单数 / 行数 / 件数', () => {
      expect(sizeText({ orderCount: 3, totalLineCount: 5, totalQty: 6 })).toBe('3 笔 / 5 行 / 6 件');
    });

    it('件数为 null 时按 0 显示而不是 NaN', () => {
      expect(sizeText({ orderCount: 1, totalLineCount: 1, totalQty: null })).toBe('1 笔 / 1 行 / 0 件');
    });
  });

  describe('错误态不被吞掉', () => {
    it('Error 的 message 原样带出', () => {
      expect(errorText(new Error('合单至少要两笔订单'))).toBe('合单至少要两笔订单');
    });

    it('非 Error 也给一句人话，不显示 undefined', () => {
      expect(errorText('boom')).toContain('合单服务暂时不可用');
      expect(errorText(null)).toContain('合单服务暂时不可用');
    });
  });
});
