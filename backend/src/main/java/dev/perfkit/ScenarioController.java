package dev.perfkit;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ScenarioController {
  public record ScenarioIn(String name, JsonNode definition) {}

  private final ScenarioRepo scenarios;
  private final ProjectRepo projects;
  private final K6Service k6;

  ScenarioController(ScenarioRepo scenarios, ProjectRepo projects, K6Service k6) {
    this.scenarios = scenarios; this.projects = projects; this.k6 = k6;
  }

  @GetMapping("/projects/{pid}/scenarios")
  public List<Scenario> list(@PathVariable Long pid) { return scenarios.findByProjectIdOrderByIdDesc(pid); }

  @GetMapping("/scenarios/{id}")
  public Scenario get(@PathVariable Long id) {
    return scenarios.findById(id).orElseThrow(() -> new NoSuchElementException("scenario " + id));
  }

  @PostMapping("/projects/{pid}/scenarios")
  public Scenario create(@PathVariable Long pid, @RequestBody ScenarioIn in) {
    projects.findById(pid).orElseThrow(() -> new NoSuchElementException("project " + pid));
    Scenario s = new Scenario();
    s.projectId = pid;
    return apply(s, in);
  }

  @PutMapping("/scenarios/{id}")
  public Scenario update(@PathVariable Long id, @RequestBody ScenarioIn in) { return apply(get(id), in); }

  private Scenario apply(Scenario s, ScenarioIn in) {
    if (in.definition() == null) throw new IllegalArgumentException("definition is required");
    new ScenarioCompiler().compile(in.definition(), null); // validate DSL on save
    s.name = in.name() == null || in.name().isBlank() ? "Untitled" : in.name();
    s.definition = in.definition().toString();
    return scenarios.save(s);
  }

  /** Preview the generated k6 script for a scenario + run config. */
  @PostMapping(value = "/scenarios/{id}/compile", produces = MediaType.TEXT_PLAIN_VALUE)
  public String compile(@PathVariable Long id, @RequestBody JsonNode config) throws IOException {
    return k6.preview(id, config);
  }

  @PostMapping("/projects/{pid}/import-openapi")
  public Scenario importOpenApi(@PathVariable Long pid, @RequestBody String spec) {
    projects.findById(pid).orElseThrow(() -> new NoSuchElementException("project " + pid));
    OpenApiImporter.Draft d = OpenApiImporter.parse(spec);
    return apply(newScenario(pid), new ScenarioIn(d.title(), d.definition()));
  }

  private Scenario newScenario(Long pid) {
    Scenario s = new Scenario();
    s.projectId = pid;
    return s;
  }
}
