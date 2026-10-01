package dev.perfkit;

import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
  private final ProjectRepo projects;

  ProjectController(ProjectRepo projects) { this.projects = projects; }

  @GetMapping public List<Project> list() { return projects.findAll(); }

  @GetMapping("/{id}")
  public Project get(@PathVariable Long id) {
    return projects.findById(id).orElseThrow(() -> new NoSuchElementException("project " + id));
  }

  @PostMapping
  public Project create(@RequestBody Project in) {
    if (in.name == null || in.name.isBlank() || in.baseUrl == null || in.baseUrl.isBlank())
      throw new IllegalArgumentException("name and baseUrl are required");
    Project p = new Project();
    p.name = in.name;
    p.baseUrl = in.baseUrl.replaceAll("/+$", "");
    return projects.save(p);
  }
}
