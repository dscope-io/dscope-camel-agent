#!/usr/bin/env python3
import json
import sys
import uuid
from datetime import datetime, timezone
from urllib import request, error

BASE_URL = "http://localhost:8080"
REPORT_PATH = "docs/AGUI_REGRESSION_REPORT.md"
TIMEOUT = 45


def http_get(path: str):
    url = f"{BASE_URL}{path}"
    req = request.Request(url, method="GET")
    with request.urlopen(req, timeout=TIMEOUT) as resp:
        body = resp.read().decode("utf-8", errors="replace")
        return resp.status, body


def http_post_json(path: str, payload: dict):
    url = f"{BASE_URL}{path}"
    data = json.dumps(payload).encode("utf-8")
    req = request.Request(url, data=data, method="POST", headers={"Content-Type": "application/json"})
    with request.urlopen(req, timeout=TIMEOUT) as resp:
        body = resp.read().decode("utf-8", errors="replace")
        return resp.status, body


def parse_sse(sse_text: str):
    events = []
    for block in [b.strip() for b in sse_text.split("\n\n") if b.strip()]:
        event_type = ""
        data_obj = None
        for line in block.splitlines():
            if line.startswith("event:"):
                event_type = line[len("event:"):].strip()
            elif line.startswith("data:"):
                raw = line[len("data:"):].strip()
                try:
                    data_obj = json.loads(raw)
                except Exception:
                    data_obj = {"raw": raw}
        events.append({"event": event_type, "data": data_obj})
    return events


def extract_delta(events):
    parts = []
    run_id = None
    for e in events:
        data = e.get("data") or {}
        if not run_id and isinstance(data, dict):
            run_id = data.get("runId")
        if e.get("event") == "TEXT_MESSAGE_CONTENT" and isinstance(data, dict):
            parts.append(data.get("delta", ""))
    return "".join(parts), run_id


def classify_output(text: str):
    if '"ticketId"' in text and '"status"' in text:
        return "ticket"
    if '"answer"' in text and "Knowledge base result" in text:
        return "kb"
    return "other"


def run_agui_prompt(prompt: str, thread_id: str, session_id: str):
    payload = {
        "threadId": thread_id,
        "sessionId": session_id,
        "messages": [{"role": "user", "content": prompt}],
    }
    code, body = http_post_json("/agui/agent", payload)
    events = parse_sse(body)
    delta, run_id = extract_delta(events)
    return {
        "http": code,
        "sse_bytes": len(body.encode("utf-8")),
        "events": len(events),
        "delta": delta,
        "runId": run_id,
        "kind": classify_output(delta),
    }


def result_row(name, expected, actual, ok, notes):
    status = "PASS" if ok else "FAIL"
    return f"| {name} | {expected} | {actual} | {status} | {notes} |"


def write_report(rows, details):
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    content = []
    content.append("# AGUI Regression Report")
    content.append("")
    content.append(f"- Generated: {timestamp}")
    content.append(f"- Base URL: {BASE_URL}")
    content.append("")
    content.append("## Matrix Results")
    content.append("")
    content.append("| Scenario | Expected | Actual | Status | Notes |")
    content.append("|---|---|---|---|---|")
    content.extend(rows)
    content.append("")
    content.append("## Scenario Details")
    content.append("")
    for name, info in details.items():
        content.append(f"### {name}")
        content.append(f"- HTTP: {info.get('http')}")
        content.append(f"- SSE bytes: {info.get('sse_bytes')}")
        content.append(f"- SSE events parsed: {info.get('events')}")
        if info.get("runId"):
            content.append(f"- runId: {info.get('runId')}")
        preview = (info.get("delta") or "").replace("\n", " ")
        if len(preview) > 220:
            preview = preview[:220] + "..."
        content.append(f"- Response preview: {preview}")
        content.append("")

    with open(REPORT_PATH, "w", encoding="utf-8") as fh:
        fh.write("\n".join(content) + "\n")


def main():
    rows = []
    details = {}

    # Endpoint checks
    ui_code, ui_body = http_get("/agui/ui")
    health_code, health_body = http_get("/health")
    rows.append(result_row(
        "UI endpoint",
        "HTTP 200",
        f"HTTP {ui_code}",
        ui_code == 200,
        f"HTML bytes={len(ui_body.encode('utf-8'))}",
    ))
    rows.append(result_row(
        "Health endpoint",
        "HTTP 200 + UP",
        f"HTTP {health_code}",
        health_code == 200 and '"status":"UP"' in health_body,
        "camel-ag-ui health payload",
    ))

    # Scenario 1: KB-only
    thread1 = f"kb-{uuid.uuid4().hex[:8]}"
    s1 = run_agui_prompt(
        "What are common causes of login failures and first troubleshooting steps?",
        thread1,
        thread1,
    )
    details["KB-only prompt"] = s1
    rows.append(result_row(
        "KB-only prompt",
        "KB JSON answer",
        s1["kind"],
        s1["http"] == 200 and s1["kind"] == "kb",
        "POST /agui/agent",
    ))

    # Scenario 2: Ticket prompt
    thread2 = f"ticket-{uuid.uuid4().hex[:8]}"
    s2 = run_agui_prompt(
        "My login keeps failing, please open a support ticket.",
        thread2,
        thread2,
    )
    details["Ticket prompt"] = s2
    rows.append(result_row(
        "Ticket prompt",
        "Ticket JSON",
        s2["kind"],
        s2["http"] == 200 and s2["kind"] == "ticket",
        "ticketId + OPEN expected",
    ))

    # Scenario 3: Ambiguous wording with 'ticket'
    thread3 = f"amb-{uuid.uuid4().hex[:8]}"
    s3 = run_agui_prompt(
        "How can I troubleshoot login failures before opening a ticket?",
        thread3,
        thread3,
    )
    details["Ambiguous prompt"] = s3
    rows.append(result_row(
        "Ambiguous prompt",
        "Route by rules (ticket)",
        s3["kind"],
        s3["http"] == 200 and s3["kind"] == "ticket",
        "contains 'open'/'ticket'",
    ))

    # Scenario 4: Multi-turn same thread
    thread4 = f"multi-{uuid.uuid4().hex[:8]}"
    m1 = run_agui_prompt(
        "Search the knowledge base for login troubleshooting steps.",
        thread4,
        thread4,
    )
    m2 = run_agui_prompt(
        "Now open a support ticket using that prior troubleshooting context.",
        thread4,
        thread4,
    )
    details["Multi-turn step 1"] = m1
    details["Multi-turn step 2"] = m2
    rows.append(result_row(
        "Multi-turn step 1",
        "KB JSON answer",
        m1["kind"],
        m1["http"] == 200 and m1["kind"] == "kb",
        f"threadId={thread4}",
    ))
    rows.append(result_row(
        "Multi-turn step 2",
        "Ticket JSON",
        m2["kind"],
        m2["http"] == 200 and m2["kind"] == "ticket",
        f"threadId={thread4}",
    ))

    # Scenario 5: Stream replay
    stream_ok = False
    stream_http = None
    stream_bytes = 0
    if s2.get("runId"):
        stream_http, stream_body = http_get(f"/agui/stream/{s2['runId']}")
        stream_bytes = len(stream_body.encode("utf-8"))
        stream_ok = stream_http == 200 and "TEXT_MESSAGE_CONTENT" in stream_body
    rows.append(result_row(
        "Stream endpoint replay",
        "HTTP 200 + SSE content",
        f"HTTP {stream_http}",
        stream_ok,
        f"bytes={stream_bytes}",
    ))

    write_report(rows, details)

    failed = [r for r in rows if "| FAIL |" in r]
    print(f"report={REPORT_PATH}")
    print(f"total={len(rows)} failed={len(failed)}")
    for r in rows:
        print(r)
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except error.URLError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise
