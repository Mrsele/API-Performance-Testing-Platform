package dev.perfkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Orchestrates a run: compile scenario -> run k6 -> parse summary -> store stats. One in-process worker for now. */
@Service
public class K6Service {
  private static final ObjectMapper M = new ObjectMapper();
  private static final Pattern STEP = Pattern.compile("^http_req_duration\\{step:(.+)}$");

  private final RunRepo runs;
  private final ScenarioRepo scenarios;
  private final ProjectRepo projects;
  private final String k6Bin;
  private final String workDir;
  private final Map<Long, Process> live = new ConcurrentHashMap<>();
  private final ExecutorService pool = Executors.newFixedThreadPool(2);

  K6Service(RunRepo runs, ScenarioRepo scenarios, ProjectRepo projects,
            @Value("${perf.k6-bin}") String k6Bin, @Value("${perf.work-dir}") String workDir) {
    this.runs = runs; this.scenarios = scenarios; this.projects = projects;
    this.k6Bin = k6Bin; this.workDir = workDir;
  }

  public TestRun start(Long scenarioId, JsonNode config) {
    scenarios.findById(scenarioId).orElseThrow(() -> new NoSuchElementException("scenario " + scenarioId));
    TestRun r = new TestRun();
    r.scenarioId = scenarioId;
    r.status = "QUEUED";
    r.config = config == null ? "{}" : config.toString();
    Long id = runs.save(r).id;
    pool.submit(() -> execute(id));
    return runs.findById(id).orElseThrow();
  }

  public TestRun stop(Long id) {
    TestRun r = runs.findById(id).orElseThrow(() -> new NoSuchElementException("run " + id));
    if ("QUEUED".equals(r.status) || "RUNNING".equals(r.status)) {
      r.status = "CANCELLED";
      runs.save(r);
      Process p = live.get(id);
      if (p != null) p.destroy();
    }
    return r;
  }

  public String preview(Long scenarioId, JsonNode config) throws IOException {
    Scenario sc = scenarios.findById(scenarioId).orElseThrow(() -> new NoSuchElementException("scenario " + scenarioId));
    return new ScenarioCompiler().compile(M.readTree(sc.definition), config);
  }

  private void execute(Long id) {
    TestRun r = runs.findById(id).orElseThrow();
    if ("CANCELLED".equals(r.status)) return;
    Path dir = Path.of(workDir, "run-" + id);
    try {
      Scenario sc = scenarios.findById(r.scenarioId).orElseThrow();
      Project p = projects.findById(sc.projectId).orElseThrow();
      String script = new ScenarioCompiler().compile(M.readTree(sc.definition), M.readTree(r.config));
      Files.createDirectories(dir);
      Path js = dir.resolve("script.js");
      Path summary = dir.resolve("summary.json");
      Path log = dir.resolve("k6.log");
      Files.writeString(js, script);

      r.status = "RUNNING";
      r.startedAt = Instant.now();
      runs.save(r);

      ProcessBuilder pb = new ProcessBuilder(k6Bin, "run", "--quiet", "--no-color", js.toString());
      pb.environment().put("BASE_URL", p.baseUrl);
      pb.environment().put("SUMMARY_PATH", summary.toString());
      pb.redirectErrorStream(true);
      pb.redirectOutput(log.toFile());
      Process proc = pb.start();
      live.put(id, proc);
      int code = proc.waitFor();
      live.remove(id);

      r = runs.findById(id).orElseThrow();
      r.logTail = tail(log, 6000);
      if (!"CANCELLED".equals(r.status)) {
        if (Files.exists(summary)) {
          r.summary = Files.readString(summary);
          r.stats = M.writeValueAsString(extractStats(M.readTree(r.summary)));
          // k6 exit codes: 0 ok, 99 thresholds crossed, anything else = error
          r.status = code == 0 ? "PASSED" : code == 99 ? "FAILED_THRESHOLDS" : "ERROR";
          r.passed = code == 0;
        } else {
          r.status = "ERROR";
        }
      }
      r.finishedAt = Instant.now();
      runs.save(r);
    } catch (Exception e) {
      live.remove(id);
      r = runs.findById(id).orElse(r);
      r.status = "ERROR";
      r.logTail = String.valueOf(e);
      r.finishedAt = Instant.now();
      runs.save(r);
    }
  }

  private static String tail(Path p, int max) throws IOException {
    if (!Files.exists(p)) return "";
    String s = Files.readString(p);
    return s.length() > max ? s.substring(s.length() - max) : s;
  }

  // ---- summary -> stats ----------------------------------------------------------------------

  private static JsonNode vals(JsonNode metrics, String name) {
    JsonNode x = metrics.path(name);
    return x.has("values") ? x.get("values") : x;
  }

  private static double n(JsonNode node, String key) {
    return node.path(key).asDouble(0);
  }

  static ObjectNode extractStats(JsonNode summary) {
    JsonNode m = summary.path("metrics");
    ObjectNode s = M.createObjectNode();
    JsonNode d = vals(m, "http_req_duration");
    s.put("avg", n(d, "avg")).put("min", n(d, "min")).put("max", n(d, "max"))
        .put("p50", n(d, "med")).put("p90", n(d, "p(90)")).put("p95", n(d, "p(95)")).put("p99", n(d, "p(99)"));
    JsonNode reqs = vals(m, "http_reqs");
    s.put("requests", n(reqs, "count")).put("rps", n(reqs, "rate"));
    s.put("errorRate", n(vals(m, "http_req_failed"), "rate"));
    s.put("iterations", n(vals(m, "iterations"), "count"));
    s.put("vusMax", n(vals(m, "vus_max"), "max"));

    List<ObjectNode> eps = new ArrayList<>();
    m.fields().forEachRemaining(e -> {
      Matcher mm = STEP.matcher(e.getKey());
      if (!mm.matches()) return;
      String step = mm.group(1);
      JsonNode dv = vals(m, e.getKey());
      ObjectNode ep = M.createObjectNode();
      ep.put("step", step).put("avg", n(dv, "avg")).put("p95", n(dv, "p(95)")).put("p99", n(dv, "p(99)"));
      ep.put("count", n(vals(m, "http_reqs{step:" + step + "}"), "count"));
      ep.put("errorRate", n(vals(m, "http_req_failed{step:" + step + "}"), "rate"));
      eps.add(ep);
    });
    eps.sort((a, b) -> Double.compare(b.get("p95").asDouble(), a.get("p95").asDouble()));
    ArrayNode epArr = s.putArray("endpoints");
    eps.forEach(epArr::add);

    ArrayNode ths = s.putArray("thresholds");
    m.fields().forEachRemaining(e -> e.getValue().path("thresholds").fields().forEachRemaining(x -> {
      String expr = x.getKey();
      if (expr.equals("max>=0") || expr.equals("rate>=0") || expr.equals("count>=0")) return; // helper thresholds
      boolean ok = x.getValue().isBoolean() ? x.getValue().asBoolean() : x.getValue().path("ok").asBoolean(true);
      ths.addObject().put("metric", e.getKey()).put("expression", expr).put("ok", ok);
    }));
    return s;
  }

  // ---- comparison -----------------------------------------------------------------------------

  public List<Map<String, Object>> compare(Long a, Long b) throws IOException {
    JsonNode sa = M.readTree(stats(a)), sb = M.readTree(stats(b));
    String[][] defs = {{"p50", "lower"}, {"p95", "lower"}, {"p99", "lower"}, {"avg", "lower"}, {"errorRate", "lower"}, {"rps", "higher"}};
    double tolerance = 5.0; // percent
    List<Map<String, Object>> out = new ArrayList<>();
    for (String[] d : defs) {
      double va = sa.path(d[0]).asDouble(), vb = sb.path(d[0]).asDouble();
      double pct = va == 0 ? (vb == 0 ? 0 : 100) : (vb - va) / va * 100;
      boolean lowerBetter = d[1].equals("lower");
      String verdict = Math.abs(pct) <= tolerance ? "unchanged"
          : (pct > 0) == lowerBetter ? "regressed" : "improved";
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("metric", d[0]); row.put("a", va); row.put("b", vb);
      row.put("deltaPct", Math.round(pct * 10) / 10.0); row.put("verdict", verdict);
      out.add(row);
    }
    return out;
  }

  private String stats(Long id) {
    TestRun r = runs.findById(id).orElseThrow(() -> new NoSuchElementException("run " + id));
    if (r.stats == null) throw new IllegalArgumentException("run " + id + " has no results yet");
    return r.stats;
  }
}
