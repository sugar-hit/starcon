import { describe, expect, it } from 'vitest';
import {
  inkForSkyLevel,
  quantizeSkyLevel,
  skyLevelForHour,
  skyLevelForLocalHour,
  skySurfaceForLevel,
  SKY_INK_THRESHOLD,
} from './clock-sky';
import { HOME_CITY, REGION_POOLS, visibleCities } from './world-clock';
import { SKYLINES, skylineCoverage, skylineFor, SKYLINE_VIEW } from './city-skyline';

describe('skyLevelForHour', () => {
  it('午夜最暗、正午最亮', () => {
    expect(skyLevelForHour(0)).toBeCloseTo(0.16, 5);
    expect(skyLevelForHour(12)).toBeCloseTo(0.95, 5);
    expect(skyLevelForHour(23)).toBeCloseTo(0.16, 5);
  });

  it('破晓与黄昏平滑过渡，不跳变', () => {
    const dawn = [4, 5, 6, 7].map(skyLevelForHour);
    const dusk = [17, 18, 19, 20].map(skyLevelForHour);
    for (const series of [dawn, dusk]) {
      for (let i = 1; i < series.length; i += 1) {
        const step = Math.abs((series[i] ?? 0) - (series[i - 1] ?? 0));
        expect(step).toBeLessThan(0.3);
      }
    }
    expect(skyLevelForHour(7)).toBeCloseTo(0.95, 5);
    expect(skyLevelForHour(20)).toBeLessThan(0.4);
  });

  it('任意小时落在 0–1', () => {
    for (let h = 0; h < 24; h += 0.25) {
      const level = skyLevelForHour(h);
      expect(level).toBeGreaterThanOrEqual(0);
      expect(level).toBeLessThanOrEqual(1);
    }
  });

  it('跨日循环：h+24 与 h 同值，负小时也正确', () => {
    expect(skyLevelForHour(30)).toBeCloseTo(skyLevelForHour(6), 5);
    expect(skyLevelForHour(-2)).toBeCloseTo(skyLevelForHour(22), 5);
  });
});

describe('quantizeSkyLevel', () => {
  it('量化后落在固定档位上', () => {
    for (let h = 0; h < 24; h += 0.1) {
      const q = skyLevelForLocalHour(h);
      const ratio = q * 24;
      expect(Math.abs(ratio - Math.round(ratio))).toBeLessThan(1e-6);
    }
  });

  it('整日只在破晓与黄昏各换一次档，总次数受限', () => {
    const changeHours: number[] = [];
    for (let h = 0; h < 24; h += 1 / 60) {
      if (skyLevelForLocalHour(h) !== skyLevelForLocalHour(h - 1 / 60)) {
        changeHours.push(Math.floor(h));
      }
    }
    // 破晓 3–7、黄昏 16–20，其余时段应当完全静止
    const inDawn = changeHours.filter((h) => h >= 3 && h < 7).length;
    const inDusk = changeHours.filter((h) => h >= 16 && h < 21).length;
    const elsewhere = changeHours.filter((h) => (h < 3 || (h >= 7 && h < 16) || h >= 21)).length;
    expect(elsewhere, '静止时段不应换档').toBe(0);
    expect(inDawn).toBeGreaterThan(0);
    expect(inDusk).toBeGreaterThan(0);
    // 24 档量化下，深夜(0.16)到白昼(0.95)单程约占 19 档，破晓+黄昏合计约 38 次
    expect(changeHours.length).toBeLessThanOrEqual(40);
  });

  it('越界输入被夹取', () => {
    expect(quantizeSkyLevel(-5)).toBe(0);
    expect(quantizeSkyLevel(9)).toBe(1);
  });
});

describe('inkForSkyLevel', () => {
  it('阈值两侧切换墨色', () => {
    expect(inkForSkyLevel(SKY_INK_THRESHOLD)).toBe('dark');
    expect(inkForSkyLevel(SKY_INK_THRESHOLD - 0.01)).toBe('light');
  });

  it('正午深墨、深夜浅墨', () => {
    expect(inkForSkyLevel(skyLevelForHour(12))).toBe('dark');
    expect(inkForSkyLevel(skyLevelForHour(2))).toBe('light');
  });
});

describe('skySurfaceForLevel', () => {
  it('夜色比白昼更厚：底部洗色与剪影都更实', () => {
    const night = skySurfaceForLevel(skyLevelForHour(2));
    const day = skySurfaceForLevel(skyLevelForHour(12));
    expect(night.washBottom).toBeGreaterThan(day.washBottom);
    expect(night.silhouette).toBeGreaterThan(day.silhouette);
    expect(night.silhouetteLift).toBeGreaterThan(day.silhouetteLift);
  });

  it('顶部永远比底部薄，视线沉向地平线', () => {
    for (let l = 0; l <= 1; l += 0.05) {
      const s = skySurfaceForLevel(l);
      expect(s.washTop).toBeLessThan(s.washBottom);
      expect(s.washTop).toBeGreaterThanOrEqual(0);
      expect(s.washBottom).toBeLessThanOrEqual(0.55);
      expect(s.silhouette).toBeGreaterThan(0);
      expect(s.silhouette).toBeLessThanOrEqual(0.6);
    }
  });

  it('明度单调：越亮底部洗色越薄', () => {
    let previous = Infinity;
    for (let l = 0; l <= 1; l += 0.1) {
      const current = skySurfaceForLevel(l).washBottom;
      expect(current).toBeLessThanOrEqual(previous);
      previous = current;
    }
  });
});

describe('城市剪影数据', () => {
  const allCities = [
    HOME_CITY.city,
    ...REGION_POOLS.flatMap((region) => region.cities.map((city) => city.city)),
  ];

  it('覆盖全部 34 个城市', () => {
    const { missing, covered } = skylineCoverage(allCities);
    expect(missing).toEqual([]);
    expect(new Set(allCities).size).toBe(34);
    expect(covered).toHaveLength(34);
  });

  it('无重复城市条目', () => {
    const names = SKYLINES.map((item) => item.city);
    expect(new Set(names).size).toBe(names.length);
  });

  it('每条 path 都是合法 SVG 命令且数值在画布内', () => {
    for (const item of SKYLINES) {
      expect(item.path.length, item.city).toBeGreaterThan(0);
      expect(item.landmark.trim().length, item.city).toBeGreaterThan(0);
      expect(item.path, item.city).toMatch(/^M/);
      // 只允许绝对指令字母
      expect(item.path, item.city).not.toMatch(/[a-z]/);
      const numbers = item.path.match(/-?\d+(\.\d+)?/g) ?? [];
      expect(numbers.length, item.city).toBeGreaterThan(4);
      for (const raw of numbers) {
        const value = Number(raw);
        // 允许峰顶到 0，但不允许越出画布上下界
        expect(value, `${item.city} 出现越界坐标 ${raw}`).toBeGreaterThanOrEqual(-1);
        expect(value, `${item.city} 出现越界坐标 ${raw}`).toBeLessThanOrEqual(
          SKYLINE_VIEW.width + 1,
        );
      }
    }
  });

  it('未知城市返回 undefined，交由调用方兜底', () => {
    expect(skylineFor('不存在的城市')).toBeUndefined();
    expect(skylineFor('北京')?.landmark).toContain('天安门');
  });

  it('任意时刻六格都能找到剪影', () => {
    for (let cursor = 0; cursor < 12; cursor += 1) {
      for (const city of visibleCities(cursor)) {
        expect(skylineFor(city.city), city.city).toBeDefined();
      }
    }
  });
});
