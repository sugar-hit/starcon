import { describe, it, expect } from 'vitest';
import { clockParts, WORLD_CITIES } from './world-clock';

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
  it('keeps five explicit city zones with valid output', () => {
    expect(WORLD_CITIES).toHaveLength(5);
    for (const city of WORLD_CITIES)
      expect(clockParts(new Date('2026-09-27T00:00:00Z'), city.zone).time).toMatch(/^\d{2}:\d{2}$/);
  });
});
