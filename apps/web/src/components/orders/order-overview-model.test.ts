import { describe, expect, it } from 'vitest';
import {
  UNAVAILABLE_SECTION_ORDER,
  actionDisabled,
  allocationBanner,
  chargeTypeLabels,
  documentStatusLabels,
  formatAmount,
  formatTime,
  manualKindLabels,
  unavailableCell,
  unavailableSectionLabel,
} from './order-overview-model';

/**
 * 9.1.1.2 订单详情前端模型单测。
 *
 * <p><b>本测试钉死的是一条纪律：拿不到的东西显示成「—」，而不是 0、不是空、不是「暂无数据」。</b>
 * 前端模型是最容易把后端辛苦守住的 null 又悄悄变成 0 或空数组的地方 ——
 * 后端返回 null 是对的，`detail.packages ?? []` 一写，界面上就变成
 * 「这单没有包裹」，而真相是「这段能力还没接入」。
 */
describe('9.1.1.2 订单详情模型：拿不到不等于没有', () => {
  describe('formatAmount', () => {
    it('null / undefined / 空串一律显示「—」，绝不显示 0', () => {
      expect(formatAmount(null)).toBe('—');
      expect(formatAmount(undefined)).toBe('—');
      expect(formatAmount('')).toBe('—');
      // ⚠️ 真正的 0 必须显示 0 —— 「这一行税额是 0」与「税额拿不到」是两回事。
      expect(formatAmount(0)).toBe('0');
      expect(formatAmount('0.0000')).toBe('0.0000');
      expect(formatAmount('1234.5600')).toBe('1234.5600');
    });
  });

  describe('unavailableCell', () => {
    const section = {
      ownerModule: '8.3.4.1 发货与包裹',
      reason: '8.3.4.1 未交付。刻意不返回空数组：空的包裹列表会被读成「这单没有包裹」。',
    };

    it('值为 null 时返回灰块文案并带上归属模块与原因', () => {
      const cell = unavailableCell(null, section);
      expect(cell).not.toBeNull();
      expect(cell?.text).toBe('—');
      expect(cell?.title).toContain('尚未接入');
      expect(cell?.title).toContain('8.3.4.1');
      // 原因必须原样透出：这一句正是「没接入」与「查过了没有」的分野。
      expect(cell?.title).toContain('会被读成');
      expect(cell?.title).toContain('这单没有包裹');
    });

    it('⚠️ 有值时必须返回 null —— 否则真数据会被灰块盖掉', () => {
      // 空数组也算「有值」：真的没有包裹是一回事，能力没接入是另一回事。
      // 这里用非空数组，以及刻意构造的空数组两种都钉住，
      // 因为「?? []」的写法恰恰是把空数组送进来的那条路。
      expect(unavailableCell([{ id: 'p1' }], section)).toBeNull();
      expect(unavailableCell([], section)).toBeNull();
      expect(unavailableCell({ any: 1 }, section)).toBeNull();
      expect(unavailableCell('anything', section)).toBeNull();
    });

    it('section 缺失时也要给出可读文案，不能抛异常或显示空白', () => {
      const cell = unavailableCell(null, undefined);
      expect(cell?.text).toBe('—');
      expect(cell?.title).toContain('待定');
    });
  });

  describe('allocationBanner：费用构成 ≠ 费用分摊', () => {
    it('分摊为 null 时给出横幅，且文案点明构成不是分摊', () => {
      const banner = allocationBanner(null, {
        ownerModule: '9.1.2.2',
        reason: '本服务没有费用分摊表：V99 只建了 order_charge（费用构成）。',
      });
      expect(banner?.title).toBe('费用分摊尚未接入');
      expect(banner?.body).toContain('没有费用分摊表');
      expect(banner?.body).toContain('order_charge');
    });

    it('section 缺失时也有兜底文案，且仍然点明「不是分摊」', () => {
      const banner = allocationBanner(null, undefined);
      expect(banner?.title).toBe('费用分摊尚未接入');
      expect(banner?.body).toContain('费用构成');
      expect(banner?.body).toContain('不是分摊');
    });

    it('⚠️ 分摊有值时横幅消失（别把真数据也标成未接入）', () => {
      expect(allocationBanner([{ lineId: 'l1', amount: '1.00' }], undefined)).toBeNull();
    });
  });

  describe('actionDisabled：本模块没有任何写端点', () => {
    it('四条操作入口一律禁用并写明归属与前置条件', () => {
      for (const code of ['REVIEW', 'NOTE', 'CANCEL', 'DUNNING']) {
        const hint = actionDisabled(code, {
          ownerModule: '9.2 审单与异常',
          precondition: '9.2 交付待审队列后',
          reason: '审单未交付',
        });
        expect(hint.disabled).toBe(true);
        expect(hint.title).toContain('暂不可用');
        expect(hint.title).toContain('9.2 审单与异常');
        expect(hint.title).toContain('前置条件');
        expect(hint.title).toContain(code);
      }
    });

    it('action 缺失时也不抛异常', () => {
      const hint = actionDisabled('REVIEW', undefined);
      expect(hint.disabled).toBe(true);
      expect(hint.title).toContain('待定');
    });

    it('⚠️ 备注必须归 9.1.2.2（sales_order.note 的写入所有者），不是 9.2', () => {
      const hint = actionDisabled('NOTE', {
        ownerModule: '9.1.2.2 手工订单（sales_order.note 的写入所有者）',
        reason: '本模块是读投影，不抢写它的事实表。',
      });
      expect(hint.title).toContain('9.1.2.2');
      expect(hint.title).not.toContain('9.2');
    });
  });

  describe('不可得分区清单', () => {
    it('固定九段且顺序稳定（与后端 capabilities 对读）', () => {
      expect([...UNAVAILABLE_SECTION_ORDER]).toEqual([
        'chargeAllocations',
        'lineComponents',
        'payments',
        'refunds',
        'fulfillmentBatches',
        'packages',
        'logisticsTrace',
        'aftersalesRefs',
        'platformRawPayload',
      ]);
    });

    it('每一段都有中文标签，未知键回退成原键而不是空白', () => {
      for (const key of UNAVAILABLE_SECTION_ORDER) {
        expect(unavailableSectionLabel(key)).not.toBe(key);
      }
      expect(unavailableSectionLabel('fulfillmentStatus')).toBe('履约状态（时间线）');
      expect(unavailableSectionLabel('somethingNew')).toBe('somethingNew');
    });
  });

  describe('词表：语义不能被标签改写', () => {
    it('⚠️ 单据状态不叫「履约状态」—— DRAFT/SUBMITTED 说的是有没有进审单队列', () => {
      expect(documentStatusLabels.DRAFT).toBe('草稿');
      expect(documentStatusLabels.SUBMITTED).toBe('已提交待审');
      // 任何一条都不得出现「履约」二字。
      for (const label of Object.values(documentStatusLabels)) {
        expect(label).not.toContain('履约');
        expect(label).not.toContain('发货');
      }
    });

    it('⚠️ 费用类型标签不得暗示这是分摊', () => {
      expect(chargeTypeLabels.SHIPPING).toBe('运费');
      for (const label of Object.values(chargeTypeLabels)) {
        expect(label).not.toContain('分摊');
      }
    });

    it('手工单子类覆盖 V99 的 CHECK 词表', () => {
      expect(Object.keys(manualKindLabels).sort()).toEqual(
        ['B2B', 'DROPSHIP', 'OFFLINE_STORE', 'SAMPLE'].sort(),
      );
    });
  });

  describe('formatTime', () => {
    it('null 显示「—」，ISO 串转可读时间', () => {
      expect(formatTime(null)).toBe('—');
      expect(formatTime(undefined)).toBe('—');
      expect(formatTime('')).toBe('—');
      expect(formatTime('2026-10-11T08:30:00Z')).toMatch(/^2026-10-11 08:30:00$/);
    });

    it('⚠️ 解析不了的串原样返回，不显示成「Invalid Date」', () => {
      expect(formatTime('not-a-date')).toBe('not-a-date');
    });
  });
});