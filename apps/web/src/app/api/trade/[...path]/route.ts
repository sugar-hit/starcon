import { routeProxy as proxy } from '../../../../lib/api-proxy';
export const dynamic = 'force-dynamic';
export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
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
