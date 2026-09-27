import { describe, expect, it } from 'vitest';
import {
  angularDistance,
  greatArc,
  graticulePaths,
  landPaths,
  project,
  shortestLonDelta,
  type GlobeView,
} from './geo';

const BEIJING = { lat: 39.9042, lon: 116.4074 };
const LONDON = { lat: 51.5074, lon: -0.1278 };
const R = 44;

describe('geo projection', () => {
  it('takes the short way around the antimeridian', () => {
    expect(shortestLonDelta(170, -170)).toBeCloseTo(20);
    expect(shortestLonDelta(-170, 170)).toBeCloseTo(-20);
    expect(shortestLonDelta(0, 90)).toBeCloseTo(90);
    expect(shortestLonDelta(10, 5)).toBeCloseTo(-5);
  });

  it('measures great-circle distance', () => {
    expect(angularDistance(BEIJING, BEIJING)).toBeCloseTo(0, 6);
    expect(angularDistance(BEIJING, LONDON)).toBeGreaterThan(70);
    expect(angularDistance(BEIJING, LONDON)).toBeLessThan(80);
    expect(angularDistance(BEIJING, { lat: -BEIJING.lat, lon: BEIJING.lon + 180 })).toBeCloseTo(
      180,
      3,
    );
  });

  it('puts the view center at the middle of the disc', () => {
    const view: GlobeView = { lat: 20, lon: 30 };
    const center = project({ lat: 20, lon: 30 }, view, R);
    expect(center.x).toBeCloseTo(0, 4);
    expect(center.y).toBeCloseTo(0, 4);
    expect(center.front).toBe(true);
  });

  it('hides the far side and clamps it onto the horizon when asked', () => {
    const view: GlobeView = { lat: 0, lon: 0 };
    const behind = { lat: 0, lon: 180 };
    expect(project(behind, view, R).front).toBe(false);

    const clamped = project({ lat: 0, lon: 170 }, view, R, true);
    expect(clamped.front).toBe(false);
    expect(Math.hypot(clamped.x, clamped.y)).toBeCloseTo(R, 3);
  });

  it('keeps the disc radius stable for any projected point', () => {
    const view: GlobeView = { lat: 35, lon: -20 };
    for (const point of [
      { lat: 60, lon: 40 },
      { lat: -40, lon: 100 },
      { lat: 0, lon: -70 },
      { lat: 20, lon: 20 },
    ]) {
      const projected = project(point, view, R, true);
      expect(Math.hypot(projected.x, projected.y)).toBeLessThanOrEqual(R + 0.01);
    }
  });

  it('draws a graticule with an equator and prime meridian', () => {
    const grid = graticulePaths({ lat: 10, lon: 0 }, R);
    expect(grid.fine.length).toBeGreaterThan(6);
    const paths = [grid.equator, grid.prime, ...grid.fine];
    expect(paths.some((path) => path.length > 0)).toBe(true);
    for (const path of paths) {
      expect(path, 'graticule path').toMatch(
        /^(M-?[\d.]+,-?[\d.]+)( L-?[\d.]+,-?[\d.]+)*( M-?[\d.]+,-?[\d.]+( L-?[\d.]+,-?[\d.]+)*)*$/,
      );
    }
  });

  it('draws continents on the visible hemisphere', () => {
    // 从大西洋看出去：欧洲、非洲、南北美都应在视野内
    const paths = landPaths({ lat: 15, lon: -30 }, R);
    expect(paths.length).toBeGreaterThan(3);
    for (const path of paths) {
      expect(path.length, 'continent path').toBeGreaterThan(20);
      expect(path, 'continent path').toMatch(/^M-?[\d.]+,-?[\d.]+/);
    }
  });

  it('switches what is visible as the globe turns', () => {
    const fromAtlantic = landPaths({ lat: 20, lon: -40 }, R).join(' ');
    const fromAsia = landPaths({ lat: 20, lon: 110 }, R).join(' ');
    expect(fromAtlantic).not.toBe(fromAsia);
  });

  it('draws an arc between two nearby cities', () => {
    const arc = greatArc(BEIJING, { lat: 39.9, lon: 121.5 }, { lat: 30, lon: 100 }, R);
    expect(arc).not.toBe('');
    expect(arc.split(' ').length).toBeGreaterThan(10);
  });

  it('returns no arc for a zero-length hop', () => {
    expect(greatArc(BEIJING, BEIJING, { lat: 39.9, lon: 116.4 }, R)).toBe('');
  });
});
