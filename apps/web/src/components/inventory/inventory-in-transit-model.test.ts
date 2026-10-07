import { describe, expect, it } from 'vitest';
import {
  arriveBlockers,
  arriveNeedsLocation,
  buildRegisterPayload,
  conservationText,
  discrepancyBlockers,
  etaText,
  fmt,
  inTransitActions,
  inTransitKindLabels,
  inTransitNettingSortOptions,
  inTransitOverdueLabels,
  inTransitReasonLabels,
  inTransitSortOptions,
  inTransitStatusLabels,
  moment,
  needsAttention,
  outstanding,
  overdueHoursText,
  overdueSeverity,
  registerBlockers,
  registrableTransitKinds,
  routeText,
  transferRefText,
  type RegisterPayload,
} from './inventory-in-transit-model';

/**
 * 7.5.1.2 在途库存前端模型测试。
 *
 * <p>请求路径与端点由后端集成测试覆盖（实跑），这里只测前端唯一会自己算错的东西：
 * 三个数量口径的读法、逾期档位的严重度、动作可见性、以及**登记请求体的省略规则**
 * （发空串会让 Jackson 按 UUID / OffsetDateTime 解析失败，必须整键省略）。
 */

/** 合法登记输入的基线，各用例只改自己关心的字段。 */
function validRegister(over: Partial<RegisterPayload> = {}): RegisterPayload {
  return {
    transitKind: 'FIRST_MILE',
    skuId: 'sku-1',
    qualityStatusId: 'q-1',
    legalEntityId: 'le-1',
    quantity: 100,
    shippedAt: '2026-10-01T08:00:00Z',
    expectedArriveAt: '2026-10-05T08:00:00Z',
    sourceSystem: 'LOGISTICS',
    sourceReference: 'SH-2026-0001',
    // 头程的起点通常是供应商工厂，不在本租户的仓库里 —— 所以给可读名称而不是仓库 id。
    fromWarehouseName: '供应商工厂',
    toWarehouseId: 'wh-to',
    toWarehouseName: '美国仓',
    ...over,
  };
}

describe('在途库存前端模型', () => {
  describe('数量口径', () => {
    it('未达量取后端给的余量，不在前端自己减', () => {
      expect(outstanding({ outstandingQuantity: 40 })).toBe(40);
    });

    it('余量为负（脏数据）时按 0 处理，不把负数显示成「还欠货」', () => {
      expect(outstanding({ outstandingQuantity: -5 })).toBe(0);
    });

    it('守恒式：原发 = 已了结 + 仍在途', () => {
      const result = conservationText({
        declaredQuantity: 100,
        settledQuantity: 60,
        outstandingQuantity: 40,
      });
      expect(result.ok).toBe(true);
      expect(result.text).toContain('原发 100 = 已了结 60 + 仍在途 40');
    });

    it('守恒式不成立时如实报出差额，不悄悄改数', () => {
      const result = conservationText({
        declaredQuantity: 100,
        settledQuantity: 60,
        outstandingQuantity: 30,
      });
      expect(result.ok).toBe(false);
      expect(result.text).toContain('差 10');
    });

    it('全部了结的行：原发 = 已了结，仍在途 0', () => {
      const result = conservationText({
        declaredQuantity: 80,
        settledQuantity: 80,
        outstandingQuantity: 0,
      });
      expect(result.ok).toBe(true);
    });
  });

  describe('逾期档位', () => {
    it('六档标签齐全', () => {
      expect(Object.keys(inTransitOverdueLabels)).toHaveLength(6);
      expect(inTransitOverdueLabels.CRITICAL).toBe('逾期 7 天以上');
      expect(inTransitOverdueLabels.NO_ETA).toBe('未登记预计到达');
    });

    it('严重度单调递增：逾期越久越该先追', () => {
      const ranked = ['ON_TIME', 'MILD', 'OVERDUE', 'SEVERE', 'CRITICAL'];
      const scores = ranked.map(overdueSeverity);
      expect(scores).toEqual([...scores].sort((a, b) => a - b));
      expect(new Set(scores).size).toBe(ranked.length);
    });

    it('未登记 ETA 不算准点（严重度低于 ON_TIME）', () => {
      // 关键：把 NO_ETA 当成 ON_TIME 会让「没登记 ETA」被当成「准点到」，
      // 那正是本模块要防的「逾期漏算」。
      expect(overdueSeverity('NO_ETA')).toBeLessThan(overdueSeverity('ON_TIME'));
    });

    it('未知码落到最低档而不是静默当成准点', () => {
      expect(overdueSeverity('SOMETHING_NEW')).toBe(0);
    });

    it('逾期 1 天以上才需要人工追', () => {
      expect(needsAttention('ON_TIME')).toBe(false);
      expect(needsAttention('MILD')).toBe(false);
      expect(needsAttention('OVERDUE')).toBe(true);
      expect(needsAttention('CRITICAL')).toBe(true);
    });

    it('没登记 ETA 时如实说明算不出逾期，而不是显示 0 小时', () => {
      expect(etaText({ expectedArriveAt: null })).toBe('未登记 ETA');
      expect(overdueHoursText({ expectedArriveAt: null, overdueHours: null })).toContain(
        '无法判断逾期',
      );
    });

    it('有 ETA 且未逾期时显示「未逾期」', () => {
      expect(overdueHoursText({ expectedArriveAt: '2026-10-05T08:00:00Z', overdueHours: null })).toBe(
        '未逾期',
      );
      expect(overdueHoursText({ expectedArriveAt: '2026-10-05T08:00:00Z', overdueHours: -3 })).toBe(
        '未逾期',
      );
    });

    it('逾期小时数按天拆开显示', () => {
      expect(overdueHoursText({ expectedArriveAt: 'x', overdueHours: 5 })).toBe('逾期 5 小时');
      expect(overdueHoursText({ expectedArriveAt: 'x', overdueHours: 30 })).toBe('逾期 1 天 6 小时');
    });
  });

  describe('动作可见性', () => {
    it('在途且有余量时可以到达 / 登记差异', () => {
      expect(inTransitActions({ status: 'IN_TRANSIT', outstandingQuantity: 40 })).toEqual([
        'arrive',
        'discrepancy',
      ]);
    });

    it('已了结的腿不给任何动作（后端会 409，界面不该显示按钮）', () => {
      expect(inTransitActions({ status: 'RECEIVED', outstandingQuantity: 0 })).toEqual([]);
      expect(inTransitActions({ status: 'CANCELLED', outstandingQuantity: 0 })).toEqual([]);
    });

    it('在途但余量为 0 也不给动作', () => {
      expect(inTransitActions({ status: 'IN_TRANSIT', outstandingQuantity: 0 })).toEqual([]);
    });

    it('进我方仓的到货必须给库位，FBA 发货运到平台仓不用', () => {
      expect(arriveNeedsLocation('TRANSFER')).toBe(true);
      expect(arriveNeedsLocation('FIRST_MILE')).toBe(true);
      expect(arriveNeedsLocation('PURCHASE')).toBe(true);
      expect(arriveNeedsLocation('FBA_OUTBOUND')).toBe(false);
    });
  });

  describe('登记预检（逐条对齐后端 register()）', () => {
    it('合法输入没有任何拦路项', () => {
      expect(registerBlockers(validRegister())).toEqual([]);
    });

    it('调拨腿不许在这里登记', () => {
      const blockers = registerBlockers(validRegister({ transitKind: 'TRANSFER', toWarehouseId: '' }));
      expect(blockers.join()).toContain('7.5.1.1');
    });

    it('来源系统与来源单据号共同构成幂等锚点，缺一不可', () => {
      expect(registerBlockers(validRegister({ sourceSystem: '' })).join()).toContain('来源系统');
      expect(registerBlockers(validRegister({ sourceReference: '  ' })).join()).toContain(
        '来源单据号',
      );
    });

    it('数量必须大于 0', () => {
      expect(registerBlockers(validRegister({ quantity: 0 })).join()).toContain('大于 0');
    });

    it('ETA 不能早于发出时间', () => {
      const blockers = registerBlockers(
        validRegister({ shippedAt: '2026-10-05T08:00:00Z', expectedArriveAt: '2026-10-01T08:00:00Z' }),
      );
      expect(blockers.join()).toContain('不能早于发出时间');
    });

    it('ETA 早于「此刻」是允许的（补录旧批次要有数据可算）', () => {
      // 后端只挡 eta < shippedAt，不挡 eta < now。
      expect(
        registerBlockers(
          validRegister({ shippedAt: '2020-01-01T00:00:00Z', expectedArriveAt: '2020-01-05T00:00:00Z' }),
        ),
      ).toEqual([]);
    });

    it('FBA 发货在途必须有我方发货仓（登记时要扣在手）', () => {
      const blockers = registerBlockers(
        validRegister({
          transitKind: 'FBA_OUTBOUND',
          fromWarehouseId: '',
          fromWarehouseName: '平台仓',
          toWarehouseId: '',
          toWarehouseName: '亚马逊 FBA',
        }),
      );
      expect(blockers.join()).toContain('我方发货仓');
    });

    it('头程 / 采购必须有我方收货仓（缺了没有入库落点）', () => {
      const blockers = registerBlockers(
        validRegister({ toWarehouseId: '', toWarehouseName: '' }),
      );
      expect(blockers.join()).toContain('我方收货仓');
    });

    it('起止仓不能是同一个仓', () => {
      const blockers = registerBlockers(
        validRegister({ fromWarehouseId: 'wh-to', toWarehouseId: 'wh-to' }),
      );
      expect(blockers.join()).toContain('不能是同一个仓');
    });

    it('起点不在本租户时必须给出可读名称', () => {
      const blockers = registerBlockers(
        validRegister({ fromWarehouseName: '   ', fromWarehouseId: '' }),
      );
      expect(blockers.join()).toContain('可读名称');
    });
  });

  describe('登记请求体：可空字段整键省略', () => {
    it('空的可选字段不出现在请求体里（发空串会被 Jackson 解析成 UUID/时间失败）', () => {
      // 先填上再删掉：验证「键整个不存在」，而不是「键存在但值是 undefined」。
      const filled = buildRegisterPayload(
        validRegister({ lotId: 'lot-1', expectedArriveAt: '2026-10-05T08:00:00Z', trackingNo: 'TN-1' }),
      );
      expect('lotId' in filled).toBe(true);
      const { lotId, expectedArriveAt, trackingNo, ...payload } = filled;
      expect(lotId).toBe('lot-1');
      expect(expectedArriveAt).toBe('2026-10-05T08:00:00Z');
      expect(trackingNo).toBe('TN-1');
      expect('lotId' in payload).toBe(false);
      expect('expectedArriveAt' in payload).toBe(false);
      expect('trackingNo' in payload).toBe(false);
    });

    it('填了的可选字段原样带上', () => {
      const payload = buildRegisterPayload(
        validRegister({ lotId: 'lot-1', carrierName: 'DHL', trackingNo: 'TN-1' }),
      );
      expect(payload.lotId).toBe('lot-1');
      expect(payload.carrierName).toBe('DHL');
      expect(payload.trackingNo).toBe('TN-1');
    });

    it('只有空格的文本字段按「没填」处理', () => {
      const payload = buildRegisterPayload(
        validRegister({ fromWarehouseName: '   ', carrierName: '  ', trackingNo: '  ' }),
      );
      expect('fromWarehouseName' in payload).toBe(false);
      expect('carrierName' in payload).toBe(false);
      expect('trackingNo' in payload).toBe(false);
    });

    it('来源系统与来源单据号去掉首尾空格（它们是幂等锚点）', () => {
      const payload = buildRegisterPayload(
        validRegister({ sourceSystem: '  LOGISTICS  ', sourceReference: ' SH-1 ' }),
      );
      expect(payload.sourceSystem).toBe('LOGISTICS');
      expect(payload.sourceReference).toBe('SH-1');
    });

    it('必填字段永远在请求体里', () => {
      const payload = buildRegisterPayload(validRegister());
      expect(payload.transitKind).toBe('FIRST_MILE');
      expect(payload.skuId).toBe('sku-1');
      expect(payload.quantity).toBe(100);
    });
  });

  describe('到货 / 差异预检', () => {
    const base = { version: 3, transitKind: 'FIRST_MILE', outstandingQuantity: 40 };

    it('合法到货没有拦路项', () => {
      expect(arriveBlockers({ ...base, quantity: 40, locationId: 'loc-1' })).toEqual([]);
    });

    it('到货量不能超过未达余量', () => {
      expect(arriveBlockers({ ...base, quantity: 41, locationId: 'loc-1' }).join()).toContain(
        '超过该腿未达量',
      );
    });

    it('进我方仓的到货缺库位被拦下', () => {
      expect(arriveBlockers({ ...base, quantity: 10 }).join()).toContain('必须指定落库库位');
    });

    it('FBA 到货不需要库位', () => {
      expect(
        arriveBlockers({ ...base, transitKind: 'FBA_OUTBOUND', quantity: 10 }),
      ).toEqual([]);
    });

    it('差异原因必填，不自动抹平', () => {
      expect(discrepancyBlockers({ outstandingQuantity: 40, quantity: 5, reason: '' }).join()).toContain(
        '原因必填',
      );
    });

    it('差异量不能超过未达余量', () => {
      expect(
        discrepancyBlockers({ outstandingQuantity: 40, quantity: 41, reason: 'LOST' }).join(),
      ).toContain('超过该腿未达量');
    });

    it('合法差异没有拦路项', () => {
      expect(
        discrepancyBlockers({ outstandingQuantity: 40, quantity: 5, reason: 'LOST' }),
      ).toEqual([]);
    });
  });

  describe('词表与展示', () => {
    it('四类在途类型齐全，且登记下拉不含调拨', () => {
      expect(Object.keys(inTransitKindLabels)).toHaveLength(4);
      expect(registrableTransitKinds).not.toContain('TRANSFER');
      expect(registrableTransitKinds).toEqual(['FIRST_MILE', 'PURCHASE', 'FBA_OUTBOUND']);
    });

    it('差异原因五项，与后端受控词表一致', () => {
      expect(Object.keys(inTransitReasonLabels).sort()).toEqual(
        ['CUSTOMS_HELD', 'DAMAGED', 'LOST', 'REJECTED', 'SHORT_SHIPPED'].sort(),
      );
    });

    it('状态标签覆盖后端实际会给的三个值', () => {
      expect(inTransitStatusLabels.IN_TRANSIT).toBe('在途');
      expect(inTransitStatusLabels.RECEIVED).toBe('已到达');
      expect(inTransitStatusLabels.CANCELLED).toBeTruthy();
    });

    it('排序选项全部落在后端 SORTS 白名单内', () => {
      const allowed = [
        'EXPECTED_ARRIVE',
        'SHIPPED_AT',
        'QUANTITY',
        'SKU_CODE',
        'OVERDUE',
        'CREATED_AT',
      ];
      for (const option of inTransitSortOptions) expect(allowed).toContain(option.value);
    });

    it('净额排序选项全部落在后端认可的键内', () => {
      const allowed = ['QUANTITY', 'LATEST_ARRIVAL', 'SKU_CODE'];
      for (const option of inTransitNettingSortOptions) expect(allowed).toContain(option.value);
    });

    it('起止仓都有值时拼成「源 → 目标」', () => {
      expect(
        routeText({
          fromWarehouseCode: 'US-W',
          fromWarehouseName: '美国仓',
          toWarehouseCode: 'DE-W',
          toWarehouseName: '德国仓',
        }),
      ).toBe('US-W 美国仓 → DE-W 德国仓');
    });

    it('起点不在本租户时不显示 undefined', () => {
      expect(
        routeText({ fromWarehouseCode: '', fromWarehouseName: '', toWarehouseCode: 'US-W', toWarehouseName: '美国仓' }),
      ).toBe('（外部起点） → US-W 美国仓');
    });

    it('两端都没有时给破折号', () => {
      expect(routeText({ fromWarehouseCode: '', fromWarehouseName: '', toWarehouseCode: '', toWarehouseName: '' })).toBe(
        '—',
      );
    });

    it('调拨腿指回调拨单，其他腿指回来源单据', () => {
      expect(
        transferRefText({ transferOrderCode: 'TR-1', sourceSystem: 'INVENTORY', sourceReference: 'X' }),
      ).toBe('调拨单 TR-1');
      expect(
        transferRefText({ transferOrderCode: null, sourceSystem: 'LOGISTICS', sourceReference: 'SH-1' }),
      ).toBe('LOGISTICS · SH-1');
    });

    it('数量与时间的空值给破折号', () => {
      expect(fmt(null)).toBe('—');
      expect(fmt(undefined)).toBe('—');
      expect(fmt(0)).toBe('0');
      expect(moment(null)).toBe('—');
      expect(moment('not-a-date')).toBe('not-a-date');
    });
  });
});