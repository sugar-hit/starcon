import { describe, expect, it } from 'vitest';
import type {
  EventSubscriptionCapabilities,
  EventSubscriptionSecretIssued,
} from '../../../../../../packages/api-contracts/src/index';
import {
  auditActionLabel,
  canDeliver,
  capabilityTone,
  deliveryCapability,
  eventTypeStatusTone,
  eventTypeVersionLabel,
  filterSummary,
  isEditable,
  paginationLabel,
  secretPlaintext,
  secretStatusTone,
  signatureHeaderLabel,
  signatureVerificationSteps,
  subscriptionStatusTone,
  unavailableHint,
} from './webhooks-model';

/**
 * 18.1.2.1 事件订阅 前端模型的单测。
 *
 * 🔴 这组用例守的是六件最容易做丢的事：
 *
 * 1. **未接入的投递不能显示成「可用」** —— `canDeliver` 只在后端明确声明
 *    `available=true` 时才为真；「后端没声明」与「声明了但未接入」都必须是 false。
 * 2. **事件定义必须带版本号显示** —— 「SkuProfileSaved」和「SkuProfileSaved v2」
 *    是两份不同的契约，显示时不带版本会让人以为「改过了」。
 * 3. **空过滤条件显示成「不过滤」而不是空白** —— 空白看着像「没配好」，
 *    而它其实是「该事件的全部实例都收」。
 * 4. **签名验证步骤必须与后端算法逐字一致** —— HMAC-SHA256，盖「时间戳 + 点 + 原始字节」。
 *    说明与实现对不上，对接方会照着说明写出一个永远验不过的实现。
 * 5. **签名头显示时不能拆掉时间戳** —— 时间戳是验签方拒绝重放的依据。
 * 6. **密钥明文不给就别给** —— 没签发过时是空串，不许显示成一个假的占位密钥。
 */

const capabilities = (over: Partial<EventSubscriptionCapabilities> = {}): EventSubscriptionCapabilities => ({
  capabilities: [
    {
      module: '18.1.2.1',
      name: '可订阅事件列表（事件定义注册表）',
      available: true,
      basis: 'platform_webhook_event_type 表 + GET /platform/webhooks/event-types',
      ownerModule: '',
      impact: '',
    },
    {
      module: '18.1.2.1',
      name: '**事件实际投递**',
      available: false,
      basis: '本仓没有从业务服务 outbox 取事件并按订阅投递出去的 relay',
      ownerModule: '18.1.2.2',
      impact: '订阅配置可以配好，但事件不会真的发到外部端点；投递端点如实答 409，一次包都不发',
    },
  ],
  downstreamGaps: [],
  ...over,
});

describe('18.1.2.1 事件订阅 · 能力声明', () => {
  it('未接入的投递必须显示成未接入而不是可用', () => {
    const caps = capabilities();
    expect(canDeliver(caps)).toBe(false);
    const declared = deliveryCapability(caps);
    expect(declared?.available).toBe(false);
    expect(declared?.ownerModule).toBe('18.1.2.2');
  });

  it('后端没有声明这一维时既不是可用也不是未接入', () => {
    const empty = capabilities({ capabilities: [] });
    expect(deliveryCapability(empty)).toBeNull();
    expect(canDeliver(empty)).toBe(false);
    expect(unavailableHint(null)).toContain('状态未知');
  });

  it('后端声明可用时才允许提供投递动作', () => {
    const ready = capabilities({
      capabilities: [
        {
          module: '18.1.2.1',
          name: '**事件实际投递**',
          available: true,
          basis: 'relay 已接入',
          ownerModule: '',
          impact: '',
        },
      ],
    });
    expect(canDeliver(ready)).toBe(true);
  });

  it('未接入的提示要写清归谁与现在怎么办', () => {
    const hint = unavailableHint(deliveryCapability(capabilities()));
    expect(hint).toContain('18.1.2.2');
    expect(hint).toContain('409');
  });

  it('能力语气：已具备是绿，未接入是灰', () => {
    expect(capabilityTone(true)).toBe('ok');
    expect(capabilityTone(false)).toBe('muted');
  });
});

describe('18.1.2.1 事件订阅 · 事件定义', () => {
  it('事件定义必须带版本号显示', () => {
    expect(eventTypeVersionLabel({ eventType: 'SkuProfileSaved', schemaVersion: 1 })).toBe(
      'SkuProfileSaved v1',
    );
    expect(eventTypeVersionLabel({ eventType: 'SkuProfileSaved', schemaVersion: 2 })).toBe(
      'SkuProfileSaved v2',
    );
  });

  it('已废弃是灰的，可用是绿的', () => {
    expect(eventTypeStatusTone('ACTIVE')).toBe('ok');
    expect(eventTypeStatusTone('DEPRECATED')).toBe('muted');
  });
});

describe('18.1.2.1 事件订阅 · 事件过滤', () => {
  it('空过滤条件显示成「不过滤」而不是空白', () => {
    expect(filterSummary(undefined)).toBe('不过滤');
    expect(filterSummary('')).toBe('不过滤');
    expect(filterSummary('{}')).toBe('不过滤');
  });

  it('有过滤条件时原样显示', () => {
    expect(filterSummary('{"tenantId":"t1"}')).toBe('{"tenantId":"t1"}');
  });
});

describe('18.1.2.1 事件订阅 · 签名', () => {
  const preview = {
    signatureHeader: 'X-Starcon-Signature',
    signatureAlgorithm: 'HMAC_SHA256',
    timestamp: '1700000000',
    signedPayload: '1700000000.{"a":1}',
    signature: 'abc123',
    headerValue: 't=1700000000,v1=abc123',
    samplePayload: '{"a":1}',
    note: '真算，但不投递',
  };

  it('签名头显示时不能拆掉时间戳', () => {
    expect(signatureHeaderLabel(preview)).toBe('X-Starcon-Signature: t=1700000000,v1=abc123');
  });

  it('验签步骤必须写明算法与「时间戳 + 点 + 原始字节」', () => {
    const steps = signatureVerificationSteps(preview).join('\n');
    expect(steps).toContain('HMAC_SHA256');
    expect(steps).toContain('1700000000.{"a":1}');
    expect(steps).toContain('十六进制小写');
    expect(steps).toContain('重放');
  });

  it('没签发过时明文是空串，不许显示成假占位', () => {
    expect(secretPlaintext(null)).toBe('');
    const issued: EventSubscriptionSecretIssued = {
      secret: {
        id: '00000000-0000-4000-8000-000000000001',
        subscriptionId: '00000000-0000-4000-8000-000000000002',
        version: 1,
        status: 'ACTIVE',
        statusLabel: '生效',
        secretHint: 'abcd',
        createdAt: '2026-10-30T00:00:00Z',
        rotatedAt: '',
      },
      plaintext: 'the-only-copy',
      warning: '只出现一次',
    };
    expect(secretPlaintext(issued)).toBe('the-only-copy');
  });

  it('密钥状态：生效是绿、已吊销是红、已轮换是灰', () => {
    expect(secretStatusTone('ACTIVE')).toBe('ok');
    expect(secretStatusTone('REVOKED')).toBe('danger');
    expect(secretStatusTone('ROTATED')).toBe('muted');
  });
});

describe('18.1.2.1 事件订阅 · 订阅与分页', () => {
  it('订阅状态：生效是绿、暂停是黄、已停用是灰', () => {
    expect(subscriptionStatusTone('ACTIVE')).toBe('ok');
    expect(subscriptionStatusTone('PAUSED')).toBe('warn');
    expect(subscriptionStatusTone('DISABLED')).toBe('muted');
  });

  it('已停用是终态，不能再改', () => {
    expect(isEditable({ status: 'ACTIVE' })).toBe(true);
    expect(isEditable({ status: 'PAUSED' })).toBe(true);
    expect(isEditable({ status: 'DISABLED' })).toBe(false);
  });

  it('分页口径用总数，不是本页行数', () => {
    expect(paginationLabel({ page: 0, size: 20, total: 0 })).toBe('第 0-0 条 / 共 0 条');
    expect(paginationLabel({ page: 0, size: 20, total: 45 })).toBe('第 1-20 条 / 共 45 条');
    expect(paginationLabel({ page: 2, size: 20, total: 45 })).toBe('第 41-45 条 / 共 45 条');
  });
});

describe('18.1.2.1 事件订阅 · 审计', () => {
  it('审计动作要有中文说法', () => {
    expect(auditActionLabel('SUBSCRIPTION_CREATED')).toBe('创建订阅');
    expect(auditActionLabel('SIGNING_SECRET_ROTATED')).toBe('轮换签名密钥');
    expect(auditActionLabel('SIGNATURE_PREVIEWED')).toBe('签名试算');
  });

  it('未知动作原样返回，不许吞掉', () => {
    expect(auditActionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});
