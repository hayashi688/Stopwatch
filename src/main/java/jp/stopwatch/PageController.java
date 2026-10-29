package jp.stopwatch;

import java.time.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class PageController {
  private final WorkService service;
  private final Clock clock;

  PageController(WorkService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @GetMapping("/")
  String index(Model model) {
    var today = LocalDate.now(clock.withZone(TimeMath.TOKYO));
    model.addAttribute("groups", service.groups());
    model.addAttribute("items", service.items(false));
    model.addAttribute("summary", service.summary(today));
    model.addAttribute("timer", service.status());
    model.addAttribute("today", today);
    return "index";
  }
}
