package dev.perfkit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectRepo extends JpaRepository<Project, Long> {}

interface ScenarioRepo extends JpaRepository<Scenario, Long> {
  List<Scenario> findByProjectIdOrderByIdDesc(Long projectId);
}

interface RunRepo extends JpaRepository<TestRun, Long> {
  List<TestRun> findByScenarioIdOrderByIdDesc(Long scenarioId);
  List<TestRun> findAllByOrderByIdDesc();
}
