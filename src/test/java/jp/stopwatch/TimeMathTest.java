package jp.stopwatch;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import jp.stopwatch.Models.WorkRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TimeMathTest {
  @Test
  void manualConversionAndLimit() {
    assertEquals(5_400_000, TimeMath.manual(1, 30));
    assertEquals(86_400_000, TimeMath.manual(24, 0));
  }

  @ParameterizedTest
  @CsvSource({"0,0", "-1,30", "1,60", "24,1", "25,0"})
  void rejectsManual(int h, int m) {
    assertThrows(IllegalArgumentException.class, () -> TimeMath.manual(h, m));
  }

  @Test
  void nullAndOrdering() {
    assertThrows(IllegalArgumentException.class, () -> TimeMath.manual(null, 0));
    assertThrows(
        IllegalArgumentException.class, () -> TimeMath.duration(Instant.EPOCH, Instant.EPOCH));
    assertThrows(
        IllegalArgumentException.class,
        () -> TimeMath.duration(Instant.EPOCH, Instant.EPOCH.minusSeconds(1)));
  }

  @Test
  void distributesTokyoMidnightExactly() {
    var r =
        new WorkRecord(
            1,
            1,
            1L,
            "TIMER",
            null,
            Instant.parse("2026-10-29T14:30:00Z"),
            Instant.parse("2026-10-29T15:30:00.123Z"),
            3_600_123,
            null,
            "設計",
            null,
            null);
    assertEquals(1_800_000, TimeMath.onDay(r, LocalDate.of(2026, 10, 29)));
    assertEquals(1_800_123, TimeMath.onDay(r, LocalDate.of(2026, 10, 30)));
    assertEquals(0, TimeMath.onDay(r, LocalDate.of(2026, 10, 31)));
  }

  @Test
  void manualStaysOnAssignedDay() {
    var r =
        new WorkRecord(
            1,
            1,
            null,
            "MANUAL",
            LocalDate.of(2026, 10, 29),
            null,
            null,
            90_000,
            null,
            "作業",
            null,
            null);
    assertEquals(90_000, TimeMath.onDay(r, r.workDate()));
    assertEquals(0, TimeMath.onDay(r, r.workDate().plusDays(1)));
  }

  @Test
  void multiDayInterval() {
    var start = Instant.parse("2026-10-28T15:00:00Z");
    var r =
        new WorkRecord(
            1,
            1,
            1L,
            "TIMER",
            null,
            start,
            start.plusSeconds(3 * 86400),
            259_200_000,
            null,
            "作業",
            null,
            null);
    assertEquals(86_400_000, TimeMath.onDay(r, LocalDate.of(2026, 10, 30)));
  }
}
