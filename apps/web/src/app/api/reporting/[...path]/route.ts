import { routeProxy as proxy } from '../../../../lib/api-proxy';
export const dynamic = 'force-dynamic';
export const GET = proxy;
export const POST = proxy;

/**
 * 🔴 13 利润与经营分析（reporting 服务，端口 8088）的浏览器出口。
 *
 * <p>⚠️ **这个出口此前一直缺着**：reporting 的白名单（`lib/api-proxy.ts` 里那 9 条
 * `reporting/` 元素）与它的前端工作区（13.1.1.1 经营总览 / 13.2.1.1 销量趋势 /
 * 13.2.2.2 SKU·SPU·Listing / 13.2.2.1 店铺与站点 …）**都早已就位**，
 * 但 `app/api/` 下**只有** catalog / channel / crm / finance / iam / platform /
 * trade / warehouse 八个服务有 `[...path]/route.ts` —— reporting 一直没有。
 *
 * <p>⇒ 后果是**浏览器里** `/api/reporting/...` 在 Next 路由层就 404，
 * 页面永远停在「读不到」的错误态；而白名单的双向断言测试是**直接调用 `proxy()`**
 * （绕开 Next 路由）⇒ 测的是白名单，测不到路由，两边都绿、页面却打不开。
 * 这正是「白名单登记了不等于浏览器里能打到」的那一类坑。
 *
 * <p>与 catalog / channel / trade / warehouse / crm / finance / platform 出口**同构**，
 * 零影响既有方法：白名单之外的形状仍然 404（放行后端不存在的路径比 404 更糟）。
 *
 * <p>刻意**不导出 DELETE / PUT**：reporting 是**投影读模型**，唯一的写动作是
 * `POST /reporting/{module}/refresh`（写的是投影，不是业务事实）——
 * 没有任何「删除」或「整份替换」语义的端点。
 */
