package dev.perfkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles the scenario JSON DSL + run config ({load, thresholds}) into a k6 script.
 * The UI edits JSON only; k6 is an implementation detail behind this class.
 * Not thread-safe: create one instance per compile.
 */
public class ScenarioCompiler {
  private static final ObjectMapper M = new ObjectMapper();
  private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([\\w.\\-]+)\\s*}}");
  private int counter = 0;

  private static final String PREAMBLE = """
      import http from 'k6/http';
      import { check, sleep, fail } from 'k6';

      const BASE_URL = __ENV.BASE_URL;

      function qs(o) {
        const p = Object.keys(o).map(k => encodeURIComponent(k) + '=' + encodeURIComponent(o[k]));
        return p.length ? '?' + p.join('&') : '';
      }

      function jp(res, path) {
        try {
          let cur = res.json();
          const parts = path.replace(/^\\$\\.?/, '').split(/\\.|\\[(\\d+)\\]/).filter(p => p !== undefined && p !== '');
          for (const p of parts) { if (cur === null || cur === undefined) return undefined; cur = cur[p]; }
          return cur;
        } catch (e) { return undefined; }
      }
      """;

  public String compile(JsonNode scenario, JsonNode runConfig) {
    counter = 0;
    Set<String> names = new LinkedHashSet<>();
    StringBuilder body = new StringBuilder();
    steps(scenario.path("steps"), body, 2, 0, names);

    ObjectNode options = M.createObjectNode();
    JsonNode load = runConfig == null ? null : runConfig.get("load");
    options.putObject("scenarios").set("main", load != null && load.isObject() ? load : defaultLoad());

    ObjectNode th = options.putObject("thresholds");
    JsonNode userTh = runConfig == null ? null : runConfig.get("thresholds");
    if (userTh != null && userTh.isObject()) {
      userTh.fields().forEachRemaining(e -> {
        ArrayNode arr = th.putArray(e.getKey());
        if (e.getValue().isArray()) e.getValue().forEach(x -> arr.add(x.asText()));
        else arr.add(e.getValue().asText());
      });
    }
    // Always-true thresholds make k6 emit per-step sub-metrics in the summary (per-endpoint stats).
    for (String n : names) {
      th.putArray("http_req_duration{step:" + n + "}").add("max>=0");
      th.putArray("http_req_failed{step:" + n + "}").add("rate>=0");
      th.putArray("http_reqs{step:" + n + "}").add("count>=0");
    }
    options.putArray("summaryTrendStats").add("avg").add("min").add("med").add("max").add("p(90)").add("p(95)").add("p(99)");

    try {
      return PREAMBLE
          + "\nexport const options = " + M.writerWithDefaultPrettyPrinter().writeValueAsString(options) + ";\n\n"
          + "export default function () {\n  const vars = {};\n" + body + "}\n\n"
          + "export function handleSummary(data) {\n  return { [__ENV.SUMMARY_PATH]: JSON.stringify(data) };\n}\n";
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ObjectNode defaultLoad() {
    ObjectNode n = M.createObjectNode();
    n.put("executor", "constant-vus").put("vus", 1).put("duration", "10s");
    return n;
  }

  private void steps(JsonNode arr, StringBuilder o, int depth, int loopDepth, Set<String> names) {
    if (arr.isMissingNode() || arr.isNull()) return;
    if (!arr.isArray()) throw new IllegalArgumentException("'steps'/'then'/'else' must be arrays");
    String pad = "  ".repeat(depth);
    for (JsonNode s : arr) {
      if (s.has("think")) {
        JsonNode t = s.get("think");
        double min = t.isNumber() ? t.asDouble() : t.path("min").asDouble(0);
        double max = t.isNumber() ? t.asDouble() : t.path("max").asDouble(min);
        o.append(pad).append("sleep(").append(min).append(min == max ? "" : " + Math.random() * " + (max - min)).append(");\n");
      } else if (s.has("request")) {
        request(s, o, depth, names);
      } else if (s.has("if")) {
        o.append(pad).append("if (").append(cond(s.get("if"))).append(") {\n");
        steps(s.path("then"), o, depth + 1, loopDepth, names);
        if (s.has("else")) {
          o.append(pad).append("} else {\n");
          steps(s.get("else"), o, depth + 1, loopDepth, names);
        }
        o.append(pad).append("}\n");
      } else if (s.has("loop")) {
        String v = "i" + loopDepth;
        o.append(pad).append("for (let ").append(v).append(" = 0; ").append(v).append(" < ")
            .append(s.get("loop").path("times").asInt(1)).append("; ").append(v).append("++) {\n");
        steps(s.path("steps"), o, depth + 1, loopDepth + 1, names);
        o.append(pad).append("}\n");
      } else if (s.has("fail")) {
        o.append(pad).append("fail(").append(tpl(s.get("fail").asText())).append(");\n");
      } else {
        throw new IllegalArgumentException("Unknown step (expected request/think/if/loop/fail): " + s);
      }
    }
  }

  private void request(JsonNode s, StringBuilder o, int depth, Set<String> names) {
    String pad = "  ".repeat(depth), p2 = pad + "  ";
    JsonNode r = s.get("request");
    String id = (s.has("id") ? s.get("id").asText() : "step" + (++counter)).replaceAll("[^A-Za-z0-9_-]", "_");
    names.add(id);
    String method = r.path("method").asText("GET").toUpperCase();
    String path = r.path("path").asText("/");

    StringBuilder url = new StringBuilder("BASE_URL + " + tpl(path));
    if (r.has("query")) url.append(" + qs(").append(jsExpr(r.get("query"))).append(")");
    String headers = r.has("headers") ? jsExpr(r.get("headers")) : "{}";
    boolean jsonBody = r.has("body") && !r.get("body").isTextual();
    String bodyExpr = !r.has("body") ? "null"
        : jsonBody ? "JSON.stringify(" + jsExpr(r.get("body")) + ")" : tpl(r.get("body").asText());
    String ctype = jsonBody ? "{ 'Content-Type': 'application/json' }" : "{}";

    o.append(pad).append("{\n");
    o.append(p2).append("const res = http.request(").append(q(method)).append(", ").append(url).append(", ").append(bodyExpr)
        .append(", { headers: Object.assign(").append(ctype).append(", ").append(headers)
        .append("), tags: { step: ").append(q(id)).append(", name: ").append(q(method + " " + path)).append(" } });\n");

    for (JsonNode e : s.path("extract")) {
      String var = e.path("var").asText();
      if (var.isEmpty()) throw new IllegalArgumentException("extract needs 'var' in step " + id);
      String from = e.path("from").asText("json");
      String expr = switch (from) {
        case "json" -> "jp(res, " + q(e.path("path").asText("$")) + ")";
        case "header" -> "res.headers[" + q(e.path("name").asText()) + "]";
        default -> throw new IllegalArgumentException("Unsupported extract source: " + from);
      };
      o.append(p2).append("vars[").append(q(var)).append("] = ").append(expr).append(";\n");
    }

    List<String> checks = new ArrayList<>();
    for (JsonNode a : s.path("assert")) {
      String type = a.path("type").asText();
      switch (type) {
        case "status" -> {
          int code = a.path("equals").asInt(200);
          checks.add(q(id + " status is " + code) + ": (r) => r.status === " + code);
        }
        case "maxDuration" -> {
          int ms = a.path("ms").asInt(1000);
          checks.add(q(id + " under " + ms + "ms") + ": (r) => r.timings.duration < " + ms);
        }
        case "json" -> {
          String p = a.path("path").asText("$");
          if (a.has("equals")) {
            checks.add(q(id + " " + p + " equals expected") + ": (r) => String(jp(r, " + q(p) + ")) === String(" + jsExpr(a.get("equals")) + ")");
          } else {
            checks.add(q(id + " " + p + " exists") + ": (r) => jp(r, " + q(p) + ") !== undefined");
          }
        }
        default -> throw new IllegalArgumentException("Unsupported assertion type: " + type);
      }
    }
    if (!checks.isEmpty()) {
      o.append(p2).append("const ok = check(res, { ").append(String.join(", ", checks)).append(" }, { step: ").append(q(id)).append(" });\n");
      if ("abort".equals(s.path("onFailure").asText("continue"))) {
        o.append(p2).append("if (!ok) fail(").append(q(id + " assertion failed")).append(");\n");
      }
    }
    o.append(pad).append("}\n");
  }

  private static String cond(JsonNode c) {
    if (c.isObject()) {
      return "String(vars[" + q(c.path("var").asText()) + "]) === String(" + jsExpr(c.get("equals")) + ")";
    }
    String t = c.asText().trim();
    Matcher m = VAR.matcher(t);
    return "vars[" + q(m.matches() ? m.group(1) : t) + "]";
  }

  /** String with {{var}} placeholders -> JS template literal reading from the vars object. */
  static String tpl(String s) {
    String esc = s.replace("\\", "\\\\").replace("`", "\\`").replace("${", "\\${");
    Matcher m = VAR.matcher(esc);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, Matcher.quoteReplacement("${vars[" + q(m.group(1)) + "] ?? ''}"));
    }
    m.appendTail(sb);
    return "`" + sb + "`";
  }

  private static String jsExpr(JsonNode n) {
    if (n.isTextual()) return tpl(n.asText());
    if (n.isNumber() || n.isBoolean()) return n.toString();
    if (n.isNull() || n.isMissingNode()) return "null";
    if (n.isArray()) {
      StringJoiner j = new StringJoiner(", ", "[", "]");
      n.forEach(x -> j.add(jsExpr(x)));
      return j.toString();
    }
    StringJoiner j = new StringJoiner(", ", "{", "}");
    n.fields().forEachRemaining(e -> j.add(q(e.getKey()) + ": " + jsExpr(e.getValue())));
    return j.toString();
  }

  private static String q(String s) {
    try {
      return M.writeValueAsString(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
