import { describe, expect, it } from 'vitest';
import type {
  NotifyBlockRow,
  NotifyDeliveryRow,
  NotifyDeliveryStatistics,
  NotifyUnsubscribeRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  ATTEMPT_SOURCE_LABELS,
  CHANNEL_LABELS,
  DELIVERY_STATUS_LABELS,
  OUTCOME_LABELS,
  SCOPE_TYPE_LABELS,
  UNSUBSCRIBE_SCOPE_LABELS,
  UNSUBSCRIBE_SOURCE_LABELS,
  blockedPhrase,
  buildDeliveryInput,
  buildRetryInput,
  buildUnsubscribeInput,
  channelLabel,
  deliveryRateExplanation,
  deliveryStatusHint,
  deliveryStatusLabel,
  deliveryStatusTone,
  emptyDeliveryDraft,
  emptyRecipientDraft,
  emptyRetryDraft,
  emptyUnsubscribeDraft,
  groupAttemptsByRecipient,
  outcomeLabel,
  rateText,
  scopeTypeLabel,
  statusCodeLabel,
  summarizeDeliveries,
  summarizeStatistics,
  unsubscribeActive,
  unsubscribePhrase,
  unsubscribeRateExplanation,
  unsubscribeScopeLabel,
  unsubscribeSourceLabel,
  validateDeliveryDraft,
  validateRetryDraft,
  validateUnsubscribeDraft,
} from './notify-delivery-model';

/**
 * 18.5.2.1「投递记录」的前端模型单测。
 *
 * <p>🔴 重点钉住六件事：
 *
 * <ol>
 *   <li><b>「投过」不等于「送达了」</b>：五种投递状态各有各的措辞与语气，
 *       「待出结果」不许显示成「失败」。</li>
 *   <li><b>触达率的分母口径</b>：解释文案必须点明「分母不含被退订拦截的人」——
 *       否则一个 100% 的触达率会掩盖「其实有 300 个人因为退订没收到」。</li>
 *   <li><b>退订拦截不是静默丢弃</b>：拦截留痕要能说清命中的是哪一档退订。</li>
 *   <li><b>请求体形状</b>：折出来的入参必须与服务端契约逐字一致。</li>
 *   <li><b>校验要有理由</b>：每个拒绝都要能直接展示给用户。</li>
 *   <li><b>撤销后不再拦截</b>：退订被撤销后状态要显示成「已撤销（不再拦截）」。</li>
 * </ol>
 */

function deliveryRow(overrides: Partial<NotifyDeliveryRow> = {}): NotifyDeliveryRow {
  return {
    id: '00000000-0000-4000-8000-000000000001',
    templateId: '00000000-0000-4000-8000-000000000002',
    templateKey: 'order-paid',
    scopeType: 'SUBSCRIBERS',
    scopeRef: 'all',
    channel: 'EMAIL',
    status: 'DELIVERED',
    recipientCount: 2,
    blockedCount: 0,
    targetedCount: 2,
    deliveredCount: 2,
    failedCount: 0,
    pendingCount: 0,
    attemptCount: 2,
    failureReason: '',
    deliveryRate: 1,
    nextRetryAt: '',
    firstAttemptAt: '2026-11-01T00:00:00Z',
    lastAttemptAt: '2026-11-01T00:00:01Z',
    deliveredAt: '2026-11-01T00:00:01Z',
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:01Z',
    ...overrides,
  };
}

describe('投递状态措辞', () => {
  it('五种状态各有各的说法与语气', () => {
    expect(DELIVERY_STATUS_LABELS.PENDING).toBe('待出结果');
    expect(DELIVERY_STATUS_LABELS.DELIVERED).toBe('已送达');
    expect(DELIVERY_STATUS_LABELS.PARTIAL).toBe('部分送达');
    expect(DELIVERY_STATUS_LABELS.FAILED).toBe('全部失败');
    expect(DELIVERY_STATUS_LABELS.BLOCKED).toBe('全部被退订拦截');

    expect(deliveryStatusTone('DELIVERED')).toBe('ok');
    expect(deliveryStatusTone('PENDING')).toBe('warn');
    expect(deliveryStatusTone('PARTIAL')).toBe('warn');
    expect(deliveryStatusTone('FAILED')).toBe('bad');
    // 🔴 全部被退订拦下**不是**失败：它是合规动作生效。
    expect(deliveryStatusTone('BLOCKED')).not.toBe('bad');
  });

  it('未知码原样回显，不显示成空白', () => {
    expect(deliveryStatusLabel('WHATEVER')).toBe('WHATEVER');
    expect(channelLabel('WHATEVER')).toBe('WHATEVER');
    expect(scopeTypeLabel('WHATEVER')).toBe('WHATEVER');
    expect(outcomeLabel('WHATEVER')).toBe('WHATEVER');
    expect(unsubscribeScopeLabel('WHATEVER')).toBe('WHATEVER');
    expect(unsubscribeSourceLabel('WHATEVER')).toBe('WHATEVER');
  });

  it('「待出结果」不会被说成失败', () => {
    const hint = deliveryStatusHint(
      deliveryRow({ status: 'PENDING', pendingCount: 1, deliveredCount: 1, failedCount: 0 }),
    );
    expect(hint).toContain('没出结果');
    expect(hint).not.toContain('失败');
  });

  it('被退订拦下时要说清「一次都没发出去」', () => {
    const hint = deliveryStatusHint(
      deliveryRow({
        status: 'BLOCKED',
        recipientCount: 3,
        blockedCount: 3,
        targetedCount: 0,
        deliveredCount: 0,
        failedCount: 0,
      }),
    );
    expect(hint).toContain('一次都没发出去');
  });

  it('响应码 0 表示没有响应，不是「0」也不是「未知」', () => {
    expect(statusCodeLabel(0)).toBe('没有响应');
    expect(statusCodeLabel(200)).toBe('200');
  });
});

describe('比率与解释口径', () => {
  it('比率显示成百分比', () => {
    expect(rateText(1)).toBe('100.0%');
    expect(rateText(0.5)).toBe('50.0%');
    expect(rateText(0)).toBe('0.0%');
    expect(rateText(null)).toBe('—');
  });

  it('触达率的解释必须点明分母不含被退订拦截的人', () => {
    const stats: NotifyDeliveryStatistics = {
      totalDeliveries: 3,
      totalRecipients: 10,
      totalBlocked: 4,
      totalTargeted: 6,
      totalDelivered: 3,
      totalFailed: 3,
      deliveryRate: 0.5,
      unsubscribeRate: 0.25,
      distinctRecipients: 8,
      unsubscribedRecipients: 2,
      blockedAttempts: 4,
      generatedAt: '2026-11-01T00:00:00Z',
    };
    const text = deliveryRateExplanation(stats);
    expect(text).toContain('50.0%');
    expect(text).toContain('已送达 3 人');
    expect(text).toContain('实际投递 6 人');
    // 🔴 不点明这句，100% 的触达率会掩盖「有 4 个人因为退订没收到」。
    expect(text).toContain('分母不含被退订拦截的 4 人');
  });

  it('退订率的解释给出分子分母', () => {
    const stats: NotifyDeliveryStatistics = {
      totalDeliveries: 1,
      totalRecipients: 8,
      totalBlocked: 0,
      totalTargeted: 8,
      totalDelivered: 8,
      totalFailed: 0,
      deliveryRate: 1,
      unsubscribeRate: 0.25,
      distinctRecipients: 8,
      unsubscribedRecipients: 2,
      blockedAttempts: 0,
      generatedAt: '2026-11-01T00:00:00Z',
    };
    const text = unsubscribeRateExplanation(stats);
    expect(text).toContain('25.0%');
    expect(text).toContain('已退订 2 人');
    expect(text).toContain('投递过的 8 人');
  });

  it('统计为 null 时给出 null 而不是半截对象', () => {
    expect(summarizeStatistics(null)).toBeNull();
  });
});

describe('退订与拦截措辞', () => {
  it('拦截留痕要能说清命中的是哪一档退订', () => {
    const block: NotifyBlockRow = {
      id: '00000000-0000-4000-8000-000000000003',
      deliveryId: '00000000-0000-4000-8000-000000000004',
      unsubscribeId: '00000000-0000-4000-8000-000000000005',
      recipientRef: 'buyer@x.com',
      channel: 'EMAIL',
      unsubscribeScopeType: 'GLOBAL',
      unsubscribeScopeKey: '*',
      blockedAt: '2026-11-01T00:00:00Z',
    };
    expect(blockedPhrase(block)).toContain('命中');
    expect(blockedPhrase(block)).toContain('全部通知');
    expect(blockedPhrase(block)).toContain('未发出');
  });

  it('模板级退订的拦截要带上模板 key', () => {
    const block: NotifyBlockRow = {
      id: '00000000-0000-4000-8000-000000000003',
      deliveryId: '00000000-0000-4000-8000-000000000004',
      unsubscribeId: '00000000-0000-4000-8000-000000000005',
      recipientRef: 'buyer@x.com',
      channel: 'EMAIL',
      unsubscribeScopeType: 'TEMPLATE',
      unsubscribeScopeKey: 'order-paid',
      blockedAt: '2026-11-01T00:00:00Z',
    };
    expect(blockedPhrase(block)).toContain('单个模板');
    expect(blockedPhrase(block)).toContain('order-paid');
  });

  it('撤销后不再拦截', () => {
    const active: NotifyUnsubscribeRow = {
      id: '00000000-0000-4000-8000-000000000006',
      recipientRef: 'buyer@x.com',
      channel: 'ALL',
      scopeType: 'GLOBAL',
      scopeKey: '*',
      templateId: '',
      reason: '',
      source: 'USER_REQUEST',
      unsubscribedAt: '2026-11-01T00:00:00Z',
      revokedAt: '',
      createdAt: '2026-11-01T00:00:00Z',
    };
    expect(unsubscribeActive(active)).toBe(true);
    expect(unsubscribePhrase(active)).toContain('生效中');

    const revoked = { ...active, revokedAt: '2026-11-02T00:00:00Z' };
    expect(unsubscribeActive(revoked)).toBe(false);
    expect(unsubscribePhrase(revoked)).toContain('已撤销');
  });
});

describe('汇总', () => {
  it('按状态拆清单并累计人数', () => {
    const summary = summarizeDeliveries([
      deliveryRow({ status: 'DELIVERED', recipientCount: 3, attemptCount: 3 }),
      deliveryRow({
        status: 'BLOCKED',
        recipientCount: 2,
        blockedCount: 2,
        targetedCount: 0,
        deliveredCount: 0,
        attemptCount: 0,
      }),
    ]);
    expect(summary.total).toBe(2);
    expect(summary.delivered).toBe(1);
    expect(summary.blocked).toBe(1);
    expect(summary.recipients).toBe(5);
    expect(summary.blockedRecipients).toBe(2);
    expect(summary.attempts).toBe(3);
  });

  it('清单为 null 时给出全零而不是崩', () => {
    const summary = summarizeDeliveries(null);
    expect(summary.total).toBe(0);
    expect(summary.recipients).toBe(0);
  });
});

describe('投递登记草稿', () => {
  it('空白草稿带一个收件人', () => {
    const draft = emptyDeliveryDraft();
    expect(draft.channel).toBe('EMAIL');
    expect(draft.recipients).toHaveLength(1);
  });

  it('必须选模板', () => {
    const draft = { ...emptyDeliveryDraft(), templateId: '' };
    expect(validateDeliveryDraft(draft)).toContain('通知模板');
  });

  it('收件范围是单个收件人时必须填标识', () => {
    const draft = {
      ...emptyDeliveryDraft(),
      templateId: 'tpl',
      scopeType: 'SINGLE',
      scopeRef: '',
    };
    expect(validateDeliveryDraft(draft)).toContain('收件人标识');
  });

  it('没有收件人时拒收', () => {
    const draft = { ...emptyDeliveryDraft(), templateId: 'tpl', recipients: [] };
    expect(validateDeliveryDraft(draft)).toContain('至少要有一个收件人');
  });

  it('标记为已送达但响应码是 5xx 时拒收', () => {
    const draft = {
      ...emptyDeliveryDraft(),
      templateId: 'tpl',
      recipients: [
        { ...emptyRecipientDraft(), ref: 'a@x.com', outcome: 'DELIVERED', statusCode: '500' },
      ],
    };
    expect(validateDeliveryDraft(draft)).toContain('2xx');
  });

  it('标记为失败但响应码是 2xx 时拒收', () => {
    const draft = {
      ...emptyDeliveryDraft(),
      templateId: 'tpl',
      recipients: [
        {
          ...emptyRecipientDraft(),
          ref: 'a@x.com',
          outcome: 'FAILED',
          statusCode: '200',
          failureReason: '奇怪',
        },
      ],
    };
    expect(validateDeliveryDraft(draft)).toContain('不能是 2xx');
  });

  it('标记为失败时必须写清原因', () => {
    const draft = {
      ...emptyDeliveryDraft(),
      templateId: 'tpl',
      recipients: [
        { ...emptyRecipientDraft(), ref: 'a@x.com', outcome: 'FAILED', statusCode: '550' },
      ],
    };
    expect(validateDeliveryDraft(draft)).toContain('失败原因');
  });

  it('合法的草稿通过校验并折成请求体', () => {
    const draft = {
      ...emptyDeliveryDraft(),
      templateId: ' tpl-1 ',
      scopeType: 'SUBSCRIBERS',
      recipients: [
        {
          ...emptyRecipientDraft(),
          ref: ' a@x.com ',
          outcome: 'DELIVERED',
          statusCode: '200',
          durationMs: '340',
        },
      ],
    };
    expect(validateDeliveryDraft(draft)).toBeNull();
    const input = buildDeliveryInput(draft);
    expect(input.templateId).toBe('tpl-1');
    expect(input.channel).toBe('EMAIL');
    expect(input.recipients).toHaveLength(1);
    expect(input.recipients[0]?.ref).toBe('a@x.com');
    expect(input.recipients[0]?.statusCode).toBe(200);
    expect(input.recipients[0]?.durationMs).toBe(340);
  });

  it('空标识的收件人不会进请求体', () => {
    const draft = {
      ...emptyDeliveryDraft(),
      templateId: 'tpl',
      recipients: [{ ...emptyRecipientDraft(), ref: '  ' }],
    };
    const input = buildDeliveryInput(draft);
    expect(input.recipients).toHaveLength(0);
  });
});

describe('退订草稿', () => {
  it('必须填收件人标识', () => {
    expect(validateUnsubscribeDraft(emptyUnsubscribeDraft())).toContain('收件人标识');
  });

  it('模板级退订必须选模板', () => {
    const draft = { ...emptyUnsubscribeDraft(), recipientRef: 'a@x.com', scopeType: 'TEMPLATE' };
    expect(validateUnsubscribeDraft(draft)).toContain('必须选择模板');
  });

  it('场景级退订必须填场景标识', () => {
    const draft = { ...emptyUnsubscribeDraft(), recipientRef: 'a@x.com', scopeType: 'SCENE' };
    expect(validateUnsubscribeDraft(draft)).toContain('场景标识');
  });

  it('折成请求体：非模板范围的 templateId 为空串', () => {
    const draft = {
      ...emptyUnsubscribeDraft(),
      recipientRef: ' a@x.com ',
      reason: ' 不想再收 ',
    };
    expect(validateUnsubscribeDraft(draft)).toBeNull();
    const input = buildUnsubscribeInput(draft);
    expect(input.recipientRef).toBe('a@x.com');
    expect(input.templateId).toBe('');
    expect(input.scopeKey).toBe('');
    expect(input.reason).toBe('不想再收');
    expect(input.source).toBe('USER_REQUEST');
  });
});

describe('重试草稿', () => {
  it('空白草稿默认按已知结果处理', () => {
    const draft = emptyRetryDraft();
    expect(draft.outcomeKnown).toBe(true);
    expect(draft.success).toBe(true);
  });

  it('标记为成功但响应码是 5xx 时拒收', () => {
    const draft = { ...emptyRetryDraft(), statusCode: '500' };
    expect(validateRetryDraft(draft)).toContain('2xx');
  });

  it('标记为失败时必须写清原因', () => {
    const draft = { ...emptyRetryDraft(), success: false, statusCode: '550' };
    expect(validateRetryDraft(draft)).toContain('失败原因');
  });

  it('结果未知时不校验结果与响应码的一致性', () => {
    const draft = {
      ...emptyRetryDraft(),
      outcomeKnown: false,
      success: false,
      statusCode: '500',
      failureReason: '',
    };
    expect(validateRetryDraft(draft)).toBeNull();
  });

  it('收件人按逗号 / 空格拆开，空的丢掉', () => {
    const draft = { ...emptyRetryDraft(), recipients: 'a@x.com, b@x.com ， ,c@x.com' };
    const input = buildRetryInput(draft);
    expect(input.recipients).toEqual(['a@x.com', 'b@x.com', 'c@x.com']);
  });

  it('没点名收件人时是空数组（服务端重试全部失败 / 待出结果的）', () => {
    const input = buildRetryInput(emptyRetryDraft());
    expect(input.recipients).toEqual([]);
  });
});

describe('逐次尝试分组', () => {
  it('按收件人分组并按 attempt_no 升序', () => {
    const grouped = groupAttemptsByRecipient([
      {
        id: '00000000-0000-4000-8000-000000000007',
        deliveryId: '00000000-0000-4000-8000-000000000008',
        attemptNo: 2,
        recipientRef: 'a@x.com',
        channel: 'EMAIL',
        statusCode: 200,
        outcome: 'DELIVERED',
        attemptSource: 'RETRY',
        durationMs: 10,
        failureReason: '',
        occurredAt: '2026-11-01T00:00:02Z',
      },
      {
        id: '00000000-0000-4000-8000-000000000009',
        deliveryId: '00000000-0000-4000-8000-000000000008',
        attemptNo: 1,
        recipientRef: 'a@x.com',
        channel: 'EMAIL',
        statusCode: 550,
        outcome: 'FAILED',
        attemptSource: 'INITIAL',
        durationMs: 10,
        failureReason: '邮箱不可用',
        occurredAt: '2026-11-01T00:00:01Z',
      },
    ]);
    expect(grouped).toHaveLength(1);
    expect(grouped[0]?.recipientRef).toBe('a@x.com');
    expect(grouped[0]?.attempts.map((a) => a.attemptNo)).toEqual([1, 2]);
    expect(grouped[0]?.attempts[0]?.outcome).toBe('FAILED');
  });
});

describe('词表齐全', () => {
  it('每个词表都覆盖了受控码', () => {
    expect(Object.keys(CHANNEL_LABELS)).toEqual(
      expect.arrayContaining(['EMAIL', 'SMS', 'PUSH', 'IN_APP', 'WEBHOOK']),
    );
    expect(Object.keys(SCOPE_TYPE_LABELS)).toEqual(
      expect.arrayContaining(['SINGLE', 'SEGMENT', 'SUBSCRIBERS']),
    );
    expect(Object.keys(OUTCOME_LABELS)).toEqual(
      expect.arrayContaining(['DELIVERED', 'FAILED', 'PENDING']),
    );
    expect(Object.keys(ATTEMPT_SOURCE_LABELS)).toEqual(
      expect.arrayContaining(['INITIAL', 'RETRY', 'MANUAL']),
    );
    expect(Object.keys(UNSUBSCRIBE_SCOPE_LABELS)).toEqual(
      expect.arrayContaining(['TEMPLATE', 'SCENE', 'GLOBAL']),
    );
    expect(Object.keys(UNSUBSCRIBE_SOURCE_LABELS)).toEqual(
      expect.arrayContaining(['USER_REQUEST', 'COMPLAINT', 'ADMIN']),
    );
  });
});
