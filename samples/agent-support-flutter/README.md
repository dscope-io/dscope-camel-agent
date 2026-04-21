# Agent Support Flutter Sample

Flutter mobile client for the Camel Agent support sample.

It targets the same runtime exposed by [samples/agent-support-service](../agent-support-service/README.md) and uses:

- AG-UI over WebSocket for text chat via `/agui/rpc`
- A2UI payloads from agent responses to render native Flutter ticket cards
- direct OpenAI Realtime WebRTC with sample-managed `/realtime/session/{id}/init`, `/token`, and `/event` endpoints

## What This Sample Covers

- plan/version-aware AG-UI chat requests (`support/v1`, `support/v2`)
- locale-aware requests and A2UI catalog negotiation
- A2UI envelope normalization into a trusted native widget catalog
- microphone capture and OpenAI Realtime peer connection setup from Flutter
- transcript final events posted back to the sample backend so the agent still owns routing and structured UI generation

## Project Shape

This directory intentionally contains the Flutter app source and package metadata, but not generated platform folders.

Flutter is not installed in this workspace, so native wrappers could not be generated here. On a machine with Flutter installed, run:

```bash
cd samples/agent-support-flutter
flutter create . --platforms=android,ios
flutter pub get
flutter run
```

## Runtime Prerequisites

Start the backend sample first:

```bash
samples/agent-support-service/run-sample.sh
```

Then point the mobile client at a reachable host:

- Android emulator: `http://10.0.2.2:8080`
- iOS simulator: `http://127.0.0.1:8080`
- physical device: `http://<your-lan-ip>:8080`

The app exposes the base URL as an editable field in the header.

## Notes

- The A2UI renderer in this sample is intentionally narrow and secure: it only maps the known support ticket catalogs to trusted native widgets.
- If you later decide to swap in Google's GenUI renderer, the integration seam is isolated in the A2UI projection code under `lib/src/agent_support_api.dart` and the widget host in `lib/src/widgets/ticket_card_view.dart`.
- Voice mode uses direct WebRTC for media and the sample backend for init/token/transcript routing, matching the existing browser sample's split responsibility model.