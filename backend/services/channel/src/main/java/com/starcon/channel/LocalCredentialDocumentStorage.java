package com.starcon.channel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link CredentialDocumentStorage} 的本地文件系统实现。
 *
 * <p>开发与测试环境用它；上生产换对象存储实现类即可。它是**真的**写盘、真读回 ——
 * 核验要把文件读回来重算 sha256，这条链路必须是真的。
 *
 * <p>根目录来自 {@code CREDENTIAL_STORAGE_ROOT}，**不给绝对路径默认值**：
 * 给死一个项目外的绝对路径会让开发机与容器的行为不一致（容器里那个目录不存在，
 * 于是每次上传都在别的机器上悄悄成功、当前机器上失败）。相对路径按进程工作目录解析，
 * 部署时由 env 指到挂载卷。
 *
 * <p>🔴 路径穿越防护：键先做归一化，再校验解析结果确实落在根目录内。少了这一步，
 * {@code credentials/a/../../etc/passwd} 这种键能把文件写到根目录之外 —— 键是调用方给的，
 * 而键来自上传请求。
 *
 * <p>🔴 本类**不删文件**：凭证是争议时的唯一证据，删文件这件事本模块没有业务场景
 * （文件版本只追加，凭证不提供物理删除）。
 */
@Component
public class LocalCredentialDocumentStorage implements CredentialDocumentStorage {

  private static final Logger log = LoggerFactory.getLogger(LocalCredentialDocumentStorage.class);

  private final Path root;

  public LocalCredentialDocumentStorage(
      @Value("${CREDENTIAL_STORAGE_ROOT:target/credential-store}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
    try {
      Files.createDirectories(this.root);
    } catch (IOException e) {
      throw new IllegalStateException("无法创建授权凭证存储根目录：" + this.root, e);
    }
    log.info("授权凭证存储根目录：{}", this.root);
  }

  /** 根目录（供健康检查与概览展示，不把绝对路径暴露给前端）。 */
  public Path root() {
    return root;
  }

  @Override
  public long put(String key, byte[] content) throws IOException {
    Path target = resolve(key);
    Files.createDirectories(target.getParent());
    Files.write(target, content);
    return content.length;
  }

  @Override
  public byte[] read(String key) throws IOException {
    return Files.readAllBytes(resolve(key));
  }

  @Override
  public boolean exists(String key) {
    try {
      return Files.isRegularFile(resolve(key));
    } catch (IllegalArgumentException e) {
      // 键本身不合法（越权路径）：当作「不存在」，而不是把一个 500 抛给运维看。
      return false;
    }
  }

  @Override
  public Optional<Long> size(String key) {
    try {
      Path target = resolve(key);
      return Files.isRegularFile(target) ? Optional.of(Files.size(target)) : Optional.empty();
    } catch (IOException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /**
   * 把键解析成根目录下的真实路径，并拒绝任何越出根目录的结果。
   *
   * <p>{@code normalize()} 之后 {@code startsWith(root)} 才是有意义的比较：
   * 不先归一化的话 {@code credentials/a/../b} 这种键能通过前缀检查却写到别处。
   */
  private Path resolve(String key) {
    Path target = root.resolve(key).normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException("非法的授权凭证存储键：" + key);
    }
    return target;
  }
}
