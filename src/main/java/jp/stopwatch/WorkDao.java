package jp.stopwatch;

import static jp.stopwatch.Models.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class WorkDao {
  private final JdbcTemplate db;

  public WorkDao(JdbcTemplate db) {
    this.db = db;
  }

  private static Instant instant(ResultSet r, String n) throws SQLException {
    var t = r.getTimestamp(n);
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private final RowMapper<Item> items =
      (r, n) ->
          new Item(
              r.getLong("id"),
              r.getObject("group_id", Long.class),
              r.getString("name"),
              r.getBoolean("is_archived"),
              r.getString("group_name"));
  private final RowMapper<Session> sessions =
      (r, n) ->
          new Session(
              r.getLong("id"),
              r.getLong("work_item_id"),
              instant(r, "started_at"),
              instant(r, "ended_at"),
              r.getString("status"));
  private final RowMapper<WorkRecord> records =
      (r, n) ->
          new WorkRecord(
              r.getLong("id"),
              r.getLong("work_item_id"),
              r.getObject("timer_session_id", Long.class),
              r.getString("record_type"),
              r.getObject("work_date", LocalDate.class),
              instant(r, "started_at"),
              instant(r, "ended_at"),
              r.getLong("duration_ms"),
              r.getString("memo"),
              r.getString("work_item_name"),
              r.getObject("group_id", Long.class),
              r.getString("group_name"));
  private static final String ITEM_SQL =
      "SELECT i.*,g.name group_name FROM work_items i LEFT JOIN work_groups g ON g.id=i.group_id ";
  private static final String RECORD_SQL =
      "SELECT r.*,i.name work_item_name,i.group_id,g.name group_name FROM work_records r JOIN"
          + " work_items i ON i.id=r.work_item_id LEFT JOIN work_groups g ON g.id=i.group_id ";

  // A transaction-scoped lock serializes state changes, including archive/start and group moves.
  public void lock() {
    db.execute("SELECT pg_advisory_xact_lock(73891245)");
  }

  public List<Group> groups() {
    return db.query(
        "SELECT id,name FROM work_groups ORDER BY id",
        (r, n) -> new Group(r.getLong(1), r.getString(2)));
  }

  public boolean groupExists(long id) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM work_groups WHERE id=?)", Boolean.class, id));
  }

  public long createGroup(String name) {
    return db.queryForObject(
        "INSERT INTO work_groups(name) VALUES (?) RETURNING id", Long.class, name);
  }

  public int updateGroup(long id, String name) {
    return db.update("UPDATE work_groups SET name=?,updated_at=now() WHERE id=?", name, id);
  }

  public int deleteGroup(long id) {
    return db.update("DELETE FROM work_groups WHERE id=?", id);
  }

  public List<Item> items(boolean all) {
    return db.query(ITEM_SQL + (all ? "" : "WHERE NOT i.is_archived ") + " ORDER BY i.id", items);
  }

  public Item item(long id) {
    return db.query(ITEM_SQL + " WHERE i.id=?", items, id).stream().findFirst().orElse(null);
  }

  public long createItem(String name, Long groupId) {
    return db.queryForObject(
        "INSERT INTO work_items(name,group_id) VALUES (?,?) RETURNING id",
        Long.class,
        name,
        groupId);
  }

  public int updateItem(long id, String name, Long groupId) {
    return db.update(
        "UPDATE work_items SET name=?,group_id=?,updated_at=now() WHERE id=?", name, groupId, id);
  }

  public void archive(long id) {
    db.update("UPDATE work_items SET is_archived=true,updated_at=now() WHERE id=?", id);
  }

  public Session running() {
    return db.query("SELECT * FROM timer_sessions WHERE status='RUNNING'", sessions).stream()
        .findFirst()
        .orElse(null);
  }

  public Session session(long id) {
    return db.query("SELECT * FROM timer_sessions WHERE id=? FOR UPDATE", sessions, id).stream()
        .findFirst()
        .orElse(null);
  }

  public long start(long item, Instant now) {
    return db.queryForObject(
        "INSERT INTO timer_sessions(work_item_id,started_at,status) VALUES (?,?,'RUNNING')"
            + " RETURNING id",
        Long.class,
        item,
        ts(now));
  }

  public void finish(long id, Instant now, String status) {
    db.update(
        "UPDATE timer_sessions SET ended_at=?,status=?,updated_at=now() WHERE id=?",
        ts(now),
        status,
        id);
  }

  public long insertRecord(
      long item,
      Long session,
      String type,
      LocalDate date,
      Instant start,
      Instant end,
      long duration,
      String memo) {
    return db.queryForObject(
        "INSERT INTO"
            + " work_records(work_item_id,timer_session_id,record_type,work_date,started_at,ended_at,duration_ms,memo)"
            + " VALUES (?,?,?,?,?,?,?,?) RETURNING id",
        Long.class,
        item,
        session,
        type,
        date,
        ts(start),
        ts(end),
        duration,
        memo);
  }

  public WorkRecord record(long id) {
    return db.query(RECORD_SQL + " WHERE r.id=?", records, id).stream().findFirst().orElse(null);
  }

  public WorkRecord sessionRecord(long id) {
    return db.query(RECORD_SQL + " WHERE timer_session_id=?", records, id).stream()
        .findFirst()
        .orElse(null);
  }

  public List<WorkRecord> day(LocalDate date) {
    Instant from = date.atStartOfDay(TimeMath.TOKYO).toInstant(),
        to = date.plusDays(1).atStartOfDay(TimeMath.TOKYO).toInstant();
    return db.query(
        RECORD_SQL
            + " WHERE (r.record_type='MANUAL' AND r.work_date=?) OR (r.record_type='TIMER' AND"
            + " r.started_at<? AND r.ended_at>?) ORDER BY r.created_at DESC,r.id DESC",
        records,
        date,
        ts(to),
        ts(from));
  }

  public void updateRecord(
      long id, long item, LocalDate date, Instant start, Instant end, long duration, String memo) {
    db.update(
        "UPDATE work_records SET"
            + " work_item_id=?,work_date=?,started_at=?,ended_at=?,duration_ms=?,memo=?,updated_at=now()"
            + " WHERE id=?",
        item,
        date,
        ts(start),
        ts(end),
        duration,
        memo,
        id);
  }

  public int deleteRecord(long id) {
    return db.update("DELETE FROM work_records WHERE id=?", id);
  }
}
