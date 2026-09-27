import { WORLD_LAND_POINTS, type GeoPoint } from './world-land';

const DEG = Math.PI / 180;
const COS_LIMIT = 0.0016; // 视界边缘约 89.9°，再往外会被拉成尖刺

export interface GlobeView {
  /** 球心纬度 */
  lat: number;
  /** 球心经度 */
  lon: number;
}

export interface ProjectedPoint {
  x: number;
  y: number;
  /** 是否在可见半球 */
  front: boolean;
}

/** 迷你地球上小于这个周长的岛屿不可见，直接剔除（投影每帧都要用） */
const MIN_RING_PERIMETER_DEG = 6;

const LAND_RINGS = WORLD_LAND_POINTS.filter((ring) => {
  let perimeter = 0;
  for (let i = 0; i < ring.length; i += 1) {
    perimeter += angularDistance(ring[i]!, ring[(i + 1) % ring.length]!);
  }
  return perimeter >= MIN_RING_PERIMETER_DEG;
});

export function toRadians(degrees: number): number {
  return degrees * DEG;
}

function normalize(x: number, y: number, z: number): [number, number, number] {
  const len = Math.hypot(x, y, z) || 1;
  return [x / len, y / len, z / len];
}

function toUnitVector(point: GeoPoint): [number, number, number] {
  const lat = toRadians(point.lat);
  const lon = toRadians(point.lon);
  return [Math.cos(lat) * Math.cos(lon), Math.cos(lat) * Math.sin(lon), Math.sin(lat)];
}

function viewVector(view: GlobeView): [number, number, number] {
  return toUnitVector(view);
}

/** 经度差取最短路径（跨 ±180° 时不绕远路） */
export function shortestLonDelta(from: number, to: number): number {
  return ((((to - from) % 360) + 540) % 360) - 180;
}

/** 球面角距（度） */
export function angularDistance(a: GeoPoint, b: GeoPoint): number {
  const lat1 = toRadians(a.lat);
  const lat2 = toRadians(b.lat);
  const dLat = lat2 - lat1;
  const dLon = toRadians(shortestLonDelta(a.lon, b.lon));
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return (2 * Math.asin(Math.min(1, Math.sqrt(h)))) / DEG;
}

/**
 * 正交投影：以 view 为球心，把经纬度投到半径 radius 的圆盘上。
 * clampBack=true 时，背面点沿大圆推到视界圆上（填充大陆用，
 * 这样跨过地平线的洲会被边缘自然切断，而不是断在圆盘中间）。
 */
export function project(
  point: GeoPoint,
  view: GlobeView,
  radius: number,
  clampBack = false,
): ProjectedPoint {
  const front = toScreen(point, view, radius, COS_LIMIT);
  if (front !== null || !clampBack) {
    return front ? { ...front, front: true } : { x: 0, y: 0, front: false };
  }
  // 背面点沿大圆推到视界：填充大陆时跨过地平线的那一半被边缘自然切断
  const c = viewVector(view);
  const p = toUnitVector(point);
  const cosC = c[0] * p[0] + c[1] * p[1] + c[2] * p[2];
  const [x, y, z] = normalize(p[0] - cosC * c[0], p[1] - cosC * c[1], p[2] - cosC * c[2]);
  const limb = toScreen({ lat: Math.asin(z) / DEG, lon: Math.atan2(y, x) / DEG }, view, radius, -1);
  return limb ? { ...limb, front: false } : { x: 0, y: 0, front: false };
}

function toScreen(point: GeoPoint, view: GlobeView, radius: number, minCos: number) {
  const lat = toRadians(point.lat);
  const lon = toRadians(point.lon);
  const lat0 = toRadians(view.lat);
  const lon0 = toRadians(view.lon);
  const cosC =
    Math.sin(lat0) * Math.sin(lat) + Math.cos(lat0) * Math.cos(lat) * Math.cos(lon - lon0);
  if (cosC < minCos) return null;
  return {
    x: radius * Math.cos(lat) * Math.sin(lon - lon0),
    y:
      radius *
      (Math.cos(lat0) * Math.sin(lat) - Math.sin(lat0) * Math.cos(lat) * Math.cos(lon - lon0)),
  };
}

const fmt = (value: number) => value.toFixed(2);

/** 沿采样点串生成 path，背面自动断开；填充大陆靠 clampBack 走完整个环 */
function polyline(
  points: readonly GeoPoint[],
  view: GlobeView,
  radius: number,
  clampBack: boolean,
): string {
  const parts: string[] = [];
  let run: string[] = [];
  const flush = () => {
    if (run.length > 1) parts.push(`M${run.join(' L')}`);
    run = [];
  };
  for (const point of points) {
    const projected = project(point, view, radius, clampBack);
    if (!projected.front) {
      if (clampBack) {
        // 填充：背面点推到地平线，圆盘裁切即地平线
        run.push(`${fmt(projected.x)},${fmt(-projected.y)}`);
        continue;
      }
      // 线状要素：把越界的那个点推到地平线再断开，切口才干净
      if (run.length > 1) {
        const limb = project(point, view, radius, true);
        run.push(`${fmt(limb.x)},${fmt(-limb.y)}`);
        flush();
      }
      continue;
    }
    run.push(`${fmt(projected.x)},${fmt(-projected.y)}`);
  }
  flush();
  return parts.join(' ');
}

/** 大圆弧采样；起点终点都在可见半球时返回一条 path */
export function greatArc(
  from: GeoPoint,
  to: GeoPoint,
  view: GlobeView,
  radius: number,
  steps = 40,
): string {
  const a = toUnitVector(from);
  const b = toUnitVector(to);
  const dot = Math.min(1, Math.max(-1, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]));
  const d = Math.acos(dot);
  if (!Number.isFinite(d) || d < 1e-6) return '';
  const sinD = Math.sin(d);
  const points: GeoPoint[] = [];
  for (let i = 0; i <= steps; i += 1) {
    const f = i / steps;
    const wa = Math.sin((1 - f) * d) / sinD;
    const wb = Math.sin(f * d) / sinD;
    const [x, y, z] = normalize(
      wa * a[0] + wb * b[0],
      wa * a[1] + wb * b[1],
      wa * a[2] + wb * b[2],
    );
    points.push({ lon: Math.atan2(y, x) / DEG, lat: Math.asin(z) / DEG });
  }
  return polyline(points, view, radius, false);
}

function parallelPoints(lat: number, steps = 40): GeoPoint[] {
  return Array.from({ length: steps + 1 }, (_, i) => ({ lat, lon: -180 + (360 * i) / steps }));
}

function meridianPoints(lon: number, steps = 26): GeoPoint[] {
  return Array.from({ length: steps + 1 }, (_, i) => ({ lat: -90 + (180 * i) / steps, lon }));
}

/** 经纬网：赤道与本初子午线加重，其余细线 */
export function graticulePaths(
  view: GlobeView,
  radius: number,
): {
  fine: string[];
  equator: string;
  prime: string;
} {
  const fine: string[] = [];
  for (const lat of [-60, -30, 30, 60]) {
    const path = polyline(parallelPoints(lat), view, radius, false);
    if (path) fine.push(path);
  }
  for (let lon = -150; lon <= 150; lon += 30) {
    const path = polyline(meridianPoints(lon), view, radius, false);
    if (path) fine.push(path);
  }
  return {
    fine,
    equator: polyline(parallelPoints(0), view, radius, false),
    prime: polyline(meridianPoints(0), view, radius, false),
  };
}

/**
 * 大陆轮廓：只填可见半球，跨过地平线的那一段在切口处收口。
 * 关键点：不能把背面点也串进同一条路径——背面点被推回视界后会和正面
 * 部分形成自交环，nonzero 缠绕相互抵消，正中间的大陆会被填没。
 */
function filledRing(ring: readonly GeoPoint[], view: GlobeView, radius: number): string {
  const parts: string[] = [];
  let run: string[] = [];
  let pendingLimb: string | null = null;
  const pt = (p: { x: number; y: number }) => `${fmt(p.x)},${fmt(-p.y)}`;
  for (const point of ring) {
    const projected = project(point, view, radius);
    if (projected.front) {
      if (pendingLimb) {
        run.push(pendingLimb);
        pendingLimb = null;
      }
      run.push(pt(projected));
      continue;
    }
    if (!run.length) continue;
    const limb = pt(project(point, view, radius, true));
    run.push(limb);
    if (run.length > 2) parts.push(`M${run[0]} L${run.slice(1).join(' L')} Z`);
    run = [];
    pendingLimb = limb;
  }
  if (run.length > 2) parts.push(`M${run[0]} L${run.slice(1).join(' L')} Z`);
  return parts.join(' ');
}

export function landPaths(view: GlobeView, radius: number): string[] {
  const paths: string[] = [];
  for (const ring of LAND_RINGS) {
    const path = filledRing(ring, view, radius);
    if (path) paths.push(path);
  }
  return paths;
}
