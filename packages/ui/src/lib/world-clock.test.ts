import { describe, expect, it } from 'vitest';
import {
  CITY_ROTATION_MS,
  rotationCities,
  continentFor,
  cityLabel,
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

describe('区域轮换', () => {
  it('每 5 秒推进一轮', () => {
    expect(CITY_ROTATION_MS).toBe(5_000);
  });

  it('北京不动，五个区域轮换并覆盖全部 34 城', () => {
    const seen = new Set<string>();
    for (let cursor = 0; cursor < 18; cursor += 1) {
      const cities = rotationCities(cursor);
      expect(cities).toHaveLength(6);
      expect(cities[0]).toEqual(HOME_CITY);
      expect(new Set(cities.map((city) => city.zone)).size).toBe(6);
      cities.forEach((city) => seen.add(city.city));
      cities.slice(1).forEach((city, index) => {
        expect(REGION_POOLS[index]!.cities).toContainEqual(city);
      });
    }
    expect(seen.size).toBe(34);
    expect(rotationCities(18)).toEqual(rotationCities(0));
    expect(
      rotationCities(1)
        .slice(1)
        .map((city) => city.city),
    ).not.toEqual(
      rotationCities(0)
        .slice(1)
        .map((city) => city.city),
    );
  });

  it('大洲按城市区分，混合区域不会统一标成其他', () => {
    expect(cityLabel(HOME_CITY)).toBe('北京 中国（亚洲）');
    const other = REGION_POOLS.find((pool) => pool.key === 'other')!;
    expect(other.cities.map(continentFor)).toEqual([
      '南美洲',
      '南美洲',
      '南美洲',
      '大洋洲',
      '大洋洲',
      '大洋洲',
      '非洲',
      '非洲',
      '非洲',
    ]);
    expect(continentFor(REGION_POOLS[0]!.cities[0]!)).toBe('北美洲');
    expect(continentFor(REGION_POOLS[1]!.cities[0]!)).toBe('欧洲');
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
