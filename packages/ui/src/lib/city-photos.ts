/**
 * 城市真实地标照片清单。
 *
 * 当前源:picsum.photos(占位,真实照片但非地标,种子=城市名保证稳定)。
 * 切 CDN:只改 url 字段,组件与样式不动。空 url 的格子回退到干净卡片。
 *
 * 切 CDN 后归责由你方决定,本文件不再承担归责职责。
 */

export interface CityPhoto {
  city: string;
  landmark: string;
  /** 照片 URL;为空表示该城市暂无照片,组件回退到干净卡片 */
  url: string;
  license: string;
  author: string;
}

/**
 * picsum 占位:用城市中文名(URL-encoded)做种子,保证同一城市始终出同一张图。
 * 宽 480 / 高 320,与卡片 ~190×105 + 2x 视网膜匹配。
 * CDN 上线后,这里换成你的 url,例如:
 *   北京: 'https://cdn.example.com/beijing.jpg',
 */
function picsum(city: string): string {
  return `https://picsum.photos/seed/${encodeURIComponent(city)}/480/320`;
}

export const CITY_PHOTOS: Record<string, CityPhoto> = {
  北京: { city: '北京', landmark: '天安门', url: picsum('beijing'), license: 'CC0', author: 'picsum.photos' },
  纽约: { city: '纽约', landmark: '自由女神像', url: picsum('newyork'), license: 'CC0', author: 'picsum.photos' },
  洛杉矶: { city: '洛杉矶', landmark: '好莱坞', url: picsum('losangeles'), license: 'CC0', author: 'picsum.photos' },
  芝加哥: { city: '芝加哥', landmark: '威利斯大厦', url: picsum('chicago'), license: 'CC0', author: 'picsum.photos' },
  多伦多: { city: '多伦多', landmark: 'CN 塔', url: picsum('toronto'), license: 'CC0', author: 'picsum.photos' },
  温哥华: { city: '温哥华', landmark: '温哥华港', url: picsum('vancouver'), license: 'CC0', author: 'picsum.photos' },
  墨西哥城: { city: '墨西哥城', landmark: '贝拉斯艺术宫', url: picsum('mexicocity'), license: 'CC0', author: 'picsum.photos' },
  伦敦: { city: '伦敦', landmark: '塔桥', url: picsum('london'), license: 'CC0', author: 'picsum.photos' },
  巴黎: { city: '巴黎', landmark: '埃菲尔铁塔', url: picsum('paris'), license: 'CC0', author: 'picsum.photos' },
  法兰克福: { city: '法兰克福', landmark: '法兰克福天际线', url: picsum('frankfurt'), license: 'CC0', author: 'picsum.photos' },
  阿姆斯特丹: { city: '阿姆斯特丹', landmark: '运河房', url: picsum('amsterdam'), license: 'CC0', author: 'picsum.photos' },
  马德里: { city: '马德里', landmark: '王宫', url: picsum('madrid'), license: 'CC0', author: 'picsum.photos' },
  斯德哥尔摩: { city: '斯德哥尔摩', landmark: '市政厅', url: picsum('stockholm'), license: 'CC0', author: 'picsum.photos' },
  新加坡: { city: '新加坡', landmark: '滨海湾金沙', url: picsum('singapore'), license: 'CC0', author: 'picsum.photos' },
  曼谷: { city: '曼谷', landmark: '郑王庙', url: picsum('bangkok'), license: 'CC0', author: 'picsum.photos' },
  吉隆坡: { city: '吉隆坡', landmark: '双子塔', url: picsum('kualalumpur'), license: 'CC0', author: 'picsum.photos' },
  雅加达: { city: '雅加达', landmark: '国家纪念塔', url: picsum('jakarta'), license: 'CC0', author: 'picsum.photos' },
  马尼拉: { city: '马尼拉', landmark: '马尼拉大教堂', url: picsum('manila'), license: 'CC0', author: 'picsum.photos' },
  胡志明市: { city: '胡志明市', landmark: '西贡圣母大教堂', url: picsum('hochiminh'), license: 'CC0', author: 'picsum.photos' },
  东京: { city: '东京', landmark: '东京塔', url: picsum('tokyo'), license: 'CC0', author: 'picsum.photos' },
  首尔: { city: '首尔', landmark: '景福宫', url: picsum('seoul'), license: 'CC0', author: 'picsum.photos' },
  孟买: { city: '孟买', landmark: '印度门', url: picsum('mumbai'), license: 'CC0', author: 'picsum.photos' },
  迪拜: { city: '迪拜', landmark: '哈利法塔', url: picsum('dubai'), license: 'CC0', author: 'picsum.photos' },
  阿拉木图: { city: '阿拉木图', landmark: '电视塔', url: picsum('almaty'), license: 'CC0', author: 'picsum.photos' },
  德黑兰: { city: '德黑兰', landmark: '自由塔', url: picsum('tehran'), license: 'CC0', author: 'picsum.photos' },
  圣保罗: { city: '圣保罗', landmark: '主教座堂', url: picsum('saopaulo'), license: 'CC0', author: 'picsum.photos' },
  布宜诺斯艾利斯: { city: '布宜诺斯艾利斯', landmark: '方尖碑', url: picsum('buenosaires'), license: 'CC0', author: 'picsum.photos' },
  圣地亚哥: { city: '圣地亚哥', landmark: '天际线', url: picsum('santiago'), license: 'CC0', author: 'picsum.photos' },
  悉尼: { city: '悉尼', landmark: '歌剧院', url: picsum('sydney'), license: 'CC0', author: 'picsum.photos' },
  墨尔本: { city: '墨尔本', landmark: '天际线', url: picsum('melbourne'), license: 'CC0', author: 'picsum.photos' },
  奥克兰: { city: '奥克兰', landmark: '天空塔', url: picsum('auckland'), license: 'CC0', author: 'picsum.photos' },
  开普敦: { city: '开普敦', landmark: '桌山', url: picsum('capetown'), license: 'CC0', author: 'picsum.photos' },
  拉各斯: { city: '拉各斯', landmark: '天际线', url: picsum('lagos'), license: 'CC0', author: 'picsum.photos' },
  内罗毕: { city: '内罗毕', landmark: '天际线', url: picsum('nairobi'), license: 'CC0', author: 'picsum.photos' },
};

export function photoFor(city: string): CityPhoto | undefined {
  return CITY_PHOTOS[city];
}

export function citiesWithPhotos(): string[] {
  return Object.values(CITY_PHOTOS)
    .filter((p) => p.url.length > 0)
    .map((p) => p.city);
}

export function photoCoverage(cities: readonly string[]): {
  covered: string[];
  missing: string[];
} {
  const covered: string[] = [];
  const missing: string[] = [];
  for (const city of cities) {
    (photoFor(city)?.url ? covered : missing).push(city);
  }
  return { covered, missing };
}
