import { describe, expect, it } from 'vitest';
import type { RmaInstructionAction, RmaInstructionEvent, RmaTrackingEvent } from '../../../../../packages/api-contracts/src/index';
import {
  actionButton,
  addressText,
  eventText,
  goodsReceivedHint,
  labelIntegrityHint,
  lagText,
  quantity,
  remainingText,
  stateTone,
  timelinessTone,
  trackingEventText,
  trackingTone,
  unavailableText,
} from './rma-instruction-model';

/**
 * 11.2.1.2 退货指令下发的展示口径测试。
 *
 * <p>测的是**读法**而不是渲染：这一层的价值在于「怎么把后端的事实讲成人话」，
 * 而几处口径一旦搞错就会误导运营，所以逐条钉死：
 *
 * <ul>
 *   <li>「已下发」与「已寄回」**必须**在语气上分开 —— 蓝图加粗句「本系统只负责指引与跟踪，
 *       <b>不控制实物</b>」。</li>
 *   <li>剩余秒数为负不显示成「-3 天」。</li>
 *   <li>轨迹「已签收」用 success，但异常 / 清关扣留 / 退回寄件人必须显眼。</li>
 *   <li>动作不可用时把理由原样带出来（按钮变灰而不给理由会让人以为系统坏了）。</li>
 * </ul>
 */

describe('状态语气', () => {
  it('在途与终态一眼分得开', () => {
    // 在途 = 还要管；终态 = 办完了。
    expect(stateTone('ISSUED')).toBe('info');
    expect(stateTone('ACKNOWLEDGED')).toBe('success');
    expect(stateTone('EXPIRED')).toBe('warning');
    expect(stateTone('CANCELLED')).toBe('neutral');
    expect(stateTone('DRAFT')).toBe('neutral');
    // 未知值落回 neutral 而不是 throw：后端加一个新状态时页面不该整页白屏。
    expect(stateTone('SOME_NEW_STATE')).toBe('neutral');
  });

  it('时效：超时显眼、正常安静', () => {
    expect(timelinessTone('OVERDUE')).toBe('danger');
    expect(timelinessTone('DUE_SOON')).toBe('warning');
    expect(timelinessTone('ON_TRACK')).toBe('success');
    expect(timelinessTone('CLOSED')).toBe('neutral');
  });
});

describe('剩余时间', () => {
  it('负数不显示成「-N 天」，直接说已超时', () => {
    expect(remainingText(-259_200)).toBe('已超时');
    expect(remainingText(3 * 86400)).toBe('剩 3 天');
    expect(remainingText(2 * 3600)).toBe('剩 2 小时');
    expect(remainingText(90)).toBe('剩 1 分钟');
    expect(remainingText(0)).toBe('剩 1 分钟');
    expect(remainingText(null)).toBe('—');
    expect(remainingText(undefined)).toBe('—');
  });
});

describe('轨迹语气', () => {
  it('已签收 success，异常与退回寄件人显眼', () => {
    expect(trackingTone('DELIVERED')).toBe('success');
    expect(trackingTone('OUT_FOR_DELIVERY')).toBe('info');
    expect(trackingTone('IN_TRANSIT')).toBe('info');
    expect(trackingTone('EXCEPTION')).toBe('danger');
    expect(trackingTone('CUSTOMS_HOLD')).toBe('danger');
    expect(trackingTone('RETURNED_TO_SENDER')).toBe('danger');
    expect(trackingTone('CANCELLED')).toBe('warning');
    expect(trackingTone(null)).toBe('neutral');
  });

  it('信息延迟（承运商晚回传）单独读，因为两列分开记了', () => {
    expect(lagText(3 * 3600)).toBe('晚回传 3 小时');
    expect(lagText(50 * 3600)).toBe('晚回传 2 天');
    expect(lagText(120)).toBe('晚回传 2 分钟');
    expect(lagText(null)).toBe('—');
    expect(lagText(-5)).toBe('—');
  });

  it('轨迹一行带承运商、单号、事件与地点', () => {
    const event = {
      carrierCode: 'FedEx',
      trackingNo: 'T-1',
      eventCodeLabel: '运输中',
      eventLocation: '洛杉矶',
    } as RmaTrackingEvent;
    expect(trackingEventText(event)).toBe('FedEx T-1 · 运输中 @ 洛杉矶');
    expect(
      trackingEventText({ ...event, eventLocation: null } as RmaTrackingEvent),
    ).toBe('FedEx T-1 · 运输中');
  });
});

describe('数量', () => {
  it('去掉无意义尾零但保留最多六位小数', () => {
    expect(quantity(3)).toBe('3');
    expect(quantity(3.000000)).toBe('3');
    expect(quantity(1.5)).toBe('1.5');
    expect(quantity(null)).toBe('—');
    expect(quantity('abc')).toBe('abc');
  });
});

describe('地址与留痕', () => {
  it('地址空段不参与拼接，免得中间出现两个连续空格', () => {
    expect(
      addressText({
        returnRecipientName: '退货仓',
        returnAddressLine1: '88 Yonge St',
        returnAddressLine2: '',
        returnCity: 'ONTARIO',
        returnProvince: 'CA',
        returnCountryCode: 'US',
        returnPostcode: 'M5V 2T6',
      } as never),
    ).toBe('退货仓，88 Yonge St，ONTARIO CA US，M5V 2T6');
  });

  it('留痕一行显示事件类型与前后状态', () => {
    const event = {
      eventTypeLabel: '下发退货指引',
      fromStatus: 'DRAFT',
      toStatus: 'ISSUED',
    } as RmaInstructionEvent;
    expect(eventText(event)).toBe('下发退货指引（DRAFT → ISSUED）');
    expect(eventText({ ...event, fromStatus: null } as RmaInstructionEvent)).toBe(
      '下发退货指引（ISSUED）',
    );
  });
});

describe('动作按钮', () => {
  const base = {
    code: 'GENERATE_LABEL',
    label: '生成退货标签',
    allowed: true,
    method: 'POST',
    path: '/trade/rma-instructions/{id}/label',
    required: false,
    precondition: null,
    blockingReason: null,
    ownerModule: null,
    unavailableReason: null,
  } satisfies RmaInstructionAction;

  it('不可用时把理由原样带出来 —— 按钮变灰而不给理由会让人以为系统坏了', () => {
    const button = actionButton({
      ...base,
      allowed: false,
      blockingReason: '只有已下发的指令才需要出标签',
    });
    expect(button.disabled).toBe(true);
    expect(button.reason).toBe('只有已下发的指令才需要出标签');
    expect(button.variant).toBe('ghost');
  });

  it('未交付模块的 unavailableReason 也会被带出来', () => {
    const button = actionButton({
      ...base,
      code: 'CREATE_WAYBILL',
      label: '创建退货运单',
      allowed: false,
      ownerModule: '10 物流',
      unavailableReason: '运单创建与面单接口属 10 物流，未交付',
    });
    expect(button.reason).toContain('10 物流');
  });

  it('缺动作入口时不静默返回可用按钮', () => {
    const button = actionButton(null);
    expect(button.disabled).toBe(true);
    expect(button.reason).toBe('后端未给出动作入口');
  });
});

describe('页面固定说明', () => {
  it('「已下发 ≠ 已寄回」与「已寄回 ≠ 我们收到货」两句都在', () => {
    expect(goodsReceivedHint).toContain('不控制实物');
    expect(goodsReceivedHint).toContain('11.2.2.1');
    // 承运商口径的送达与实收登记是两件事，这一句不能省。
    expect(goodsReceivedHint).toContain('承运商说');
  });

  it('标签完整性校验失败的说明点明「为什么不照常渲染」', () => {
    expect(labelIntegrityHint).toContain('篡改者');
  });

  it('未交付模块的文案带上归属模块号', () => {
    expect(unavailableText({ ownerModule: '11.2.2.1 退货入库', reason: '登记实收未交付' })).toBe(
      '未交付（归属 11.2.2.1 退货入库）：登记实收未交付',
    );
  });
});
