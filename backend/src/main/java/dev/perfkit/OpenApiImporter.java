package dev.perfkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.util.HashSet;
import java.util.Set;

/** Turns an OpenAPI 3.x spec (JSON or YAML) into a draft scenario: one step per operation. */
public class OpenApiImporter {
  public record Draft(String title, ObjectNode definition) {}

  private static final ObjectMapper M = new ObjectMapper();

  public static Draft parse(String spec) {
    var res = new OpenAPIV3Parser().readContents(spec, null, null);
    OpenAPI api = res.getOpenAPI();
    if (api == null || api.getPaths() == null) {
      throw new IllegalArgumentException("Could not parse OpenAPI spec: " + res.getMessages());
    }
    ObjectNode def = M.createObjectNode();
    ArrayNode steps = def.putArray("steps");
    Set<String> used = new HashSet<>();

    api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
      String base = op.getOperationId() != null ? op.getOperationId() : method.name().toLowerCase() + "_" + path;
      String id = base.replaceAll("[^A-Za-z0-9_-]", "_");
      String uniq = id;
      int i = 2;
      while (!used.add(uniq)) uniq = id + "_" + i++;

      int code = 200;
      if (op.getResponses() != null) {
        for (String k : op.getResponses().keySet()) {
          if (k.matches("2\\d\\d")) { code = Integer.parseInt(k); break; }
        }
      }
      ObjectNode step = steps.addObject();
      step.put("id", uniq);
      ObjectNode req = step.putObject("request");
      req.put("method", method.name());
      req.put("path", path.replaceAll("\\{([^}]+)}", "{{$1}}")); // path params become variables
      step.putArray("assert").addObject().put("type", "status").put("equals", code);
      steps.addObject().putObject("think").put("min", 1).put("max", 3);
    }));

    String title = api.getInfo() != null && api.getInfo().getTitle() != null ? api.getInfo().getTitle() : "API";
    return new Draft("Imported: " + title, def);
  }
}
