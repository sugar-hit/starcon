import { describe, expect, it } from 'vitest';
import type {
  OutboundOrderDetail,
  OutboundQuantityAvailability,
  OutboundUnavailableLink,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  actionButtonText,
  detailHref,
  eventText,
  pieceText,
  quantityCell,
  quantityHint,
  timeText,
  unavailableHint,
} from './outbound-order-detail-model';

/**
 * 8.3.1.2 详情页展示口径的单测。
 *
 * <p>重点盯两件**错了会造成业务事故**的事：
 *
 * <ul>
 *   <li>已拣量/已发量拿不到时，界面**不能**显示成 0（`quantityCell` 返回 null 而非 '0'）。
 *   <li>四段不可得能力必须显示归属模块，而不是画成空列表。
 * </ul>
 */

const pickedNull: OutboundQuantityAvailability = {
  quantity: 'PICKED',
  label: '已拣量',
  value: null,
  available: false,
  ownerModule: '8.3.2.1',
  reason: '8.3.2.1 拣货任务尚未交付。',
};

const shippedFilled: OutboundQuantityAvailability = {
  quantity: 'SHIPPED',
  label: '已发量',
  value: 5.5,
  available: true,
  ownerModule: null,
  reason: '终态 + 明细不可改，已发量等于计划件数。',
};

const packagesLink: OutboundUnavailableLink = {
  label: '包裹列表',
  available: false,
  ownerModule: '8.3.4.1',
  href: '/warehouse/outbound',
  reason: '8.3.4.1 发货交接尚未交付。',
};

describe('8.3.1.2 出库单详情展示口径', () => {
  it('件数去掉尾随零但不丢精度', () => {
    expect(pieceText('5.500000')).toBe('5.5');
    expect(pieceText('2.000000')).toBe('2');
    expect(pieceText('0.123456')).toBe('0.123456');
    // 没有事实时显示破折号，绝不显示 0。
    expect(pieceText(null)).toBe('—');
    expect(pieceText(undefined)).toBe('—');
    expect(pieceText('')).toBe('—');
  });

  it('拿不到的数量返回null而不是0', () => {
    // 这是本模块最要紧的一条：渲染成 0 会让作业员以为一件都没拣，然后重复拣一遍。
    expect(quantityCell(pickedNull)).toBeNull();
    expect(quantityCell(pickedNull)).not.toBe('0');
    expect(quantityCell(shippedFilled)).toBe('5.5');
  });

  it('数量不可得时给出归属模块与「不是0」的提示', () => {
    const hint = quantityHint(pickedNull);
    expect(hint).toContain('8.3.2.1');
    expect(hint).toContain('不是 0');
    // 可得的量不产生提示。
    expect(quantityHint(shippedFilled)).toBe('');
  });

  it('不可得能力带出归属模块', () => {
    expect(unavailableHint(packagesLink)).toContain('8.3.4.1');
    // ownerModule 为空时（承运渠道那种本服务已持有的事实）只给原因。
    expect(unavailableHint({ ...packagesLink, ownerModule: null })).toBe(packagesLink.reason);
  });

  it('时间线文案读得出状态跃迁', () => {
    const base = {
      id: 'e1',
      eventType: 'STATUS_CHANGED',
      eventTypeLabel: '状态推进',
      fromStatusLabel: null,
      toStatusLabel: null,
      actorUserId: 'u1',
      occurredAt: '2026-10-07T10:00:00Z',
      detail: 'x',
    };
    expect(
      eventText({
        ...base,
        fromStatus: 'PICKING',
        fromStatusLabel: '拣货中',
        toStatus: 'PENDING_RECHECK',
        toStatusLabel: '待复核',
      }),
    ).toBe('拣货中 → 待复核');
    // 建单没有起始状态，但仍要说清落点。
    expect(
      eventText({ ...base, fromStatus: null, toStatus: 'PENDING_PICKING', toStatusLabel: '待拣' }),
    ).toBe('进入 待拣');
    // 标签缺失时回退到枚举名 —— 静默吞掉会让人以为这条事件没有状态。
    expect(eventText({ ...base, fromStatus: 'PICKING', toStatus: 'PENDING_RECHECK' })).toBe(
      'PICKING → PENDING_RECHECK',
    );
    // 打印两个状态都有、但状态没变。
    expect(
      eventText({
        ...base,
        fromStatus: 'PICKING',
        fromStatusLabel: '拣货中',
        toStatus: 'PICKING',
        toStatusLabel: '拣货中',
      }),
    ).toBe('拣货中 → 拣货中');
    // 两个状态都没有（理论上不该发生）时退回事件类型，而不是显示空白。
    expect(eventText({ ...base, fromStatus: null, toStatus: null })).toBe('状态推进');
  });

  it('时间戳不可解析时原样返回而不是显示Invalid Date', () => {
    expect(timeText(null)).toBe('—');
    expect(timeText('不是时间')).toBe('不是时间');
    expect(timeText('2026-10-07T10:00:00Z')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  });

  it('推进按钮标出哪些要填原因', () => {
    const base = {
      action: 'TRANSITION',
      toStatus: 'PENDING_PACKING',
      toStatusLabel: '待打包',
      requiresCarrier: false,
      note: '复核通过',
      ownerModule: '8.3.3.2 打包与称重',
    };
    expect(actionButtonText({ ...base, reasonRequired: false })).toBe('推进到「待打包」');
    expect(actionButtonText({ ...base, reasonRequired: true })).toBe('推进到「待打包」（需填原因）');
  });

  it('详情链接稳定', () => {
    expect(detailHref('abc-123')).toBe('/warehouse/outbound/abc-123');
  });

  it('详情的真实形状满足前端渲染所需（类型层钉住字段名）', () => {
    const detail: OutboundOrderDetail = {
      id: 'o1',
      orderCode: 'OB-20261007-ABCDEF01',
      sourceType: 'SALES',
      sourceTypeLabel: '销售',
      sourceTransferOrderId: null,
      sourceTransferCode: null,
      sourceExternalRef: 'SO-1',
      warehouseId: 'w1',
      warehouseCode: 'WH-ONE',
      warehouseName: '一仓',
      carrierCode: '',
      status: 'PENDING_PICKING',
      statusLabel: '待拣',
      terminal: false,
      shippedAt: null,
      cancelledAt: null,
      cancelReason: null,
      createdByUserId: 'u1',
      createdAt: '2026-10-07T10:00:00Z',
      skuLineCount: 2,
      totalPieceCount: 5.5,
      lines: [
        {
          lineNo: 1,
          skuId: 's1',
          skuCode: 'SKU-1',
          skuName: '商品一',
          plannedQuantity: 2.5,
          pickedQuantity: null,
          shippedQuantity: null,
        },
      ],
      quantities: [pickedNull, shippedFilled],
      pickRequested: false,
      pickRequest: null,
      timeline: [],
      timelineEventCount: 0,
      linkedOrder: packagesLink,
      shipToAddress: packagesLink,
      reservationRefs: packagesLink,
      packages: [packagesLink],
      actions: [],
    };
    // 未交接时 pickRequest 是 null 而不是空对象：界面据此显示「尚未交接」。
    expect(detail.pickRequest).toBeNull();
    expect(detail.lines[0]?.pickedQuantity).toBeNull();
  });
});