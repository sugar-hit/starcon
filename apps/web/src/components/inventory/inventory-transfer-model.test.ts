import { describe, expect, it } from 'vitest';
import {
  conservationText,
  fmt,
  moment,
  receivable,
  routeText,
  shippable,
  transferActions,
  transferDiscrepancyLabels,
  transferStatusLabels,
} from './inventory-transfer-model';

/**
 * 7.5.1.1 调拨单前端模型测试。
 *
 * <p>覆盖三处最容易在前端算错的地方：状态允许的动作、剩余可发/可收数量、
 * 以及守恒式的自检文案。请求路径由后端集成测试覆盖（端点层实跑），
 * 这里只测「同一个数字怎么读」——这才是前端唯一会自己算错的东西。
 */
describe('调拨单前端模型', () => {
  describe('状态与动作', () => {
    it('六态都有中文标签', () => {
      expect(transferStatusLabels.DRAFT).toBe('草稿');
      expect(transferStatusLabels.PENDING_SHIPMENT).toBe('待发');
      expect(transferStatusLabels.IN_TRANSIT).toBe('在途');
      expect(transferStatusLabels.PARTIALLY_RECEIVED).toBe('部分收');
      expect(transferStatusLabels.RECEIVED).toBe('已收');
      expect(transferStatusLabels.DISCREPANCY).toBe('差异');
    });

    it('草稿只给确认，确认前不能发也不能收', () => {
      expect(transferActions('DRAFT')).toEqual(['confirm']);
    });

    it('待发可发可收可结案', () => {
      expect(transferActions('PENDING_SHIPMENT')).toEqual(['ship', 'receive', 'close']);
    });

    it('在途与部分收都可发可收可结案（支持分批）', () => {
      expect(transferActions('IN_TRANSIT')).toEqual(['ship', 'receive', 'close']);
      expect(transferActions('PARTIALLY_RECEIVED')).toEqual(['ship', 'receive', 'close']);
    });

    it('两个终态不给任何动作：货已不在路上', () => {
      expect(transferActions('RECEIVED')).toEqual([]);
      expect(transferActions('DISCREPANCY')).toEqual([]);
    });

    it('未知状态不给动作而不是给全部', () => {
      expect(transferActions('SOMETHING_NEW')).toEqual([]);
    });

    it('差异原因有中文标签', () => {
      expect(transferDiscrepancyLabels.LOST).toBe('运输丢失');
      expect(transferDiscrepancyLabels.DAMAGED).toBe('破损');
    });
  });

  describe('剩余可发与可收', () => {
    it('可发量取后端给的未发出量', () => {
      expect(shippable({ unshippedQuantity: 40 })).toBe(40);
      expect(shippable({ unshippedQuantity: 0 })).toBe(0);
    });

    it('未发出量为负时收敛到 0，不给负数输入框', () => {
      expect(shippable({ unshippedQuantity: -5 })).toBe(0);
      expect(shippable({ unshippedQuantity: Number.NaN })).toBe(0);
    });

    it('可收量取在途余量', () => {
      expect(receivable({ inTransitQuantity: 35 })).toBe(35);
      expect(receivable({ inTransitQuantity: 0 })).toBe(0);
      expect(receivable({ inTransitQuantity: -1 })).toBe(0);
    });
  });

  describe('守恒式自检', () => {
    it('已发出 = 已实收 + 差异 + 在途 时判为平衡', () => {
      const result = conservationText({
        shippedQuantity: 60,
        receivedQuantity: 25,
        discrepancyQuantity: 0,
        inTransitQuantity: 35,
      });
      expect(result.ok).toBe(true);
      expect(result.text).toContain('已发出 60');
      expect(result.text).toContain('仍在途 35');
    });

    it('全收完时在途为 0 仍判为平衡', () => {
      expect(
        conservationText({
          shippedQuantity: 60,
          receivedQuantity: 60,
          discrepancyQuantity: 0,
          inTransitQuantity: 0,
        }).ok,
      ).toBe(true);
    });

    it('对不上时判为不平衡并给出差额', () => {
      const result = conservationText({
        shippedQuantity: 60,
        receivedQuantity: 20,
        discrepancyQuantity: 0,
        inTransitQuantity: 35,
      });
      expect(result.ok).toBe(false);
      expect(result.text).toContain('差 5');
    });

    it('有差异时也算平衡（差异是合法去向）', () => {
      expect(
        conservationText({
          shippedQuantity: 30,
          receivedQuantity: 20,
          discrepancyQuantity: 10,
          inTransitQuantity: 0,
        }).ok,
      ).toBe(true);
    });

    it('小数尾差不算破坏平衡', () => {
      expect(
        conservationText({
          shippedQuantity: 10.0001,
          receivedQuantity: 4,
          discrepancyQuantity: 0,
          inTransitQuantity: 6.0001,
        }).ok,
      ).toBe(true);
    });
  });

  describe('展示格式', () => {
    it('数量按中文千分位展示，最多 4 位小数', () => {
      expect(fmt(1234)).toBe('1,234');
      expect(fmt(60.5)).toBe('60.5');
      expect(fmt(0)).toBe('0');
    });

    it('空值显示破折号而不是 0', () => {
      expect(fmt(null)).toBe('—');
      expect(fmt(undefined)).toBe('—');
    });

    it('时间带偏移解析；无法解析时原样返回而不是 Invalid Date', () => {
      expect(moment('2026-10-20T09:30:00+08:00')).not.toBe('Invalid Date');
      expect(moment('2026-10-20T09:30:00+08:00')).not.toBe('—');
      expect(moment(null)).toBe('—');
      expect(moment('不是时间')).toBe('不是时间');
    });

    it('路线文案含源仓与目标仓', () => {
      expect(
        routeText({
          fromWarehouseCode: 'WH-SH',
          fromWarehouseName: '上海仓',
          toWarehouseCode: 'WH-SZ',
          toWarehouseName: '深圳仓',
        }),
      ).toBe('WH-SH 上海仓 → WH-SZ 深圳仓');
    });
  });
});
