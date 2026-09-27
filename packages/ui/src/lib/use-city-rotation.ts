'use client';

import * as React from 'react';
import {
  CLOCK_ROTATION_INTERVAL_MS,
  nextRotationCursor,
  rotationCities,
  type WorldCity,
} from './world-clock';

export interface CityRotationSlot {
  city: WorldCity;
  /** 已换场次数，首屏为 0；用于首屏不播入场动效 */
  switches: number;
}

/**
 * 末两格的轮换时钟：共用一个游标，每 intervalMs 同一次换场。
 * 两格取池中相邻城市，任意时刻都不会出现两格同城。
 */
export function useCityRotation(intervalMs = CLOCK_ROTATION_INTERVAL_MS): {
  slots: CityRotationSlot[];
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

  const slots = rotationCities(state.cursor).map((city) => ({ city, switches: state.switches }));
  return { slots };
}
