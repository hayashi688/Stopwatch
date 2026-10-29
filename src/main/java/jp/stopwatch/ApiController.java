package jp.stopwatch;

import static jp.stopwatch.Requests.*;

import jakarta.validation.Valid;
import java.time.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final WorkService service;

  public ApiController(WorkService service) {
    this.service = service;
  }

  @GetMapping("/groups")
  Object groups() {
    return service.groups();
  }

  @PostMapping("/groups")
  Object group(@Valid @RequestBody GroupInput r) {
    return service.addGroup(r);
  }

  @PutMapping("/groups/{id}")
  Object group(@PathVariable long id, @Valid @RequestBody GroupInput r) {
    return service.editGroup(id, r);
  }

  @DeleteMapping("/groups/{id}")
  void deleteGroup(@PathVariable long id) {
    service.deleteGroup(id);
  }

  @GetMapping("/work-items")
  Object items(@RequestParam(defaultValue = "false") boolean includeArchived) {
    return service.items(includeArchived);
  }

  @PostMapping("/work-items")
  Object item(@Valid @RequestBody ItemInput r) {
    return service.addItem(r);
  }

  @PutMapping("/work-items/{id}")
  Object item(@PathVariable long id, @Valid @RequestBody ItemInput r) {
    return service.editItem(id, r);
  }

  @PatchMapping("/work-items/{id}/archive")
  void archive(@PathVariable long id) {
    service.archive(id);
  }

  @GetMapping("/timer/status")
  Object status() {
    return service.status();
  }

  @PostMapping("/timer/start")
  Object start(@Valid @RequestBody Start r) {
    return service.start(r);
  }

  @PostMapping("/timer/stop")
  Object stop(@Valid @RequestBody SessionInput r) {
    return service.stop(r);
  }

  @PostMapping("/timer/cancel")
  Object cancel(@Valid @RequestBody SessionInput r) {
    return service.cancel(r);
  }

  @PostMapping("/work-records/manual")
  Object manual(@Valid @RequestBody Manual r) {
    return service.manual(r);
  }

  @GetMapping("/work-records")
  Object records(@RequestParam LocalDate date) {
    return service.records(date);
  }

  @PutMapping("/work-records/{id}")
  Object edit(@PathVariable long id, @Valid @RequestBody EditRecord r) {
    return service.editRecord(id, r);
  }

  @DeleteMapping("/work-records/{id}")
  void delete(@PathVariable long id) {
    service.deleteRecord(id);
  }

  @GetMapping("/summary/daily")
  Object summary(@RequestParam LocalDate date) {
    return service.summary(date);
  }
}
