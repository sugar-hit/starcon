import { describe, expect, it } from 'vitest';
import { CITY_PHOTOS, photoFor } from './city-photos';
import { FIXED_CITIES } from './world-clock';

describe('地标照片清单', () => {
  const cities = FIXED_CITIES.map((city) => city.city);

  it('固定六城全部有照片', () => {
    for (const city of cities) {
      expect(photoFor(city), `${city} 缺照片条目`).toBeDefined();
    }
  });

  it('照片 URL 是 https 且指向 Wikimedia 缩略图直连', () => {
    for (const entry of Object.values(CITY_PHOTOS)) {
      expect(entry.url, `${entry.city} 必须是 https`).toMatch(/^https:\/\//);
      // 直连 upload|thumb.wikimedia.org —— Special:FilePath 多跳会被 Chrome ORB 拦
      expect(entry.url, `${entry.city} 应直连 Wikimedia 缩略图`).toMatch(
        /^https:\/\/(upload|thumb)\.wikimedia\.org\//,
      );
      expect(entry.url, `${entry.city} 不应带查询串`).not.toContain('?');
    }
  });

  it('每条都有归因信息：地标名、来源页、许可证、作者', () => {
    for (const entry of Object.values(CITY_PHOTOS)) {
      expect(entry.landmark.length, `${entry.city} 缺地标名`).toBeGreaterThan(0);
      expect(entry.source, `${entry.city} 缺来源页`).toMatch(/^https:\/\/commons\.wikimedia\.org\//);
      expect(entry.license.length, `${entry.city} 缺许可证`).toBeGreaterThan(0);
      expect(entry.author.length, `${entry.city} 缺作者`).toBeGreaterThan(0);
    }
  });

  it('每城照片 URL 互不相同（避免六格同图）', () => {
    const urls = Object.values(CITY_PHOTOS).map((entry) => entry.url);
    expect(new Set(urls).size).toBe(urls.length);
  });

  it('未知城市返回 undefined', () => {
    expect(photoFor('不存在的城市')).toBeUndefined();
  });
});
