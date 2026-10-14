package com.starcon.trade;

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
 * <p>与 identity / catalog / channel / warehouse 四个服务的同名测试逐字同构； trade 的卷 09 此前没有任何端点，因此也没有这份契约导出，
 * {@code tools/codegen/catalog-contracts.py} 的服务清单里也就没有 trade。 9.3.2.2 是 trade 的第一条业务端点，随它一起补上。
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
    Files.writeString(directory.resolve("trade.openapi.json"), response.getContentAsString());
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
