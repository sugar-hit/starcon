package com.starcon.platform;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 18.1.3.1「导入导出任务」的**进程内执行器**。
 *
 * <p>蓝图加粗句：「<b>大文件一律异步</b>，并在下载链接上设置有效期，避免存储无限增长。」
 *
 * <p>🔴 <b>为什么是进程内线程池而不是「任务表 + 外部调度器」</b>：
 * 导出要经**属主服务的授权端点**读数据，而上游按**已认证成员关系**解析租户 ——
 * 那需要调用方的会话。若把执行放到外部调度器，就必须把会话凭据存进数据库
 * （= 把 secret 落库），或者改造成服务间凭据（= 新增一套凭据体系）。
 * ⇒ 这里让**调用方的 bearer 只在内存里活到这次执行结束**，任务表里**一个凭据都不存**。
 *
 * <p>🔴 **任务表里没有 bearer**：进程重启后任务停在 PENDING / RUNNING，
 * 恢复扫描会把它标成「执行中断」而不是偷偷重试 ——
 * 因为没有凭据就**读不到上游**，重试也只会再失败一次。
 *
 * <p>🔴 **执行器持有自己的事务边界**（{@code access.run}）：每个任务的推进都是**独立提交**的，
 * 所以「跑了 800 行后崩掉」留下的是**已推进 800 行的真实进度**，不是全部回滚。
 */
@Service
public class ImportExportExecutor {
  private static final Logger log = LoggerFactory.getLogger(ImportExportExecutor.class);

  /** 同时跑几个任务（导出要翻上游的分页，不能一个任务把线程池占满）。 */
  static final int POOL_SIZE = 2;

  private final ImportExportService service;
  private final AccessService access;
  private final ExecutorService pool;

  public ImportExportExecutor(ImportExportService service, AccessService access) {
    this.service = service;
    this.access = access;
    var counter = new AtomicLong();
    ThreadFactory factory =
        runnable -> {
          var thread = new Thread(runnable, "platform-io-" + counter.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    this.pool = Executors.newFixedThreadPool(POOL_SIZE, factory);
  }

  /**
   * 提交一个任务执行（**立即返回**：这是「已受理」的含义）。
   *
   * @param taskId 任务 id
   * @param bearer 调用方自己的会话（**只在内存里活着**，任务表里不存）
   * @param actor 已认证的成员上下文（租户由它解析）
   */
  public void submit(UUID taskId, String bearer, AccessService.Access actor) {
    pool.submit(
        () -> {
          try {
            access.run(
                actor,
                "platform.io.read",
                () -> {
                  service.execute(taskId, bearer, actor.userId());
                  return null;
                });
          } catch (RuntimeException e) {
            // 执行器自己炸了（不该发生）：记日志，让任务停在 PENDING，
            // 由恢复扫描把它标成「执行中断」。
            log.error("导入导出任务 {} 执行失败", taskId, e);
          }
        });
  }

  /**
   * 把**卡住**的任务标成失败（执行中断）。
   *
   * <p>🔴 **为什么不能自动重试**：重试要调用方的 bearer，而任务表里不存凭据
   * （存凭据 = 把 secret 落库）。⇒ 卡住的任务只能如实标「执行中断」，由人重新发起。
   *
   * @param stuckBefore 这个时刻之前还在 RUNNING 的任务算卡住
   * @return 被标失败的任务数
   */
  public int failStuck(java.time.OffsetDateTime stuckBefore) {
    return service.failStuck(stuckBefore);
  }

  /** 关停线程池（应用停止时）。 */
  @PreDestroy
  public void shutdown() {
    pool.shutdownNow();
  }
}
