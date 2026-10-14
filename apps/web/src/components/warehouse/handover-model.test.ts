import { describe, expect, it } from 'vitest';

import {
  effectiveWaybill,
  handedOverNote,
  handoverQuery,
  handoverStatusLabel,
  handoverVerdict,
  ledgerDigest,
  registeredCell,
  waybillInputOf,
} from './handover-model';

/**
 * 8.3.4.1 发货交接前端模型测试。
 *
 * <p>重点钉死四条界面纪律（每条都对应蓝图里一句原话）：
 * 承运商与运单号缺一不可、已交接 ≠ 已送达、未预占出库不冒充「预占已消耗」、
 * 交接判据只认后端。
 */
describe('handover-model', () => {
  describe('承运商与运单号：两个字段，缺一不可', () => {
    const base = {
      carrierCode: 'SF',
      carrierName: '顺丰速运',
      masterWaybillNo: 'SF123456',
      receiverName: '王师傅',
      receiverContact: '13800000000',
    };

    it('两者都有才放行提交', () => {
      const check = waybillInputOf(base);
      expect(check.ok).toBe(true);
      if (check.ok) {
        expect(check.body.carrierCode).toBe('SF');
        expect(check.body.masterWaybillNo).toBe('SF123456');
      }
    });

    it('缺承运渠道：只有运单号就是「不知道谁运的」，前端当场拒', () => {
      const check = waybillInputOf({ ...base, carrierCode: '   ' });
      expect(check.ok).toBe(false);
      if (!check.ok) expect(check.message).toContain('承运渠道');
    });

    it('缺运单号：只有承运商就是「不知道运的是哪一单」，前端当场拒', () => {
      const check = waybillInputOf({ ...base, masterWaybillNo: '' });
      expect(check.ok).toBe(false);
      if (!check.ok) expect(check.message).toContain('运单号');
    });

    it('逐箱运单号留空的那些不进请求体，不给后端塞一批空字符串', () => {
      const check = waybillInputOf({
        ...base,
        parcelWaybills: { p1: 'SF-A', p2: '  ', p3: '' },
      });
      expect(check.ok).toBe(true);
      if (check.ok) {
        expect(check.body.parcelWaybillNos).toEqual([{ parcelId: 'p1', parcelWaybillNo: 'SF-A' }]);
      }
    });

    it('一个逐箱号都没有时整个字段为 null，而不是空数组', () => {
      const check = waybillInputOf({ ...base, parcelWaybills: { p1: '  ' } });
      expect(check.ok).toBe(true);
      if (check.ok) expect(check.body.parcelWaybillNos).toBeNull();
    });
  });

  describe('每箱运单号：留空是「跟随主运单号」，不是「没登记」', () => {
    it('逐箱登记了就用逐箱的', () => {
      expect(effectiveWaybill('SF-A', 'SF123456')).toEqual({
        text: 'SF-A',
        inherited: false,
        missing: false,
      });
    });

    it('逐箱没登记但有主运单号：显示「跟随主运单号」，不能显示成空白', () => {
      const r = effectiveWaybill('', 'SF123456');
      expect(r.inherited).toBe(true);
      expect(r.missing).toBe(false);
      expect(r.text).toContain('SF123456');
    });

    it('两边都没有才是真的「未登记」', () => {
      const r = effectiveWaybill(null, null);
      expect(r.missing).toBe(true);
      expect(r.text).toBe('未登记运单号');
    });
  });

  describe('未登记的量：未登记 ≠ 登记了一个 0', () => {
    it('null / 空串 / 纯空格都显示「未登记」', () => {
      expect(registeredCell(null)).toBe('未登记');
      expect(registeredCell(undefined)).toBe('未登记');
      expect(registeredCell('')).toBe('未登记');
      expect(registeredCell('   ')).toBe('未登记');
    });

    it('已登记的值原样返回', () => {
      expect(registeredCell('SF')).toBe('SF');
      expect(registeredCell('SF123456')).toBe('SF123456');
    });
  });

  describe('交接判据：只认后端的 handableOver', () => {
    it('后端说不能交接时，前端不自作主张放行', () => {
      const verdict = handoverVerdict({
        handableOver: false,
        blockingReasons: ['库存不足，差 400 件'],
        warnings: [],
        carrierRegistered: true,
        waybillRegistered: true,
        handoverUserSet: true,
        handoverAtSet: true,
        parcelCount: 1,
        itemCount: 1,
        totalQuantity: '500',
        shortQuantity: '400',
        shortSkus: ['SKU-1 缺 400'],
      });
      expect(verdict.handable).toBe(false);
      expect(verdict.reasons).toEqual(['库存不足，差 400 件']);
    });

    it('判据还没加载时一律按不可交接处理', () => {
      expect(handoverVerdict(null).handable).toBe(false);
    });

    it('后端说可以交接时前端不额外加门槛', () => {
      const verdict = handoverVerdict({
        handableOver: true,
        blockingReasons: [],
        warnings: ['以下 SKU 没有任何在册预占，本次是「未预占出库」'],
        carrierRegistered: true,
        waybillRegistered: true,
        handoverUserSet: true,
        handoverAtSet: true,
        parcelCount: 1,
        itemCount: 1,
        totalQuantity: '2',
        shortQuantity: '0',
        shortSkus: [],
      });
      expect(verdict.handable).toBe(true);
      // 「未预占出库」是提示，不是阻断 —— 把它当阻断会让所有没预占的订单永远交不出去。
      expect(verdict.warnings[0]).toContain('未预占出库');
    });
  });

  describe('状态词：已交接 ≠ 已送达', () => {
    it('三态各有中文标签，没有「已签收」「已送达」', () => {
      expect(handoverStatusLabel('OPEN')).toBe('交接中');
      expect(handoverStatusLabel('HANDED_OVER')).toBe('已交接');
      expect(handoverStatusLabel('CANCELLED')).toBe('已作废');
      const labels = ['OPEN', 'HANDED_OVER', 'CANCELLED'].map(handoverStatusLabel);
      expect(labels.join('/')).not.toContain('签收');
      expect(labels.join('/')).not.toContain('送达');
    });

    it('未知状态原样返回，不编一个标签出来', () => {
      expect(handoverStatusLabel('WHATEVER')).toBe('WHATEVER');
      expect(handoverStatusLabel(null)).toBe('—');
    });
  });

  describe('分录摘要：数字来自后端，措辞由前端负责', () => {
    it('没有分录时如实说「尚未产生」', () => {
      expect(ledgerDigest([])).toBe('尚未产生出库分录');
    });

    it('reserved_delta 为 0 时不啰嗦地提「释放预占」', () => {
      expect(ledgerDigest([{ onHandDelta: '-2', reservedDelta: '0' }])).toBe('出库 -2');
    });

    it('释放了实物预占时两个数都念出来', () => {
      expect(ledgerDigest([{ onHandDelta: '-6', reservedDelta: '-2' }])).toBe(
        '出库 -6，释放预占 -2',
      );
    });
  });

  describe('交接后的提示：不替下游承诺', () => {
    it('说明轨迹与平台回传尚未在本页发生', () => {
      const note = handedOverNote(3);
      expect(note).toContain('出库 3 条分录');
      expect(note).toContain('10 物流');
      expect(note).toContain('尚未在本页发生');
    });
  });

  describe('列表查询串', () => {
    it('不带任何条件时不产生多余的 ?', () => {
      expect(handoverQuery({})).toBe('');
    });

    it('条件逐个编码，空值不参与', () => {
      const q = handoverQuery({ status: 'OPEN', keyword: 'SF 123', page: 0, size: 50 });
      expect(q).toContain('status=OPEN');
      expect(q).toContain('keyword=SF%20123');
      expect(q).toContain('page=0');
      expect(q).toContain('size=50');
      expect(q).not.toContain('undefined');
    });
  });
});
