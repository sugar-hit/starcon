import { routeProxy as proxy } from '../../../../lib/api-proxy';
export const dynamic = 'force-dynamic';
export const GET = proxy;
export const POST = proxy;
/**
 * 🔴 15.2.2.1 索评车道新增了 PUT：频次硬上限是**幂等 upsert**（一租户一行），
 * 用 PUT 表达「把这份上限替换成这份」。
 *
 * <p>与 catalog / channel / trade / warehouse / iam 五个出口**同构**（它们都导出了 PUT / DELETE），
 * 这里此前只有 GET + POST —— 因为 15.4.2.1 客服知识库用不到写方法之外的动词。
 * 刻意**仍然没有 DELETE**：索评请求是频次计数的凭据（V220 第五节），
 * 放行 DELETE 等于在浏览器侧承诺一个后端不存在的语义。
 */
export const PUT = proxy;

/**
 * 🔴 15.2.2.2 索评规则车道新增了 DELETE：索评**规则** / **话术模板** / **节假日**都是
 * **配置**（不是凭据），删了就是删了 —— 配置项不能只进不出，否则界面上会出现
 * 「配错了却改不掉」的僵尸配置。
 *
 * <p>⚠️ 「已被生效策略落地引用过的规则 / 变体删不掉」这条防线**不在**白名单里：
 * 白名单是**按路径登记、不区分方法**的，真正挡住删除的是服务端端点与
 * V228 的 DB 触发器（guard_crm_review_rule_delete 等）。
 * 15.2.2.1 的索评请求仍然**不提供** DELETE 端点 —— 它是频次计数的凭据。
 */
export const DELETE = proxy;

/**
 * crm（15 客户与客服）的浏览器代理出口。
 *
 * <p>与 catalog / channel / iam / warehouse / trade 五个出口**完全同构**：同一个
 * {@link proxy}、同一份白名单（{@code lib/api-proxy.ts} 的 crm/ 那一支）。
 * 刻意不在这里写任何 crm 专属逻辑 —— 一旦这里出现第二份判定，
 * 「白名单登记了但路由没转发」与「路由转发了但白名单没登记」就会变成两个独立问题。
 *
 * <p>本文件放行两支：
 *   * 15.4.2.1 客服知识库：`knowledge/...`；
 *   * 15.2.2.1 索评：`review-requests/...`。
 * 15.1.1.1 消息收件箱、15.4.1.x 工单、15.5.1.1 自动回复等卷 15 模块**都未开发**，
 * 放行一个还不存在的形状只会让界面以为「已经可用」。
 */
