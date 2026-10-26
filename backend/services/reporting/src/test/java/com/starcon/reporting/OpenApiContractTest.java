package com.starcon.reporting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 导出真实的 SpringDoc 契约，不碰数据库也不开监听端口。
 *
 * <p>与 identity / catalog / channel / warehouse / trade / crm / finance 七个服务的同名测试逐字同构。
 *
 * <p>⚠️ **本文件与 {@code tools/codegen/catalog-contracts.py} 的 {@code SERVICES} 里的 {@code ('reporting',
 * 8088)} 必须在同一个 commit 里**： {@code load_specs()} 会**无条件**读 {@code
 * backend/target/openapi/reporting.openapi.json}，而那个文件正是本测试导出的。 少了任何一边，合并后 {@code pnpm
 * contracts:generate} 会直接崩 —— 而且它看起来像别人弄坏了你的东西。
 */
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
      "INTERNAL_KEY=contract-test",
      "IDENTITY_URL=http://localhost:8081"
    })
@AutoConfigureMockMvc
class OpenApiContractTest {
  @MockBean DSLContext sql;
  @MockBean PlatformTransactionManager transactions;
  @Autowired MockMvc http;

  @Test
  void exportContract() throws Exception {
    var response =
        http.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse();
    var directory = backendRoot().resolve("target/openapi");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("reporting.openapi.json"), response.getContentAsString());
  }

  /** tools/codegen/catalog-contracts.py --spec-dir 的共享快照目录。 */
  private static Path backendRoot() {
    for (var current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        current != null;
        current = current.getParent()) {
      if (Files.isDirectory(current.resolve("services"))
          && Files.isRegularFile(current.resolve("pom.xml"))) return current;
    }
    throw new IllegalStateException(
        "Backend root not reachable from " + System.getProperty("user.dir"));
  }
}
