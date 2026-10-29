package jp.stopwatch;

import static jp.stopwatch.Models.*;
import static jp.stopwatch.Requests.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class WorkService {
  private final WorkDao dao;
  private final Clock clock;

  public WorkService(WorkDao dao, Clock clock) {
    this.dao = dao;
    this.clock = clock;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private void exists(boolean yes) {
    if (!yes) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "対象のデータが見つかりません。");
  }

  private void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private Item item(long id, boolean active) {
    Item i = dao.item(id);
    exists(i != null);
    if (active && i.archived()) conflict("アーカイブ済みの作業項目は選択できません。");
    return i;
  }

  private void group(Long id) {
    if (id != null) exists(dao.groupExists(id));
  }

  public List<Group> groups() {
    return dao.groups();
  }

  public Group addGroup(GroupInput r) {
    dao.lock();
    String name = r.name().strip();
    long id = dao.createGroup(name);
    return new Group(id, name);
  }

  public Group editGroup(long id, GroupInput r) {
    dao.lock();
    String name = r.name().strip();
    exists(dao.updateGroup(id, name) > 0);
    return new Group(id, name);
  }

  public void deleteGroup(long id) {
    dao.lock();
    exists(dao.deleteGroup(id) > 0);
  }

  public List<Item> items(boolean all) {
    return dao.items(all);
  }

  public Item addItem(ItemInput r) {
    dao.lock();
    group(r.groupId());
    return dao.item(dao.createItem(r.name().strip(), r.groupId()));
  }

  public Item editItem(long id, ItemInput r) {
    dao.lock();
    item(id, false);
    group(r.groupId());
    dao.updateItem(id, r.name().strip(), r.groupId());
    return dao.item(id);
  }

  public void archive(long id) {
    dao.lock();
    item(id, false);
    var running = dao.running();
    if (running != null && running.workItemId() == id) conflict("計測中の作業項目はアーカイブできません。");
    dao.archive(id);
  }

  public TimerStatus status() {
    return status(dao.running());
  }

  private TimerStatus status(Session s) {
    return s == null
        ? new TimerStatus("IDLE", null, null, null, null, now())
        : new TimerStatus(
            s.status(),
            s.id(),
            s.workItemId(),
            item(s.workItemId(), false).name(),
            s.startedAt(),
            now());
  }

  public TimerStatus start(Start r) {
    dao.lock();
    item(r.workItemId(), true);
    if (dao.running() != null) conflict("すでに計測中です。");
    long id = dao.start(r.workItemId(), now());
    return status(dao.session(id));
  }

  public WorkRecord stop(SessionInput r) {
    dao.lock();
    Session s = dao.session(r.sessionId());
    exists(s != null);
    if (s.status().equals("STOPPED")) {
      var saved = dao.sessionRecord(s.id());
      if (saved == null) conflict("この計測の記録は削除されています。");
      return saved;
    }
    if (!s.status().equals("RUNNING")) conflict("キャンセルされた計測は保存できません。");
    Instant end = now();
    long duration = TimeMath.duration(s.startedAt(), end);
    long id =
        dao.insertRecord(s.workItemId(), s.id(), "TIMER", null, s.startedAt(), end, duration, null);
    dao.finish(s.id(), end, "STOPPED");
    return dao.record(id);
  }

  public Map<String, String> cancel(SessionInput r) {
    dao.lock();
    Session s = dao.session(r.sessionId());
    exists(s != null);
    if (s.status().equals("STOPPED")) conflict("停止済みの計測はキャンセルできません。");
    if (s.status().equals("RUNNING")) {
      Instant end = now();
      if (end.isBefore(s.startedAt())) conflict("サーバー時刻を確認してください。");
      dao.finish(s.id(), end, "CANCELED");
    }
    return Map.of("status", "CANCELED");
  }

  public WorkRecord manual(Manual r) {
    dao.lock();
    item(r.workItemId(), true);
    long duration = TimeMath.manual(r.hours(), r.minutes());
    return dao.record(
        dao.insertRecord(
            r.workItemId(), null, "MANUAL", r.workDate(), null, null, duration, r.memo()));
  }

  public List<WorkRecord> records(LocalDate date) {
    return dao.day(date);
  }

  public WorkRecord editRecord(long id, EditRecord r) {
    dao.lock();
    var old = dao.record(id);
    exists(old != null);
    if (old.recordType().equals("TIMER")) {
      if (r.startedAt() == null || r.endedAt() == null)
        throw new IllegalArgumentException("開始・終了日時を入力してください。");
      Instant start = r.startedAt().truncatedTo(ChronoUnit.MILLIS),
          end = r.endedAt().truncatedTo(ChronoUnit.MILLIS);
      dao.updateRecord(
          id, old.workItemId(), null, start, end, TimeMath.duration(start, end), r.memo());
    } else {
      if (r.workItemId() == null || r.workDate() == null)
        throw new IllegalArgumentException("作業日と作業項目を指定してください。");
      item(r.workItemId(), r.workItemId() != old.workItemId());
      dao.updateRecord(
          id,
          r.workItemId(),
          r.workDate(),
          null,
          null,
          TimeMath.manual(r.hours(), r.minutes()),
          r.memo());
    }
    return dao.record(id);
  }

  public void deleteRecord(long id) {
    dao.lock();
    exists(dao.deleteRecord(id) > 0);
  }

  public DailySummary summary(LocalDate date) {
    var records = dao.day(date);
    Map<Long, Long> groupTotals = new LinkedHashMap<>(), itemTotals = new LinkedHashMap<>();
    Map<Long, String> groupNames = new HashMap<>(), itemNames = new HashMap<>();
    long total = 0;
    for (var r : records) {
      long ms = TimeMath.onDay(r, date);
      total += ms;
      groupTotals.merge(r.groupId(), ms, Long::sum);
      itemTotals.merge(r.workItemId(), ms, Long::sum);
      groupNames.put(r.groupId(), r.groupName() == null ? "未分類" : r.groupName());
      itemNames.put(r.workItemId(), r.workItemName());
    }
    return new DailySummary(
        date,
        total,
        groupTotals.entrySet().stream()
            .map(e -> new Total(e.getKey(), groupNames.get(e.getKey()), e.getValue()))
            .toList(),
        itemTotals.entrySet().stream()
            .map(e -> new Total(e.getKey(), itemNames.get(e.getKey()), e.getValue()))
            .toList());
  }
}
