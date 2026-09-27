import { describe, expect, it } from 'vitest';
import { HOME_CITY, REGION_POOLS } from './world-clock';
import {
  CITY_PHOTOS,
  citiesWithPhotos,
  photoCoverage,
  photoFor,
} from './city-photos';

describe('city-photos manifest', () => {
  const allCities = [
    HOME_CITY.city,
    ...REGION_POOLS.flatMap((region) => region.cities.map((city) => city.city)),
  ];

  it('覆盖全部 34 个城市,即使 url 暂缺也应有占位条目', () => {
    expect(new Set(allCities).size).toBe(34);
    for (const city of allCities) {
      const entry = photoFor(city);
      expect(entry, `${city} 缺 manifest 条目`).toBeDefined();
      expect(entry?.city).toBe(city);
      expect(entry?.landmark.length, `${city} 缺 landmark 描述`).toBeGreaterThan(0);
    }
  });

  it('已配 URL 的条目 url 是合法 http(s) 地址', () => {
    for (const entry of Object.values(CITY_PHOTOS)) {
      if (entry.url.length === 0) continue;
      expect(entry.url, `${entry.city} url 不合法`).toMatch(/^https?:\/\//);
      // 许可证元数据必须配齐(占位时除外)
      expect(entry.license.length, `${entry.city} 缺 license`).toBeGreaterThan(0);
      expect(entry.author.length, `${entry.city} 缺 author`).toBeGreaterThan(0);
    }
  });

  it('photoCoverage 区分已配/未配 url', () => {
    const { covered, missing } = photoCoverage(allCities);
    expect(covered.length + missing.length).toBe(34);
    expect(citiesWithPhotos()).toEqual(covered);
    // 至少有一个配上了 url(否则 manifest 完全空)
    expect(covered.length, '应有部分城市先配上 url,作为 CDN 上线前的可视化样例').toBeGreaterThan(0);
    // 不重复
    expect(new Set([...covered, ...missing]).size).toBe(34);
  });

  it('未知城市返回 undefined', () => {
    expect(photoFor('不存在的城市')).toBeUndefined();
  });
});
