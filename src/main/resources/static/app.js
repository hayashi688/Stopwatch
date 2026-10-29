"use strict";
const $ = (id) => document.getElementById(id);
const state = {
  groups: [],
  items: [],
  records: [],
  todayTotals: [],
  selected: null,
  timer: null,
  busy: false,
  offset: 0,
};
const tokyoDate = () =>
  new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Tokyo",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
const fmt = (ms) => {
  const s = Math.floor(ms / 1000);
  return [Math.floor(s / 3600), Math.floor(s / 60) % 60, s % 60]
    .map((x) => String(x).padStart(2, "0"))
    .join(":");
};
const at = (iso) =>
  iso
    ? new Intl.DateTimeFormat("ja-JP", {
        timeZone: "Asia/Tokyo",
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false,
      }).format(new Date(iso))
    : "";
function el(tag, text, cls) {
  const n = document.createElement(tag);
  if (text != null) n.textContent = text;
  if (cls) n.className = cls;
  return n;
}
function button(text, fn, cls = "secondary small") {
  const b = el("button", text, cls);
  b.type = "button";
  b.onclick = fn;
  return b;
}
function message(text = "") {
  $("message").textContent = text;
}
async function api(path, method = "GET", body) {
  const headers = { Accept: "application/json" };
  if (method !== "GET") {
    headers["Content-Type"] = "application/json";
    headers[document.querySelector('meta[name="_csrf_header"]').content] =
      document.querySelector('meta[name="_csrf"]').content;
  }
  const response = await fetch("/api" + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await response.text();
  let parsed;
  try {
    parsed = data ? JSON.parse(data) : null;
  } catch {
    throw Error("応答を読み取れませんでした。ページを再読み込みしてください。");
  }
  if (!response.ok)
    throw Error(
      parsed?.message ||
        (response.status === 403
          ? "操作の有効期限が切れました。ページを再読み込みしてください。"
          : "通信に失敗しました。"),
    );
  return parsed;
}
function controls() {
  const running = state.timer?.status === "RUNNING";
  $("start").disabled = state.busy || running || !state.selected;
  $("stop").disabled = state.busy || !running;
  $("cancel").disabled = state.busy || !running;
  document
    .querySelectorAll('form button[type="submit"]')
    .forEach((b) => (b.disabled = state.busy));
  $("timer-state").textContent = running ? "計測中" : "未開始";
  $("timer-state").classList.toggle("running", running);
  $("selected-name").textContent = running
    ? state.timer.workItemName
    : state.items.find((i) => i.id === state.selected)?.name ||
      "作業項目を選択してください";
}
function tick() {
  const t = state.timer;
  const elapsed =
    t?.status === "RUNNING"
      ? Math.max(0, Date.now() + state.offset - Date.parse(t.startedAt))
      : 0;
  $("elapsed").textContent = fmt(elapsed);
  $("long-warning").hidden = elapsed < 12 * 3600000;
}
async function refresh() {
  const day = $("summary-date").value;
  if (!day) throw Error("集計対象日を選択してください。");
  const [groups, items, timer, summary, records, today] = await Promise.all([
    api("/groups"),
    api("/work-items"),
    api("/timer/status"),
    api("/summary/daily?date=" + day),
    api("/work-records?date=" + day),
    api("/summary/daily?date=" + tokyoDate()),
  ]);
  Object.assign(state, {
    groups,
    items,
    timer,
    records,
    todayTotals: today.workItems,
    offset: Date.parse(timer.serverNow) - Date.now(),
  });
  if (!items.some((i) => i.id === state.selected)) state.selected = null;
  renderItems();
  renderManual();
  renderSummary(summary);
  renderHistory();
  controls();
  tick();
}
async function action(fn, success) {
  if (state.busy) return;
  state.busy = true;
  controls();
  message();
  try {
    await fn();
    await refresh();
    if (success) message(success);
  } catch (e) {
    message(e.message || "通信に失敗しました。");
  } finally {
    state.busy = false;
    controls();
  }
}
function options(select, items, placeholder, old = select.value) {
  select.replaceChildren();
  if (placeholder !== null) select.add(new Option(placeholder, ""));
  for (const i of items) select.add(new Option(i.name, String(i.id)));
  select.value = old;
  if (select.selectedIndex < 0) select.selectedIndex = 0;
}
function renderItems() {
  const root = $("work-items");
  root.replaceChildren();
  options($("item-group"), state.groups, "未分類");
  for (const g of [{ id: null, name: "未分類" }, ...state.groups]) {
    const items = state.items.filter((i) => i.groupId === g.id);
    if (!items.length && g.id === null) continue;
    const heading = el("div", null, "group-heading");
    heading.append(el("h3", g.name));
    if (g.id !== null) {
      const actions = el("div");
      actions.append(
        button("編集", () => editGroup(g)),
        button("削除", () => deleteGroup(g), "danger quiet small"),
      );
      heading.append(actions);
    }
    root.append(heading);
    for (const i of items) {
      const row = el("div", null, "item-row");
      const pick = button(
        "",
        () => {
          if (state.busy) return;
          state.selected = i.id;
          renderItems();
          controls();
        },
        "item-pick" + (state.selected === i.id ? " selected" : ""),
      );
      pick.setAttribute("aria-pressed", String(state.selected === i.id));
      pick.append(
        el("span", i.name),
        el(
          "span",
          fmt(state.todayTotals.find((t) => t.id === i.id)?.durationMs || 0),
          "item-time",
        ),
      );
      row.append(
        pick,
        button("編集", () => editItem(i)),
        button("保管", () => archiveItem(i), "quiet small"),
      );
      root.append(row);
    }
  }
  $("empty-items").hidden = state.items.length > 0;
}
function renderManual() {
  const filter = $("manual-group");
  const old = filter.value;
  filter.replaceChildren(new Option("すべて", "all"), new Option("未分類", ""));
  for (const g of state.groups) filter.add(new Option(g.name, g.id));
  filter.value = old;
  if (filter.selectedIndex < 0) filter.value = "all";
  renderManualItems();
}
function renderManualItems() {
  const value = $("manual-group").value;
  options(
    $("manual-item"),
    state.items.filter(
      (i) => value === "all" || String(i.groupId ?? "") === value,
    ),
    "選択してください",
  );
}
function renderSummary(s) {
  $("daily-total").textContent = fmt(s.durationMs);
  for (const [id, totals] of [
    ["group-totals", s.groups],
    ["item-totals", s.workItems],
  ]) {
    const root = $(id);
    root.replaceChildren();
    for (const t of totals) {
      const row = el("div", null, "total-row");
      row.append(el("span", t.name), el("b", fmt(t.durationMs)));
      root.append(row);
    }
    if (!totals.length) root.append(el("p", "記録はありません。", "hint"));
  }
}
function renderHistory() {
  const root = $("history");
  root.replaceChildren();
  $("empty-history").hidden = state.records.length > 0;
  for (const r of state.records) {
    const tr = el("tr"),
      date = el("td", r.recordType === "MANUAL" ? r.workDate : at(r.startedAt));
    if (r.recordType === "TIMER")
      date.append(el("small", "〜 " + at(r.endedAt)));
    const item = el("td", r.workItemName);
    item.append(el("small", r.groupName || "未分類"));
    const actions = el("td");
    actions.append(
      button("編集", () => editRecord(r)),
      button("削除", () => deleteRecord(r), "danger quiet small"),
    );
    tr.append(
      date,
      item,
      el("td", r.recordType === "TIMER" ? "ストップウォッチ" : "手動"),
      el("td", fmt(r.durationMs)),
      el("td", r.memo || "", "memo"),
      actions,
    );
    root.append(tr);
  }
}
let editorSave = null;
function field(label, name, type, value, attrs = {}) {
  const l = el("label", label),
    input = el(type === "textarea" ? "textarea" : "input");
  if (type !== "textarea") input.type = type;
  input.name = name;
  input.value = value ?? "";
  for (const [k, v] of Object.entries(attrs)) input.setAttribute(k, v);
  l.append(input);
  return l;
}
function selectField(label, name, items, value, placeholder) {
  const l = el("label", label),
    s = el("select");
  s.name = name;
  options(s, items, placeholder, String(value ?? ""));
  l.append(s);
  return l;
}
function openEditor(title, fields, save) {
  if (state.busy) return;
  $("edit-title").textContent = title;
  $("edit-error").textContent = "";
  $("edit-fields").replaceChildren(...fields);
  editorSave = save;
  $("editor").showModal();
}
$("edit-form").onsubmit = async (e) => {
  e.preventDefault();
  if (state.busy) return;
  state.busy = true;
  controls();
  $("edit-error").textContent = "";
  try {
    await editorSave(Object.fromEntries(new FormData(e.target)));
    $("editor").close();
    await refresh();
    message("保存しました。");
  } catch (err) {
    if ($("editor").open) $("edit-error").textContent = err.message;
    else message("保存後の再読み込みに失敗しました。" + err.message);
  } finally {
    state.busy = false;
    controls();
  }
};
$("close-editor").onclick = () => {
  if (!state.busy) $("editor").close();
};
$("editor").addEventListener("cancel", (e) => {
  if (state.busy) e.preventDefault();
});
function editGroup(g) {
  openEditor(
    "グループを編集",
    [
      field("グループ名", "name", "text", g.name, {
        required: "",
        maxlength: 100,
      }),
    ],
    (d) => api("/groups/" + g.id, "PUT", d),
  );
}
function editItem(i) {
  openEditor(
    "作業項目を編集",
    [
      field("作業項目名", "name", "text", i.name, {
        required: "",
        maxlength: 200,
      }),
      selectField("グループ", "groupId", state.groups, i.groupId, "未分類"),
    ],
    (d) =>
      api("/work-items/" + i.id, "PUT", {
        name: d.name,
        groupId: d.groupId ? Number(d.groupId) : null,
      }),
  );
}
function localInput(iso) {
  return (
    new Intl.DateTimeFormat("sv-SE", {
      timeZone: "Asia/Tokyo",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
    })
      .format(new Date(iso))
      .replace(" ", "T") +
    "." +
    String(new Date(iso).getUTCMilliseconds()).padStart(3, "0")
  );
}
function editRecord(r) {
  const fields =
    r.recordType === "MANUAL"
      ? [
          field("作業日", "workDate", "date", r.workDate, { required: "" }),
          selectField(
            "作業項目",
            "workItemId",
            state.items.some((i) => i.id === r.workItemId)
              ? state.items
              : [
                  ...state.items,
                  {
                    id: r.workItemId,
                    name: r.workItemName + "（アーカイブ済み）",
                  },
                ],
            r.workItemId,
            null,
          ),
          field("時間", "hours", "number", Math.floor(r.durationMs / 3600000), {
            required: "",
            min: 0,
            max: 24,
            step: 1,
          }),
          field(
            "分",
            "minutes",
            "number",
            Math.floor(r.durationMs / 60000) % 60,
            { required: "", min: 0, max: 59, step: 1 },
          ),
        ]
      : [
          field(
            "開始日時（日本時間）",
            "startedAt",
            "datetime-local",
            localInput(r.startedAt),
            { required: "", step: "0.001" },
          ),
          field(
            "終了日時（日本時間）",
            "endedAt",
            "datetime-local",
            localInput(r.endedAt),
            { required: "", step: "0.001" },
          ),
        ];
  fields.push(field("メモ", "memo", "textarea", r.memo, { maxlength: 10000 }));
  openEditor("作業記録を編集", fields, (d) => {
    const body =
      r.recordType === "MANUAL"
        ? {
            workDate: d.workDate,
            workItemId: Number(d.workItemId),
            hours: Number(d.hours),
            minutes: Number(d.minutes),
            memo: d.memo,
          }
        : {
            startedAt: new Date(d.startedAt + "+09:00").toISOString(),
            endedAt: new Date(d.endedAt + "+09:00").toISOString(),
            memo: d.memo,
          };
    return api("/work-records/" + r.id, "PUT", body);
  });
}
function confirmAction(text, yes = "削除する", no = "削除しない") {
  if (state.busy) return Promise.resolve(false);
  $("confirm-message").textContent = text;
  $("confirm-yes").textContent = yes;
  $("confirm-no").textContent = no;
  $("confirm-dialog").showModal();
  return new Promise((resolve) => {
    const done = (value) => {
      $("confirm-dialog").close();
      $("confirm-dialog").onclose = null;
      resolve(value);
    };
    $("confirm-yes").onclick = () => done(true);
    $("confirm-no").onclick = () => done(false);
    $("confirm-dialog").onclose = () => resolve(false);
  });
}
async function deleteGroup(g) {
  if (
    await confirmAction(
      "「" +
        g.name +
        "」を削除しますか？作業項目は未分類に移動し、記録は残ります。",
    )
  )
    action(() => api("/groups/" + g.id, "DELETE"), "グループを削除しました。");
}
async function archiveItem(i) {
  if (
    await confirmAction(
      "「" +
        i.name +
        "」をアーカイブしますか？選択一覧から除外しますが、記録は残ります。",
      "アーカイブする",
      "戻る",
    )
  )
    action(
      () => api("/work-items/" + i.id + "/archive", "PATCH", {}),
      "アーカイブしました。",
    );
}
async function deleteRecord(r) {
  if (await confirmAction("この作業記録を削除しますか？"))
    action(
      () => api("/work-records/" + r.id, "DELETE"),
      "記録を削除しました。",
    );
}
$("group-form").onsubmit = (e) => {
  e.preventDefault();
  const form = e.target;
  action(async () => {
    await api("/groups", "POST", { name: form.elements.name.value });
    form.reset();
  }, "グループを追加しました。");
};
$("item-form").onsubmit = (e) => {
  e.preventDefault();
  const f = e.target;
  action(async () => {
    await api("/work-items", "POST", {
      name: f.elements.name.value,
      groupId: f.elements.groupId.value
        ? Number(f.elements.groupId.value)
        : null,
    });
    f.elements.name.value = "";
  }, "作業項目を追加しました。");
};
$("manual-form").onsubmit = (e) => {
  e.preventDefault();
  const f = e.target;
  action(async () => {
    await api("/work-records/manual", "POST", {
      workItemId: Number(f.elements.workItemId.value),
      workDate: f.elements.workDate.value,
      hours: Number(f.elements.hours.value),
      minutes: Number(f.elements.minutes.value),
      memo: f.elements.memo.value,
    });
    $("summary-date").value = f.elements.workDate.value;
    f.elements.hours.value = 0;
    f.elements.minutes.value = 0;
    f.elements.memo.value = "";
  }, "作業時間を登録しました。");
};
$("manual-group").onchange = renderManualItems;
$("start").onclick = () =>
  action(() => api("/timer/start", "POST", { workItemId: state.selected }));
$("stop").onclick = () =>
  action(
    () => api("/timer/stop", "POST", { sessionId: state.timer.sessionId }),
    "計測時間を保存しました。",
  );
$("cancel").onclick = async () => {
  if (
    await confirmAction(
      "計測中の時間を破棄しますか？",
      "破棄する",
      "キャンセルしない",
    )
  )
    action(
      () => api("/timer/cancel", "POST", { sessionId: state.timer.sessionId }),
      "計測時間を破棄しました。",
    );
};
$("summary-date").onchange = () => {
  if (!$("summary-date").value) {
    message("日付を選択してください。");
    return;
  }
  action(async () => {});
};
function moveDay(delta) {
  if (state.busy) return;
  const d = new Date($("summary-date").value + "T00:00:00Z");
  if (Number.isNaN(d.getTime())) return;
  d.setUTCDate(d.getUTCDate() + delta);
  $("summary-date").value = d.toISOString().slice(0, 10);
  action(async () => {});
}
$("prev-day").onclick = () => moveDay(-1);
$("next-day").onclick = () => moveDay(1);
$("today").onclick = () => {
  if (state.busy) return;
  $("summary-date").value = tokyoDate();
  action(async () => {});
};
setInterval(tick, 250);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden && !state.busy) action(async () => {});
});
action(async () => {});
