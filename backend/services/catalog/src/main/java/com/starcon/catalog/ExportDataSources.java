package com.starcon.catalog;

import static com.starcon.catalog.ExportDataSource.*;
import static com.starcon.catalog.ExportModels.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 导出数据源注册表：把容器里所有 {@link ExportDataSource} 收集成 {@code key → 数据源}。
 *
 * <p>这就是「多数据源注册式设计」的全部机制：**没有 switch、没有枚举白名单表**。
 * 加一个数据源只需要新增一个 {@code @Component}，Spring 注入进来就自动出现在
 * 能力声明、可选字段与执行路径里 —— 迁移、服务层与前端都不需要改。
 *
 * <p>键冲突在这里失败（构造期抛错）而不是静默取第一个：两个数据源抢同一个键时，
 * 后注册的那个会悄悄顶掉前一个，用户导出拿到的是另一个数据源的行却标着这个源的标题。
 */
@Component
public class ExportDataSources {
  private final Map<String, ExportDataSource> byKey = new LinkedHashMap<>();

  public ExportDataSources(List<ExportDataSource> sources) {
    for (var source : sources) {
      var previous = byKey.put(source.key(), source);
      if (previous != null)
        throw new IllegalStateException(
            "导出数据源键重复：" + source.key() + "（" + previous.label() + " 与 " + source.label() + "）");
    }
  }

  /**
   * 按键取数据源。
   *
   * <p>键不存在 → 404（前端据此提示「该数据源不存在」，而不是抛一个泛化的 500）。
   * 存在但 {@code available=false} → 422 并写明缺哪个上游模块。
   */
  public ExportDataSource require(String key) {
    if (key == null || key.isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要导出的数据源");
    var source = byKey.get(key.trim().toUpperCase());
    if (source == null)
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "未知的数据源：" + key + "（可用：" + String.join("、", keys()) + "）");
    if (!source.available())
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "「" + source.label() + "」的上游列表模块尚未交付，暂时无法导出：" + source.unavailableNote());
    return source;
  }

  /** 按键查但不要求可用 —— 能力声明要用它把未交付的数据源也列出来并说明原因。 */
  public ExportDataSource find(String key) {
    return key == null ? null : byKey.get(key.trim().toUpperCase());
  }

  /** 按键查字段白名单；键不存在返回空列表（服务层据此报 422）。 */
  public List<ExportColumn> columnsOf(String key) {
    var source = find(key);
    return source == null ? List.of() : source.columns();
  }

  /** 按键查排序白名单；键不存在返回空列表。 */
  public List<ExportSort> sortsOf(String key) {
    var source = find(key);
    return source == null ? List.of() : source.sorts();
  }

  public List<String> keys() {
    return List.copyOf(byKey.keySet());
  }

  public List<ExportDataSource> all() {
    return List.copyOf(byKey.values());
  }
}