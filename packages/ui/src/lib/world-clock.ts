export interface WorldCity {
  city: string;
  country: string;
  zone: string;
}

/** 基准时间，始终排第一格 */
export const HOME_CITY: WorldCity = { city: '北京', country: '中国', zone: 'Asia/Shanghai' };

/** 固定城市，按运营关注度排列，永远不参与轮换 */
export const PRIMARY_CITIES: readonly WorldCity[] = [
  { city: '纽约', country: '美国', zone: 'America/New_York' },
  { city: '伦敦', country: '英国', zone: 'Europe/London' },
  { city: '东京', country: '日本', zone: 'Asia/Tokyo' },
];

/**
 * 轮换城市池：末两格按游标顺序循环，切换到其他全球主要城市。
 * 池首两项即首屏展示的城市，后续按跨境电商运营关注度排列。
 */
export const ROTATING_CITY_POOL: readonly WorldCity[] = [
  { city: '伊斯坦布尔', country: '土耳其', zone: 'Europe/Istanbul' },
  { city: '罗马', country: '意大利', zone: 'Europe/Rome' },
  { city: '新加坡', country: '新加坡', zone: 'Asia/Singapore' },
  { city: '迪拜', country: '阿联酋', zone: 'Asia/Dubai' },
  { city: '洛杉矶', country: '美国', zone: 'America/Los_Angeles' },
  { city: '圣保罗', country: '巴西', zone: 'America/Sao_Paulo' },
  { city: '孟买', country: '印度', zone: 'Asia/Kolkata' },
  { city: '法兰克福', country: '德国', zone: 'Europe/Berlin' },
  { city: '首尔', country: '韩国', zone: 'Asia/Seoul' },
  { city: '悉尼', country: '澳大利亚', zone: 'Australia/Sydney' },
  { city: '温哥华', country: '加拿大', zone: 'America/Vancouver' },
  { city: '多伦多', country: '加拿大', zone: 'America/Toronto' },
  { city: '曼谷', country: '泰国', zone: 'Asia/Bangkok' },
  { city: '开普敦', country: '南非', zone: 'Africa/Johannesburg' },
];

/** 参与轮换的格子数量（工作台末两格） */
export const ROTATING_SLOT_COUNT = 2;

/** 轮换间隔 */
export const CLOCK_ROTATION_INTERVAL_MS = 6000;

/**
 * 取某一游标下各轮换格的城市。
 * 末两格共用一个游标、同一次换场，取池中相邻两个城市：
 * 既保证两格永远不同城，也避免各自走位时出现"两格同城"的过渡态。
 */
export function rotationCities(cursor: number, slots = ROTATING_SLOT_COUNT): WorldCity[] {
  const total = ROTATING_CITY_POOL.length;
  return Array.from({ length: slots }, (_, index) => ROTATING_CITY_POOL[(cursor + index) % total]!);
}

/** 游标前进一格，循环回到池首 */
export function nextRotationCursor(cursor: number): number {
  return (cursor + 1) % ROTATING_CITY_POOL.length;
}

/** 工作台一屏展示的六个城市 */
export function visibleCities(cursor: number): WorldCity[] {
  return [HOME_CITY, ...PRIMARY_CITIES, ...rotationCities(cursor)];
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
  };
}
