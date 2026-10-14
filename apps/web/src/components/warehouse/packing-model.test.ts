import { describe, expect, it } from 'vitest';

import {
  completionVerdict,
  declarationCell,
  declarationSourceLabel,
  derivedCell,
  itemsDigest,
  materialRoleLabel,
  measuredCell,
  packingQuery,
  packingStatusLabel,
  shippedHandoffNote,
} from './packing-model';

/**
 * 8.3.3.2 打包与称重前端模型测试。
 *
 * <p>重点钉死四条界面纪律（每条都对应蓝图里一句原话）：
 * 未测 ≠ 0、派生量不自己算、体积重不试算、完成 ≠ 已发货。
 */
describe('packing-model', () => {
  describe('实测值展示：没量 ≠ 量出来是 0', () => {
    it('未测的重量与体积显示「未量」，不显示 0', () => {
      expect(measuredCell(null, '未称重')).toBe('未称重');
      expect(measuredCell(undefined, '未量尺寸')).toBe('未量尺寸');
      expect(measuredCell('', '未量')).toBe('未量');
    });

    it('已测的值原样返回，不被二次格式化', () => {
      expect(measuredCell('1200')).toBe('1200');
      expect(measuredCell('0.006')).toBe('0.006');
    });

    it('派生量缺失时给占位符，不让页面自己算', () => {
      expect(derivedCell(null)).toBe('—');
      expect(derivedCell('1000')).toBe('1000');
    });
  });

  describe('完成判据：只认后端的 packable', () => {
    it('后端说不能完成时，前端不自作主张放行', () => {
      const verdict = completionVerdict({
        packable: false,
        blockingReasons: ['有 1 个包裹没称重或没量尺寸'],
        warnings: [],
        parcelCount: 1,
        unmeasuredParcelCount: 1,
        emptyParcelCount: 0,
        baselineLineCount: 1,
        baselineQuantity: '2',
        packedQuantity: '2',
        shortfallQuantity: '0',
      });
      expect(verdict.packable).toBe(false);
      expect(verdict.reasons).toEqual(['有 1 个包裹没称重或没量尺寸']);
    });

    it('判据还没加载时一律按不可完成处理', () => {
      expect(completionVerdict(null).packable).toBe(false);
      expect(completionVerdict(undefined).reasons).toEqual(['尚未加载完成判据']);
    });
  });

  describe('申报信息：读不到就说读不到', () => {
    it('NOT_FOUND 时不编 HS 编码，并点名 3.7.2 属 catalog', () => {
      const cell = declarationCell('NOT_FOUND', null);
      expect(cell.missing).toBe(true);
      expect(cell.text).toContain('3.7.2');
      expect(cell.text).toContain('catalog');
    });

    it('真有报关明细时才显示编码', () => {
      const cell = declarationCell('CUSTOMS_DECLARATION_LINE', '8471.30.01');
      expect(cell.missing).toBe(false);
      expect(cell.text).toBe('8471.30.01');
    });

    it('声明来源有中文标签', () => {
      expect(declarationSourceLabel('CUSTOMS_DECLARATION_LINE')).toBe('取自报关单明细');
      expect(declarationSourceLabel('NOT_FOUND')).toContain('catalog');
    });
  });

  describe('状态词表里没有「已发货」', () => {
    it('四个状态各有中文标签', () => {
      expect(packingStatusLabel('OPEN')).toBe('打包中');
      expect(packingStatusLabel('PACKED')).toBe('已打包');
      expect(packingStatusLabel('FAILED')).toBe('打包失败');
      expect(packingStatusLabel('CANCELLED')).toBe('已取消');
    });

    it('完成后只说交到待发货，不说已发货', () => {
      const note = shippedHandoffNote();
      expect(note).toContain('待发货');
      expect(note).toContain('8.3.4.1');
      expect(note).not.toContain('已发货（');
    });
  });

  describe('明细不许折叠成一个总数', () => {
    it('逐行列出「哪些 SKU、多少件」', () => {
      expect(
        itemsDigest([
          { skuCode: 'SKU-1', quantity: '2' },
          { skuCode: 'SKU-2', quantity: '1' },
        ]),
      ).toBe('SKU-1 × 2、SKU-2 × 1');
    });

    it('没装任何明细时说清楚是空的', () => {
      expect(itemsDigest([])).toBe('未装任何明细');
    });
  });

  it('包材角色有中文标签', () => {
    expect(materialRoleLabel('PACKAGING')).toBe('包材');
    expect(materialRoleLabel('CUSHIONING')).toBe('填充物');
  });

  it('查询串按维度拼接，空维度不产生脏参数', () => {
    expect(packingQuery({})).toBe('');
    expect(packingQuery({ status: 'OPEN', page: 0, size: 50 })).toBe('?status=OPEN&page=0&size=50');
  });
});