/**
 * 构建期工具:按地标名搜 Wikimedia Commons,取规范缩略图直连 URL + 归因信息。
 *
 * 为什么不用 Special:FilePath:
 *   Special:FilePath 会 302 → Special:Redirect → upload.wikimedia.org 多跳,
 *   Chrome 的 ORB(Opaque Response Blocking)会把这条链判成跨源可疑响应直接拦掉,
 *   表现为 net::ERR_BLOCKED_BY_ORB。直连 upload.wikimedia.org 就没有这个问题,
 *   而且该域带 Access-Control-Allow-Origin: *,canvas 读像素也可用。
 *
 * 用法:node tools/scripts/fetch-landmark-photos.mjs
 * 输出:可直接粘进 city-photos.ts 的条目(含 license / author 归因信息)。
 */

const TARGETS = [
  { city: '北京', query: 'Tiananmen' },
  { city: '纽约', query: 'Statue of Liberty' },
  { city: '伦敦', query: 'Big Ben Elizabeth Tower' },
  { city: '新加坡', query: 'Marina Bay Sands' },
  { city: '东京', query: 'Tokyo Tower' },
  { city: '悉尼', query: 'Sydney Opera House' },
];

const WIDTH = 640;
const API = 'https://commons.wikimedia.org/w/api.php';
const UA = 'starcon-landmark-fetch/1.0 (https://example.local)';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Commons 对匿名高频请求会回 429,带退避重试 */
async function apiGet(params, { retries = 4 } = {}) {
  for (let attempt = 0; attempt <= retries; attempt += 1) {
    const res = await fetch(`${API}?${params}`, { headers: { 'Api-User-Agent': UA } });
    if (res.ok) return res.json();
    if (res.status === 429 && attempt < retries) {
      const wait = 2000 * 2 ** attempt;
      process.stderr.write(`  429,退避 ${wait}ms\n`);
      await sleep(wait);
      continue;
    }
    throw new Error(`HTTP ${res.status}`);
  }
  throw new Error('HTTP 429 exhausted');
}

async function search(query) {
  const params = new URLSearchParams({
    action: 'query',
    format: 'json',
    list: 'search',
    srsearch: `${query} filetype:bitmap`,
    srnamespace: '6',
    srlimit: '8',
  });
  const json = await apiGet(params);
  return (json.query?.search ?? []).map((s) => s.title.replace(/^File:/, ''));
}

async function fetchInfo(file) {
  const params = new URLSearchParams({
    action: 'query',
    format: 'json',
    titles: `File:${file}`,
    prop: 'imageinfo',
    iiprop: 'url|extmetadata',
    iiurlwidth: String(WIDTH),
  });
  const page = Object.values((await apiGet(params)).query?.pages ?? {})[0];
  if (!page || page.missing !== undefined) return null;
  return page.imageinfo?.[0] ?? null;
}

const stripHtml = (value = '') => value.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim();

/** thumburl 末尾带 utm 查询串,去掉更干净 */
const clean = (url = '') => url.split('?')[0];

const results = [];
for (const { city, query } of TARGETS) {
  try {
    const files = await search(query);
    await sleep(1500);
    let picked = null;
    // 依次尝试候选位图,取第一个能查到 imageinfo 的
    for (const file of files.filter((f) => /\.(jpe?g|png)$/i.test(f)).slice(0, 3)) {
      const info = await fetchInfo(file);
      await sleep(1500);
      if (info?.thumburl) {
        picked = {
          city,
          file,
          url: clean(info.thumburl),
          width: info.thumbwidth,
          height: info.thumbheight,
          license: stripHtml(info.extmetadata?.LicenseShortName?.value),
          author: stripHtml(info.extmetadata?.Artist?.value).slice(0, 60),
          page: info.descriptionurl,
        };
        break;
      }
    }
    results.push(picked ?? { city, query, error: 'no usable bitmap found' });
  } catch (err) {
    results.push({ city, query, error: err.message });
  }
}

for (const r of results) {
  if (r.error) {
    console.log(`✗ ${r.city.padEnd(6)} ${r.query}: ${r.error}`);
  } else {
    console.log(`✓ ${r.city.padEnd(6)} ${r.width}x${r.height}  ${r.license}  by ${r.author}`);
    console.log(`    ${r.url}`);
  }
}
