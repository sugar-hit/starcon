/**
 * 构建期拉取工具:为每个城市找到 Wikimedia Commons 上最常用的封面图 URL。
 *
 * 用法:
 *   node tools/scripts/fetch-city-photos.mjs
 *
 * 输出:打印每个城市的 `city → landmark → url`。
 * 把结果填入 packages/ui/src/lib/city-photos.ts 的 CITY_PHOTOS 即可。
 *
 * 切 CDN:只改 manifest 里的 url,组件与样式不动。
 */

const LANDMARKS = {
  北京: 'Tiananmen',
  纽约: 'Statue of Liberty',
  洛杉矶: 'Hollywood Sign',
  芝加哥: 'Willis Tower',
  多伦多: 'CN Tower Toronto',
  温哥华: 'Vancouver skyline',
  墨西哥城: 'Palacio de Bellas Artes',
  伦敦: 'Tower Bridge London',
  巴黎: 'Eiffel Tower',
  法兰克福: 'Frankfurt skyline',
  阿姆斯特丹: 'Amsterdam canal',
  马德里: 'Royal Palace Madrid',
  斯德哥尔摩: 'Stockholm City Hall',
  新加坡: 'Marina Bay Sands',
  曼谷: 'Wat Arun',
  吉隆坡: 'Petronas Twin Towers',
  雅加达: 'Monas',
  马尼拉: 'Manila Cathedral',
  胡志明市: 'Notre Dame Cathedral Saigon',
  东京: 'Tokyo Tower',
  首尔: 'Gyeongbokgung',
  孟买: 'Gateway of India',
  迪拜: 'Burj Khalifa',
  阿拉木图: 'Almaty Tower',
  德黑兰: 'Azadi Tower',
  圣保罗: 'Catedral da Sé São Paulo',
  布宜诺斯艾利斯: 'Obelisco Buenos Aires',
  圣地亚哥: 'Santiago Chile skyline',
  悉尼: 'Sydney Opera House',
  墨尔本: 'Melbourne skyline',
  奥克兰: 'Sky Tower Auckland',
  开普敦: 'Table Mountain',
  拉各斯: 'Lagos skyline',
  内罗毕: 'Nairobi skyline',
};

const WIDTH = 480;
const UA = 'starcon-fetch-city-photos/1.0 (https://example.local)';

async function searchFile(query) {
  const params = new URLSearchParams({
    action: 'query',
    format: 'json',
    list: 'search',
    srsearch: `${query} filetype:bitmap`,
    srnamespace: '6', // File namespace
    srlimit: '5',
  });
  const res = await fetch(`https://commons.wikimedia.org/w/api.php?${params}`, {
    headers: { 'Api-User-Agent': UA },
  });
  if (!res.ok) throw new Error(`search HTTP ${res.status}`);
  const json = await res.json();
  return (json.query?.search ?? []).map((s) => s.title.replace(/^File:/, ''));
}

function specialPath(filename, width) {
  return `https://commons.wikimedia.org/wiki/Special:FilePath/${encodeURIComponent(filename)}?width=${width}`;
}

async function pickBest(query) {
  const files = await searchFile(query);
  // 跳过 SVG / 视频 / 音频,只保留位图
  for (const f of files) {
    if (/\.(jpe?g|png|webp)$/i.test(f)) return f;
  }
  return files[0] ?? null;
}

const entries = await Promise.all(
  Object.entries(LANDMARKS).map(async ([city, query]) => {
    try {
      const filename = await pickBest(query);
      if (!filename) throw new Error('no bitmap found');
      return { city, landmark: query, url: specialPath(filename, WIDTH), ok: true };
    } catch (err) {
      return { city, landmark: query, url: null, ok: false, error: err.message };
    }
  }),
);

for (const r of entries) {
  if (r.ok) {
    console.log(`✓ ${r.city.padEnd(8)} ${r.landmark.padEnd(34)} ${r.url}`);
  } else {
    console.log(`✗ ${r.city.padEnd(8)} ${r.landmark.padEnd(34)} ${r.error}`);
  }
}
