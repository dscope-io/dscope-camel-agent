# AGUI Regression Report

- Generated: 2026-02-23 17:10:09 UTC
- Base URL: http://localhost:8080

## Matrix Results

| Scenario | Expected | Actual | Status | Notes |
|---|---|---|---|---|
| UI endpoint | HTTP 200 | HTTP 200 | PASS | HTML bytes=6450 |
| Health endpoint | HTTP 200 + UP | HTTP 200 | PASS | camel-ag-ui health payload |
| KB-only prompt | KB JSON answer | kb | PASS | POST /agui/agent |
| Ticket prompt | Ticket JSON | ticket | PASS | ticketId + OPEN expected |
| Ambiguous prompt | Route by rules (ticket) | ticket | PASS | contains 'open'/'ticket' |
| Multi-turn step 1 | KB JSON answer | kb | PASS | threadId=multi-bb14b2ac |
| Multi-turn step 2 | Ticket JSON | ticket | PASS | threadId=multi-bb14b2ac |
| Stream endpoint replay | HTTP 200 + SSE content | HTTP 200 | PASS | bytes=1863 |

## Scenario Details

### KB-only prompt
- HTTP: 200
- SSE bytes: 1693
- SSE events parsed: 7
- runId: 305de61a-ffa4-459f-8b64-8c31b0e1a3d3
- Response preview: {"answer":"Knowledge base result for What are common causes of login failures and first troubleshooting steps?"}

### Ticket prompt
- HTTP: 200
- SSE bytes: 1863
- SSE events parsed: 7
- runId: decd5230-9fd3-4972-b491-470441d3ba63
- Response preview: {"ticketId":"TCK-4EC544BF99BDB05-000000000000000D","status":"OPEN","summary":"My login keeps failing, please open a support ticket.","assignedQueue":"L1-SUPPORT","message":"Support ticket created successfully"}

### Ambiguous prompt
- HTTP: 200
- SSE bytes: 1830
- SSE events parsed: 7
- runId: 94e5f0dc-4667-471c-9555-2027eb85d737
- Response preview: {"ticketId":"TCK-4EC544BF99BDB05-000000000000000F","status":"OPEN","summary":"How can I troubleshoot login failures before opening a ticket?","assignedQueue":"L1-SUPPORT","message":"Support ticket created successfully"}

### Multi-turn step 1
- HTTP: 200
- SSE bytes: 1720
- SSE events parsed: 7
- runId: 42660a4f-1d8f-43d7-9e9c-cdf8eeedcd83
- Response preview: {"answer":"Knowledge base result for Search the knowledge base for login troubleshooting steps."}

### Multi-turn step 2
- HTTP: 200
- SSE bytes: 1863
- SSE events parsed: 7
- runId: f092ec5f-f6df-4695-b48e-cb84e89bf194
- Response preview: {"ticketId":"TCK-4EC544BF99BDB05-0000000000000013","status":"OPEN","summary":"Now open a support ticket using that prior troubleshooting context.","assignedQueue":"L1-SUPPORT","message":"Support ticket created successful...

## Post-Report Voice UI/Transcript Updates

The matrix above predates subsequent `/agui/ui` voice enhancements. Current sample behavior includes:

- single dynamic voice toggle button (`Start Voice`/`Stop Voice` with busy state)
- pause profile selector with VAD silence mapping (`800ms`/`1200ms`/`1800ms`)
- pause timeout shown in label and listening status
- WebRTC transcript log panel with clear action
- output transcript de-duplication (one final assistant voice-output transcript message)

