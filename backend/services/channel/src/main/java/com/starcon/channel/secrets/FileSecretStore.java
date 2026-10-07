package com.starcon.channel.secrets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文件型密钥管理器落点。
 *
 * <p>生产环境应替换为 Vault / K8s External Secrets 的实现（AGENTS.md：第三方 key 一律走 secret
 * manager）。这个实现保证在替换之前，令牌<b>确实</b>落在数据库之外的文件系统里而不是被
 * 顺手写进某张表 —— 边界是真的，只是落点可换。
 *
 * <p>文件权限固定 0600，目录 0700：令牌文件不该被同机器上的其他用户读到。
 */
@Component
public class FileSecretStore implements SecretStore {

  private final Path root;
  private final String provider;

  public FileSecretStore(@Value("${CHANNEL_SECRET_STORE_DIR:./.starcon-secrets}") String dir) {
    this.root = Path.of(dir).toAbsolutePath().normalize();
    this.provider = "file://" + this.root;
    try {
      Files.createDirectories(this.root);
      restrict(this.root, true);
    } catch (IOException e) {
      throw new UncheckedIOException("无法创建密钥存储目录：" + this.root, e);
    }
  }

  @Override
  public String provider() {
    return provider;
  }

  @Override
  public String write(String secretPath, String secret) {
    Path file = resolve(secretPath);
    try {
      Files.createDirectories(file.getParent());
      restrict(file.getParent(), true);
      // 原子覆盖：重新授权中途失败不会把上一份可用令牌写成半截
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, secret, StandardCharsets.UTF_8);
      restrict(tmp, false);
      Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      restrict(file, false);
      return reference(secretPath);
    } catch (IOException e) {
      throw new UncheckedIOException("令牌写入密钥管理器失败", e);
    }
  }

  @Override
  public String reference(String secretPath) {
    return provider + "/" + normalize(secretPath);
  }

  /** 把逻辑路径限制在该目录内：路径来自请求，绝不允许写到目录之外。 */
  private Path resolve(String secretPath) {
    Path file = root.resolve(normalize(secretPath)).normalize();
    if (!file.startsWith(root))
      throw new IllegalArgumentException("密钥路径越界：" + secretPath);
    return file;
  }

  private static String normalize(String secretPath) {
    String cleaned = secretPath.replaceAll("[^A-Za-z0-9._/-]", "_");
    while (cleaned.contains("..")) cleaned = cleaned.replace("..", "_");
    return cleaned.startsWith("/") ? cleaned.substring(1) : cleaned;
  }

  private static void restrict(Path path, boolean directory) {
    try {
      Set<PosixFilePermission> owner = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      if (directory) owner.add(PosixFilePermission.OWNER_EXECUTE);
      Files.setPosixFilePermissions(path, owner);
    } catch (IOException | UnsupportedOperationException ignored) {
      // 非 POSIX 文件系统（部分容器挂载）上退化为 JVM 默认权限，不因此让授权流程失败
    }
  }

  @Override
  public String toString() {
    return "FileSecretStore[" + provider.toLowerCase(Locale.ROOT).replace("file://", "") + "]";
  }
}
