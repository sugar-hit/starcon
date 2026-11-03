import { routeProxy as proxy } from '../../../../lib/api-proxy';
export const dynamic = 'force-dynamic';
export const GET = proxy;
export const POST = proxy;

/**
 * 🔴 18.1.1.1 应用与凭证引入了 DELETE：撤销一条授权范围 / 一条 IP 白名单。
 *
 * <p>与 catalog / channel / trade / warehouse / crm / finance 出口**同构**，零影响既有方法。
 */
export const DELETE = proxy;

/**
 * 🔴 18.3 起 platform 有了「整份替换」语义的端点，PUT 必须放行。
 *
 * <p>本文件曾经**刻意不导出 PUT**，理由写的是「本模块没有替换语义的端点」——
 * 那句话在写下时是真的，但 platform 后来陆续加上了 5 个 `@PutMapping`：
 *
 * <ul>
 *   <li>{@code PUT /platform/auto-approvals/policies/{id}}（18.3.3.1 自动审批）
 *   <li>{@code PUT /platform/data-migration/mappings/{id}}（18.1.3.2 数据迁移）
 *   <li>{@code PUT /platform/automation/effect/baselines}（18.3.2.2 规则效果）
 *   <li>{@code PUT /platform/automation/effect/feedbacks/{id}/status}
 *   <li>{@code PUT /platform/automation/effect/advices/{id}/status}
 * </ul>
 *
 * ⇒ 不导出 PUT 时，这 5 个端点在浏览器里一律 405，**功能等于没交付**。
 *
 * <p>⚠️ 安全边界没有因此放宽：放行与否由 {@code apps/web/src/lib/api-proxy.ts} 的
 * **路径白名单**决定（逐条正则，UUID 段用完整形状），与方法无关 —— 上列 5 条路径
 * **本来就已经在白名单里**，缺的只是出口的方法。代理对非白名单路径仍然 404。
 *
 * <p>⚠️ 白名单里也刻意没有放行 `platform/app-quota-usages`：
 * 那是**服务端到服务端**的用量回报路径（内部密钥），浏览器打不到 ——
 * 放行它等于让任意浏览器往别的租户写用量。
 */
export const PUT = proxy;
