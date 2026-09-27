import { describe, it, expect } from 'vitest';
import {
  CLOCK_ROTATION_INTERVAL_MS,
  HOME_CITY,
  PRIMARY_CITIES,
  ROTATING_CITY_POOL,
  ROTATING_SLOT_COUNT,
  clockParts,
  nextRotationCursor,
  rotationCities,
  visibleCities,
} from './world-clock';

const SAMPLE = new Date('2026-09-27T00:00:00Z');

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

  it('rotates the last two slots and keeps every zone renderable', () => {
    for (const city of [HOME_CITY, ...PRIMARY_CITIES, ...ROTATING_CITY_POOL]) {
      expect(clockParts(SAMPLE, city.zone).time).toMatch(/^\d{2}:\d{2}$/);
      expect(clockParts(SAMPLE, city.zone).offset).toMatch(/^GMT/);
    }
  });

  it('never repeats a zone across the fixed and rotating cities', () => {
    const zones = new Set([HOME_CITY, ...PRIMARY_CITIES, ...ROTATING_CITY_POOL].map((c) => c.zone));
    expect(zones.size).toBe(1 + PRIMARY_CITIES.length + ROTATING_CITY_POOL.length);
  });

  it('gives every rotation step two different cities from the pool', () => {
    for (let cursor = 0; cursor < ROTATING_CITY_POOL.length; cursor += 1) {
      const cities = rotationCities(cursor);
      expect(cities).toHaveLength(ROTATING_SLOT_COUNT);
      expect(new Set(cities.map((city) => city.zone)).size).toBe(ROTATING_SLOT_COUNT);
      for (const city of cities) expect(ROTATING_CITY_POOL).toContain(city);
    }
  });

  it('never lets the two rotating slots and the fixed cities collide', () => {
    let cursor = 0;
    for (let step = 0; step < ROTATING_CITY_POOL.length * 2; step += 1) {
      expect(new Set(visibleCities(cursor).map((city) => city.zone)).size).toBe(6);
      cursor = nextRotationCursor(cursor);
    }
  });

  it('lets each rotating slot walk the whole pool once per cycle', () => {
    let cursor = 0;
    const seen = [new Set<string>(), new Set<string>()];
    for (let step = 0; step < ROTATING_CITY_POOL.length; step += 1) {
      rotationCities(cursor).forEach((city, slot) => seen[slot]?.add(city.zone));
      cursor = nextRotationCursor(cursor);
    }
    for (const visited of seen) expect(visited.size).toBe(ROTATING_CITY_POOL.length);
    expect(rotationCities(cursor)[0]).toEqual(rotationCities(0)[0]);
    expect(cursor).toBe(0);
  });

  it('shows the local zone first and the two rotating cities last', () => {
    const cities = visibleCities(0);
    expect(cities).toHaveLength(6);
    expect(cities[0]).toEqual(HOME_CITY);
    expect(cities.slice(1, 4)).toEqual([...PRIMARY_CITIES]);
    expect(cities.slice(4)).toEqual(rotationCities(0));
    expect(CLOCK_ROTATION_INTERVAL_MS).toBeGreaterThanOrEqual(3000);
  });
});
