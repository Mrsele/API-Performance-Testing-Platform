package dev.perfkit;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class TestRun {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  public Long scenarioId;
  public String status;               // QUEUED, RUNNING, PASSED, FAILED_THRESHOLDS, ERROR, CANCELLED
  public Boolean passed;
  @JsonRawValue @Column(columnDefinition = "text") public String config;  // {load, thresholds}
  @JsonRawValue @Column(columnDefinition = "text") public String stats;   // derived metrics
  @JsonIgnore @Column(columnDefinition = "text") public String summary;   // raw k6 summary
  @Column(columnDefinition = "text") public String logTail;
  public Instant createdAt = Instant.now();
  public Instant startedAt;
  public Instant finishedAt;
}
