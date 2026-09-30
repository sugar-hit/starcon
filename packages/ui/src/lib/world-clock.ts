export interface WorldCity {
  /** 中文城市名 */
  city: string;
  /** 中文国家/地区名 */
  country: string;
  /** IANA 时区，交给 Intl 处理夏令时与跨日 */
  zone: string;
  /** 地理坐标，用于迷你地球的正交投影 */
  lat: number;
  lon: number;
}

/** 轮换分组：北美 / 欧洲 / 东南亚 / 亚洲其他 / 其他（南美、大洋洲、非洲） */
export type WorldRegion = 'na' | 'eu' | 'sea' | 'asia' | 'other';

export interface RegionPool {
  key: WorldRegion;
  label: string;
  cities: WorldCity[];
}

/** 本地时区，固定第一格，不参与轮换 */
export const HOME_CITY: WorldCity = {
  city: '北京',
  country: '中国',
  zone: 'Asia/Shanghai',
  lat: 39.9042,
  lon: 116.4074,
};

export const REGION_POOLS: readonly RegionPool[] = [
  {
    key: 'na',
    label: '北美',
    cities: [
      { city: '纽约', country: '美国', zone: 'America/New_York', lat: 40.7128, lon: -74.006 },
      {
        city: '洛杉矶',
        country: '美国',
        zone: 'America/Los_Angeles',
        lat: 34.0522,
        lon: -118.2437,
      },
      { city: '芝加哥', country: '美国', zone: 'America/Chicago', lat: 41.8781, lon: -87.6298 },
      { city: '多伦多', country: '加拿大', zone: 'America/Toronto', lat: 43.6532, lon: -79.3832 },
      {
        city: '温哥华',
        country: '加拿大',
        zone: 'America/Vancouver',
        lat: 49.2827,
        lon: -123.1207,
      },
      {
        city: '墨西哥城',
        country: '墨西哥',
        zone: 'America/Mexico_City',
        lat: 19.4326,
        lon: -99.1332,
      },
    ],
  },
  {
    key: 'eu',
    label: '欧洲',
    cities: [
      { city: '伦敦', country: '英国', zone: 'Europe/London', lat: 51.5074, lon: -0.1278 },
      { city: '巴黎', country: '法国', zone: 'Europe/Paris', lat: 48.8566, lon: 2.3522 },
      { city: '法兰克福', country: '德国', zone: 'Europe/Berlin', lat: 50.1109, lon: 8.6821 },
      { city: '阿姆斯特丹', country: '荷兰', zone: 'Europe/Amsterdam', lat: 52.3676, lon: 4.9041 },
      { city: '马德里', country: '西班牙', zone: 'Europe/Madrid', lat: 40.4168, lon: -3.7038 },
      { city: '斯德哥尔摩', country: '瑞典', zone: 'Europe/Stockholm', lat: 59.3293, lon: 18.0686 },
    ],
  },
  {
    key: 'sea',
    label: '东南亚',
    cities: [
      { city: '新加坡', country: '新加坡', zone: 'Asia/Singapore', lat: 1.3521, lon: 103.8198 },
      { city: '曼谷', country: '泰国', zone: 'Asia/Bangkok', lat: 13.7563, lon: 100.5018 },
      { city: '吉隆坡', country: '马来西亚', zone: 'Asia/Kuala_Lumpur', lat: 3.139, lon: 101.6869 },
      { city: '雅加达', country: '印尼', zone: 'Asia/Jakarta', lat: -6.2088, lon: 106.8456 },
      { city: '马尼拉', country: '菲律宾', zone: 'Asia/Manila', lat: 14.5995, lon: 120.9842 },
      { city: '胡志明市', country: '越南', zone: 'Asia/Ho_Chi_Minh', lat: 10.8231, lon: 106.6297 },
    ],
  },
  {
    key: 'asia',
    label: '亚洲其他',
    cities: [
      { city: '东京', country: '日本', zone: 'Asia/Tokyo', lat: 35.6762, lon: 139.6503 },
      { city: '首尔', country: '韩国', zone: 'Asia/Seoul', lat: 37.5665, lon: 126.978 },
      { city: '孟买', country: '印度', zone: 'Asia/Kolkata', lat: 19.076, lon: 72.8777 },
      { city: '迪拜', country: '阿联酋', zone: 'Asia/Dubai', lat: 25.2048, lon: 55.2708 },
      { city: '阿拉木图', country: '哈萨克斯坦', zone: 'Asia/Almaty', lat: 43.222, lon: 76.8512 },
      { city: '德黑兰', country: '伊朗', zone: 'Asia/Tehran', lat: 35.6892, lon: 51.389 },
    ],
  },
  {
    key: 'other',
    label: '南美、大洋洲、非洲',
    cities: [
      { city: '圣保罗', country: '巴西', zone: 'America/Sao_Paulo', lat: -23.5505, lon: -46.6333 },
      {
        city: '布宜诺斯艾利斯',
        country: '阿根廷',
        zone: 'America/Argentina/Buenos_Aires',
        lat: -34.6037,
        lon: -58.3816,
      },
      { city: '圣地亚哥', country: '智利', zone: 'America/Santiago', lat: -33.4489, lon: -70.6693 },
      { city: '悉尼', country: '澳大利亚', zone: 'Australia/Sydney', lat: -33.8688, lon: 151.2093 },
      {
        city: '墨尔本',
        country: '澳大利亚',
        zone: 'Australia/Melbourne',
        lat: -37.8136,
        lon: 144.9631,
      },
      { city: '奥克兰', country: '新西兰', zone: 'Pacific/Auckland', lat: -36.8485, lon: 174.7633 },
      { city: '开普敦', country: '南非', zone: 'Africa/Johannesburg', lat: -33.9249, lon: 18.4241 },
      { city: '拉各斯', country: '尼日利亚', zone: 'Africa/Lagos', lat: 6.5244, lon: 3.3792 },
      { city: '内罗毕', country: '肯尼亚', zone: 'Africa/Nairobi', lat: -1.2921, lon: 36.8219 },
    ],
  },
];

/** 34 城轮换：北京固定，其余五个槽位每 5 秒推进一城。 */
export const CITY_ROTATION_MS = 5_000;

export function rotationCities(cursor: number): readonly WorldCity[] {
  return [
    HOME_CITY,
    ...REGION_POOLS.map(
      (pool) => pool.cities[(cursor + (pool.key === 'other' ? 3 : 0)) % pool.cities.length]!,
    ),
  ];
}

/** 分组用于轮换，大洲用于展示；东南亚不是大洲，“其他”也不能当大洲。 */
export function continentFor(city: WorldCity): string {
  const area = city.zone.split('/')[0];
  if (area === 'Asia') return '亚洲';
  if (area === 'Europe') return '欧洲';
  if (area === 'Africa') return '非洲';
  if (area === 'Australia' || area === 'Pacific') return '大洋洲';
  if (['巴西', '阿根廷', '智利'].includes(city.country)) return '南美洲';
  return '北美洲';
}

export function cityLabel(city: WorldCity): string {
  return `${city.city} ${city.country}（${continentFor(city)}）`;
}

/** 拆出小时与分钟，冒号单独渲染以便做呼吸动效；占位符原样返回 */
export function splitClockTime(time: string): { hours: string; minutes: string } {
  const [hours = '—', minutes = '—'] = time.split(':');
  return { hours, minutes };
}

export function clockParts(date: Date, timeZone: string) {
  return {
    time: new Intl.DateTimeFormat('en-GB', {
      timeZone,
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    }).format(date),
    date: new Intl.DateTimeFormat('zh-CN', { timeZone, month: '2-digit', day: '2-digit' }).format(
      date,
    ),
    offset:
      new Intl.DateTimeFormat('en', { timeZone, timeZoneName: 'shortOffset' })
        .formatToParts(date)
        .find((part) => part.type === 'timeZoneName')?.value ?? '',
    /** 当地小时（含分钟小数），供天空层推导昼夜明度 */
    localHour: localHourOf(date, timeZone),
  };
}

/** 某时刻在指定时区的小时数（0–24，含分钟小数） */
export function localHourOf(date: Date, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date);
  const read = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.find((part) => part.type === type)?.value ?? 0);
  return read('hour') + read('minute') / 60 + read('second') / 3600;
}
