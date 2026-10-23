import { api } from './api';
import type {
  AccountLocaleCapabilities,
  AccountLocaleView,
  PlatformView,
} from '../../../../packages/api-contracts/src/index';

/**
 * 1.4.2.1 语言与时区 —— 前端模型（纯函数 + 接口调用）。
 *
 * <p>蓝图：「界面语言（中文/英文等）与展示时区，影响时间显示与日期分组，**不改变存储时区**。
 * 输入：用户选择；平台站点时区清单。输出：界面语言包与时间显示口径。」
 *
 * <p>本文件钉住四件最容易做错、而做错会**静默**的事：
 *
 * <ol>
 *   <li><b>时区候选清单有两个来源，不能混为一谈</b>：
 *       「平台站点时区」来自 channel 服务的 {@code marketplace.default_timezone}（只读，是本模块蓝图点名的输入）；
 *       「全部 IANA 时区」来自 {@code Intl.supportedValuesOf('timeZone')}（浏览器的 tzdb）。
 *       后者<b>不是</b>平台站点清单，只是「所有可选时区」。界面上必须分开标注，
 *       否则用户会以为「平台站点时区」就是这几百项。</li>
 *   <li><b>显示口径由服务端声明</b>（{@code displayConvention}），前端**原样**传给
 *       {@code Intl.DateTimeFormat}，不在前端另写一份常量 —— 两边各写一份必然漂移。</li>
 *   <li><b>存下来的时区不一定能用</b>：有人绕过本模块用通用偏好端点写了
 *       {@code Not/AZone} 时，渲染会抛异常。所以渲染前一律做一次「能不能渲染」的检查，
 *       不能渲染就回落到默认时区。</li>
 *   <li><b>界面语言没有开关</b>：本仓没有 i18n 运行时与语言包，所以界面上**不提供**语言切换下拉 ——
 *       提供了就是「选了 English 但文案一点没变」的假开关。可用性由服务端能力声明如实报出。</li>
 * </ol>
 */

/** 平台站点时区（来自 channel 服务的 {@code marketplace.default_timezone}，只读）。 */
export interface PlatformSiteTimezone {
  platformCode: string;
  platformName: string;
  code: string;
  name: string;
  timezone: string;
  locale: string;
}

/** 时区候选的来源。 */
export type TimezoneSource = 'platform-site' | 'iana' | 'current';

/** 一个可选时区。 */
export interface TimezoneCandidate {
  /** IANA 时区 id（如 {@code Asia/Shanghai}）。 */
  id: string;
  /** 当前偏移标签（Intl 的 shortOffset，如 {@code GMT+8}）。 */
  offset: string;
  /** 来源：平台站点 / 全部 IANA / 当前选中。 */
  source: TimezoneSource;
  /** 命中它的平台站点名（平台站点时区才有）。 */
  platformLabels: string[];
}

/** 时间显示口径：服务端声明的 {@code Intl.DateTimeFormat} 选项集。 */
export interface DisplayConvention {
  locale: string;
  options: Intl.DateTimeFormatOptions;
}

/** 平台默认界面语言（与服务端 {@code AccountLocaleRules.DEFAULT_LOCALE} 一致）。 */
export const DEFAULT_LOCALE = 'zh-CN';

/** 平台默认展示时区（与服务端 {@code AccountLocaleRules.DEFAULT_TIMEZONE} 一致）。 */
export const DEFAULT_TIMEZONE = 'Asia/Shanghai';

/**
 * 本地兜底显示口径。
 *
 * <p>只在<b>服务端不可达</b>时用（例如后端没起来）。正常路径一律用服务端声明的那一份，
 * 前端不替服务端做主。
 */
export const DEFAULT_DISPLAY_CONVENTION: DisplayConvention = {
  locale: DEFAULT_LOCALE,
  options: {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
    timeZoneName: 'shortOffset',
  },
};

/** 参考时区：给「同一时刻在不同时区下长什么样」一个对照（与仓库里其它模块用过的四个时区一致）。 */
export const REFERENCE_TIMEZONES: readonly string[] = [
  'Asia/Shanghai',
  'Europe/London',
  'America/New_York',
  'America/Los_Angeles',
];

// ----------------------------------------------------------------------
// 接口调用
// ----------------------------------------------------------------------

/** 读我当前生效的界面语言与展示时区。 */
export function fetchAccountLocale(): Promise<AccountLocaleView> {
  return api<AccountLocaleView>('identity/user-locale');
}

/** 读能力声明（逐条报「能做什么、不能做什么、缺什么、归谁」）。 */
export function fetchAccountLocaleCapabilities(): Promise<AccountLocaleCapabilities> {
  return api<AccountLocaleCapabilities>('identity/user-locale/capabilities');
}

/** 保存界面语言与展示时区（两个字段都可以只给一个）。 */
export function saveAccountLocale(next: {
  locale?: string;
  timezone?: string;
}): Promise<AccountLocaleView> {
  return api<AccountLocaleView>('identity/user-locale', 'PUT', next);
}

/** 重置（删掉服务端那一行 = 回到默认）。 */
export function resetAccountLocale(): Promise<void> {
  return api<void>('identity/user-locale', 'DELETE');
}

/**
 * 读平台站点时区清单（channel 服务只读端点 {@code GET /channel/platforms}）。
 *
 * <p>⚠️ 该端点要 {@code channel.registry.read} 权限；没有权限时会 403 ——
 * 那是「拿不到平台站点清单」，不是「平台站点没有时区」，界面上要分开说。
 */
export async function fetchPlatformSiteTimezones(): Promise<PlatformSiteTimezone[]> {
  const platforms = await api<PlatformView[]>('channel/platforms');
  const sites: PlatformSiteTimezone[] = [];
  for (const platform of platforms ?? []) {
    for (const site of platform.marketplaces ?? []) {
      if (!site.defaultTimezone) continue;
      sites.push({
        platformCode: platform.code,
        platformName: platform.name,
        code: site.code,
        name: site.name,
        timezone: site.defaultTimezone,
        locale: site.defaultLocale,
      });
    }
  }
  return sites;
}

// ----------------------------------------------------------------------
// 时区候选清单
// ----------------------------------------------------------------------

/**
 * 浏览器 tzdb 里的全部 IANA 时区。
 *
 * <p>用 {@code Intl.supportedValuesOf('timeZone')} 而不是手写常量数组 ——
 * 手写的几十项既不完整也会过期。浏览器不支持这个 API 时返回空数组，
 * 届时候选清单只剩平台站点时区与默认值，界面上要如实说明。
 */
export function supportedTimeZoneIds(): string[] {
  const intl = Intl as typeof Intl & { supportedValuesOf?: (key: string) => string[] };
  if (typeof intl.supportedValuesOf !== 'function') return [];
  try {
    return intl.supportedValuesOf('timeZone');
  } catch {
    return [];
  }
}

/** 这个时区能不能被 {@code Intl} 渲染（不能 ⇒ 渲染会抛异常，必须先拦住）。 */
export function isRenderableTimeZone(id: string): boolean {
  try {
    new Intl.DateTimeFormat('en', { timeZone: id });
    return true;
  } catch {
    return false;
  }
}

/** 当前偏移标签（照抄 {@code packages/ui/src/lib/world-clock.ts} 的 {@code shortOffset} 写法）。 */
export function formatOffset(instant: Date, timeZone: string): string {
  try {
    const parts = new Intl.DateTimeFormat('en', { timeZone, timeZoneName: 'shortOffset' }).formatToParts(
      instant,
    );
    return parts.find((part) => part.type === 'timeZoneName')?.value ?? '';
  } catch {
    return '';
  }
}

/**
 * 造时区候选清单。
 *
 * <p><b>顺序有讲究</b>：平台站点时区排在最前（那是蓝图点名的输入，用户最可能要的就在那里），
 * 其次是全部 IANA 时区（按 id 排序，稳可预期），最后兜上「当前选中」——
 * 当前选中的时区即使不在上面两份清单里（比如历史遗留的别名），也必须出现在清单里，
 * 否则用户打开设置页会发现自己选的那个时区「不见了」。
 */
export function buildTimezoneCandidates(options: {
  platformSites: PlatformSiteTimezone[];
  current: string;
  defaultTimezone: string;
  instant: Date;
}): TimezoneCandidate[] {
  const { platformSites, current, defaultTimezone, instant } = options;
  const byId = new Map<string, TimezoneCandidate>();

  const put = (id: string, source: TimezoneSource, label?: string) => {
    const existing = byId.get(id);
    if (existing) {
      if (label && !existing.platformLabels.includes(label)) {
        existing.platformLabels.push(label);
      }
      if (existing.source !== 'platform-site' && source === 'platform-site') {
        existing.source = source;
      }
      return;
    }
    byId.set(id, {
      id,
      offset: formatOffset(instant, id),
      source,
      platformLabels: label ? [label] : [],
    });
  };

  for (const site of platformSites) put(site.timezone, 'platform-site', `${site.platformName} ${site.name}`);
  for (const id of [...supportedTimeZoneIds()].sort()) put(id, 'iana');
  put(defaultTimezone, 'iana');
  if (current) put(current, 'current');

  return [...byId.values()];
}

/** 按关键字过滤候选清单（匹配 id 或平台站点名）。 */
export function filterTimezoneCandidates(
  candidates: TimezoneCandidate[],
  query: string,
): TimezoneCandidate[] {
  const keyword = query.trim().toLowerCase();
  if (keyword === '') return candidates;
  return candidates.filter(
    (candidate) =>
      candidate.id.toLowerCase().includes(keyword) ||
      candidate.platformLabels.some((label) => label.toLowerCase().includes(keyword)),
  );
}

// ----------------------------------------------------------------------
// 渲染口径
// ----------------------------------------------------------------------

/**
 * 按服务端声明的口径渲染一个时刻。
 *
 * <p><b>口径来自服务端</b>：{@code convention.options} 原样传给 {@code Intl.DateTimeFormat}，
 * 前端不自己拼日期格式串 —— 否则全站会出现两套写法、两种偏移标签。
 */
export function formatDateTime(
  instant: Date,
  timeZone: string,
  convention: DisplayConvention = DEFAULT_DISPLAY_CONVENTION,
): string {
  const zone = isRenderableTimeZone(timeZone) ? timeZone : DEFAULT_TIMEZONE;
  try {
    return new Intl.DateTimeFormat(convention.locale, {
      ...convention.options,
      timeZone: zone,
    }).format(instant);
  } catch {
    return new Intl.DateTimeFormat(DEFAULT_DISPLAY_CONVENTION.locale, {
      ...DEFAULT_DISPLAY_CONVENTION.options,
      timeZone: DEFAULT_TIMEZONE,
    }).format(instant);
  }
}

/** 把服务端声明的口径转成本地类型（契约里它是一个开放对象）。 */
export function toDisplayConvention(raw: {
  locale: string;
  options: unknown;
}): DisplayConvention {
  return {
    locale: raw.locale || DEFAULT_LOCALE,
    options: (raw.options ?? {}) as Intl.DateTimeFormatOptions,
  };
}
