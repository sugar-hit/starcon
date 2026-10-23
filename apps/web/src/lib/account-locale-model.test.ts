import { describe, expect, it } from 'vitest';
import {
  DEFAULT_DISPLAY_CONVENTION,
  DEFAULT_LOCALE,
  DEFAULT_TIMEZONE,
  REFERENCE_TIMEZONES,
  buildTimezoneCandidates,
  filterTimezoneCandidates,
  formatDateTime,
  formatOffset,
  isRenderableTimeZone,
  supportedTimeZoneIds,
  toDisplayConvention,
  type PlatformSiteTimezone,
} from './account-locale-model';

/**
 * 1.4.2.1 语言与时区 —— 前端模型的单元测试。
 *
 * <p>本模型守的是四件**静默**的事：
 *
 * <ol>
 *   <li><b>平台站点时区 ≠ 全部 IANA 时区</b>：前者来自 channel 的站点参数（蓝图点名的输入），
 *       后者只是「所有可选时区」。混为一谈，界面上就会把几百项 IANA 时区说成「平台站点时区」。</li>
 *   <li><b>当前选中的时区必须出现在候选清单里</b>：哪怕它不在平台清单、也不在浏览器的
 *       IANA 表里（历史遗留的别名），用户打开设置页也不能发现「我选的那个不见了」。</li>
 *   <li><b>渲染前先问「能不能渲染」</b>：存下来的时区万一无效，{@code Intl} 会抛异常，
 *       一抛就把整页炸掉。</li>
 *   <li><b>显示口径来自服务端</b>：前端不自己拼日期格式串，否则全站会出现两套写法、两种偏移标签。</li>
 * </ol>
 *
 * <p>蓝图原话：「**不改变存储时区**」—— 这里断言的是「同一个绝对时刻在不同展示时区下
 * 渲染出不同的当地时间」，而不是「存进去的时区改变了时刻本身」。
 */

/** 平台站点时区样例（形状照抄 channel 服务的 {@code PlatformView.marketplaces}）。 */
const SITE: PlatformSiteTimezone = {
  platformCode: 'AMAZON',
  platformName: 'Amazon',
  code: 'US',
  name: '美国站',
  timezone: 'America/Los_Angeles',
  locale: 'en-US',
};

const INSTANT = new Date('2026-03-15T10:20:30Z');

describe('1.4.2.1 时区候选：平台站点时区与全部 IANA 时区是两回事', () => {
  it('平台站点时区排在最前，且带上平台站点名', () => {
    const candidates = buildTimezoneCandidates({
      platformSites: [SITE],
      current: DEFAULT_TIMEZONE,
      defaultTimezone: DEFAULT_TIMEZONE,
      instant: INSTANT,
    });
    expect(candidates[0]?.id).toBe('America/Los_Angeles');
    expect(candidates[0]?.source).toBe('platform-site');
    expect(candidates[0]?.platformLabels.join('、')).toContain('Amazon');
  });

  it('同一个时区被平台站点与 IANA 表同时命中时只出现一条', () => {
    const candidates = buildTimezoneCandidates({
      platformSites: [SITE],
      current: DEFAULT_TIMEZONE,
      defaultTimezone: DEFAULT_TIMEZONE,
      instant: INSTANT,
    });
    const ids = candidates.map((c) => c.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('当前选中的时区即使不在任何清单里也必须出现（否则用户发现自己的选择不见了）', () => {
    const legacy = 'US/Eastern';
    const candidates = buildTimezoneCandidates({
      platformSites: [],
      current: legacy,
      defaultTimezone: DEFAULT_TIMEZONE,
      instant: INSTANT,
    });
    const hit = candidates.find((c) => c.id === legacy);
    expect(hit).toBeDefined();
    expect(hit?.source).toBe('current');
  });

  it('默认时区一定在清单里', () => {
    const candidates = buildTimezoneCandidates({
      platformSites: [],
      current: 'Europe/London',
      defaultTimezone: DEFAULT_TIMEZONE,
      instant: INSTANT,
    });
    expect(candidates.some((c) => c.id === DEFAULT_TIMEZONE)).toBe(true);
  });
});

describe('1.4.2.1 候选清单过滤', () => {
  const candidates = buildTimezoneCandidates({
    platformSites: [SITE],
    current: DEFAULT_TIMEZONE,
    defaultTimezone: DEFAULT_TIMEZONE,
    instant: INSTANT,
  });

  it('关键字命中时区 id', () => {
    const hits = filterTimezoneCandidates(candidates, 'shanghai');
    expect(hits.some((c) => c.id === 'Asia/Shanghai')).toBe(true);
  });

  it('关键字命中平台站点名', () => {
    const hits = filterTimezoneCandidates(candidates, 'amazon');
    expect(hits.some((c) => c.id === 'America/Los_Angeles')).toBe(true);
  });

  it('空关键字返回全部', () => {
    expect(filterTimezoneCandidates(candidates, '   ')).toHaveLength(candidates.length);
  });

  it('匹配不到时返回空数组而不是全部', () => {
    expect(filterTimezoneCandidates(candidates, 'zzzz-not-a-zone')).toHaveLength(0);
  });
});

describe('1.4.2.1 渲染前必须先问「能不能渲染」', () => {
  it('有效的 IANA 时区可渲染', () => {
    expect(isRenderableTimeZone('Asia/Shanghai')).toBe(true);
    expect(isRenderableTimeZone('America/New_York')).toBe(true);
  });

  it('无效时区不可渲染（Intl 会抛异常，必须先拦住）', () => {
    expect(isRenderableTimeZone('Not/AZone')).toBe(false);
    expect(isRenderableTimeZone('')).toBe(false);
  });

  it('无效时区渲染时回落到默认时区而不是抛异常', () => {
    const broken = formatDateTime(INSTANT, 'Not/AZone');
    const fallback = formatDateTime(INSTANT, DEFAULT_TIMEZONE);
    expect(broken).toBe(fallback);
    expect(() => formatDateTime(INSTANT, 'Not/AZone')).not.toThrow();
  });
});

describe('1.4.2.1 显示口径由服务端声明，前端原样传给 Intl', () => {
  it('同一时刻在不同展示时区下渲染出不同的当地时间', () => {
    const shanghai = formatDateTime(INSTANT, 'Asia/Shanghai');
    const newYork = formatDateTime(INSTANT, 'America/New_York');
    expect(shanghai).not.toBe(newYork);
    expect(shanghai).toContain('18:20:30');
    expect(newYork).toContain('06:20:30');
  });

  it('服务端声明的口径被真的用上了（换一份口径，输出跟着变）', () => {
    const withoutSeconds = toDisplayConvention({
      locale: 'zh-CN',
      options: {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hourCycle: 'h23',
      },
    });
    const rendered = formatDateTime(INSTANT, 'Asia/Shanghai', withoutSeconds);
    expect(rendered).not.toContain('30');
    expect(rendered).toContain('18:20');
  });

  it('偏移标签用服务端声明的 shortOffset', () => {
    const offset = formatOffset(INSTANT, 'Asia/Shanghai');
    expect(offset).toBeTruthy();
    expect(offset).toContain('GMT');
  });

  it('口径缺失时套本地兜底，不让渲染崩掉', () => {
    const raw = toDisplayConvention({ locale: '', options: undefined });
    expect(raw.locale).toBe(DEFAULT_LOCALE);
    expect(raw.options).toEqual({});
    expect(() => formatDateTime(INSTANT, 'Asia/Shanghai', raw)).not.toThrow();
  });

  it('本地兜底口径与服务端声明的那一份一致（两边各写一份必然漂移）', () => {
    expect(DEFAULT_DISPLAY_CONVENTION.locale).toBe(DEFAULT_LOCALE);
    expect(DEFAULT_DISPLAY_CONVENTION.options.hourCycle).toBe('h23');
    expect(DEFAULT_DISPLAY_CONVENTION.options.timeZoneName).toBe('shortOffset');
  });
});

describe('1.4.2.1 夏令时：口径走的是 tzdb 而不是固定偏移', () => {
  it('同一个「纽约」在冬夏两个时刻差一小时', () => {
    const winter = new Date('2026-01-15T10:20:30Z');
    const summer = new Date('2026-03-15T10:20:30Z');
    const a = formatDateTime(winter, 'America/New_York');
    const b = formatDateTime(summer, 'America/New_York');
    expect(a).toContain('05:20:30');
    expect(b).toContain('06:20:30');
    expect(a).not.toBe(b);
  });
});

describe('1.4.2.1 浏览器时区表与参考时区', () => {
  it('取 IANA 时区表不抛异常（浏览器不支持该 API 时返回空数组）', () => {
    expect(() => supportedTimeZoneIds()).not.toThrow();
    expect(Array.isArray(supportedTimeZoneIds())).toBe(true);
  });

  it('参考时区清单里每一个都是真实可渲染的时区', () => {
    for (const zone of REFERENCE_TIMEZONES) {
      expect(isRenderableTimeZone(zone), zone).toBe(true);
    }
  });
});
