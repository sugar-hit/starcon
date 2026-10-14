import { InventoryReservationReleaseWorkspace } from '@/components/inventory/inventory-reservation-release';

/**
 * 7.2.1.3 释放与过期。
 *
 * <p>路由 `/inventory/reservation-release`：蓝图 07 卷的 L3 锚点路由是 `/inventory/reservation`
 * （那是 7.2.1.1 预占需求列表），本模块是它下面第三个动作页，
 * **刻意不放在 `/inventory/reservation/release`** —— 那个路径会被 `[reservationId]`
 * 动态段接住，两条路由在同一个目录下互相打架（Next.js 虽让静态段优先，但这份代码里
 * 同一个前缀下已经有 `[reservationId]` 一条动态路由，再加一个同名静态段只会在重构时踩雷）。
 * 兄弟目录 `allocation-priority/` 与 `allocation/` 也是这个排法。
 */
export default function ReservationReleasePage() {
  return <InventoryReservationReleaseWorkspace />;
}
