import { describe, expect, it } from 'vitest';
import {
  conservationText,
  crossEntityActions,
  crossEntitySettlementLabels,
  crossEntityStatusLabels,
  crossEntityValuationLabels,
  fmt,
  money,
  moment,
  partiesText,
  receivable,
  remainingToReceive,
  routeText,
} from './legal-entity-transfer-model';

/**
 * 7.5.2.1 跨法人调拨前端模型测试。
 *
 * <p>覆盖本模块最容易在前端算错的地方：状态允许的动作、还能收多少、守恒式自检，
 * 以及金额**必须带币种**（跨币种直接相加是错的）。
 *
 * <p>请求路径与白名单不在这里测 —— 那由 api-proxy 路由测试与后端集成测试覆盖；
 * 这里只测「同一个数字怎么读」，这才是前端唯一会自己算错的东西。
 */
describe('跨法人调拨前端模型', () => {
  describe('状态与动作', () => {
    it('八态都有中文标签', () => {
      expect(crossEntityStatusLabels.DRAFT).toBe('草稿');
      expect(crossEntityStatusLabels.PENDING_SOURCE_CONFIRM).toBe('待转出方确认');
      expect(crossEntityStatusLabels.PENDING_TARGET_CONFIRM).toBe('待转入方确认');
      expect(crossEntityStatusLabels.CONFIRMED).toBe('双方已确认');
      expect(crossEntityStatusLabels.IN_TRANSIT).toBe('在途');
      expect(crossEntityStatusLabels.COMPLETED).toBe('已交割');
      expect(crossEntityStatusLabels.DISCREPANCY).toBe('差异');
      expect(crossEntityStatusLabels.CANCELLED).toBe('已作废');
    });

    it('按钮按状态给：终态不再显示发货/确认', () => {
      expect(crossEntityActions('DRAFT')).toEqual(['source-confirm']);
      expect(crossEntityActions('PENDING_TARGET_CONFIRM')).toEqual(['target-confirm']);
      expect(crossEntityActions('CONFIRMED')).toEqual(['ship', 'cancel']);
      expect(crossEntityActions('IN_TRANSIT')).toEqual(['receive', 'close']);
      // 已交割 / 差异 / 已作废都是终态：货权已经转移或已经结束。
      expect(crossEntityActions('COMPLETED')).toEqual([]);
      expect(crossEntityActions('DISCREPANCY')).toEqual([]);
      expect(crossEntityActions('CANCELLED')).toEqual([]);
    });

    it('两个确认是两个独立动作，不是一个带参数的', () => {
      // 草稿只显示转出方确认；等转出方签完才轮到转入方。
      // 若这里合成一个动作，界面就会让一个人把两方都签掉。
      expect(crossEntityActions('DRAFT')).not.toContain('target-confirm');
      expect(crossEntityActions('PENDING_SOURCE_CONFIRM')).not.toContain('source-confirm');
    });
  });

  describe('转移定价', () => {
    it('定价口径与凭据状态都有标签', () => {
      expect(crossEntityValuationLabels.MANUAL).toBe('人工定价');
      expect(crossEntityValuationLabels.TRANSFER_COST).toBe('转出方成本');
      expect(crossEntityValuationLabels.LOCAL_COST).toBe('转入方当地成本');
      // 刻意没有「已结算/已入账」：那属于 12 财务的账，本模块无权宣告。
      expect(crossEntitySettlementLabels.ISSUED).toBe('已开具（待对方确认）');
      expect(crossEntitySettlementLabels.ACKNOWLEDGED).toBe('已被转入方确认');
      expect(Object.keys(crossEntitySettlementLabels)).not.toContain('SETTLED');
    });

    it('金额必须带币种', () => {
      expect(money(1250, 'CNY')).toContain('CNY');
      expect(money(1250, 'USD')).toContain('USD');
      expect(money(null, 'CNY')).toBe('—');
      // 零定价也要显示成 0，而不是空白 —— 「没填」和「按零定价」是两件事。
      expect(money(0, 'CNY')).toContain('0');
    });
  });

  describe('数量守恒', () => {
    it('还能收多少取在途余量，不在前端重算', () => {
      expect(receivable({ inTransitQuantity: 40 })).toBe(40);
      expect(receivable({ inTransitQuantity: -5 })).toBe(0);
    });

    it('应收 = 调拨 − 已实收 − 已确认差异', () => {
      expect(
        remainingToReceive({ quantity: 60, receivedQuantity: 20, discrepancyQuantity: 0 }),
      ).toBe(40);
      // 短少 10 之后不再计入应收：短少已经结案，不是还能收的量。
      expect(
        remainingToReceive({ quantity: 60, receivedQuantity: 50, discrepancyQuantity: 10 }),
      ).toBe(0);
    });

    it('守恒式自检文案', () => {
      const ok = conservationText({
        totalQuantity: 60,
        receivedQuantity: 20,
        discrepancyQuantity: 0,
        inTransitQuantity: 40,
      });
      expect(ok.ok).toBe(true);

      const bad = conservationText({
        totalQuantity: 60,
        receivedQuantity: 20,
        discrepancyQuantity: 0,
        inTransitQuantity: 10,
      });
      expect(bad.ok).toBe(false);
      expect(bad.text).toContain('差 30');
    });
  });

  describe('展示', () => {
    it('两个法人是页面的第一视觉要素', () => {
      const text = partiesText({
        fromLegalEntityCode: 'LE-HK',
        fromLegalEntityName: '香港主体',
        toLegalEntityCode: 'LE-SG',
        toLegalEntityName: '新加坡主体',
      });
      expect(text).toContain('LE-HK');
      expect(text).toContain('→');
      expect(text).toContain('LE-SG');
    });

    it('仓库路线', () => {
      expect(
        routeText({
          fromWarehouseCode: 'WH-HK',
          fromWarehouseName: '香港仓',
          toWarehouseCode: 'WH-SG',
          toWarehouseName: '新加坡仓',
        }),
      ).toBe('WH-HK 香港仓 → WH-SG 新加坡仓');
    });

    it('空值给破折号，时间保留时刻不改事实', () => {
      expect(fmt(null)).toBe('—');
      expect(fmt(undefined)).toBe('—');
      expect(moment(null)).toBe('—');
      expect(moment('2026-03-01T09:30:00+08:00')).toContain('2026');
    });
  });
});
