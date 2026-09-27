import { describe, expect, it } from 'vitest';
import {
  CLOCK_ROTATION_INTERVAL_MS,
  CLOCK_SWAP_STAGGER_MS,
  HOME_CITY,
  REGION_POOLS,
  clockParts,
  nextRotationCursor,
  visibleCities,
} from './world-clock';

const SAMPLE = new Date('2026-09-27T00:00:00Z');
const ALL_CITIES = [HOME_CITY, ...REGION_POOLS.flatMap((region) => region.cities)];

describe('world clocks', () => {
  it('shows the previous date in New York at Beijing midnight', () => {
    expect(clockParts(new Date('2026-09-27T00:30:00+08:00'), 'America/New_York')).toMatchObject({
      time: '12:30',
      date: '09/26',
      offset: 'GMT-4',
    });
  });

  it('uses daylight saving rules instead of fixed US and UK offsets', () => {
    expect(clockParts(new Date('2026-01-15T12:00:00Z'), 'America/New_York').time).toBe('07:00');
    expect(clockParts(new Date('2026-07-15T12:00:00Z'), 'America/New_York').time).toBe('08:00');
    expect(clockParts(new Date('2026-01-15T12:00:00Z'), 'Europe/London').time).toBe('12:00');
    expect(clockParts(new Date('2026-07-15T12:00:00Z'), 'Europe/London').time).toBe('13:00');
  });

  it('renders a valid time and offset for every city in every pool', () => {
    for (const city of ALL_CITIES) {
      const parts = clockParts(SAMPLE, city.zone);
      expect(parts.time, city.city).toMatch(/^\d{2}:\d{2}$/);
      expect(parts.offset, city.city).toMatch(/^GMT/);
      expect(parts.date, city.city).toMatch(/^\d{2}\/\d{2}$/);
    }
  });

  it('covers five rotating regions with usable city pools', () => {
    expect(REGION_POOLS.map((region) => region.key)).toEqual(['na', 'eu', 'sea', 'asia', 'other']);
    for (const region of REGION_POOLS) {
      expect(region.label.length, region.key).toBeGreaterThan(0);
      expect(region.cities.length, region.key).toBeGreaterThanOrEqual(5);
      expect(new Set(region.cities.map((city) => city.zone)).size, region.key).toBe(
        region.cities.length,
      );
    }
  });

  it('keeps every city coordinate on the globe', () => {
    for (const city of ALL_CITIES) {
      expect(city.lat, city.city).toBeGreaterThanOrEqual(-90);
      expect(city.lat, city.city).toBeLessThanOrEqual(90);
      expect(city.lon, city.city).toBeGreaterThanOrEqual(-180);
      expect(city.lon, city.city).toBeLessThanOrEqual(180);
    }
  });

  it('does not repeat a zone anywhere on screen, at any rotation step', () => {
    const zones = new Set(ALL_CITIES.map((city) => city.zone));
    expect(zones.size).toBe(ALL_CITIES.length);
    const steps = Math.max(...REGION_POOLS.map((region) => region.cities.length)) * 2;
    for (let cursor = 0; cursor < steps; cursor += 1) {
      const onScreen = visibleCities(cursor);
      expect(onScreen, `cursor ${cursor}`).toHaveLength(6);
      expect(new Set(onScreen.map((city) => city.zone)).size, `cursor ${cursor}`).toBe(6);
    }
  });

  it('keeps the local zone first and walks each region through its own pool', () => {
    expect(visibleCities(0)[0]).toEqual(HOME_CITY);
    expect(visibleCities(0).slice(1)).toEqual(REGION_POOLS.map((r) => r.cities[0]));
    expect(visibleCities(1).slice(1)).toEqual(REGION_POOLS.map((r) => r.cities[1]));
    expect(visibleCities(1)[0]).toEqual(HOME_CITY);
  });

  it('excludes the local city and south-east Asia from the other-Asia pool', () => {
    const otherAsia = REGION_POOLS.find((region) => region.key === 'asia')!.cities;
    const southEast = REGION_POOLS.find((region) => region.key === 'sea')!.cities;
    expect(otherAsia.some((city) => city.country === '中国')).toBe(false);
    expect(otherAsia.some((city) => southEast.includes(city))).toBe(false);
  });

  it('groups south america, oceania and africa into the last region', () => {
    const other = REGION_POOLS.find((region) => region.key === 'other')!;
    const countries = new Set(other.cities.map((city) => city.country));
    for (const expected of ['巴西', '澳大利亚', '南非', '肯尼亚']) {
      expect(countries.has(expected), expected).toBe(true);
    }
    expect(other.cities.some((city) => city.zone.startsWith('Asia/'))).toBe(false);
    expect(other.cities.some((city) => city.zone.startsWith('Europe/'))).toBe(false);
  });

  it('advances the cursor and keeps rotation timings usable', () => {
    expect(nextRotationCursor(0)).toBe(1);
    expect(nextRotationCursor(7)).toBe(8);
    expect(CLOCK_ROTATION_INTERVAL_MS).toBeGreaterThanOrEqual(4000);
    // 错峰步长要小于一个轮换间隔，否则整条时间带会串成一团
    expect(CLOCK_SWAP_STAGGER_MS * REGION_POOLS.length).toBeLessThan(CLOCK_ROTATION_INTERVAL_MS);
  });
});
