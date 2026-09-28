package com.starcon.tenancy;

import com.fasterxml.uuid.Generators;
import java.util.UUID;

/** Application-side UUIDv7, supported by PostgreSQL 16's uuid column. */
public final class Ids {
  private static final com.fasterxml.uuid.impl.TimeBasedEpochGenerator GENERATOR =
      Generators.timeBasedEpochGenerator();

  private Ids() {}

  public static UUID next() {
    return GENERATOR.generate();
  }
}
