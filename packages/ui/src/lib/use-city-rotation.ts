'use client';

import * as React from 'react';
import {
  CLOCK_ROTATION_INTERVAL_MS,
  nextRotationCursor,
  visibleCities,
  type WorldCity,
} from './world-clock';

/**
 * 时间带轮换：每 intervalMs 前进一个游标，各区域取自己池子里的下一个城市。
 * 返回 switches 供 UI 判断是否刚换过场（首屏为 0，不播入场动效）。
 */
export function useCityRotation(intervalMs = CLOCK_ROTATION_INTERVAL_MS): {
  cursor: number;
  switches: number;
  cities: WorldCity[];
} {
  const [state, setState] = React.useState({ cursor: 0, switches: 0 });

  React.useEffect(() => {
    const timer = window.setInterval(() => {
      setState((prev) => ({
        cursor: nextRotationCursor(prev.cursor),
        switches: prev.switches + 1,
      }));
    }, intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);

  return { ...state, cities: visibleCities(state.cursor) };
}
