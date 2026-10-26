import { describe, expect, it } from 'vitest';
import type {
  ExternalChannel,
  ExternalDelivery,
  ExternalDeliveryList,
} from '../../../../../packages/api-contracts/src/index';
import {
  ALERT_STATE_LABELS,
  CARD_STYLE_LABELS,
  CHANNEL_CODE_LABELS,
  DELIVERY_STATUS_LABELS,
  NOTIFICATION_TYPE_LABELS,
  SOURCE_KIND_LABELS,
  buildChannelInput,
  buildPushInput,
  channelUnusableReason,
  channelUsable,
  draftOf,
  emptyChannelDraft,
  emptyPushDraft,
  failurePhrase,
  isValidRelativeLink,
  isValidSecretRef,
  nextAttemptPhrase,
  splitByStatus,
  statusTone,
  summarizeDeliveries,
  validateChannelDraft,
  validatePushDraft,
} from './external-channel-push-model';

/**
 * 1.3.2.2 外部渠道推送的前端模型单测。
 *
 * <p>🔴 重点钉住三件事：
 *
 * <ol>
 *   <li><b>凭据不落库</b>：{@code https://…} 这类地址本体在前端就被判为**不合法**，
 *       而且校验消息要写清「为什么」；</li>
 *   <li><b>请求体形状</b>：ALERT_HIT 与 NOTIFICATION 两个来源的入参形状不同，
 *       用不到的字段必须是 {@code null}（而不是空串）；</li>
 *   <li><b>汇总与措辞</b>：投递清单的角标数字与状态分桶要和清单同源。</li>
 * </ol>
 */

/** 造一条投递记录（只填测试用得到的字段）。 */
function delivery(overrides: Partial<ExternalDelivery> = {}): ExternalDelivery {
  return {
    id: '3f9a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8',
    channelId: '11111111-1111-4111-8111-111111111111',
    channelCode: 'WECHAT_WORK',
    channelName: '运营群机器人',
    recipientMembershipId: '22222222-2222-4222-8222-222222222222',
    sourceKind: 'ALERT_HIT',
    sourceRef: '33333333-3333-4333-8333-333333333333',
    notificationType: 'STOCKOUT',
    notificationRef: 'SKU-001',
    title: 'SKU 库存可覆盖天数偏低 命中',
    body: '规则 SKU_STOCK_COVER_DAYS_LOW · 对象 SKU/SKU-001 · 库存可覆盖天数 9.5（阈值 14 LT）',
    linkUrl: '/inventory/stockout-alerts',
    cardStyle: 'CARD',
    status: 'FAILED',
    attemptCount: 1,
    maxAttempts: 3,
    failureCode: 'SECRETS_UNRESOLVED',
    failureMessage: 'secret manager 未接通',
    nextAttemptAt: '2026-10-26T10:00:00Z',
    firstAttemptAt: '2026-10-26T09:00:00Z',
    lastAttemptAt: '2026-10-26T09:00:00Z',
    deliveredAt: null,
    alertState: 'NONE',
    alertedAt: null,
    alertMessage: null,
    createdAt: '2026-10-26T09:00:00Z',
    ...overrides,
  };
}

/** 造一条渠道。 */
function channel(overrides: Partial<ExternalChannel> = {}): ExternalChannel {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    channelCode: 'WECHAT_WORK',
    displayName: '运营群机器人',
    secretRef: 'secret://projects/starcon/tenants/x/wecom',
    cardEnabled: true,
    linkEnabled: true,
    status: 'ENABLED',
    credentialResolvable: false,
    credentialNote: 'secret manager 未接通',
    createdBy: '22222222-2222-4222-8222-222222222222',
    createdAt: '2026-10-26T09:00:00Z',
    updatedAt: '2026-10-26T09:00:00Z',
    ...overrides,
  };
}

// ==========================================================================
// 一、凭据不落库
// ==========================================================================

describe('secret manager 引用', () => {
  it('接受四种引用形态', () => {
    for (const ref of [
      'secret://projects/starcon/tenants/x/wecom',
      'vault://kv/starcon/wecom',
      'external-secrets://starcon/wecom',
      'k8s://starcon-secrets/wecom',
    ]) {
      expect(isValidSecretRef(ref), ref).toBe(true);
    }
  });

  it('🔴 拒绝地址本体 —— 那正是「凭据落库」的形态', () => {
    for (const ref of [
      'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=SECRET',
      'http://example.com/hook?token=abc',
      'https://example.com',
    ]) {
      expect(isValidSecretRef(ref), ref).toBe(false);
    }
  });

  it('拒绝空串与形状不合法的东西', () => {
    for (const ref of ['', '   ', 'secret:/no-slashes', 'ftp://example.com/hook', 'wecom']) {
      expect(isValidSecretRef(ref), ref).toBe(false);
    }
  });
});

describe('渠道草稿校验', () => {
  it('空白草稿会指出缺什么', () => {
    const draft = emptyChannelDraft();
    expect(validateChannelDraft(draft)).toBe('请填写渠道显示名');
    expect(validateChannelDraft({ ...draft, displayName: '运营群' })).toBe(
      '请填写 secret manager 引用',
    );
  });

  it('🔴 地址本体给出一条说清「为什么不许填」的提示', () => {
    const draft = {
      ...emptyChannelDraft(),
      displayName: '运营群',
      secretRef: 'https://qyapi.weixin.qq.com/hook?key=SECRET',
    };
    const reason = validateChannelDraft(draft);
    expect(reason).toContain('凭据不落库');
    expect(reason).toContain('引用');
  });

  it('形状不合法时给出另一条提示', () => {
    const draft = { ...emptyChannelDraft(), displayName: '运营群', secretRef: 'wecom' };
    expect(validateChannelDraft(draft)).toContain('引用形状不合法');
  });

  it('合法草稿没有理由', () => {
    const draft = {
      ...emptyChannelDraft(),
      displayName: '运营群',
      secretRef: 'secret://projects/starcon/x/wecom',
    };
    expect(validateChannelDraft(draft)).toBeNull();
  });

  it('请求体去掉首尾空白并逐字段对应', () => {
    const draft = {
      ...emptyChannelDraft(),
      displayName: '  运营群  ',
      secretRef: '  secret://projects/starcon/x/wecom  ',
    };
    expect(buildChannelInput(draft)).toEqual({
      channelCode: 'WECHAT_WORK',
      displayName: '运营群',
      secretRef: 'secret://projects/starcon/x/wecom',
      cardEnabled: true,
      linkEnabled: true,
      status: 'ENABLED',
    });
  });

  it('已有渠道 → 草稿是逐字段还原', () => {
    const original = channel({ cardEnabled: false, linkEnabled: false, status: 'DISABLED' });
    expect(draftOf(original)).toEqual({
      channelCode: 'WECHAT_WORK',
      displayName: '运营群机器人',
      secretRef: 'secret://projects/starcon/tenants/x/wecom',
      cardEnabled: false,
      linkEnabled: false,
      status: 'DISABLED',
    });
  });
});

// ==========================================================================
// 二、跳转目标
// ==========================================================================

describe('跳转目标', () => {
  it('只允许站内相对路径', () => {
    expect(isValidRelativeLink('/inventory/stockout-alerts')).toBe(true);
    expect(isValidRelativeLink('')).toBe(true);
    expect(isValidRelativeLink('https://evil.example.com/steal')).toBe(false);
    expect(isValidRelativeLink('inventory/stockout-alerts')).toBe(false);
    expect(isValidRelativeLink('/有 空格')).toBe(false);
  });

  it('推送草稿里带站外地址会被挡住', () => {
    const draft = {
      ...emptyPushDraft(),
      sourceKind: 'NOTIFICATION',
      notificationRef: 'SKU-001',
      title: '缺货提醒',
      channelIds: ['11111111-1111-4111-8111-111111111111'],
      recipientMembershipId: '22222222-2222-4222-8222-222222222222',
      linkUrl: 'https://evil.example.com/steal',
    };
    expect(validatePushDraft(draft)).toContain('站内相对路径');
  });
});

// ==========================================================================
// 三、推送入参
// ==========================================================================

describe('推送草稿校验与入参', () => {
  const base = {
    ...emptyPushDraft(),
    // 基线取「通知投影」来源并把它的必填项填齐：这样后面逐条校验
    // 「缺渠道 / 缺收件人」时，不会先被「缺命中 id」那条拦下。
    sourceKind: 'NOTIFICATION',
    notificationRef: 'SKU-001',
    title: '缺货提醒',
    notificationType: 'STOCKOUT',
    channelIds: ['11111111-1111-4111-8111-111111111111'],
    recipientMembershipId: '22222222-2222-4222-8222-222222222222',
  };

  it('缺的东西逐条指出来', () => {
    expect(validatePushDraft({ ...base, sourceKind: 'ALERT_HIT', alertHitId: '' })).toContain(
      '候选清单',
    );
    expect(validatePushDraft({ ...base, sourceKind: 'NOTIFICATION', notificationRef: '' })).toContain(
      '上游主键',
    );
    expect(
      validatePushDraft({ ...base, sourceKind: 'NOTIFICATION', notificationRef: 'SKU-1', title: '' }),
    ).toContain('标题');
    expect(validatePushDraft({ ...base, channelIds: [] })).toContain('目标渠道');
    expect(validatePushDraft({ ...base, recipientMembershipId: '' })).toContain('收件人');
  });

  it('ALERT_HIT 来源：只带命中 id，标题正文由服务端按命中事实生成', () => {
    const input = buildPushInput({ ...base, sourceKind: 'ALERT_HIT', alertHitId: 'hit-1' });
    expect(input.alertHitId).toBe('hit-1');
    expect(input.notificationRef).toBeNull();
    expect(input.title).toBeNull();
    expect(input.body).toBeNull();
    expect(input.notificationType).toBe('STOCKOUT');
  });

  it('NOTIFICATION 来源：带上主键与标题，正文空串按「没有正文」处理', () => {
    const input = buildPushInput({
      ...base,
      sourceKind: 'NOTIFICATION',
      notificationRef: '  SKU-001  ',
      title: '  缺货提醒  ',
      body: '   ',
    });
    expect(input.notificationRef).toBe('SKU-001');
    expect(input.title).toBe('缺货提醒');
    expect(input.body).toBeNull();
    expect(input.alertHitId).toBeNull();
  });

  it('跳转目标空着就不带这个字段', () => {
    expect(buildPushInput({ ...base, sourceKind: 'ALERT_HIT', alertHitId: 'hit-1' }).linkUrl).toBeNull();
    expect(
      buildPushInput({
        ...base,
        sourceKind: 'ALERT_HIT',
        alertHitId: 'hit-1',
        linkUrl: '/inventory',
      }).linkUrl,
    ).toBe('/inventory');
  });
});

// ==========================================================================
// 四、汇总与措辞
// ==========================================================================

describe('投递清单的汇总与分桶', () => {
  const list: ExternalDeliveryList = {
    deliveries: [
      delivery({ status: 'DELIVERED', deliveredAt: '2026-10-26T09:01:00Z', failureCode: null, failureMessage: null }),
      delivery({ status: 'FAILED' }),
      delivery({ status: 'DEFERRED', nextAttemptAt: '2026-10-26T23:00:00Z' }),
      delivery({
        status: 'FAILED_EXHAUSTED',
        alertState: 'RAISED',
        alertedAt: '2026-10-26T09:05:00Z',
        alertMessage: '投递失败且重试已耗尽',
        nextAttemptAt: null,
      }),
    ],
    total: 9,
    limit: 20,
    offset: 0,
  };

  it('汇总数字与清单同源（total 取服务端给的筛选后总数）', () => {
    const summary = summarizeDeliveries(list);
    expect(summary.total).toBe(9);
    expect(summary.delivered).toBe(1);
    expect(summary.failed).toBe(1);
    expect(summary.deferred).toBe(1);
    expect(summary.exhausted).toBe(1);
    expect(summary.alerting).toBe(1);
  });

  it('空清单给出全零而不是「读不到」', () => {
    expect(summarizeDeliveries(null)).toEqual({
      total: 0,
      delivered: 0,
      failed: 0,
      deferred: 0,
      exhausted: 0,
      alerting: 0,
    });
  });

  it('按状态分桶互不重叠', () => {
    const grouped = splitByStatus(list.deliveries);
    expect(grouped.delivered).toHaveLength(1);
    expect(grouped.pending).toHaveLength(1);
    expect(grouped.deferred).toHaveLength(1);
    expect(grouped.exhausted).toHaveLength(1);
  });

  it('状态的视觉语气分得开', () => {
    expect(statusTone('DELIVERED')).toBe('ok');
    expect(statusTone('FAILED')).toBe('warn');
    expect(statusTone('DEFERRED')).toBe('warn');
    expect(statusTone('FAILED_EXHAUSTED')).toBe('bad');
    expect(statusTone('SOMETHING_ELSE')).toBe('idle');
  });

  it('失败原因有话可说，推迟说清是免打扰', () => {
    expect(failurePhrase(delivery())).toBe('secret manager 未接通');
    expect(failurePhrase(delivery({ status: 'DEFERRED', failureMessage: null }))).toContain(
      '免打扰',
    );
    expect(failurePhrase(delivery({ status: 'DELIVERED' }))).toBe('');
  });

  it('下次重试一句话说得清', () => {
    expect(nextAttemptPhrase(delivery())).toContain('下次自动重试');
    expect(nextAttemptPhrase(delivery({ nextAttemptAt: null }))).toBe('不会再自动重试');
  });
});

// ==========================================================================
// 五、渠道可用性
// ==========================================================================

describe('渠道可用性', () => {
  it('启用且凭据可解析才算可用', () => {
    expect(channelUsable(channel({ credentialResolvable: true }))).toBe(true);
    expect(channelUsable(channel({ status: 'DISABLED', credentialResolvable: true }))).toBe(false);
    expect(channelUsable(channel({ credentialResolvable: false }))).toBe(false);
  });

  it('不可用时说得清是哪一条', () => {
    expect(channelUnusableReason(channel({ status: 'DISABLED' }))).toBe('渠道已停用');
    expect(channelUnusableReason(channel({ credentialResolvable: false }))).toContain(
      'secret manager',
    );
    expect(channelUnusableReason(channel({ credentialResolvable: true }))).toBe('');
  });
});

// ==========================================================================
// 六、词表齐备
// ==========================================================================

describe('受控词表', () => {
  it('四个渠道码与五个通知类型都齐', () => {
    expect(Object.keys(CHANNEL_CODE_LABELS)).toEqual([
      'WECHAT_WORK',
      'DINGTALK',
      'FEISHU',
      'EMAIL',
    ]);
    expect(Object.keys(NOTIFICATION_TYPE_LABELS)).toEqual([
      'STOCKOUT',
      'TIMELINESS_OVERDUE',
      'SYNC_FAILURE',
      'RECONCILIATION_DIFF',
      'MESSAGE',
    ]);
  });

  it('四个投递状态与三种告警状态都有中文名', () => {
    expect(Object.keys(DELIVERY_STATUS_LABELS)).toHaveLength(4);
    expect(Object.keys(ALERT_STATE_LABELS)).toHaveLength(3);
    expect(Object.keys(SOURCE_KIND_LABELS)).toHaveLength(2);
    expect(Object.keys(CARD_STYLE_LABELS)).toHaveLength(2);
  });
});
