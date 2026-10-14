import { routeProxy as proxy } from '../../../../lib/api-proxy';
export const dynamic = 'force-dynamic';
export const GET = proxy;
export const POST = proxy;

/**
 * crm（15 客户与客服）的浏览器代理出口。
 *
 * <p>与 catalog / channel / iam / warehouse / trade 五个出口**完全同构**：同一个
 * {@link proxy}、同一份白名单（{@code lib/api-proxy.ts} 的 crm/ 那一支）。
 * 刻意不在这里写任何 crm 专属逻辑 —— 一旦这里出现第二份判定，
 * 「白名单登记了但路由没转发」与「路由转发了但白名单没登记」就会变成两个独立问题。
 *
 * <p>本车道（15.4.2.1）放行的只有 {@code knowledge/} 那一支：
 * 15.1.1.1 消息收件箱、15.4.1.x 工单、15.5.1.1 自动回复等卷 15 模块**都未开发**，
 * 放行一个还不存在的形状只会让界面以为「已经可用」。
 *
 * <p>⚠️ 没有 PUT / DELETE：引用记录是只追加的审计事实，已发布版本只能向前关闭
 * （走 POST close）而不是被改写。放行 DELETE 等于在浏览器侧承诺一个后端不存在的语义。
 */
