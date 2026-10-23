import { routeProxy as proxy } from '../../../../lib/api-proxy';
export const dynamic = 'force-dynamic';
export const GET = proxy;
export const POST = proxy;

/**
 * 🔴 12.1.1.1 应收单管理引入了 PUT：应收确认政策是**配置**（不是凭据），
 * 用 PUT 表达「把这个来源的确认政策替换成这份」（后端是幂等 upsert）。
 *
 * <p>与 catalog / channel / trade / warehouse / crm 五个出口**同构**，零影响既有方法。
 */
export const PUT = proxy;

/**
 * 刻意**不导出 DELETE**：本模块没有删除端点。
 *
 * <p>应收单是**凭据**（它记着「谁欠我们多少钱、什么时候确认、汇率是多少」），
 * 错了只能**作废**（红冲），不能删 —— 删掉就没有痕迹了。
 * 催收任务同理：取消走 {@code status='CANCELLED'}。
 *
 * <p>⚠️ 白名单里也刻意没有放行任何 finance 的删除形状：
 * 放行一个后端不存在的写入口，比 404 更糟（界面会以为「已经可用」）。
 */
