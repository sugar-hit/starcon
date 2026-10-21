import { describe, expect, it } from 'vitest';

import {
  deltaText,
  deviationText,
  diffHeadline,
  emptyRuleInput,
  numberOrNull,
  outcomeLabel,
  outcomeTone,
  priceColumnText,
  priceText,
  priceWithCurrency,
  recordHeadline,
  ruleScopeText,
  ruleSummaryText,
  ruleTypeHint,
  ruleTypeLabel,
  runSummaryText,
  validateRuleInput,
} from './platform-price-sync-model';
import type {
  PlatformPriceDiff,
  PlatformPriceRule,
  PlatformPriceSyncRecord,
} from './platform-price-sync-model';

/**
 * 5.4.2.1 平台价格同步的前端**纯函数**断言。
 *
 * <p>本文件钉死的是蓝图那句加粗句在页面上的**具体写法**：
 *
 * <blockquote>
 *
 * **平台可能拒绝或调整价格**（如低于最低价触发），差异必须显性反馈，不能假设回写即成功。
 *
 * </blockquote>
 *
 * <p>三条断言各对应一个落点：
 * <ol>
 *   <li>「还没结论」<b>不许</b>渲染成 0（{@link priceText}）；</li>
 *   <li>差额必须带符号、带偏离比例（{@link diffHeadline}）；</li>
 *   <li>PENDING 必须与成功/失败分开（{@link outcomeLabel} / {@link outcomeTone}）。</li>
 * </ol>
 */
describe('5.4.2.1 平台价格同步前端模型', () => {
  describe('🔴 null 绝不渲染成 0', () => {
    it('actualPrice 为 null 时显示「待平台回执」而不是 0.0000', () => {
      expect(priceText(null)).toBe('待平台回执');
      expect(priceText(undefined)).toBe('待平台回执');
      expect(priceText(0)).toBe('0.0000');
      // 0 与 null 必须分得开：0 是一个真实的价，null 是「还不知道」。
      expect(priceText(0)).not.toBe(priceText(null));
    });

    it('带币种的价在缺价时不拼出「 USD」这种半截话', () => {
      expect(priceWithCurrency(null, 'USD')).toBe('待平台回执');
      expect(priceWithCurrency(12.5, 'USD')).toBe('12.5000 USD');
    });

    it('三列价分开给：期望价 / 承接价 / 实际价', () => {
      const columns = priceColumnText(120, 100, null);
      expect(columns.expected).toBe('120.0000');
      expect(columns.predicted).toBe('100.0000');
      expect(columns.actual).toBe('待平台回执');

      const noRule = priceColumnText(120, null, null);
      expect(noRule.predicted).toBe('本次判定未给出承接价');
      expect(noRule.actual).toBe('待平台回执');
    });
  });

  describe('差额必须带符号与偏离比例', () => {
    it('差额带符号', () => {
      expect(deltaText(-2)).toBe('-2.0000');
      expect(deltaText(0)).toBe('0.0000');
      expect(deltaText(3.5)).toBe('+3.5000');
      expect(deltaText(null)).toBe('—');
    });

    it('偏离比例是百分比', () => {
      expect(deviationText(0.016667)).toBe('1.67%');
      expect(deviationText(null)).toBe('—');
    });

    it('差异一句话说全：期望价 → 实际价 + 差额 + 偏离', () => {
      const diff: PlatformPriceDiff = {
        id: 'd1',
        syncId: 's1',
        writebackId: 'w1',
        skuId: 'sku1',
        platformCode: 'AMAZON',
        siteCode: 'US',
        currency: 'USD',
        expectedPrice: 120,
        actualPrice: 118,
        priceDelta: -2,
        deviationRatio: 0.016667,
        outcome: 'ADJUSTED',
        source: 'PLATFORM_RECEIPT',
        reasonCode: 'PLATFORM_RECEIPT',
        reasonMessage: '平台压价',
        createdAt: '2026-10-21T00:00:00Z',
      };
      const text = diffHeadline(diff);
      expect(text).toContain('期望 120.0000 USD');
      expect(text).toContain('实际 118.0000');
      expect(text).toContain('差 -2.0000');
      expect(text).toContain('偏离 1.67%');
    });

    it('平台没给实际价时，差异摘要说清「没有给出实际价」而不是显示 0', () => {
      const diff: PlatformPriceDiff = {
        id: 'd2',
        syncId: 's2',
        writebackId: 'w2',
        skuId: 'sku2',
        platformCode: 'AMAZON',
        siteCode: 'US',
        currency: 'USD',
        expectedPrice: 80,
        actualPrice: null,
        priceDelta: null,
        deviationRatio: null,
        outcome: 'REJECTED',
        source: 'RULE_EVALUATION',
        reasonCode: 'BELOW_MIN_PRICE',
        reasonMessage: '低于平台最低价',
        createdAt: '2026-10-21T00:00:00Z',
      };
      const text = diffHeadline(diff);
      expect(text).toContain('期望 80.0000 USD');
      expect(text).toContain('平台没有给出实际价');
      expect(text).toContain('BELOW_MIN_PRICE');
      // 🔴 不能出现「实际 0.0000」—— 那是把「还不知道」说成「平台把价压到 0」。
      expect(text).not.toContain('实际 0.0000');
      expect(text).not.toContain('差 0.0000');
    });
  });

  describe('结局标签与色调', () => {
    it('PENDING 与成功/失败分开', () => {
      expect(outcomeLabel('PENDING')).toContain('待平台回执');
      expect(outcomeTone('PENDING')).toBe('info');
      expect(outcomeTone('ACCEPTED')).toBe('success');
      expect(outcomeTone('ADJUSTED')).toBe('warning');
      expect(outcomeTone('REJECTED')).toBe('danger');
      expect(outcomeTone('FAILED')).toBe('danger');
    });

    it('记录摘要在四种结局下都不显示 0', () => {
      const base: PlatformPriceSyncRecord = {
        id: 'r1',
        writebackId: 'w1',
        orderId: 'o1',
        orderItemId: 'oi1',
        skuId: 'sku1',
        skuCode: 'SKU-1',
        platformCode: 'AMAZON',
        siteCode: 'US',
        currency: 'USD',
        expectedPrice: 120,
        predictedPrice: null,
        actualPrice: null,
        priceDelta: null,
        deviationRatio: null,
        outcome: 'PENDING',
        ruleOutcome: 'NO_RULE',
        ruleId: null,
        ruleReason: '',
        platformOperationId: null,
        platformRequestId: '',
        idempotencyKey: 'k1',
        lastError: '',
        dispatchedAt: null,
        completedAt: null,
        createdAt: '2026-10-21T00:00:00Z',
        updatedAt: '2026-10-21T00:00:00Z',
      };
      expect(recordHeadline(base)).toContain('待平台回执');
      expect(recordHeadline({ ...base, outcome: 'REJECTED' })).toContain('平台拒收');
      expect(recordHeadline({ ...base, outcome: 'FAILED' })).toContain('本次没发出去');
      expect(recordHeadline({ ...base, outcome: 'ACCEPTED', actualPrice: 120 })).toContain(
        '实际 120.0000',
      );
      expect(
        recordHeadline({
          ...base,
          outcome: 'ADJUSTED',
          actualPrice: 118,
          priceDelta: -2,
          deviationRatio: 0.016667,
        }),
      ).toContain('差 -2.0000');
      expect(
        recordHeadline({
          ...base,
          outcome: 'ADJUSTED',
          actualPrice: 118,
          priceDelta: -2,
          deviationRatio: 0.016667,
        }),
      ).toContain('偏离 1.67%');
    });
  });

  describe('平台定价规则的展示口径', () => {
    const rule: PlatformPriceRule = {
      id: 'rule1',
      ruleType: 'MIN_PRICE',
      platformCode: 'AMAZON',
      siteCode: 'US',
      skuId: null,
      status: 'ACTIVE',
      priority: 100,
      minPrice: 50,
      currency: 'USD',
      comparePlatformCode: null,
      compareSiteCode: null,
      maxGapRatio: null,
      referenceSiteCode: null,
      minRatio: null,
      maxRatio: null,
      note: '',
      createdAt: '2026-10-21T00:00:00Z',
      updatedAt: '2026-10-21T00:00:00Z',
    };

    it('规则类型有中文名与一句话说明', () => {
      expect(ruleTypeLabel('MIN_PRICE')).toBe('最低价');
      expect(ruleTypeLabel('AUTO_COMPARE')).toBe('自动比价');
      expect(ruleTypeLabel('MULTI_SITE_LINK')).toBe('多站点联动');
      expect(ruleTypeHint('MIN_PRICE')).toContain('拒收');
      expect(ruleTypeHint('AUTO_COMPARE')).toContain('压到对比价');
      expect(ruleTypeHint('MULTI_SITE_LINK')).toContain('联动');
    });

    it('作用域写明「全部站点 / 全部 SKU」', () => {
      expect(ruleScopeText(rule)).toBe('AMAZON / US / 全部 SKU');
      expect(ruleScopeText({ ...rule, siteCode: '', skuId: 'x' })).toBe(
        'AMAZON / 全部站点 / 指定 SKU',
      );
    });

    it('参数摘要按类型取不同的列', () => {
      expect(ruleSummaryText(rule)).toContain('最低价 50.0000 USD');
      expect(
        ruleSummaryText({
          ...rule,
          ruleType: 'AUTO_COMPARE',
          comparePlatformCode: 'SHOPIFY',
          compareSiteCode: 'US',
          maxGapRatio: 0.05,
        }),
      ).toContain('SHOPIFY');
      expect(
        ruleSummaryText({
          ...rule,
          ruleType: 'MULTI_SITE_LINK',
          referenceSiteCode: 'DE',
          minRatio: 0.9,
          maxRatio: 1.1,
        }),
      ).toContain('DE');
    });
  });

  describe('客户端校验（与后端同一套口径）', () => {
    it('最低价缺币种或缺价被拒', () => {
      const input = { ...emptyRuleInput(), platformCode: 'AMAZON' };
      expect(validateRuleInput({ ...input, minPrice: null })).toContain('最低价必须大于 0');
      expect(validateRuleInput({ ...input, currency: 'usd' })).toContain(
        '最低价规则必须填三位大写币种代码',
      );
      expect(validateRuleInput({ ...input, minPrice: 50, currency: 'USD' })).toEqual([]);
    });

    it('自动比价缺对比平台被拒', () => {
      const input = { ...emptyRuleInput(), platformCode: 'AMAZON' };
      const problems = validateRuleInput({
        ...input,
        ruleType: 'AUTO_COMPARE',
        comparePlatformCode: '',
        maxGapRatio: 0.05,
      });
      expect(problems).toContain('自动比价必须填对比平台码');
    });

    it('多站点联动上限小于下限被拒', () => {
      const input = { ...emptyRuleInput(), platformCode: 'AMAZON' };
      const problems = validateRuleInput({
        ...input,
        ruleType: 'MULTI_SITE_LINK',
        referenceSiteCode: 'DE',
        minRatio: 1.1,
        maxRatio: 0.9,
      });
      expect(problems).toContain('联动上限比例不能小于下限比例');
    });

    it('平台码必填', () => {
      expect(validateRuleInput({ ...emptyRuleInput(), platformCode: '' })).toContain(
        '必须填平台码',
      );
    });
  });

  describe('小工具', () => {
    it('空串解析成 null 而不是 0', () => {
      expect(numberOrNull('')).toBeNull();
      expect(numberOrNull('  ')).toBeNull();
      expect(numberOrNull('abc')).toBeNull();
      expect(numberOrNull('12.5')).toBe(12.5);
    });

    it('同步结果摘要把四个数都说出来', () => {
      const text = runSummaryText({
        scanned: 5,
        created: 0,
        dispatched: 2,
        rejected: 1,
        skipped: 1,
        failed: 1,
        items: [],
      });
      expect(text).toContain('扫描 5 条');
      expect(text).toContain('已投递 2 条');
      expect(text).toContain('被平台规则拒收 1 条');
      expect(text).toContain('没发出去 1 条');
      expect(text).toContain('跳过 1 条');
    });
  });
});
