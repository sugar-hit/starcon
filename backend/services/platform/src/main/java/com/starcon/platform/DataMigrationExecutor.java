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
 * 18.1.3.2「数据迁移」的**进程内执行器**。
 *
 * <p>蓝图：「<b>历史数据迁移必须支持试运行</b>，直接正式跑会产生大量脏数据难以回退。」
 * —— 试运行与正式迁移都要跑完整份文件，逐行判重、逐行写目标库，
 * 那是**分钟级**的事，不能占着 HTTP 请求线程。
 *
 * <p>🔴 <b>为什么是进程内线程池而不是「任务表 + 外部调度器」</b>（与 18.1.3.1 同一纪律）：
 * 迁移要经**目标服务的授权端点**写数据，而目标库按**已认证成员关系**解析租户 ——
 * 那需要调用方的会话。若把执行放到外部调度器，就必须把会话凭据存进数据库
 * （= 把 secret 落库），或者改造成服务间凭据（= 新增一套凭据体系）。
 * ⇒ 这里让**调用方的 bearer 只在内存里活到这次执行结束**，任务表里**一个凭据都不存**。
 *
 * <p>🔴 <b>任务表里没有 bearer**：进程重启后任务停在 PENDING / RUNNING，
 * 恢复扫描会把它标成「执行中断」而不是偷偷重试 ——
 * 因为没有凭据就**写不进目标库**，重试也只会再失败一次。
 *
 * <p>🔴 <b>执行器自己不持有长事务</b>：它只负责调度，**每一行的事务由服务层开**
 * （见 {@code DataMigrationService.executeRun}）—— 所以「迁了 800 行后崩掉」
 * 留下的是**已推进 800 行的真实进度**，那 800 行的上游 id 也已经落库，
 * 崩掉之后**回滚得掉**。
 */
@Service
public class DataMigrationExecutor {
  private static final Logger log = LoggerFactory.getLogger(DataMigrationExecutor.class);

  /** 同时跑几个任务（迁移要翻目标库的分页，不能一个任务把线程池占满）。 */
  static final int POOL_SIZE = 2;

  private final DataMigrationService service;
  private final AccessService access;
  private final ExecutorService pool;

  public DataMigrationExecutor(DataMigrationService service, AccessService access) {
    this.service = service;
    this.access = access;
    var counter = new AtomicLong();
    ThreadFactory factory =
        runnable -> {
          var thread = new Thread(runnable, "platform-migration-" + counter.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    this.pool = Executors.newFixedThreadPool(POOL_SIZE, factory);
  }

  /**
   * 提交一次迁移（**立即返回**：这是「已受理」的含义）。
   *
   * @param runId 迁移任务 id
   * @param bearer 调用方自己的会话（**只在内存里活着**，任务表里不存）
   * @param actor 已认证的成员上下文（租户由它解析）
   */
  public void submit(UUID runId, String bearer, AccessService.Access actor) {
    pool.submit(() -> run(runId, bearer, actor, false));
  }

  /**
   * 提交一次回滚（**立即返回**）。
   *
   * <p>⚠️ 回滚也要调用方的 bearer（要写目标库的删除端点）⇒ 同一套纪律。
   */
  public void submitRollback(UUID runId, String bearer, AccessService.Access actor) {
    pool.submit(() -> run(runId, bearer, actor, true));
  }

  /**
   * 在租户事务里跑一段工作。
   *
   * <p>🔴 <b>可见性栅栏</b>：发起请求的那笔事务持有任务级 advisory 锁
   * （见 {@code DataMigrationService.createRun}）；这里取同一把锁 ——
   * <b>拿到锁就意味着那笔事务已经提交</b>，任务行一定看得见。
   * ⇒ 「任务凭空消失」的时序 bug 被消除，而且**不靠 sleep 轮询**。
   *
   * <p>⚠️ <b>真正的「执行」只做一次</b>：认领由服务层的条件更新
   * （{@code status='PENDING'}）原子地完成，重复调用不会跑两遍。
   */
  private void run(UUID runId, String bearer, AccessService.Access actor, boolean rollback) {
    var what = rollback ? "回滚任务" : "迁移任务";
    try {
      access.run(
          actor,
          "platform.migration.read",
          () -> {
            service.awaitVisible(runId);
            return null;
          });
      if (rollback) {
        service.executeRollback(runId, bearer, actor);
      } else {
        service.executeRun(runId, bearer, actor);
      }
    } catch (RuntimeException e) {
      log.error("{} {} 执行失败", what, runId, e);
      try {
        if (rollback) {
          service.markRollbackFailed(runId, actor, message(e));
        } else {
          service.markRunFailed(runId, actor, message(e));
        }
      } catch (RuntimeException ignored) {
        log.error("{} {} 连标记失败都没成功", what, runId, ignored);
      }
    }
  }

  private static String message(RuntimeException e) {
    var message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  /**
   * 把**卡住**的任务标成失败（执行中断）。
   *
   * <p>🔴 **为什么不能自动重试**：重试要调用方的 bearer，而任务表里不存凭据。
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
