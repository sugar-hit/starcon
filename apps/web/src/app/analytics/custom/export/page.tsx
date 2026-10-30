import { ExportWorkspace } from '@/components/analytics/export-workspace';

/**
 * 13.6.2.1 导出与订阅（卷 13「利润与经营分析」L3 13.6.2 的第二格）。
 *
 * <p>蓝图原文：「报表导出（Excel/CSV/PDF）与定时订阅推送（邮件/企业 IM），支持大文件异步导出。」
 * 输入「导出请求；订阅配置。」输出「文件下载链接；订阅投递记录。」
 * 关系：上游 = 13.6.1.1；下游 = 1.3.2.2 推送。
 * <strong>大导出走异步任务并给出有效期</strong>，同步导出大文件会拖垮服务。
 *
 * <p><b>🔴 页面顶部那段话不是提示语，是这一页的设计前提</b>：
 *
 * <ol>
 *   <li><strong>「受理」不是「完成」</strong>。受理返回的是「排队中」，行数与文件要轮询
 *       任务详情才拿得到 —— 页面<strong>绝不</strong>在受理后就显示「导出完成」。</li>
 *   <li><strong>「已投递」不是「排上队了」</strong>。投递记录的状态是<strong>真的投递结果</strong>：
 *       没配置 SMTP / Webhook 时它会记为失败并写明原因，页面照着显示，
 *       <strong>不把失败说成「已发送」</strong>。</li>
 *   <li><strong>过期就是过期</strong>。下载链接过了有效期会回 410 ⇒ 页面显示明确的过期态，
 *       而不是给一个点了就报错的按钮。</li>
 *   <li><strong>口径唯一</strong>。导出走的是 13.6.1.1 的同一套取数逻辑 ⇒ 导出的内容与
 *       报表页面上看到的逐格一致，<strong>不另算一遍口径</strong>。</li>
 * </ol>
 */
export default function ExportPage() {
  return <ExportWorkspace />;
}
