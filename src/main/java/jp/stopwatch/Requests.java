package jp.stopwatch;

import jakarta.validation.constraints.*;
import java.time.*;

public final class Requests {
  private Requests() {}

  public record GroupInput(@NotBlank @Size(max = 100) String name) {}

  public record ItemInput(@NotBlank @Size(max = 200) String name, Long groupId) {}

  public record Start(@NotNull @Positive Long workItemId) {}

  public record SessionInput(@NotNull @Positive Long sessionId) {}

  public record Manual(
      @NotNull @Positive Long workItemId,
      @NotNull LocalDate workDate,
      @NotNull @Min(0) @Max(24) Integer hours,
      @NotNull @Min(0) @Max(59) Integer minutes,
      @Size(max = 10000) String memo) {}

  public record EditRecord(
      Long workItemId,
      LocalDate workDate,
      Integer hours,
      Integer minutes,
      Instant startedAt,
      Instant endedAt,
      @Size(max = 10000) String memo) {}
}
