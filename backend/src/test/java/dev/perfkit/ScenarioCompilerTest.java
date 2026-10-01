package dev.perfkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ScenarioCompilerTest {
  @Test
  void compilesRequestExtractAndAssert() throws Exception {
    String json = """
        {"steps":[{"id":"login","request":{"method":"POST","path":"/login","body":{"u":"{{user}}"}},
          "extract":[{"var":"token","path":"$.token"}],"assert":[{"type":"status","equals":200}]}]}
        """;
    String js = new ScenarioCompiler().compile(new ObjectMapper().readTree(json), null);
    assertTrue(js.contains("http.request(\"POST\""));
    assertTrue(js.contains("vars[\"token\"] = jp(res, \"$.token\")"));
    assertTrue(js.contains("http_req_duration{step:login}"));
    assertTrue(js.contains("${vars[\\\"user\\\"] ?? ''}") || js.contains("vars[\"user\"]"));
  }
}
