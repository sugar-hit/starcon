package com.starcon.identity;

/**
 * 1.4.2.1 语言与时区 —— 本模块自己的「说清原因」的 400。
 *
 * <p><b>为什么不直接用 {@code ResponseStatusException}</b>：identity 的全局
 * {@code ApiErrorHandler} 只处理 {@code DataAccessException} 与
 * {@code IllegalArgumentException}，{@code ResponseStatusException} 会走
 * {@code sendError} —— 响应体是<b>空的</b>，浏览器那边只能看到一句
 * 「保存失败，请检查输入后重试」，本模块「为什么被拒」这句话压根到不了界面。
 *
 * <p>所以本模块抛自己的异常，并在 {@link AccountLocaleController} 里用
 * <b>控制器局部</b>的 {@code @ExceptionHandler} 把它渲染成带 {@code detail} 的
 * ProblemDetail —— <b>只影响本控制器</b>，不动全局错误处理，也就不改变其它模块
 * 既有的错误响应形状。
 */
public class AccountLocaleException extends RuntimeException {

  public AccountLocaleException(String message) {
    super(message);
  }
}
