package jp.stopwatch;

import static jp.stopwatch.Requests.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${DB_TEST_URL:jdbc:postgresql://127.0.0.1:5432/stopwatch_test}",
      "spring.datasource.username=${DB_TEST_USER:stopwatch}",
      "spring.datasource.password=${DB_TEST_PASSWORD:}"
    })
@AutoConfigureMockMvc
class WorkFlowIT {
  @Autowired WorkService service;
  @Autowired WorkDao dao;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @MockitoBean Clock clock;
  private static final Instant START = Instant.parse("2026-10-29T14:30:00Z");
  private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

  @BeforeEach
  void reset() {
    assertTrue(
        db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
        "Use a dedicated database ending in _test");
    db.execute(
        "TRUNCATE work_records,timer_sessions,work_items,work_groups RESTART IDENTITY CASCADE");
    when(clock.instant()).thenReturn(START);
    when(clock.withZone(any())).thenAnswer(i -> Clock.fixed(clock.instant(), i.getArgument(0)));
  }

  private long item() {
    var g = service.addGroup(new GroupInput("案件A"));
    return service.addItem(new ItemInput("設計", g.id())).id();
  }

  private long start(long id) {
    return service.start(new Start(id)).sessionId();
  }

  private void later(long seconds) {
    when(clock.instant()).thenReturn(START.plusSeconds(seconds));
  }

  @Test
  void timerPersistsAndStopIsIdempotent() {
    long item = item(), session = start(item);
    later(3600);
    var restored = service.status();
    assertEquals(session, restored.sessionId());
    assertEquals(START, restored.startedAt());
    var r = service.stop(new SessionInput(session));
    assertEquals(3_600_000, r.durationMs());
    assertEquals(r.id(), service.stop(new SessionInput(session)).id());
    assertEquals("IDLE", service.status().status());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM work_records", Integer.class));
  }

  @Test
  void cancelIsIdempotentAndCannotBeStopped() {
    long session = start(item());
    later(50);
    service.cancel(new SessionInput(session));
    service.cancel(new SessionInput(session));
    assertThrows(Exception.class, () -> service.stop(new SessionInput(session)));
    assertEquals(0, service.summary(DAY).durationMs());
    assertTrue(service.records(DAY).isEmpty());
    assertEquals("CANCELED", dao.session(session).status());
  }

  @Test
  void noAutomaticRecordEvenAfterTwelveHours() {
    start(item());
    later(90000);
    assertEquals("RUNNING", service.status().status());
    assertTrue(service.records(DAY).isEmpty());
    assertEquals(0, service.summary(DAY).durationMs());
  }

  @Test
  void manualAndTimerAggregateAcrossMidnight() {
    long i = item();
    long session = start(i);
    later(3600);
    service.stop(new SessionInput(session));
    service.manual(new Manual(i, DAY, 1, 30, "メモ"));
    service.manual(new Manual(i, DAY, 0, 30, null));
    var s = service.summary(DAY);
    assertEquals(9_000_000, s.durationMs());
    assertEquals(s.durationMs(), s.groups().getFirst().durationMs());
    assertEquals(s.durationMs(), s.workItems().getFirst().durationMs());
    assertEquals(1_800_000, service.summary(DAY.plusDays(1)).durationMs());
    assertEquals(3, service.records(DAY).size());
  }

  @Test
  void groupRemovalPreservesItemsAndRecords() {
    long i = item();
    service.manual(new Manual(i, DAY, 1, 0, null));
    service.deleteGroup(service.groups().getFirst().id());
    assertNull(dao.item(i).groupId());
    assertEquals("未分類", service.summary(DAY).groups().getFirst().name());
    assertEquals(1, service.records(DAY).size());
  }

  @Test
  void nameUniquenessIncludesUnclassified() {
    service.addItem(new ItemInput("設計", null));
    assertThrows(Exception.class, () -> service.addItem(new ItemInput("設計", null)));
    long i = item();
    assertThrows(
        Exception.class, () -> service.addItem(new ItemInput("設計", dao.item(i).groupId())));
    assertThrows(Exception.class, () -> service.addGroup(new GroupInput("案件A")));
  }

  @Test
  void groupDeletionConflictRollsBack() {
    long i = item();
    service.addItem(new ItemInput("設計", null));
    assertThrows(Exception.class, () -> service.deleteGroup(dao.item(i).groupId()));
    assertEquals(1, service.groups().size());
    assertNotNull(dao.item(i).groupId());
  }

  @Test
  void archiveBlocksRunningAndNewRecordsButKeepsHistory() {
    long i = item(), s = start(i);
    assertThrows(Exception.class, () -> service.archive(i));
    later(1);
    service.stop(new SessionInput(s));
    service.archive(i);
    assertTrue(service.items(false).isEmpty());
    assertEquals(1, service.records(DAY).size());
    assertThrows(Exception.class, () -> service.start(new Start(i)));
    assertThrows(Exception.class, () -> service.manual(new Manual(i, DAY, 1, 0, null)));
  }

  @Test
  void editAndDeleteManualAndTimer() {
    long i = item();
    var m = service.manual(new Manual(i, DAY, 1, 0, "old"));
    service.editRecord(m.id(), new EditRecord(i, DAY.plusDays(1), 2, 0, null, null, "new"));
    assertEquals(0, service.summary(DAY).durationMs());
    assertEquals(7_200_000, service.summary(DAY.plusDays(1)).durationMs());
    service.deleteRecord(m.id());
    long s = start(i);
    later(100);
    var t = service.stop(new SessionInput(s));
    service.editRecord(
        t.id(), new EditRecord(null, null, null, null, START, START.plusSeconds(300), "updated"));
    assertEquals(300_000, service.summary(DAY).durationMs());
    service.deleteRecord(t.id());
    assertThrows(Exception.class, () -> service.stop(new SessionInput(s)));
    assertTrue(service.records(DAY).isEmpty());
  }

  @Test
  void renameMoveAndMultipleItemTotals() {
    long i = item();
    var g = service.addGroup(new GroupInput("学習"));
    service.editGroup(g.id(), new GroupInput("自己学習"));
    service.editItem(i, new ItemInput("読書", g.id()));
    service.manual(new Manual(i, DAY, 1, 0, null));
    long j = service.addItem(new ItemInput("練習", g.id())).id();
    service.manual(new Manual(j, DAY, 0, 30, null));
    assertEquals(2, service.summary(DAY).workItems().size());
    assertEquals(5_400_000, service.summary(DAY).groups().getFirst().durationMs());
    assertEquals("読書", dao.item(i).name());
  }

  @Test
  void unknownIdsAndInvalidTimeFail() {
    assertThrows(Exception.class, () -> service.start(new Start(999L)));
    assertThrows(Exception.class, () -> service.stop(new SessionInput(999L)));
    assertThrows(Exception.class, () -> service.cancel(new SessionInput(999L)));
    long i = item();
    assertThrows(Exception.class, () -> service.manual(new Manual(i, DAY, 0, 0, null)));
    assertThrows(Exception.class, () -> service.manual(new Manual(i, DAY, 24, 1, null)));
    assertThrows(Exception.class, () -> service.addItem(new ItemInput("名前", 999L)));
  }

  @Test
  void simultaneousStartsAllowOnlyOne() throws Exception {
    long i = item();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<Boolean> c =
          () -> {
            gate.await();
            try {
              start(i);
              return true;
            } catch (Exception e) {
              return false;
            }
          };
      var a = pool.submit(c);
      var b = pool.submit(c);
      gate.countDown();
      assertNotEquals(a.get(), b.get());
    }
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM timer_sessions WHERE status='RUNNING'", Integer.class));
  }

  @Test
  void simultaneousStopsReturnSameRecord() throws Exception {
    long s = start(item());
    later(10);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> service.stop(new SessionInput(s)));
      var b = pool.submit(() -> service.stop(new SessionInput(s)));
      assertEquals(a.get().id(), b.get().id());
    }
    assertEquals(1, db.queryForObject("SELECT count(*) FROM work_records", Integer.class));
  }

  @Test
  void databaseErrorRollsBackRecordAndSession() {
    long s = start(item());
    later(50);
    db.execute(
        "CREATE FUNCTION test_fail_finish() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE"
            + " EXCEPTION 'test failure'; END $$");
    db.execute(
        "CREATE TRIGGER test_fail BEFORE UPDATE ON timer_sessions FOR EACH ROW EXECUTE FUNCTION"
            + " test_fail_finish()");
    try {
      assertThrows(Exception.class, () -> service.stop(new SessionInput(s)));
      assertEquals("RUNNING", service.status().status());
      assertEquals(0, db.queryForObject("SELECT count(*) FROM work_records", Integer.class));
    } finally {
      db.execute("DROP TRIGGER test_fail ON timer_sessions");
      db.execute("DROP FUNCTION test_fail_finish()");
    }
    service.stop(new SessionInput(s));
    assertEquals(1, service.records(DAY).size());
  }

  @Test
  void dbRejectsInvalidTypesAndParallelRunning() {
    long i = item();
    start(i);
    assertThrows(
        Exception.class,
        () ->
            db.update(
                "INSERT INTO timer_sessions(work_item_id,started_at,status) VALUES"
                    + " (?,now(),'RUNNING')",
                i));
    assertThrows(
        Exception.class,
        () ->
            db.update(
                "INSERT INTO work_records(work_item_id,record_type,work_date,duration_ms) VALUES"
                    + " (?,'MANUAL',current_date,0)",
                i));
  }

  @Test
  void csrfValidationAndServerRenderedPage() throws Exception {
    mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("作業時間ノート")));
    mvc.perform(post("/api/groups").contentType("application/json").content("{\"name\":\"test\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/groups")
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"   \"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/work-records/manual")
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"workItemId\":1,\"workDate\":\"2026-10-29\",\"hours\":1.5,\"minutes\":0}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/work-records").param("date", "bad-date"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void apiWorkflowAndStatusRestoration() throws Exception {
    long i = item();
    mvc.perform(
            post("/api/timer/start")
                .with(csrf())
                .contentType("application/json")
                .content("{\"workItemId\":" + i + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"));
    mvc.perform(get("/api/timer/status"))
        .andExpect(jsonPath("$.workItemId").value(i))
        .andExpect(jsonPath("$.startedAt").value(START.toString()));
    later(5);
    long s = service.status().sessionId();
    mvc.perform(
            post("/api/timer/stop")
                .with(csrf())
                .contentType("application/json")
                .content("{\"sessionId\":" + s + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.durationMs").value(5000));
  }
}
