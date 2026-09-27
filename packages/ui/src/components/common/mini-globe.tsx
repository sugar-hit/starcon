'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';
import {
  graticulePaths,
  landPaths,
  project,
  shortestLonDelta,
  type GlobeView,
} from '../../lib/geo';
import type { WorldCity } from '../../lib/world-clock';

const VIEW = 100; // viewBox 边长
const CENTER = VIEW / 2;
const RADIUS = 44;
const PIN_OFFSET_LON = -18; // 球心相对城市西移，让定位点落在右上而不是正中
const PIN_OFFSET_LAT = -9;

interface MiniGlobeProps {
  city: WorldCity;
  /** 换场延迟，错峰形成波浪 */
  delay?: number;
  /** 旋转补间时长 */
  duration?: number;
  className?: string;
  /** 无障碍名称，为空则视为装饰 */
  label?: string;
}

function viewFor(city: WorldCity): GlobeView {
  return {
    lat: Math.max(-72, Math.min(72, city.lat + PIN_OFFSET_LAT)),
    lon: city.lon + PIN_OFFSET_LON,
  };
}

function easeInOutCubic(t: number): number {
  return t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2;
}

/**
 * 迷你地球：真实经纬度的正交投影。
 * 换城市时球体沿最短路径转过去，定位点跟着飞入；
 * 补间逐帧直接改 SVG path 的 d，不经过 React 渲染。
 */
export function MiniGlobe({ city, delay = 0, duration = 900, className, label }: MiniGlobeProps) {
  const landRef = React.useRef<SVGPathElement>(null);
  const gridRef = React.useRef<SVGPathElement>(null);
  const pinRef = React.useRef<SVGGElement>(null);
  const viewRef = React.useRef<GlobeView>(viewFor(city));
  const cityRef = React.useRef(city);
  const frameRef = React.useRef(0);
  const timerRef = React.useRef(0);
  const idRef = React.useRef(`globe-${Math.random().toString(36).slice(2, 8)}`);

  const draw = React.useCallback((view: GlobeView, arrival: number) => {
    const grid = graticulePaths(view, RADIUS);
    if (gridRef.current) {
      gridRef.current.setAttribute('d', [...grid.fine, grid.equator, grid.prime].join(' '));
    }
    if (landRef.current) {
      landRef.current.setAttribute('d', landPaths(view, RADIUS).join(' '));
    }
    const pin = project(cityRef.current, view, RADIUS);
    if (pinRef.current) {
      const scale = 0.55 + 0.45 * arrival;
      pinRef.current.setAttribute(
        'transform',
        `translate(${CENTER + pin.x} ${CENTER - pin.y}) scale(${scale.toFixed(3)})`,
      );
      pinRef.current.setAttribute('opacity', arrival.toFixed(3));
    }
  }, []);

  // 首次挂载直接落到目标视角，不播入场动画
  React.useEffect(() => {
    cityRef.current = city;
    viewRef.current = viewFor(city);
    draw(viewRef.current, 1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  React.useEffect(() => {
    cityRef.current = city;
    const target = viewFor(city);
    window.clearTimeout(timerRef.current);
    window.cancelAnimationFrame(frameRef.current);
    const from = viewRef.current;
    // 视角没变就不补间（首屏、本地时区格都是静止的）
    if (
      Math.abs(target.lat - from.lat) < 0.01 &&
      Math.abs(shortestLonDelta(from.lon, target.lon)) < 0.01
    ) {
      draw(from, 1);
      return;
    }
    timerRef.current = window.setTimeout(() => {
      const dLon = shortestLonDelta(from.lon, target.lon);
      const start = performance.now();
      const step = (now: number) => {
        const t = Math.min(1, (now - start) / duration);
        const eased = easeInOutCubic(t);
        const view: GlobeView = {
          lat: from.lat + (target.lat - from.lat) * eased,
          lon: from.lon + dLon * eased,
        };
        viewRef.current = view;
        draw(view, t);
        if (t < 1) frameRef.current = requestAnimationFrame(step);
      };
      frameRef.current = requestAnimationFrame(step);
    }, delay);
    return () => {
      window.clearTimeout(timerRef.current);
      window.cancelAnimationFrame(frameRef.current);
    };
  }, [city, delay, duration, draw]);

  const id = idRef.current;
  return (
    <svg
      viewBox={`0 0 ${VIEW} ${VIEW}`}
      className={cn('shrink-0', className)}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      focusable="false"
    >
      <defs>
        <radialGradient id={`${id}-ocean`} cx="36%" cy="30%" r="76%">
          <stop offset="0%" stopColor="hsl(var(--brand) / 0.16)" />
          <stop offset="62%" stopColor="hsl(var(--brand) / 0.09)" />
          <stop offset="100%" stopColor="hsl(var(--brand) / 0.05)" />
        </radialGradient>
        <radialGradient id={`${id}-shade`} cx="34%" cy="28%" r="80%">
          <stop offset="0%" stopColor="#fff" stopOpacity="0.5" />
          <stop offset="52%" stopColor="#fff" stopOpacity="0" />
          <stop offset="100%" stopColor="hsl(var(--brand))" stopOpacity="0.14" />
        </radialGradient>
        <radialGradient id={`${id}-pin`} cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="hsl(var(--brand))" stopOpacity="0.42" />
          <stop offset="100%" stopColor="hsl(var(--brand))" stopOpacity="0" />
        </radialGradient>
        <clipPath id={`${id}-clip`}>
          <circle cx={CENTER} cy={CENTER} r={RADIUS} />
        </clipPath>
      </defs>

      <circle cx={CENTER} cy={CENTER} r={RADIUS} fill={`url(#${id}-ocean)`} />
      <g clipPath={`url(#${id}-clip)`}>
        {/* 投影输出以球心为原点，这里平移到 viewBox 中心 */}
        <g transform={`translate(${CENTER} ${CENTER})`}>
          <path
            ref={landRef}
            fill="hsl(var(--brand) / 0.3)"
            fillRule="nonzero"
            stroke="hsl(var(--brand) / 0.34)"
            strokeWidth="0.5"
          />
          <path
            ref={gridRef}
            fill="none"
            stroke="hsl(var(--brand) / 0.28)"
            strokeWidth="0.7"
            strokeLinecap="round"
          />
        </g>
      </g>
      <circle cx={CENTER} cy={CENTER} r={RADIUS} fill={`url(#${id}-shade)`} />
      <circle
        cx={CENTER}
        cy={CENTER}
        r={RADIUS}
        fill="none"
        stroke="hsl(var(--brand) / 0.28)"
        strokeWidth="0.8"
      />
      <g ref={pinRef}>
        <circle r="9" fill={`url(#${id}-pin)`} />
        <circle r="3.4" fill="none" stroke="hsl(var(--brand))" strokeWidth="1.1" />
        <circle r="1.4" fill="hsl(var(--brand))" />
      </g>
    </svg>
  );
}
