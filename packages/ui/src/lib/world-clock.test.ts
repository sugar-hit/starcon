import { describe, expect, it } from 'vitest';
import {
  FIXED_CITIES,
  FIXED_REGIONS,
  HOME_CITY,
  REGION_POOLS,
  clockParts,
  localHourOf,
  splitClockTime,
} from './world-clock';

const ALL_CITIES = [
  HOME_CITY.city,
  ...REGION_POOLS.flatMap((region) => region.cities.map((city) => city.city)),
];

describe('时区与夏令时', () => {
  it('用 IANA 时区处理跨日与夏令时', () => {
    // 2026-07-01 12:00 UTC：纽约夏令时 UTC-4，伦敦 BST UTC+1
    const summer = new Date('2026-07-01T12:00:00Z');
    const ny = clockParts(summer, 'America/New_York');
    expect(ny.time).toBe('08:00');
    expect(ny.offset).toContain('GMT-4');
    expect(clockParts(summer, 'Europe/London').offset).toContain('GMT+1');

    // 2026-01-01 12:00 UTC：纽约冬令时 UTC-5，伦敦 GMT+0
    const winter = new Date('2026-01-01T12:00:00Z');
    expect(clockParts(winter, 'America/New_York').offset).toContain('GMT-5');
    expect(clockParts(winter, 'Europe/London').offset).toContain('GMT');
  });

  it('跨日时日期与本地小时同步翻转', () => {
    // UTC 2026-09-27 22:00 → 悉尼 9 月是冬令时 UTC+10，已是 09-28 08:00
    const moment = new Date('2026-09-27T22:00:00Z');
    const sydney = clockParts(moment, 'Australia/Sydney');
    expect(sydney.time).toBe('08:00');
    expect(sydney.date).toBe('09/28');
    expect(localHourOf(moment, 'Australia/Sydney')).toBeCloseTo(8, 2);
  });
});

describe('固定六城', () => {
  it('正好六格且第一格是北京', () => {
    expect(FIXED_CITIES).toHaveLength(6);
    expect(FIXED_CITIES[0]).toEqual(HOME_CITY);
  });

  it('时区互不重复', () => {
    const zones = FIXED_CITIES.map((city) => city.zone);
    expect(new Set(zones).size).toBe(6);
  });

  it('每格都有区域标签，北京为 null', () => {
    expect(FIXED_REGIONS).toHaveLength(6);
    expect(FIXED_REGIONS[0]).toBeNull();
    for (const region of FIXED_REGIONS.slice(1)) {
      expect(region).toBeTruthy();
    }
  });

  it('六城都覆盖六个大区且各自属于对应区域', () => {
    const byRegion = new Map(REGION_POOLS.map((r) => [r.label, r]));
    for (let i = 1; i < FIXED_CITIES.length; i += 1) {
      const city = FIXED_CITIES[i]!;
      const label = FIXED_REGIONS[i]!;
      const pool = byRegion.get(label);
      expect(pool, `区域池 ${label} 应存在`).toBeDefined();
      expect(
        pool!.cities.some((c) => c.city === city.city),
        `${city.city} 应属于 ${label}`,
      ).toBe(true);
    }
  });

  it('坐标落在合法范围内', () => {
    for (const city of FIXED_CITIES) {
      expect(city.lat).toBeGreaterThanOrEqual(-90);
      expect(city.lat).toBeLessThanOrEqual(90);
      expect(city.lon).toBeGreaterThanOrEqual(-180);
      expect(city.lon).toBeLessThanOrEqual(180);
      expect(city.city.length).toBeGreaterThan(0);
      expect(city.country.length).toBeGreaterThan(0);
    }
  });

  it('城市名不与区域池重复挑选（固定版就是池内成员）', () => {
    const poolNames = new Set(ALL_CITIES);
    for (const city of FIXED_CITIES) {
      expect(poolNames.has(city.city)).toBe(true);
    }
  });
});

describe('区域池数据完整性', () => {
  it('五个区域覆盖 34 城且无重名', () => {
    expect(REGION_POOLS).toHaveLength(5);
    expect(ALL_CITIES).toHaveLength(34);
    expect(new Set(ALL_CITIES).size).toBe(34);
  });

  it('每个区域池内时区不重复', () => {
    for (const region of REGION_POOLS) {
      const zones = region.cities.map((c) => c.zone);
      expect(new Set(zones).size, `${region.label} 池内时区应唯一`).toBe(zones.length);
    }
  });
});

describe('splitClockTime', () => {
  it('拆分后读数不变', () => {
    const { hours, minutes } = splitClockTime('14:07');
    expect(hours).toBe('14');
    expect(minutes).toBe('07');
    expect(`${hours}:${minutes}`).toBe('14:07');
  });

  it('占位符原样返回不崩', () => {
    expect(splitClockTime('—:—')).toEqual({ hours: '—', minutes: '—' });
    // 空串 split 后没有第一段，minutes 落到默认值
    expect(splitClockTime('')).toEqual({ hours: '', minutes: '—' });
  });
});
