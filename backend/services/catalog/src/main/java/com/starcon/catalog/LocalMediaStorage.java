package com.starcon.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link MediaStorage} 的本地文件系统实现。
 *
 * <p>开发与测试环境用它；上生产换对象存储实现类即可（见 {@link MediaStorage} 头部）。 它是**真的**写盘、真读回，不做内存替身：失效检测要能验出「文件确实不在了」，
 * 用内存 Map 存就永远验不出 MISSING，那样这条能力等于没做。
 *
 * <p>根目录来自 {@code MEDIA_STORAGE_ROOT}，**不给绝对路径默认值**： 给死一个项目外的绝对路径会让开发机与容器的行为不一致（容器里那个目录不存在，
 * 于是每次上传都在别的机器上悄悄成功、当前机器上失败）。 相对路径按进程工作目录解析，部署时由 env 指到挂载卷。
 *
 * <p>路径穿越防护：键先做归一化，再校验解析结果确实落在根目录内。 少了这一步，{@code media/<tenant>/../../etc/passwd} 这种键能把文件写到根目录之外——
 * 键是调用方给的，而键来自上传请求。
 */
@Component
public class LocalMediaStorage implements MediaStorage {
  private static final Logger log = LoggerFactory.getLogger(LocalMediaStorage.class);

  private final Path root;

  public LocalMediaStorage(@Value("${MEDIA_STORAGE_ROOT:target/media-store}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
    try {
      Files.createDirectories(this.root);
    } catch (IOException e) {
      throw new IllegalStateException("无法创建媒体存储根目录：" + this.root, e);
    }
    log.info("媒体存储根目录：{}", this.root);
  }

  /** 根目录，供健康检查与概览展示（不暴露绝对路径给前端，只报是否可用）。 */
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
      // 键本身不合法（越权路径）：当作「不存在」，由失效检测报 MISSING，
      // 而不是把一个 500 抛给运维看。
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

  @Override
  public void delete(String key) throws IOException {
    Path target = resolve(key);
    Files.deleteIfExists(target);
  }

  /**
   * 把键解析成根目录下的真实路径，并拒绝任何越出根目录的结果。
   *
   * <p>{@code normalize()} 之后 {@code startsWith(root)} 才是有意义的比较： 不先归一化的话 {@code media/a/../b}
   * 这种键能通过前缀检查却写到别处。
   */
  private Path resolve(String key) {
    Path target = root.resolve(key).normalize();
    if (!target.startsWith(root)) throw new IllegalArgumentException("非法的媒体存储键：" + key);
    return target;
  }
}
