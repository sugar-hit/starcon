'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';
import { skylineFor, SKYLINE_VIEW } from '../../lib/city-skyline';
import { inkForSkyLevel, skySurfaceForLevel, type ClockInk } from '../../lib/clock-sky';

interface CitySkylineProps {
  city: string;
  /** 天空明度 0–1，由当地时刻推导 */
  level: number;
  className?: string;
}

/**
 * 城市天际线剪影：铺满整格作为氛围层，剪影本身贴底且保持原比例。
 *
 * 剪影不承载信息——所以走 aria-hidden，城市身份由旁边的文字标签负责。
 * 明度越低（夜）剪影越亮、天空越厚，文字转浅色；反之转深色。
 */
export function CitySkyline({ city, level, className }: CitySkylineProps) {
  const skyline = skylineFor(city);
  const idRef = React.useRef(`sky-${Math.random().toString(36).slice(2, 8)}`);
  const ink: ClockInk = inkForSkyLevel(level);
  const surface = skySurfaceForLevel(level);

  if (!skyline) return null;

  const id = idRef.current;
  return (
    <span aria-hidden="true" className={cn('pointer-events-none absolute inset-0', className)}>
      {/* 天空洗色：顶部薄、底部厚 */}
      <span
        className="absolute inset-0"
        style={{
          background: `linear-gradient(to bottom, hsl(var(--brand) / ${surface.washTop}), hsl(var(--brand) / ${surface.washBottom}))`,
        }}
      />
      {/*
        剪影层：等比缩放居中，垂直方向从底部裁切。
        用 slice 而不是 none——拉伸会把地标压变形（圆变椭圆、塔变矮），
        等比放大后只裁掉多余的上方空白，轮廓比例保持不变。
      */}
      <svg
        viewBox={`0 0 ${SKYLINE_VIEW.width} ${SKYLINE_VIEW.height}`}
        preserveAspectRatio="xMidYMax slice"
        className="absolute inset-0 h-full w-full"
        style={{ opacity: surface.silhouette }}
      >
        <defs>
          <linearGradient id={`${id}-fill`} x1="0" y1="0" x2="0" y2="1">
            {ink === 'light' ? (
              <>
                {/* 夜：剪影比夜空更亮，才能读出轮廓 */}
                <stop offset="0%" stopColor={`hsl(var(--brand) / ${0.62 + surface.silhouetteLift})`} />
                <stop offset="100%" stopColor={`hsl(var(--brand) / ${0.34 + surface.silhouetteLift})`} />
              </>
            ) : (
              <>
                {/* 昼：剪影更沉，压住底部不抢焦点 */}
                <stop offset="0%" stopColor="hsl(var(--brand) / 0.42)" />
                <stop offset="100%" stopColor="hsl(var(--brand) / 0.22)" />
              </>
            )}
          </linearGradient>
        </defs>
        <path d={skyline.path} fill={`url(#${id}-fill)`} />
      </svg>
    </span>
  );
}
