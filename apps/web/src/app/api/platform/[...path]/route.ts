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
 * 刻意**不导出 PUT**：本模块没有「整份替换」语义的端点 ——
 * 配额配置走 POST（同窗口类型重复设置 = 覆盖），授权与白名单走 POST / DELETE。
 *
 * <p>⚠️ 白名单里也刻意没有放行 `platform/app-quota-usages`：
 * 那是**服务端到服务端**的用量回报路径（内部密钥），浏览器打不到 ——
 * 放行它等于让任意浏览器往别的租户写用量。
 */
