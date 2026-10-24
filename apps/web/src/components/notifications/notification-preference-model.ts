/**
 * 1.3.1.2 通知偏好的前端模型（**纯函数层**）。
 *
 * <p>蓝图原文：「用户按通知类型选择接收渠道（站内/邮件/企业 IM）与免打扰时段。
 * ……输出：偏好配置；供 1.3.2.2 推送时过滤。
 * ……**关键业务通知（超期发货、账务差异）不允许被完全关闭**，系统强制保留下不可关闭的底线项。」
 *
 * <p>🔴 **本文件是纯函数层**：不碰网络、不碰 React。它做五件事：
 *
 * <ol>
 *   <li><b>勾选态</b>：把服务端下发的生效值折成「类型 → 渠道集合」，并守住
 *       **底线渠道不可取消**这条纪律（客户端先挡一道，服务端再挡一道）；</li>
 *   <li><b>构造入参</b>：把勾选态与时段折成保存请求体（形状与服务端契约逐字一致）；</li>
 *   <li><b>时段文本</b>：校验 / 归一 / 描述「当地时刻」时段的写法；</li>
 *   <li><b>时区清单</b>：从<b>运行时</b>（{@link Intl.supportedValuesOf}）取 IANA 区域 id 清单 ——
 *       不写死一份数组（写死的那份迟早与 JDK 的 tzdb 漂移）；</li>
 *   <li><b>汇总</b>：把生效值折成页面上要显示的几个数字。</li>
 * </ol>
 *
 * <p>🔴 <b>权威词表在服务端</b>（identity 的 {@code NotificationPreferenceCapabilities}），
 * 本文件里的渠道码只是「前端要渲染哪几个渠道」的清单，与服务端逐字一致；
 * 页面加载时会用服务端下发的词表校对，不一致就以服务端为准。
 *
 * <p>🔴 <b>免打扰时段是「当地时刻 + 时区」语义</b>：时段文本是「当地几点到几点」，
 * 不是 UTC 时刻，也不是「距离现在几小时」。前端只做**形状**校验，
 * 「现在算不算在免打扰时段里」由服务端按用户自己的时区判定（{@code /resolve} 端点）。
 */

// 相对路径而不是 `@/lib/api`：根 vitest 配置没有 `@/*` 别名（那只在 Next 构建里解析），
// 用别名会让这个模型的单测在 vite 下加载失败。与 `notification-inbox-model` 保持同一套约定。
import type {
  NotificationPreferenceCapabilities,
  NotificationPreferenceInput,
  NotificationPreferenceResolution,
  NotificationPreferenceTypeSetting,
  NotificationPreferenceView,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 接收渠道（顺序 = 蓝图里「站内/邮件/企业 IM」的顺序）。
 *
 * <p>🔴 **权威词表在服务端**（identity 的能力声明）。这里这份与它逐字一致，
 * 页面加载时会用服务端下发的词表校对。
 */
export const PREFERENCE_CHANNELS = ['IN_APP', 'EMAIL', 'IM'] as const;

export type PreferenceChannel = (typeof PREFERENCE_CHANNELS)[number];

/** 渠道 → 中文名。 */
export const CHANNEL_LABELS: Record<PreferenceChannel, string> = {
  IN_APP: '站内',
  EMAIL: '邮件',
  IM: '企业 IM',
};

/** 类型 → 勾选中的渠道集合。 */
export type ChannelSelection = Record<string, PreferenceChannel[]>;

/** 免打扰时段的上限（与服务端一致）。 */
export const MAX_QUIET_WINDOWS = 4;

/** 时段形状：`HH:mm-HH:mm`（当地时间，24 小时制）。 */
const WINDOW_PATTERN = /^([01]\d|2[0-3]):([0-5]\d)-([01]\d|2[0-3]):([0-5]\d)$/;

/** ================================================================ 勾选态 */

/**
 * 把服务端下发的生效值折成「类型 → 渠道集合」。
 *
 * <p>服务端下发的渠道码**不在词表里**的（理论上不会发生，词表是受控的）一律丢掉 ——
 * 前端不把不认识的东西渲染成一个可点的复选框。
 */
export function selectionOf(view: NotificationPreferenceView): ChannelSelection {
  const selection: ChannelSelection = {};
  for (const setting of view.types) {
    selection[setting.type] = setting.channels.filter(isKnownChannel);
  }
  return selection;
}

/** 渠道码是否在词表里。 */
export function isKnownChannel(code: string): code is PreferenceChannel {
  return (PREFERENCE_CHANNELS as readonly string[]).includes(code);
}

/**
 * 勾选 / 取消一个渠道。
 *
 * <p>🔴 **底线渠道不可取消**：关键业务通知（超期发货、账务差异）必须留着「站内」，
 * 客户端先挡一道（按钮直接不可点），服务端再挡一道（400）——
 * 只在前端置灰是不够的，前端是可以被绕过的。
 *
 * <p>其余情况：渠道集合**不允许变成空**（与 1.3.1.1「订阅集合不允许为空」同一条纪律），
 * 取消最后一个渠道时保持原样。
 */
export function toggleChannel(
  selection: ChannelSelection,
  type: string,
  code: PreferenceChannel,
  baselineChannel: string | null,
): ChannelSelection {
  const current = selection[type] ?? [];
  if (current.includes(code)) {
    if (baselineChannel && code === baselineChannel) return selection;
    if (current.length <= 1) return selection;
    return { ...selection, [type]: current.filter((item) => item !== code) };
  }
  return { ...selection, [type]: [...current, code] };
}

/** 某一类的渠道集合是不是被勾空了（服务端会拒）。 */
export function isEmptySelection(channels: readonly PreferenceChannel[]): boolean {
  return channels.length === 0;
}

/** ================================================================ 入参 */

/**
 * 构造保存请求体。
 *
 * <p>🔴 **契约里刻意没有裸 Map**（springdoc 对 `Map` 只生成空的 `{}`），
 * 所以「类型 → 渠道集合」用**列表桶**表达 —— 与服务端逐字一致。
 *
 * @param selection 类型 → 渠道集合
 * @param windows 免打扰时段（当地时间）
 * @param timezone 时区（IANA 区域 id）
 */
export function buildPreferenceInput(
  selection: ChannelSelection,
  windows: readonly string[],
  timezone: string,
): NotificationPreferenceInput {
  return {
    types: Object.entries(selection).map(([type, channels]) => ({
      type,
      channels: [...channels],
    })),
    windows: [...windows],
    timezone,
  };
}

// ================================================================ 时段文本

/**
 * 校验并归一化一段免打扰时段文本。
 *
 * <p>返回 {@code null} 表示**不合法**（形状不对、或起止相同）。
 * 起止相同等于「全天免打扰」，那会让关键业务通知在外部渠道上永远静默 ——
 * 服务端也会拒，这里先挡住是为了让用户当场看到原因。
 */
export function normalizeWindowText(raw: string): string | null {
  const text = raw.trim();
  if (!WINDOW_PATTERN.test(text)) return null;
  const [start = '', end = ''] = text.split('-');
  if (start === end) return null;
  return text;
}

/** 时段文本是否合法（用于输入框的即时反馈）。 */
export function isValidWindowText(raw: string): boolean {
  return normalizeWindowText(raw) !== null;
}

/**
 * 时段的中文描述（把「当地时刻」说清楚）。
 *
 * <p>🔴 跨午夜必须说出来：`22:00-08:00` 不是「22 点到 8 点」，
 * 而是「今天 22 点到**次日** 8 点」—— 说不清楚的话用户会以为免打扰只有 6 小时。
 */
export function describeWindow(window: string): string {
  const [start = '', end = ''] = window.split('-');
  if (!start || !end) return window;
  return start > end ? `${start} → 次日 ${end}` : `${start} → ${end}`;
}

/** ================================================================ 时区清单

/**
 * IANA 时区清单 —— 从**运行时**取，不写死一份数组。
 *
 * <p>🔴 **为什么不写死**：写死的那份迟早与 JDK 的 tzdb 漂移
 * （新区被加进来、老区被改名），表现是「下拉里选不到自己所在的时区」。
 * {@link Intl.supportedValuesOf} 取的是当前运行时的 tzdb，和浏览器实际使用的
 * 夏令时规则是同一份 —— 这正是免打扰时段要用的那份。
 */
export function listTimezones(): string[] {
  const supported = (
    Intl as unknown as { supportedValuesOf?: (key: string) => string[] }
  ).supportedValuesOf;
  if (typeof supported === 'function') {
    try {
      return [...supported.call(Intl, 'timeZone')].sort();
    } catch {
      // 落到下面的兜底
    }
  }
  // 兜底：运行时不支持这个 API 时，至少给出当前生效的那个（由调用方补进来）。
  return [];
}

/**
 * 把时区清单按**区域前缀**分组（`Asia/…` 一组、`Europe/…` 一组），供下拉分组显示。
 *
 * @param zones 时区清单
 * @param extra 一定要出现在清单里的时区（当前生效的那个）
 */
export function groupTimezones(
  zones: readonly string[],
  extra?: string | null,
): { region: string; zones: string[] }[] {
  const all = new Set(zones.filter((zone) => typeof zone === 'string' && zone.length > 0));
  if (extra) all.add(extra);
  const groups = new Map<string, string[]>();
  for (const zone of [...all].sort()) {
    const region = zone.includes('/') ? zone.split('/')[0]! : zone;
    const bucket = groups.get(region) ?? [];
    bucket.push(zone);
    groups.set(region, bucket);
  }
  return [...groups.entries()].map(([region, items]) => ({ region, zones: items }));
}

// ================================================================ 汇总

/** 页面上要显示的几个数字。 */
export interface PreferenceSummary {
  /** 通知类型总数。 */
  typeCount: number;
  /** 关键业务通知（不可关闭的底线项）数。 */
  mandatoryCount: number;
  /** 免打扰时段数。 */
  quietWindowCount: number;
  /** 只留了站内渠道的类型数（完全静音的类型）。 */
  inboxOnlyCount: number;
}

/** 把生效值折成页面上要显示的几个数字。 */
export function summarizePreference(view: NotificationPreferenceView): PreferenceSummary {
  let mandatoryCount = 0;
  let inboxOnlyCount = 0;
  for (const setting of view.types) {
    if (setting.mandatory) mandatoryCount += 1;
    if (setting.channels.length === 1 && setting.channels[0] === 'IN_APP') inboxOnlyCount += 1;
  }
  return {
    typeCount: view.types.length,
    mandatoryCount,
    quietWindowCount: view.windows.length,
    inboxOnlyCount,
  };
}

/** 某一类的设置（类型 → 设置）。 */
export function settingOf(
  view: NotificationPreferenceView,
  type: string,
): NotificationPreferenceTypeSetting | undefined {
  return view.types.find((setting) => setting.type === type);
}

// ================================================================ 免打扰状态

/**
 * 从解析结果里取**全局**的免打扰状态。
 *
 * <p>🔴 免打扰时段是**全局**的（不是每类通知各设一套），
 * 所以一次 {@code /resolve} 就够了 —— 页面上要显示的「现在是不是免打扰时段」
 * 与通知类型无关。
 */
export function quietStateOf(resolution: NotificationPreferenceResolution): {
  quietNow: boolean;
  quietWindows: string[];
} {
  return { quietNow: resolution.quietNow, quietWindows: resolution.quietWindows };
}

/** 时区来源 → 人话（说清楚用户的免打扰按哪个时区算）。 */
export function describeTimezoneSource(view: NotificationPreferenceView): string {
  if (view.timezoneSource === 'PREFERENCE') return '按你在本模块设置的时区';
  if (view.timezoneSource === 'ACCOUNT_LOCALE') return '沿用「语言与时区」里的展示时区';
  return `按平台默认时区（${view.defaultTimezone}）`;
}

/** 能力声明里「不可关闭的底线项」的人话清单。 */
export function mandatorySummary(capabilities: NotificationPreferenceCapabilities): string[] {
  return capabilities.types
    .filter((rule) => rule.mandatory)
    .map((rule) => `${rule.label}（${rule.code}）：${rule.rule}`);
}
