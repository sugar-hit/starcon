import { describe, expect, it } from 'vitest';
import {
  actionUsable,
  detailSections,
  formatAmount,
  formatFileSize,
  formatQuantity,
  formatTime,
  groupAttachments,
  sectionByKey,
  timelineEventText,
  timelineTone,
  undeliveredBlock,
} from './aftersales-case-detail-model';
import type {
  AftersalesActionEntry,
  AftersalesAttachment,
  AftersalesCaseDetail,
  AftersalesDetailCapabilities,
  AftersalesTimelineEvent,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 11.1.1.2 售后单详情前端模型。
 *
 * <p>这些断言守的是**界面纪律**，不是渲染细节：
 *
 * <ul>
 *   <li><b>说话方四档必须真的分得开</b> —— 时间线是详情页的主干，
 *       买家侧与我方侧画成同一个颜色，那句「对齐」的加粗句在界面上就不成立。</li>
 *   <li><b>未知说话方不得回落成任何一侧</b>：回落 SYSTEM（中性）而不是 BUYER / US ——
 *       把后端新增的说话方算进任何一侧都是替平台编话。</li>
 *   <li><b>未交付模块必须给出「该能力未交付 + 归属模块 + 原因」</b>，
 *       而不是空白（会被读成「这张单没有退款也没有纠纷」）。</li>
 *   <li><b>时间线文案按后端给定的 occurredAt 原样呈现</b>，前端不重排、不补时间戳。</li>
 * </ul>
 */

const EVENT: AftersalesTimelineEvent = {
  id: 'e1',
  kind: 'MESSAGE',
  kindLabel: '沟通记录',
  actorSide: 'BUYER',
  actorSideLabel: '买家',
  occurredAt: '2026-03-01T00:00:00Z',
  summary: '买家说',
  body: '包裹到时就是破的',
  reference: 'm-1',
};

function detail(patch: Partial<AftersalesCaseDetail> = {}): AftersalesCaseDetail {
  return {
    header: {
      id: 'c-1',
      caseNo: 'AS1',
      caseType: 'RETURN_REFUND',
      caseTypeLabel: '退货退款',
      status: 'RECEIVED',
      statusLabel: '已受理待处理',
      terminal: false,
      reasonCode: 'QUALITY_DEFECT',
      reasonLabel: '质量问题',
      reasonNote: null,
      requestedAmount: 18.5,
      currency: 'USD',
      requestedQuantity: 2,
      buyerRequest: '收到就碎了',
      timelinessState: 'OVERDUE',
      timelinessLabel: '已超时',
      replyDueAt: '2026-03-01T00:00:00Z',
      resolveDueAt: null,
      effectiveDueAt: '2026-03-01T00:00:00Z',
      remainingSeconds: -100,
      receivedAt: '2026-03-01T00:00:00Z',
      resolvedAt: null,
    },
    order: {
      orderId: 'o-1',
      orderNo: 'SO-1',
      orderSource: 'AMAZON',
      platformShopCode: null,
      externalOrderNo: null,
      orderStatus: 'SUBMITTED',
      currency: 'USD',
      paymentMethod: 'PREPAID',
      grandTotalAmount: 20,
      subtotalAmount: 20,
      shippingAmount: 0,
      submittedAt: '2026-03-01T00:00:00Z',
      customerCode: 'C-1',
      customerName: '买家',
      customerType: 'INDIVIDUAL',
      recipientName: '买家',
      recipientPhone: null,
      recipientEmail: null,
      countryCode: 'US',
      province: 'CA',
      city: 'LA',
      addressLine: 'CA LA 100 Main St',
    },
    orderLines: [],
    lines: [],
    messages: [],
    attachments: [],
    notes: [],
    authorizations: [],
    ownerAssignments: [],
    ticket: null,
    platform: {
      platformCaseNo: 'PLAT-1',
      platformCaseUrl: null,
      platformReceivedAt: '2026-03-01T00:00:00Z',
      replyDueAt: '2026-03-01T00:00:00Z',
      resolveDueAt: null,
      effectiveDueAt: '2026-03-01T00:00:00Z',
      timelinessState: 'OVERDUE',
      timelinessLabel: '已超时',
      remainingSeconds: -100,
      platformDecisionStatus: null,
      decisionStatusAvailable: false,
      decisionStatusOwnerModule: '2 平台同步 + 11.4 平台纠纷',
      platformShopCode: null,
      platformShopCodeAvailable: false,
      platformShopCodeOwnerModule: '2 平台与店铺',
    },
    liability: {
      decisionCode: 'UNDECIDED',
      decisionLabel: '尚未判定',
      liability: '还没有任何授权记录',
      decidedAt: null,
      authorizedAmount: null,
      currency: null,
      attributionDelivered: false,
      attributionOwner: '11.1.2.2 商品问题归因',
      attributionNote: '环节级归因未交付',
      ownerCurrent: '未分配责任人',
      ownerSourceLabel: '未分配',
    },
    timeline: [EVENT],
    timelineTotal: 1,
    buyerEventCount: 1,
    ourEventCount: 0,
    relatedModules: [
      {
        code: 'RETURNS',
        label: '退货管理',
        delivered: false,
        ownerModule: '11.2 退货管理',
        reason: '11.2 未交付',
        href: null,
      },
    ],
    actions: [],
    evaluatedAt: '2026-03-05T00:00:00Z',
    alignmentNote: '按发生时刻排序',
    ...patch,
  };
}

describe('时间线说话方', () => {
  it('四档语气互不相同', () => {
    const tones = (['BUYER', 'US', 'PLATFORM', 'SYSTEM'] as const).map((s) => timelineTone(s).tone);
    expect(new Set(tones).size).toBe(4);
    expect(new Set((['BUYER', 'US', 'PLATFORM', 'SYSTEM'] as const).map((s) => timelineTone(s).badge)).size).toBe(4);
  });

  it('未知说话方回落到 SYSTEM 而不是任何一侧', () => {
    // 回落到 BUYER 会把系统事件说成买家说的；回落到 US 会说成我们说的 —— 两者都是替平台编话。
    expect(timelineTone('SOMETHING_NEW').tone).toBe('system');
    expect(timelineTone(null).tone).toBe('system');
    expect(timelineTone(undefined).tone).toBe('system');
  });

  it('文案原样使用后端的 occurredAt，不在前端重排', () => {
    const text = timelineEventText(EVENT);
    expect(text).toContain('买家');
    expect(text).toContain('沟通记录');
    expect(text).toContain('买家说');
    // 时间片段必须来自 occurredAt 本身
    expect(text).toContain(formatTime(EVENT.occurredAt));
  });
});

describe('未交付模块的区块', () => {
  it('未交付时给出明确文案而不是空白', () => {
    const b = undeliveredBlock({
      delivered: false,
      label: '退货管理',
      ownerModule: '11.2 退货管理',
      reason: '11.2 未交付',
    });
    expect(b.available).toBe(false);
    expect(b.title).toBe('该能力未交付');
    expect(b.detail).toContain('11.2 退货管理');
    expect(b.detail).toContain('11.2 未交付');
    // 关键：不能是空的 —— 空白会被读成「这张单没有退货」
    expect(b.detail).not.toBe('');
  });

  it('已交付时就是模块名本身', () => {
    const b = undeliveredBlock({ delivered: true, label: '退货管理', ownerModule: '', reason: '' });
    expect(b.available).toBe(true);
    expect(b.title).toBe('退货管理');
  });
});

describe('操作入口', () => {
  const base: AftersalesActionEntry = {
    code: 'X',
    label: 'X',
    available: true,
    method: 'POST',
    path: '/trade/aftersales/cases/{id}/notes',
    requiresWrite: true,
    precondition: null,
    ownerModule: null,
    reason: null,
  };

  it('未交付的入口不可用', () => {
    expect(
      actionUsable({
        ...base,
        code: 'CREATE_REFUND',
        available: false,
        method: '',
        path: '',
        ownerModule: '11.3.1.1 退款申请',
        reason: '11.3 未交付',
      }),
    ).toBe(false);
  });

  it('可用但没有路径的入口同样不可点', () => {
    // available=true 而 path 为空 = 点了没反应，比不可点更糟
    expect(actionUsable({ ...base, path: '' })).toBe(false);
  });

  it('正常的追加入口可点', () => {
    expect(actionUsable(base)).toBe(true);
  });
});

describe('区块与概览', () => {
  it('概览九个区块且缺数据时不编造', () => {
    const sections = detailSections(detail());
    expect(sections).toHaveLength(9);
    // 没登记商品行时说「0 行」，不是「未知」
    expect(sections.find((s) => s.key === 'lines')?.value).toContain('0 行');
    expect(sections.find((s) => s.key === 'ticket')?.value).toBe('未转工单');
    expect(sections.find((s) => s.key === 'liability')?.value).toBe('尚未判定');
    expect(sections.find((s) => s.key === 'amount')?.value).toBe('18.50 USD');
  });

  it('订单缺失时明说缺失而不是显示空白', () => {
    const d = detail({ order: null });
    expect(detailSections(d).find((s) => s.key === 'order')?.value).toBe('关联订单缺失');
  });

  it('区块能力表按 key 取，取不到返回 undefined（界面退化成普通说明，不编原因）', () => {
    const caps = {
      sections: [
        {
          section: 'relatedRefunds',
          label: '退款与退货关联',
          available: false,
          ownerModule: '11.2 / 11.3',
          reason: '未交付',
          landsIn: '',
        },
      ],
    } as unknown as AftersalesDetailCapabilities;
    expect(sectionByKey(caps, 'relatedRefunds')?.available).toBe(false);
    expect(sectionByKey(caps, 'nope')).toBeUndefined();
    expect(sectionByKey(null, 'relatedRefunds')).toBeUndefined();
  });
});

describe('凭证分组与格式化', () => {
  const att = (side: 'BUYER' | 'US', name: string): AftersalesAttachment => ({
    id: name,
    evidenceKind: 'OTHER',
    evidenceKindLabel: '其他',
    attachedBySide: side,
    attachedBySideLabel: side === 'BUYER' ? '买家' : '我方',
    fileName: name,
    contentType: null,
    storageRef: `obj://${name}`,
    fileSizeBytes: 1024,
    description: null,
    evidenceSource: 'TENANT_MAINTAINED',
    evidenceSourceLabel: '租户登记',
    sourceReference: null,
    capturedAt: '2026-03-01T00:00:00Z',
    uploadedBy: null,
  });

  it('按提供方分组（举证看的是谁的证据）', () => {
    const g = groupAttachments([att('BUYER', 'a.jpg'), att('US', 'b.png'), att('BUYER', 'c.jpg')]);
    expect(g).toHaveLength(2);
    expect(g[0]?.side).toBe('BUYER');
    expect(g[0]?.items).toHaveLength(2);
    expect(g[1]?.side).toBe('US');
    expect(g[1]?.items).toHaveLength(1);
  });

  it('格式化不把空值变成 0', () => {
    expect(formatAmount(null)).toBe('—');
    expect(formatQuantity(null)).toBe('—');
    expect(formatFileSize(null)).toBe('—');
    expect(formatTime(null)).toBe('—');
    // 解析失败原样返回，而不是显示 Invalid Date
    expect(formatTime('not-a-date')).toBe('not-a-date');
  });
});
