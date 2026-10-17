import { routeProxy as proxy } from '../../../../lib/api-proxy';
export const dynamic = 'force-dynamic';
export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
/**
 * PATCH 是 11.1.2.1 售后原因分类引入的（分类改名 / 排序 / 停用 / 换父分类，
 * 以及平台原因码映射改内部分类与状态）。
 *
 * <p>⚠️ 这一行是**新增**，不是改动既有方法：此前本路由没有导出 PATCH，
 * 于是所有 PATCH 请求在 Next 路由层就 405 了 —— 代理的白名单再对也没用，
 * 因为请求根本到不了 `routeProxy`。补上它对既有 GET/POST/PUT/DELETE 行为零影响。
 */
export const PATCH = proxy;
export const DELETE = proxy;

/**
 * trade（09 订单履约）的浏览器代理出口。
 *
 * <p>与 catalog / channel / iam / warehouse 四个出口**完全同构**：同一个
 * {@link proxy}、同一份白名单（{@code lib/api-proxy.ts} 的 trade/ 那一支）。
 * 刻意不在这里写任何 trade 专属逻辑 —— 一旦这里出现第二份判定，
 * 「白名单登记了但路由没转发」与「路由转发了但白名单没登记」就会变成两个独立问题。
 *
 * <p>9.3.2.2（FBA 与自发货选择）是 trade 的第一条业务端点，也是白名单
 * 首次放行 trade/ 子路径的那条车道；此前 trade/ 在白名单里是空模式，
 * 任何 trade 路径都 404 —— 那时候 trade 服务确实一条端点都没有。
 */
