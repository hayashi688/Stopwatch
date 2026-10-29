package jp.stopwatch;

import java.time.*;
import jp.stopwatch.Models.WorkRecord;

public final class TimeMath {
  public static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

  private TimeMath() {}

  public static long manual(Integer hours, Integer minutes) {
    if (hours == null || minutes == null || hours < 0 || hours > 24 || minutes < 0 || minutes > 59)
      throw new IllegalArgumentException("時間は0〜24、分は0〜59の整数で入力してください。");
    long total = (hours * 60L + minutes) * 60_000;
    if (total <= 0 || total > 86_400_000)
      throw new IllegalArgumentException("作業時間は1分以上24時間以内で入力してください。");
    return total;
  }

  public static long duration(Instant start, Instant end) {
    if (start == null || end == null || !end.isAfter(start))
      throw new IllegalArgumentException("終了日時は開始日時より後にしてください。");
    try {
      long ms = Duration.between(start, end).toMillis();
      if (ms <= 0) throw new IllegalArgumentException("1ミリ秒以上の時間が必要です。");
      return ms;
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("日時の範囲が大きすぎます。");
    }
  }

  public static long onDay(WorkRecord r, LocalDate day) {
    if (r.recordType().equals("MANUAL")) return day.equals(r.workDate()) ? r.durationMs() : 0;
    Instant from = day.atStartOfDay(TOKYO).toInstant(),
        to = day.plusDays(1).atStartOfDay(TOKYO).toInstant();
    Instant a = r.startedAt().isAfter(from) ? r.startedAt() : from,
        b = r.endedAt().isBefore(to) ? r.endedAt() : to;
    return b.isAfter(a) ? Duration.between(a, b).toMillis() : 0;
  }
}
