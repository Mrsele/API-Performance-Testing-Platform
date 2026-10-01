package dev.perfkit;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RunController {
  private final K6Service k6;
  private final RunRepo runs;

  RunController(K6Service k6, RunRepo runs) { this.k6 = k6; this.runs = runs; }

  @PostMapping("/scenarios/{id}/runs")
  public TestRun start(@PathVariable Long id, @RequestBody JsonNode config) { return k6.start(id, config); }

  @GetMapping("/runs")
  public List<TestRun> list(@RequestParam(required = false) Long scenarioId) {
    return scenarioId == null ? runs.findAllByOrderByIdDesc() : runs.findByScenarioIdOrderByIdDesc(scenarioId);
  }

  @GetMapping("/runs/{id}")
  public TestRun get(@PathVariable Long id) {
    return runs.findById(id).orElseThrow(() -> new NoSuchElementException("run " + id));
  }

  @GetMapping(value = "/runs/{id}/summary", produces = "application/json")
  public String summary(@PathVariable Long id) {
    String s = get(id).summary;
    return s == null ? "{}" : s;
  }

  @PostMapping("/runs/{id}/stop")
  public TestRun stop(@PathVariable Long id) { return k6.stop(id); }

  @GetMapping("/runs/compare")
  public List<Map<String, Object>> compare(@RequestParam Long a, @RequestParam Long b) throws IOException {
    return k6.compare(a, b);
  }
}
