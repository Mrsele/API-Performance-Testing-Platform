package dev.perfkit;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class Project {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  public String name;
  public String baseUrl;
  public Instant createdAt = Instant.now();
}
