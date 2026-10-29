package jp.stopwatch;

import java.time.*;

public final class Models {
  private Models() {}

  public record Group(long id, String name) {}

  public record Item(long id, Long groupId, String name, boolean archived, String groupName) {}

  public record Session(
      long id, long workItemId, Instant startedAt, Instant endedAt, String status) {}

  public record WorkRecord(
      long id,
      long workItemId,
      Long timerSessionId,
      String recordType,
      LocalDate workDate,
      Instant startedAt,
      Instant endedAt,
      long durationMs,
      String memo,
      String workItemName,
      Long groupId,
      String groupName) {}

  public record TimerStatus(
      String status,
      Long sessionId,
      Long workItemId,
      String workItemName,
      Instant startedAt,
      Instant serverNow) {}

  public record Total(Long id, String name, long durationMs) {}

  public record DailySummary(
      LocalDate date,
      long durationMs,
      java.util.List<Total> groups,
      java.util.List<Total> workItems) {}
}
