/**
 * 1.3.2.2 外部渠道推送的前端模型（**纯函数层**）。
 *
 * <p>蓝图原文：「把选定的通知推送到企业 IM（企业微信/钉钉/飞书）或邮件，
 * 支持卡片化消息与点击跳转。」「**输出**：外部渠道投递记录（成功/失败/重试）；
 * 失败自动重试并告警。」
 *
 * <p>🔴 <b>本文件是纯函数层</b>：不碰网络、不碰 React。它做四件事：
 *
 * <ol>
 *   <li><b>凭据不落库</b>：表单里只收 **secret manager 引用**，
 *       {@code https://…} 这类地址本体在前端就被挡住（服务端与数据库层各再挡一道）；</li>
 *   <li><b>构造入参</b>：把表单草稿折成请求体（形状与服务端契约逐字一致）；</li>
 *   <li><b>校验形状</b>：引用形状 / 跳转目标 / 必填项（服务端再拒一次，
 *       前端先挡一道是为了让用户当场看到原因）；</li>
 *   <li><b>措辞与汇总</b>：把受控码折成人话，把投递清单折成页面上要显示的几个数字。</li>
 * </ol>
 *
 * <p>🔴 <b>权威词表在服务端</b>（identity 的 {@code ExternalPushCapabilities}），
 * 本文件里的码表只是「前端要渲染哪几个」的清单，页面加载时会用服务端下发的词表校对。
 */

/**
 * 相对路径而不是 `@/lib/api`：根 vitest 配置没有 `@/*` 别名（那只在 Next 构建里解析），
 * 用别名会让这个模型的单测在 vite 下加载失败。与 `alert-rule-model` 保持同一套约定。
 */
import type {
  ExternalChannel,
  ExternalChannelInput,
  ExternalDelivery,
  ExternalDeliveryList,
  ExternalPushRequest,
} from '../../../../../packages/api-contracts/src/index';

/** 渠道码 → 中文名（与服务端词表逐字一致）。 */
export const CHANNEL_CODE_LABELS: Record<string, string> = {
  WECHAT_WORK: '企业微信',
  DINGTALK: '钉钉',
  FEISHU: '飞书',
  EMAIL: '邮件',
};

/** 投递状态 → 中文名。 */
export const DELIVERY_STATUS_LABELS: Record<string, string> = {
  DELIVERED: '已投递',
  FAILED: '投递失败（待重试）',
  FAILED_EXHAUSTED: '投递失败（重试已耗尽）',
  DEFERRED: '已推迟（免打扰时段）',
};

/** 告警状态 → 中文名。 */
export const ALERT_STATE_LABELS: Record<string, string> = {
  NONE: '无告警',
  RAISED: '告警未确认',
  ACKNOWLEDGED: '告警已确认',
};

/** 来源种类 → 中文名。 */
export const SOURCE_KIND_LABELS: Record<string, string> = {
  ALERT_HIT: '预警命中（1.3.2.1）',
  NOTIFICATION: '通知投影（1.3.1）',
};

/** 消息样式 → 中文名。 */
export const CARD_STYLE_LABELS: Record<string, string> = {
  CARD: '卡片消息',
  TEXT: '纯文本',
};

/** 通知类型 → 中文名（**与 1.3.1.1 的受控词表逐字同表**）。 */
export const NOTIFICATION_TYPE_LABELS: Record<string, string> = {
  STOCKOUT: '库存缺货',
  TIMELINESS_OVERDUE: '履约超时',
  SYNC_FAILURE: '同步失败',
  RECONCILIATION_DIFF: '对账差异',
  MESSAGE: '站内信',
};

/**
 * **secret manager 引用**的形状：{@code <scheme>://<路径>}。
 *
 * <p>scheme 只收四个：secret / vault / external-secrets / k8s。
 *
 * <p>🔴 {@code https://} **不在名单里**：那不是引用，那是**凭据本体**（Webhook 地址本身）。
 */
export const SECRET_REF_PATTERN = /^(secret|vault|external-secrets|k8s):\/\/[A-Za-z0-9][A-Za-z0-9._/-]{0,180}$/;

/** 站内相对路径的形状（点击跳转目标）。 */
export const RELATIVE_LINK_PATTERN = /^\/[^\s]{1,499}$/;

/**
 * 引用形状是否合法（与服务端的 {@code ExternalPushRules.normalizeSecretRef} 同口径）。
 *
 * <p>🔴 {@code https://} 开头的一律**不合法** —— 那是要落库的凭据本体。
 */
export function isValidSecretRef(raw: string): boolean {
  const ref = raw.trim();
  if (!ref) return false;
  const lower = ref.toLowerCase();
  if (lower.startsWith('http://') || lower.startsWith('https://')) return false;
  return SECRET_REF_PATTERN.test(ref);
}

/** 跳转目标是否合法（只允许站内相对路径）。 */
export function isValidRelativeLink(raw: string): boolean {
  const link = raw.trim();
  if (!link) return true;
  if (link.includes('://')) return false;
  return RELATIVE_LINK_PATTERN.test(link);
}

/** 表单草稿（一个外部渠道）。 */
export interface ChannelDraft {
  channelCode: string;
  displayName: string;
  secretRef: string;
  cardEnabled: boolean;
  linkEnabled: boolean;
  status: string;
}

/** 新建渠道时的空白草稿。 */
export function emptyChannelDraft(): ChannelDraft {
  return {
    channelCode: 'WECHAT_WORK',
    displayName: '',
    secretRef: '',
    cardEnabled: true,
    linkEnabled: true,
    status: 'ENABLED',
  };
}

/** 已有渠道 → 草稿（用于编辑）。 */
export function draftOf(channel: ExternalChannel): ChannelDraft {
  return {
    channelCode: channel.channelCode,
    displayName: channel.displayName,
    secretRef: channel.secretRef,
    cardEnabled: channel.cardEnabled,
    linkEnabled: channel.linkEnabled,
    status: channel.status,
  };
}

/**
 * 渠道草稿校验：返回**原因**（null = 合法）。
 *
 * <p>🔴 「凭据不落库」在这里就挡住：地址本体给不出「请填引用」之外更有用的提示，
 * 但**必须**当场说清为什么被拒 —— 否则用户会以为系统坏了。
 */
export function validateChannelDraft(draft: ChannelDraft): string | null {
  if (!draft.channelCode) return '请选择外部渠道';
  if (!draft.displayName.trim()) return '请填写渠道显示名';
  if (draft.displayName.trim().length > 100) return '渠道显示名不能超过 100 个字符';
  if (!draft.secretRef.trim()) return '请填写 secret manager 引用';
  if (!isValidSecretRef(draft.secretRef)) {
    const ref = draft.secretRef.trim().toLowerCase();
    if (ref.startsWith('http://') || ref.startsWith('https://')) {
      return '凭据不落库：这里只填 secret manager 引用（如 secret://projects/starcon/tenants/x/wecom），不要填 Webhook 地址本身。';
    }
    return '引用形状不合法：应为 <scheme>://<路径>，scheme 只允许 secret / vault / external-secrets / k8s。';
  }
  return null;
}

/** 渠道草稿 → 请求体（形状与服务端契约逐字一致）。 */
export function buildChannelInput(draft: ChannelDraft): ExternalChannelInput {
  return {
    channelCode: draft.channelCode,
    displayName: draft.displayName.trim(),
    secretRef: draft.secretRef.trim(),
    cardEnabled: draft.cardEnabled,
    linkEnabled: draft.linkEnabled,
    status: draft.status,
  };
}

/** 表单草稿（一次推送）。 */
export interface PushDraft {
  sourceKind: string;
  alertHitId: string;
  notificationType: string;
  notificationRef: string;
  title: string;
  body: string;
  linkUrl: string;
  cardStyle: string;
  channelIds: string[];
  recipientMembershipId: string;
}

/** 新建推送时的空白草稿。 */
export function emptyPushDraft(): PushDraft {
  return {
    sourceKind: 'ALERT_HIT',
    alertHitId: '',
    notificationType: 'STOCKOUT',
    notificationRef: '',
    title: '',
    body: '',
    linkUrl: '',
    cardStyle: 'CARD',
    channelIds: [],
    recipientMembershipId: '',
  };
}

/** 推送草稿校验：返回**原因**（null = 合法）。 */
export function validatePushDraft(draft: PushDraft): string | null {
  if (!draft.sourceKind) return '请选择推送来源';
  if (draft.sourceKind === 'ALERT_HIT' && !draft.alertHitId) {
    return '来源是预警命中时必须先从候选清单里选一条';
  }
  if (draft.sourceKind === 'NOTIFICATION') {
    if (!draft.notificationRef.trim()) return '来源是通知投影时必须填写上游主键';
    if (!draft.title.trim()) return '来源是通知投影时必须填写标题';
  }
  if (!draft.notificationType) return '请选择通知类型（规则命中不带类型，由这里选定）';
  if (!draft.recipientMembershipId) return '必须指定收件人：推送要按收件人的通知偏好过滤';
  if (!draft.channelIds.length) return '至少选一个目标渠道';
  if (!isValidRelativeLink(draft.linkUrl)) {
    return '跳转目标只允许站内相对路径（以 / 开头）';
  }
  return null;
}

/** 推送草稿 → 请求体（形状与服务端契约逐字一致）。 */
export function buildPushInput(draft: PushDraft): ExternalPushRequest {
  const input: ExternalPushRequest = {
    sourceKind: draft.sourceKind,
    alertHitId: null,
    notificationType: draft.notificationType,
    notificationRef: null,
    title: null,
    body: null,
    linkUrl: null,
    cardStyle: draft.cardStyle,
    channelIds: draft.channelIds,
    recipientMembershipId: draft.recipientMembershipId,
  };
  if (draft.sourceKind === 'ALERT_HIT') {
    input.alertHitId = draft.alertHitId;
  } else {
    input.notificationRef = draft.notificationRef.trim();
    input.title = draft.title.trim();
    if (draft.body.trim()) input.body = draft.body.trim();
  }
  if (draft.linkUrl.trim()) input.linkUrl = draft.linkUrl.trim();
  return input;
}

/** 投递清单的汇总数字（页面上那几个角标）。 */
export interface DeliverySummary {
  total: number;
  delivered: number;
  failed: number;
  deferred: number;
  exhausted: number;
  alerting: number;
}

/** 汇总一份投递清单（**与列表同源**：不另查 count 端点）。 */
export function summarizeDeliveries(list: ExternalDeliveryList | null): DeliverySummary {
  const deliveries = list?.deliveries ?? [];
  let delivered = 0;
  let failed = 0;
  let deferred = 0;
  let exhausted = 0;
  let alerting = 0;
  for (const delivery of deliveries) {
    if (delivery.status === 'DELIVERED') delivered++;
    if (delivery.status === 'FAILED') failed++;
    if (delivery.status === 'DEFERRED') deferred++;
    if (delivery.status === 'FAILED_EXHAUSTED') exhausted++;
    if (delivery.alertState === 'RAISED') alerting++;
  }
  return { total: list?.total ?? deliveries.length, delivered, failed, deferred, exhausted, alerting };
}

/** 按状态拆清单（成功 / 待重试 / 推迟 / 已耗尽）。 */
export function splitByStatus(deliveries: ExternalDelivery[]): {
  delivered: ExternalDelivery[];
  pending: ExternalDelivery[];
  deferred: ExternalDelivery[];
  exhausted: ExternalDelivery[];
} {
  return {
    delivered: deliveries.filter((item) => item.status === 'DELIVERED'),
    pending: deliveries.filter((item) => item.status === 'FAILED'),
    deferred: deliveries.filter((item) => item.status === 'DEFERRED'),
    exhausted: deliveries.filter((item) => item.status === 'FAILED_EXHAUSTED'),
  };
}

/** 状态的视觉语气（页面着色用）。 */
export function statusTone(status: string): 'ok' | 'warn' | 'bad' | 'idle' {
  switch (status) {
    case 'DELIVERED':
      return 'ok';
    case 'FAILED':
    case 'DEFERRED':
      return 'warn';
    case 'FAILED_EXHAUSTED':
      return 'bad';
    default:
      return 'idle';
  }
}

/** 失败原因的一句话（页面直接显示，别让用户去猜）。 */
export function failurePhrase(delivery: ExternalDelivery): string {
  if (delivery.status === 'DELIVERED') return '';
  if (delivery.failureMessage) return delivery.failureMessage;
  if (delivery.status === 'DEFERRED') return '落在收件人的免打扰时段里，已推迟到时段结束再投。';
  return delivery.failureCode ?? '未知原因';
}

/** 下次重试的一句话。 */
export function nextAttemptPhrase(delivery: ExternalDelivery): string {
  if (!delivery.nextAttemptAt) return '不会再自动重试';
  return `下次自动重试：${delivery.nextAttemptAt}`;
}

/** 渠道是否可用（启用 + 凭据可解析）—— 页面据此提示「这条渠道投不出去」。 */
export function channelUsable(channel: ExternalChannel): boolean {
  return channel.status === 'ENABLED' && channel.credentialResolvable;
}

/** 渠道不可用时的一句说明。 */
export function channelUnusableReason(channel: ExternalChannel): string {
  if (channel.status === 'DISABLED') return '渠道已停用';
  if (!channel.credentialResolvable) {
    return channel.credentialNote ?? '凭据引用解析不了（secret manager 未接通）';
  }
  return '';
}
