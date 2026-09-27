/**
 * 时钟格的"天空"层。
 *
 * 每个城市按**当地时刻**推导出天空明度（夜暗昼亮），再由明度决定文字用深墨还是浅墨。
 * 这样"文字基于背景自动换色"不是逐格手调的装饰，而是可推导、可单测的规则：
 * 同一时刻，凌晨的城市显示为深底浅字，下午的城市显示为浅底深字。
 */

/** 文字墨色模式：深底浅字 / 浅底深字 */
export type ClockInk = 'light' | 'dark';

/** 天空明度分段步长，量化后一天最多跨 24 档，避免每秒重算产生连续渐变 */
export const SKY_LEVEL_STEPS = 24;

/** 明度高于此值用深墨，否则用浅墨 */
export const SKY_INK_THRESHOLD = 0.55;

/**
 * 当地小时（0–24，可含小数）→ 天空明度 0–1。
 *
 * 分段：深夜 0.16 / 破晓 0.16→0.95 / 白昼 0.95 / 黄昏 0.95→0.22 / 入夜 0.22→0.16。
 * 曲线连续，所以量化前是平滑的。
 */
export function skyLevelForHour(hour: number): number {
  const h = ((hour % 24) + 24) % 24;

  if (h < 3) return 0.16;
  if (h < 7) return lerp(0.16, 0.95, smoothstep((h - 3) / 4));
  if (h < 16) return 0.95;
  if (h < 20) return lerp(0.95, 0.22, smoothstep((h - 16) / 4));
  if (h < 21) return lerp(0.22, 0.16, smoothstep((h - 20) / 1));
  return 0.16;
}

/** 量化到固定档位，避免 1s 读秒时背景连续漂移 */
export function quantizeSkyLevel(level: number, steps = SKY_LEVEL_STEPS): number {
  const clamped = Math.max(0, Math.min(1, level));
  return Math.round(clamped * steps) / steps;
}

export function skyLevelForLocalHour(hour: number, steps = SKY_LEVEL_STEPS): number {
  return quantizeSkyLevel(skyLevelForHour(hour), steps);
}

/** 天空明度 → 文字墨色模式 */
export function inkForSkyLevel(level: number): ClockInk {
  return level >= SKY_INK_THRESHOLD ? 'dark' : 'light';
}

export interface SkySurface {
  /** 顶部洗色透明度（品牌色相） */
  washTop: number;
  /** 底部洗色透明度 */
  washBottom: number;
  /** 地平线剪影填充透明度 */
  silhouette: number;
  /** 剪影颜色明度偏移：夜里剪影要更亮才有轮廓，白天更沉 */
  silhouetteLift: number;
}

/**
 * 天空明度 → 背景洗色与剪影的分层强度。
 *
 * 白昼：底浅、剪影沉，文字用深墨。
 * 夜晚：底深、剪影略提亮，文字用浅墨。
 * 中间过渡（破晓/黄昏）连续插值，不会跳变。
 */
export function skySurfaceForLevel(level: number): SkySurface {
  const l = Math.max(0, Math.min(1, level));
  const day = l;

  return {
    // 夜空底部厚、顶部薄，让视线自然沉到地平线
    washTop: round4(lerp(0.2, 0.05, day)),
    washBottom: round4(lerp(0.5, 0.1, day)),
    // 剪影整体不透明度：夜里要透出轮廓，白天压住底部
    silhouette: round4(lerp(0.55, 0.32, day)),
    // 夜里剪影额外提亮，让它比夜空更亮
    silhouetteLift: round4(lerp(0.3, 0, day)),
  };
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

function smoothstep(t: number): number {
  const x = Math.max(0, Math.min(1, t));
  return x * x * (3 - 2 * x);
}

function round4(value: number): number {
  return Math.round(value * 10000) / 10000;
}
