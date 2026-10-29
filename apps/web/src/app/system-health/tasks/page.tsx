import { ServiceTaskStatusWorkspace } from '@/components/system-health/service-task-status-workspace';

/**
 * 1.5.2.1 服务与任务状态 —— 独立页（`/system-health/tasks`）。
 *
 * <p>蓝图给本模块的定位是「展示平台自身运行状态：后台任务 / 同步任务最近运行与耗时、
 * 失败任务、计划任务是否停滞，便于运维与用户自助判断"是系统问题还是数据问题"。
 * 输出：任务状态列表；停滞 / 失败任务提示。」
 *
 * <p><b>为什么是 `/system-health` 下的独立页</b>：1.5 系统健康中心的几个子项回答的是
 * 几种不同的排查 —— 1.5.1.1 回答「现在能不能正常同步」（已交付在 `/system-health`），
 * 1.5.1.2 回答「账能不能信」（已交付在 `/system-health/consistency`），
 * 1.5.2.1 回答「**是系统挂了还是我们的数据不对**」。后者要同时看同步运行、增量补拉、
 * 批量搬迁、发布任务、链接采集五类任务，塞进任何一个归属系统的页面里都会让人
 * 误以为那只是那个系统的事。
 *
 * <p><b>它不是占位页</b>：真实数据、真实停滞 / 失败判定、真实失败分类，
 * 真实错误 / 空 / 加载三态。处置入口一律是**跳转**到归属系统 —— 本页只读
 * （卷 01 边界：工作台只读 + 跳转）。
 */
export default function ServiceTaskStatusPage() {
  return <ServiceTaskStatusWorkspace />;
}
