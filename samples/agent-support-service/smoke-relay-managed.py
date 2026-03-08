#!/usr/bin/env python3
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def post_json(url: str, payload: dict, timeout: int = 30) -> dict:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as response:
        body = response.read().decode("utf-8")
        return json.loads(body) if body else {}


def health_ok(base_url: str) -> bool:
    try:
        with urllib.request.urlopen(f"{base_url}/health", timeout=4) as response:
            return response.status == 200
    except Exception:
        return False


def first_content(messages: list, role: str, source: str) -> str:
    for item in messages:
        if str(item.get("role", "")).lower() == role and str(item.get("source", "")) == source:
            return str(item.get("content", ""))
    return ""


def main() -> int:
    explicit = os.environ.get("AGENT_BASE_URL", "").strip()
    candidates = [explicit] if explicit else ["http://localhost:8080", "http://localhost:8087"]
    candidates = [value for value in candidates if value]

    base_url = ""
    for candidate in candidates:
        if health_ok(candidate):
            base_url = candidate
            break

    if not base_url:
        print("FAIL: no healthy sample service found on localhost:8080 or localhost:8087")
        print("Tip: start sample first, or set AGENT_BASE_URL")
        return 2

    conversation_id = f"smoke-relay-{int(time.time())}"
    event_url = f"{base_url}/realtime/session/{urllib.parse.quote(conversation_id)}/event"
    print(f"Base URL: {base_url}")
    print(f"Conversation: {conversation_id}")

    try:
        start_response = post_json(
            event_url,
            {
                "type": "session.start",
                "session": {
                    "voice": "alloy",
                    "input_audio_transcription": {"model": "gpt-4o-mini-transcribe"},
                    "turn_detection": None,
                    "audio": {
                        "input": {
                            "transcription": {
                                "model": "gpt-4o-mini-transcribe",
                                "language": "en",
                            },
                            "turn_detection": None,
                        },
                        "output": {"voice": "alloy"},
                    },
                },
            },
            timeout=20,
        )
        if start_response.get("accepted") is not True:
            print("FAIL: session.start was not accepted")
            print(json.dumps(start_response, indent=2))
            return 3

        input_transcript = "My login is failing, please open a support ticket"
        observed_input_response = post_json(
            event_url,
            {
                "type": "transcript.observed",
                "direction": "input",
                "relayManaged": True,
                "observedEventType": "conversation.item.input_audio_transcription.completed",
                "transcript": input_transcript,
                "text": input_transcript,
            },
            timeout=40,
        )

        if observed_input_response.get("routedToAgent") is not True:
            print("FAIL: relay-managed observed input did not route to agent")
            print(json.dumps(observed_input_response, indent=2))
            return 4

        input_messages = observed_input_response.get("aguiMessages", [])
        user_content = first_content(input_messages, "user", "voice-transcript")
        assistant_content = first_content(input_messages, "assistant", "agent")
        if not user_content or not assistant_content:
            print("FAIL: relay-managed observed input response missing AGUI user/assistant messages")
            print(json.dumps(observed_input_response, indent=2))
            return 5

        output_transcript = "Support ticket created successfully."
        observed_output_response = post_json(
            event_url,
            {
                "type": "transcript.observed",
                "direction": "output",
                "relayManaged": True,
                "observedEventType": "response.output_audio_transcript.done",
                "transcript": output_transcript,
                "text": output_transcript,
            },
            timeout=20,
        )

        output_messages = observed_output_response.get("aguiMessages", [])
        output_content = first_content(output_messages, "assistant", "voice-output-transcript")
        if observed_output_response.get("routedToAgent") is not False or not output_content:
            print("FAIL: relay-managed observed output did not return output transcript AGUI payload")
            print(json.dumps(observed_output_response, indent=2))
            return 6

        print("PASS: relay-managed smoke checks succeeded")
        print(json.dumps(
            {
                "inputRoutedToAgent": observed_input_response.get("routedToAgent"),
                "inputAguiMessages": len(input_messages),
                "outputRoutedToAgent": observed_output_response.get("routedToAgent"),
                "outputAguiMessages": len(output_messages),
            },
            indent=2,
        ))
        return 0
    except urllib.error.HTTPError as error:
        body = ""
        try:
            body = error.read().decode("utf-8")
        except Exception:
            body = ""
        print(f"FAIL: HTTP {error.code} during smoke execution")
        if body:
            print(body)
        return 7
    except Exception as error:
        print(f"FAIL: smoke execution error: {error}")
        return 8
    finally:
        try:
            post_json(event_url, {"type": "session.close"}, timeout=10)
        except Exception:
            pass


if __name__ == "__main__":
    sys.exit(main())