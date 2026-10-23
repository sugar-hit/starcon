import { SyncHealthWorkspace } from '@/components/system-health/sync-health-workspace';

/**
 * 1.5.1.1 同步与 API 健康 —— 独立页（`/system-health`）。
 *
 * <p>蓝图给本模块的定位是「<b>跨系统排查的起点</b>」：用户反馈「没同步 / 没订单」时，
 * 第一个该看的页面，而不是让人在五个页面里找。所以它是一张**独立的一屏**，
 * 而不是某个系统页面里的一个 Tab —— 排查时要的是「一屏看全」，不是「先点进渠道、
 * 再点进同步、再翻失败队列」。
 *
 * <p><b>它不是占位页</b>：真实数据、真实红黄绿、真实失败 / 限流 / 积压计数，
 * 真实错误 / 空 / 加载态。待处理项是**跳转**到归属系统的页面 ——
 * 本页只读（卷 01 边界：工作台只读 + 跳转）。
 */
export default function SystemHealthPage() {
  return <SyncHealthWorkspace />;
}
