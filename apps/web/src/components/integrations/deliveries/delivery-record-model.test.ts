import { describe, expect, it } from 'vitest';
import type { DeliveryRecordCapabilities } from '../../../../../../packages/api-contracts/src/index';
import {
  DEAD_LETTER_STATUS_OPTIONS,
  DELIVERY_STATUS_OPTIONS,
  attemptSourceLabel,
  capabilityTone,
  deadLetterStatusTone,
  deliveryCapability,
  deliveryStatusHint,
  deliveryStatusTone,
  eventIdLabel,
  isDelivered,
  paginationLabel,
  replayNotice,
  replayTriggerLabel,
  statusCodeLabel,
  unavailableHint,
} from './delivery-record-model';

/**
 * 18.1.2.2「投递记录」前端模型的单测。
 *
 * 🔴 这组用例守的是七件最容易做丢的事：
 *
 * 1. **响应码 0 不是「未知」也不是「成功」** —— 它表示「**根本没拿到响应**」
 *    （连接被拒 / 超时 / 没发出去）。显示成「0」会让人以为是接口返回了 0，
 *    显示成「未知」会让人以为是后端没算出来。
 * 2. **「待重试」与「已转死信」是两件事** —— 前者还在自动重试队列里（别去手工重放），
 *    后者重试耗尽了（该人工处置）。混成一句「失败」会让人对着正在重试的投递重复操作。
 * 3. **事件 ID 完整显示、不截断** —— 它是接收方幂等去重的依据，运营要拿它去和对方对账。
 * 4. **重投说明必须写清「发的是同一份报文」** —— 否则运营会以为重投会造成下游重复业务。
 * 5. **分页用 total 而不是「本页有几行」** —— 前者才是「一共多少」。
 * 6. **后端没声明某一维 ≠ 声明了但未接入** —— 前者要显示成「状态未知」，
 *    不能显示成「未接入」（那是在替后端撒谎）。
 * 7. **状态词表与后端枚举一一对应** —— 少一个值，筛选框里就少一个选项，
 *    用户会以为「这个状态不存在」。
 */

const capabilities = (
  over: Partial<DeliveryRecordCapabilities> = {},
): DeliveryRecordCapabilities => ({
  capabilities: [
    {
      module: '18.1.2.2',
      name: '投递明细记录',
      available: true,
      basis: 'GET /platform/deliveries；platform_delivery_record 表',
      ownerModule: '',
      impact: '',
    },
    {
      module: '18.1.2.2',
      name: '**事件实际投递**（真实 HTTP POST，带签名头）',
      available: true,
      basis: 'POST /platform/deliveries/publish；java.net.http.HttpClient 真的 POST',
      ownerModule: '',
      impact: '',
    },
    {
      module: '18.1.2.2',
      name: '**上游 outbox relay**（把业务事件搬到平台）',
      available: false,
      basis: 'backend/libraries 下没有 event-envelope 库',
      ownerModule: '（跨服务事件总线，不在本模块范围）',
      impact: '事件只能由 POST /platform/deliveries/publish 手工发布进本模块',
    },
  ],
  downstreamGaps: [],
  ...over,
});

describe('18.1.2.2 投递记录 · 响应码口径', () => {
  it('响应码 0 必须显示成「没有响应」而不是「0」', () => {
    expect(statusCodeLabel(0)).toBe('没有响应');
  });

  it('真实响应码原样显示', () => {
    expect(statusCodeLabel(200)).toBe('200');
    expect(statusCodeLabel(404)).toBe('404');
    expect(statusCodeLabel(500)).toBe('500');
  });

  it('只有 2xx 且状态为 DELIVERED 才算送达', () => {
    expect(isDelivered({ status: 'DELIVERED', statusCode: 200 })).toBe(true);
    expect(isDelivered({ status: 'DELIVERED', statusCode: 204 })).toBe(true);
    expect(isDelivered({ status: 'DELIVERED', statusCode: 302 })).toBe(false);
    expect(isDelivered({ status: 'PENDING', statusCode: 200 })).toBe(false);
    expect(isDelivered({ status: 'DEAD_LETTER', statusCode: 0 })).toBe(false);
  });
});

describe('18.1.2.2 投递记录 · 投递状态', () => {
  it('四种状态各有各的语气，送达是绿的、待重试是黄的', () => {
    expect(deliveryStatusTone('DELIVERED')).toBe('ok');
    expect(deliveryStatusTone('PENDING')).toBe('warn');
    expect(deliveryStatusTone('FAILED')).toBe('danger');
    expect(deliveryStatusTone('DEAD_LETTER')).toBe('muted');
  });

  it('「待重试」必须说明还在队列里并给出下次重试时间', () => {
    const hint = deliveryStatusHint({
      status: 'PENDING',
      statusCode: 500,
      nextRetryAt: '2026-10-31T10:00:00Z',
      attemptCount: 2,
    });
    expect(hint).toContain('自动重试');
    expect(hint).toContain('2026-10-31T10:00:00Z');
  });

  it('「已转死信」必须说明重试已用尽、该人工处置', () => {
    const hint = deliveryStatusHint({
      status: 'DEAD_LETTER',
      statusCode: 500,
      attemptCount: 5,
    });
    expect(hint).toContain('用尽');
    expect(hint).toContain('人工');
    expect(hint).not.toContain('自动重试队列');
  });

  it('「失败」必须说明不会再自动重试（否则会去重复手工重放）', () => {
    const hint = deliveryStatusHint({
      status: 'FAILED',
      statusCode: 0,
      attemptCount: 1,
    });
    expect(hint).toContain('不会再自动重试');
  });

  it('死信状态的语气：待处理是黄的、重放过是绿的', () => {
    expect(deadLetterStatusTone('PENDING')).toBe('warn');
    expect(deadLetterStatusTone('REPLAYED')).toBe('ok');
    expect(deadLetterStatusTone('EXPIRED')).toBe('muted');
  });
});

describe('18.1.2.2 投递记录 · 事件 ID 与重投口径', () => {
  it('事件 ID 完整显示，不截断 —— 它是接收方幂等去重的依据', () => {
    const id = '01a9a9a6-c95f-4cd8-9112-00558f9dc9af';
    expect(eventIdLabel(id)).toBe(id);
    expect(eventIdLabel(id)).toHaveLength(36);
  });

  it('重投说明必须写清「发的是同一份报文、事件 ID 不变」', () => {
    const notice = replayNotice();
    expect(notice).toContain('同一份报文');
    expect(notice).toContain('event_id');
  });

  it('尝试来源与重放触发方式各有各的说法', () => {
    expect(attemptSourceLabel('INITIAL')).toBe('首次');
    expect(attemptSourceLabel('RETRY')).toBe('自动重试');
    expect(attemptSourceLabel('MANUAL')).toBe('手动重投');
    expect(attemptSourceLabel('DEAD_LETTER_REPLAY')).toBe('死信重放');
    expect(replayTriggerLabel('DEAD_LETTER')).toBe('死信重放');
    expect(replayTriggerLabel('MANUAL')).toBe('手动重投');
  });
});

describe('18.1.2.2 投递记录 · 分页', () => {
  it('用 total 显示「一共多少」而不是「本页装满了」', () => {
    expect(paginationLabel({ page: 0, size: 20, total: 0 })).toBe('第 0-0 条 / 共 0 条');
    expect(paginationLabel({ page: 0, size: 20, total: 5 })).toBe('第 1-5 条 / 共 5 条');
    expect(paginationLabel({ page: 1, size: 20, total: 45 })).toBe('第 21-40 条 / 共 45 条');
  });
});

describe('18.1.2.2 投递记录 · 能力声明', () => {
  it('能定位到「事件实际投递」那一维', () => {
    const declared = deliveryCapability(capabilities());
    expect(declared?.available).toBe(true);
  });

  it('后端没声明这一维时返回 null —— 那与「声明了但未接入」不同', () => {
    expect(deliveryCapability(null)).toBeNull();
    expect(deliveryCapability({ capabilities: [], downstreamGaps: [] })).toBeNull();
  });

  it('未接入的能力要显示成「未接入 + 归谁 + 怎么影响」', () => {
    const hint = unavailableHint({
      module: '18.1.2.2',
      name: '**上游 outbox relay**（把业务事件搬到平台）',
      available: false,
      basis: 'backend/libraries 下没有 event-envelope 库',
      ownerModule: '（跨服务事件总线，不在本模块范围）',
      impact: '事件只能手工发布',
    });
    expect(hint).toContain('未接入');
    expect(hint).toContain('跨服务事件总线');
    expect(hint).toContain('手工发布');
  });

  it('后端没声明时显示成「状态未知」，不显示成「未接入」', () => {
    expect(unavailableHint(null)).toContain('状态未知');
    expect(unavailableHint(null)).not.toContain('未接入');
  });

  it('已具备的能力语气是 ok，未具备的是 muted', () => {
    expect(capabilityTone(true)).toBe('ok');
    expect(capabilityTone(false)).toBe('muted');
  });
});

describe('18.1.2.2 投递记录 · 状态词表', () => {
  it('投递状态词表与后端枚举一一对应', () => {
    expect(DELIVERY_STATUS_OPTIONS.map((o) => o.value)).toEqual([
      'PENDING',
      'DELIVERED',
      'FAILED',
      'DEAD_LETTER',
    ]);
  });

  it('死信状态词表与后端枚举一一对应', () => {
    expect(DEAD_LETTER_STATUS_OPTIONS.map((o) => o.value)).toEqual([
      'PENDING',
      'REPLAYED',
      'EXPIRED',
    ]);
  });
});
