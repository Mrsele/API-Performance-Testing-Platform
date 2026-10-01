package dev.perfkit;

import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class Scenario {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  public Long projectId;
  public String name;
  @JsonRawValue @Column(columnDefinition = "text") public String definition; // scenario DSL (JSON)
  public Instant createdAt = Instant.now();
}
