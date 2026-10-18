import { describe, expect, it } from 'vitest';
import type { RmaReturnableLine } from '../../../../../packages/api-contracts/src/index';
import {
  actionButton,
  addressText,
  amount,
  dispositionHint,
  eventText,
  quantity,
  receivedHint,
  receivedQuantityText,
  remainingText,
  returnableText,
  shippedSourceText,
  stateTone,
  timelinessTone,
} from './rma-authorization-model';

/**
 * 11.2.1.1 退货授权单的前端口径测试。
 *
 * <p>这些断言盯的是**页面上最容易说错话的地方**：蓝图加粗句「授权不等于已收到」，
 * 以及职责句里「原订单行已退/已发数量约束」这两处 ——
 * 它们一旦在前端被简化成「可退 0」「已收到」，运营就会照着错的口径做决定。
 */

function line(over: Partial<RmaReturnableLine> = {}): RmaReturnableLine {
  return {
    caseLineId: '11111111-1111-1111-1111-111111111111',
    orderLineId: '22222222-2222-2222-2222-222222222222',
    skuCode: 'SKU-1',
    skuName: '商品',
    caseRequestedQty: 3,
    orderedQty: 5,
    shippedQty: 3,
    shippedQuantitySource: 'MODULE_9_3_3_1_FULFILLMENT_PROGRESS',
    receivedQty: null,
    receivedQuantitySource: 'NOT_DELIVERED_11_2_2_2',
    inflightAuthorizedQty: 0,
    returnableQty: 3,
    refundableAmount: 15,
    currency: 'USD',
    blockingReasons: [],
    ...over,
  } as RmaReturnableLine;
}

describe('可退数量的三种读法必须分开', () => {
  it('正常行显示可退数量', () => {
    const r = returnableText(line());
    expect(r.value).toBe('3');
    expect(r.tone).toBe('success');
  });

  it('被挡住时显示「不可退」而不是 0', () => {
    // 没发出去的货不能退 —— 与「额度被别人占满」是两回事，不能合成一个 0。
    const r = returnableText(
      line({ returnableQty: 0, blockingReasons: ['该订单行已发数量为 0：货还没发出去，不能退'] }),
    );
    expect(r.value).toBe('不可退');
    expect(r.tone).toBe('danger');
    // ⚠️ 绝不能读成 "0"：那会让「货没发」被当成「额度用完」。
    expect(r.value).not.toBe('0');
  });

  it('额度被别人占满是第三种读法', () => {
    const r = returnableText(line({ returnableQty: 0, inflightAuthorizedQty: 3 }));
    expect(r.value).toBe('额度已占满');
    expect(r.tone).toBe('warning');
  });
});

describe('已退数量不可得不显示 0', () => {
  it('11.2.2.2 未交付时如实说不可得', () => {
    expect(receivedQuantityText(line())).toBe('不可得（11.2.2.2 未交付）');
    // ⚠️ 显示 "0" 会让运营以为「这行从来没退过」，在重复退货面前失去唯一提醒。
    expect(receivedQuantityText(line())).not.toBe('0');
  });

  it('已交付后照常显示数值', () => {
    expect(
      receivedQuantityText(
        line({ receivedQty: 2, receivedQuantitySource: 'MODULE_11_2_2_2_RECEIPT_ALLOCATION' }),
      ),
    ).toBe('2');
  });
});

describe('已发数量来源如实标注', () => {
  it('没有履约投影时说清按 0 处理', () => {
    const l = line({ shippedQty: null, shippedQuantitySource: 'NO_FULFILLMENT_PROGRESS' });
    expect(shippedSourceText(l)).toContain('没有履约进度投影');
    expect(shippedSourceText(l)).toContain('按 0');
  });

  it('有投影时标注来源模块', () => {
    expect(shippedSourceText(line())).toContain('9.3.3.1');
  });
});

describe('寄回时限读数', () => {
  it('负数读成「已超时」而不是负天数', () => {
    expect(remainingText(-86400)).toBe('已超时');
    expect(remainingText(-1)).toBe('已超时');
  });

  it('null 显示破折号而不是 0', () => {
    expect(remainingText(null)).toBe('—');
    expect(remainingText(undefined)).toBe('—');
  });

  it('按天 / 小时 / 分钟降级', () => {
    expect(remainingText(3 * 86400)).toBe('剩 3 天');
    expect(remainingText(5 * 3600)).toBe('剩 5 小时');
    expect(remainingText(120)).toBe('剩 2 分钟');
    expect(remainingText(0)).toBe('剩 1 分钟');
  });
});

/** RmaActionEntry 的每个字段都是必带（可为 null）—— 手写全字段而不是 `as never`，
 *  这样字段一改，测试就会在这里报出来而不是静默继续。 */
function entry(over: Partial<import('../../../../../packages/api-contracts/src/index').RmaActionEntry> = {}) {
  return {
    code: 'DECIDE',
    label: '审批',
    allowed: false,
    method: 'POST',
    path: '/trade/rma-authorizations/{id}/decide',
    required: true,
    precondition: null,
    blockingReason: null,
    ownerModule: null,
    unavailableReason: null,
    ...over,
  };
}

describe('动作按钮不可用时必须带理由', () => {
  it('允许时是 default（可点）', () => {
    const b = actionButton(entry({ allowed: true }));
    expect(b.variant).toBe('default');
    expect(b.disabled).toBe(false);
  });

  it('禁止时把 blockingReason 原样带出来', () => {
    // 按钮变灰而不给理由，会让人以为系统坏了。
    const b = actionButton(entry({ allowed: false, blockingReason: '只有草稿可审批' }));
    expect(b.disabled).toBe(true);
    expect(b.reason).toBe('只有草稿可审批');
  });

  it('缺失动作时也不假装可用', () => {
    expect(actionButton(null).disabled).toBe(true);
  });
});

describe('状态与时效色调', () => {
  it('在途是 info、拒绝是 danger、超时是 warning', () => {
    expect(stateTone('AUTHORIZED')).toBe('info');
    expect(stateTone('PARTIALLY_AUTHORIZED')).toBe('info');
    expect(stateTone('REJECTED')).toBe('danger');
    expect(stateTone('EXPIRED')).toBe('warning');
    expect(stateTone('CANCELLED')).toBe('neutral');
    expect(stateTone('DRAFT')).toBe('neutral');
  });

  it('时效三态分得开', () => {
    expect(timelinessTone('OVERDUE')).toBe('danger');
    expect(timelinessTone('DUE_SOON')).toBe('warning');
    expect(timelinessTone('ON_TRACK')).toBe('success');
    expect(timelinessTone('CLOSED')).toBe('neutral');
  });
});

describe('格式化', () => {
  it('数量去尾零但保精度', () => {
    expect(quantity(3.000001)).toBe('3.000001');
    expect(quantity('2.500000')).toBe('2.5');
    expect(quantity(null)).toBe('—');
    expect(quantity('')).toBe('—');
  });

  it('金额两位小数', () => {
    expect(amount(15)).toBe('15.00');
    expect(amount(null)).toBe('—');
  });

  it('地址跳过空段，不出现连续空格', () => {
    const text = addressText({
      returnRecipientName: '退货仓',
      returnAddressLine1: '88 Yonge St',
      returnAddressLine2: '',
      returnCity: 'TORONTO',
      returnProvince: 'ON',
      returnCountryCode: 'CA',
      returnPostcode: 'M5V 2T6',
    } as never);
    expect(text).toBe('退货仓，88 Yonge St，TORONTO ON CA，M5V 2T6');
    expect(text).not.toContain('，，');
  });
});

describe('文案必须带上它要防的误解', () => {
  it('receivedHint 说清没有「登记已收到」', () => {
    expect(receivedHint).toContain('11.2.2.1');
    expect(receivedHint).toContain('没有');
    expect(receivedHint).toContain('超时');
  });

  it('dispositionHint 说清是意图不是结论', () => {
    expect(dispositionHint).toContain('意图');
    expect(dispositionHint).toContain('11.2.3.2');
  });

  it('审批留痕读出状态变化', () => {
    expect(
      eventText({
        eventType: 'EXPIRED',
        eventTypeLabel: '超时未寄，自动关闭',
        fromStatus: 'AUTHORIZED',
        toStatus: 'EXPIRED',
        reasonNote: '',
        occurredBy: 'x',
        occurredAt: '2026-01-01T00:00:00Z',
        id: 'x',
      }),
    ).toBe('超时未寄，自动关闭（AUTHORIZED → EXPIRED）');
  });
});
