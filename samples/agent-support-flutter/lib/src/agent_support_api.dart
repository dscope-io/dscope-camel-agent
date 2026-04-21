import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:web_socket_channel/io.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

class AgentSupportApi {
  AgentSupportApi({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;

  static const List<String> supportedCatalogIds = A2UiCatalogRegistry.supportedCatalogIds;

  String defaultBaseUrl() {
    if (Platform.isAndroid) {
      return 'http://10.0.2.2:8080';
    }
    return 'http://127.0.0.1:8080';
  }

  Future<List<AgUiEvent>> sendPromptViaWebSocket({
    required String baseUrl,
    required String sessionId,
    required String prompt,
    required String locale,
    required String planName,
    required String planVersion,
  }) async {
    final channel = _connect(_wsUri(baseUrl, '/agui/rpc'));
    final completer = Completer<List<AgUiEvent>>();
    final events = <AgUiEvent>[];
    late final StreamSubscription<dynamic> subscription;
    Timer? timeout;

    void finish([Object? error]) {
      timeout?.cancel();
      subscription.cancel();
      channel.sink.close();
      if (error != null) {
        if (!completer.isCompleted) {
          completer.completeError(error);
        }
        return;
      }
      if (!completer.isCompleted) {
        completer.complete(events);
      }
    }

    timeout = Timer(const Duration(seconds: 15), () {
      if (events.isEmpty) {
        finish(StateError('AG-UI WebSocket timed out without events'));
      } else {
        finish();
      }
    });

    subscription = channel.stream.listen(
      (dynamic raw) {
        final parsed = parseAgUiEvents(raw?.toString() ?? '');
        events.addAll(parsed);
        final runFinished = parsed.any((event) =>
            event.eventType == 'RUN_FINISHED' || event.eventType == 'RUN_ERROR');
        if (runFinished) {
          finish();
        }
      },
      onError: finish,
      onDone: () {
        if (events.isEmpty) {
          finish(StateError('AG-UI WebSocket closed without events'));
        } else {
          finish();
        }
      },
      cancelOnError: true,
    );

    channel.sink.add(jsonEncode({
      'threadId': sessionId,
      'sessionId': sessionId,
      'planName': planName,
      'planVersion': planVersion,
      'locale': locale,
      'a2uiSupportedCatalogIds': supportedCatalogIds,
      'messages': [
        {'role': 'user', 'content': prompt}
      ]
    }));

    return completer.future;
  }

  Future<Map<String, dynamic>> initializeRealtimeSession({
    required String baseUrl,
    required String sessionId,
    required String locale,
    required String planName,
    required String planVersion,
    required Map<String, dynamic> session,
  }) async {
    final response = await _client.post(
      _httpUri(baseUrl, '/realtime/session/$sessionId/init'),
      headers: _jsonHeaders(locale),
      body: jsonEncode({
        'session': session,
        'locale': locale,
        'planName': planName,
        'planVersion': planVersion,
        'a2uiSupportedCatalogIds': supportedCatalogIds,
      }),
    );
    return _decodeJsonResponse(response, 'Realtime init');
  }

  Future<String> requestRealtimeToken({
    required String baseUrl,
    required String sessionId,
    required String locale,
  }) async {
    final response = await _client.post(
      _httpUri(baseUrl, '/realtime/session/$sessionId/token'),
      headers: _jsonHeaders(locale),
      body: jsonEncode({
        'locale': locale,
        'a2uiSupportedCatalogIds': supportedCatalogIds,
      }),
    );
    final payload = _decodeJsonResponse(response, 'Realtime token');
    final token = payload['value'] ?? payload['client_secret']?['value'];
    if (token is String && token.trim().isNotEmpty) {
      return token.trim();
    }
    throw StateError('Realtime token response missing ephemeral key');
  }

  Future<Map<String, dynamic>> postTranscriptFinal({
    required String baseUrl,
    required String sessionId,
    required String transcript,
    required String locale,
    required String planName,
    required String planVersion,
  }) async {
    final response = await _client.post(
      _httpUri(baseUrl, '/realtime/session/$sessionId/event'),
      headers: _jsonHeaders(locale),
      body: jsonEncode({
        'type': 'transcript.final',
        'text': transcript,
        'locale': locale,
        'planName': planName,
        'planVersion': planVersion,
        'a2uiSupportedCatalogIds': supportedCatalogIds,
      }),
    );
    return _decodeJsonResponse(response, 'Realtime event');
  }

  Map<String, dynamic> buildRealtimeSessionConfig({
    required String locale,
    String voice = 'alloy',
  }) {
    final language = locale.split('-').first;
    return {
      'type': 'realtime',
      'model': 'gpt-realtime',
      'audio': {
        'input': {
          'transcription': {
            'model': 'gpt-4o-mini-transcribe',
            'language': language,
          },
          'turn_detection': {
            'type': 'server_vad',
            'silence_duration_ms': 1200,
          },
        },
        'output': {
          'voice': voice,
        },
      },
      'instructions': 'Respond using locale $locale. Keep answers concise and operational.',
    };
  }

  A2UiProjection? extractProjectionFromAgUiEvents(List<AgUiEvent> events) {
    for (final event in events) {
      final projection = _extractProjection(event.data['a2ui']) ??
          _extractProjection(event.data['payload']?['a2ui']) ??
          _extractProjection(event.data);
      if (projection != null) {
        return projection;
      }
    }
    return null;
  }

  A2UiProjection? extractProjectionFromRealtimeResponse(Map<String, dynamic> payload) {
    return _extractProjection(payload['a2ui']) ??
        _extractProjection(payload['payload']?['a2ui']);
  }

  TicketCardModel? ticketCardFromProjection(A2UiProjection? projection) {
    if (projection == null) {
      return null;
    }
    final rendererKind = A2UiCatalogRegistry.resolveRendererKind(
      catalogId: projection.catalogId,
      template: projection.template,
    );
    if (rendererKind != A2UiRendererKind.ticketCard) {
      return null;
    }
    return TicketCardModel.fromA2Ui(projection);
  }

  StructuredSurfaceModel? surfaceFromProjection(A2UiProjection? projection) {
    if (projection == null) {
      return null;
    }
    return StructuredSurfaceModel.fromA2Ui(projection);
  }

  StructuredSurfaceModel? surfaceFromAssistantText(
    String assistantText, {
    required String locale,
    String template = 'ticket-card',
    String catalogId = 'legacy.ticket-json',
  }) {
    final text = assistantText.trim();
    if (text.isEmpty || !text.startsWith('{') || !text.endsWith('}')) {
      return null;
    }
    try {
      final decoded = jsonDecode(text);
      if (decoded is! Map<String, dynamic>) {
        return null;
      }
      return StructuredSurfaceModel.fromA2Ui(
        A2UiProjection(
          catalogId: catalogId,
          template: template,
          locale: locale,
          data: decoded,
        ),
      );
    } catch (_) {
      return null;
    }
  }

  List<AgUiEvent> parseAgUiEvents(String payload) {
    final text = payload.trim();
    if (text.isEmpty) {
      return const <AgUiEvent>[];
    }
    if (text.contains('event:') && text.contains('data:')) {
      return _parseSseEvents(text);
    }
    try {
      final decoded = jsonDecode(text);
      if (decoded is List) {
        return decoded
            .map(_normalizeAgUiEvent)
            .whereType<AgUiEvent>()
            .toList(growable: false);
      }
      final single = _normalizeAgUiEvent(decoded);
      return single == null ? const <AgUiEvent>[] : <AgUiEvent>[single];
    } catch (_) {
      return const <AgUiEvent>[];
    }
  }

  String extractAssistantText(List<AgUiEvent> events) {
    final buffer = StringBuffer();
    for (final event in events) {
      if (event.eventType == 'TEXT_MESSAGE_CONTENT') {
        buffer.write(event.data['delta'] ?? '');
      }
    }
    return buffer.toString().trim();
  }

  String? extractTranscriptFromRealtimeEvent(Map<String, dynamic> event) {
    final type = (event['type'] ?? '').toString().toLowerCase();
    final item = event['item'];
    final role = item is Map ? (item['role'] ?? '').toString().toLowerCase() : '';
    final isTranscriptEvent = type.contains('input_audio_transcription') ||
        type.contains('transcription.completed') ||
        (type.contains('conversation.item.created') && role == 'user');
    if (!isTranscriptEvent) {
      return null;
    }

    final content = item is Map ? item['content'] : null;
    if (content is List) {
      for (final entry in content) {
        if (entry is! Map) {
          continue;
        }
        final transcript = _firstNonBlank([
          entry['transcript'],
          entry['text'],
          entry['input_audio_transcript'],
          entry['transcription'],
          entry['input_text'],
        ]);
        if (transcript != null) {
          return transcript;
        }
      }
    }

    return _firstNonBlank([
      event['transcript'],
      event['text'],
      event['delta'],
      event['payload']?['transcript'],
    ]);
  }

  void close() {
    _client.close();
  }

  WebSocketChannel _connect(Uri uri) => IOWebSocketChannel.connect(uri);

  Uri _httpUri(String baseUrl, String path) => Uri.parse(baseUrl).resolve(path);

  Uri _wsUri(String baseUrl, String path) {
    final source = Uri.parse(baseUrl);
    final scheme = source.scheme == 'https' ? 'wss' : 'ws';
    return source.replace(scheme: scheme, path: path, query: null, fragment: null);
  }

  Map<String, String> _jsonHeaders(String locale) => <String, String>{
        'Content-Type': 'application/json',
        'Accept-Language': locale,
      };

  Map<String, dynamic> _decodeJsonResponse(http.Response response, String label) {
    Map<String, dynamic> decoded = <String, dynamic>{};
    if (response.body.trim().isNotEmpty) {
      final dynamic parsed = jsonDecode(response.body);
      if (parsed is Map<String, dynamic>) {
        decoded = parsed;
      }
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      final error = decoded['error'] ?? decoded['message'] ?? response.body;
      throw StateError('$label failed: $error');
    }
    return decoded;
  }

  List<AgUiEvent> _parseSseEvents(String payload) {
    final chunks = payload.split(RegExp(r'\n\s*\n'));
    final events = <AgUiEvent>[];
    for (final chunk in chunks) {
      final lines = chunk.split('\n');
      String eventType = '';
      final dataLines = <String>[];
      for (final rawLine in lines) {
        final line = rawLine.trimRight();
        if (line.startsWith('event:')) {
          eventType = line.substring(6).trim();
        } else if (line.startsWith('data:')) {
          dataLines.add(line.substring(5).trim());
        }
      }
      if (eventType.isEmpty || dataLines.isEmpty) {
        continue;
      }
      try {
        final decoded = jsonDecode(dataLines.join('\n'));
        final normalized = _normalizeAgUiEvent(decoded, forcedEventType: eventType);
        if (normalized != null) {
          events.add(normalized);
        }
      } catch (_) {
      }
    }
    return events;
  }

  AgUiEvent? _normalizeAgUiEvent(dynamic raw, {String? forcedEventType}) {
    if (raw is! Map) {
      return null;
    }
    final rawMap = raw.cast<dynamic, dynamic>();
    final eventType = (forcedEventType ?? rawMap['eventType'] ?? rawMap['type'] ?? '').toString().trim();
    if (eventType.isEmpty) {
      return null;
    }
    final rawData = rawMap['data'];
    final data = rawData is Map
        ? rawData.cast<String, dynamic>()
        : raw.cast<String, dynamic>();
    return AgUiEvent(eventType: eventType, data: data);
  }

  A2UiProjection? _extractProjection(dynamic raw) {
    if (raw is! Map) {
      return null;
    }
    final payload = raw.cast<String, dynamic>();
    final rootCatalogId = _firstNonBlank([
      payload['catalogId'],
      payload['catalog'] is Map ? (payload['catalog'] as Map)['catalogId'] : null,
      payload['catalog'] is Map ? (payload['catalog'] as Map)['id'] : null,
    ]);
    final template = _firstNonBlank([payload['template']]);
    final locale = _firstNonBlank([payload['locale']]) ?? 'en';

    final envelopes = payload['envelopes'];
    if (envelopes is! List) {
      return null;
    }

    for (final envelope in envelopes) {
      if (envelope is! Map) {
        continue;
      }
      final updateDataModel = envelope['updateDataModel'];
      final createSurface = envelope['createSurface'];
      final value = updateDataModel is Map ? updateDataModel['value'] : null;
      if (value is! Map<String, dynamic>) {
        continue;
      }
      final catalogId = _firstNonBlank([
        createSurface is Map ? createSurface['catalogId'] : null,
        updateDataModel is Map ? updateDataModel['catalogId'] : null,
        rootCatalogId,
      ]);
      if (catalogId == null) {
        continue;
      }
      return A2UiProjection(
        catalogId: catalogId,
        template: template ?? 'ticket-card',
        locale: locale,
        data: value,
        surfaceId: _firstNonBlank([
              createSurface is Map ? createSurface['surfaceId'] : null,
              updateDataModel is Map ? updateDataModel['surfaceId'] : null,
              payload['surfaceId'],
            ]) ??
            '',
      );
    }
    return null;
  }

  String? _firstNonBlank(List<dynamic> values) {
    for (final value in values) {
      final text = value?.toString().trim() ?? '';
      if (text.isNotEmpty) {
        return text;
      }
    }
    return null;
  }
}

class AgUiEvent {
  const AgUiEvent({required this.eventType, required this.data});

  final String eventType;
  final Map<String, dynamic> data;
}

class ChatEntry {
  const ChatEntry({
    required this.role,
    required this.text,
    this.surface,
    this.source = '',
  });

  final ChatRole role;
  final String text;
  final StructuredSurfaceModel? surface;
  final String source;
}

enum ChatRole { user, assistant, system }

enum A2UiRendererKind { generic, ticketCard, statusPanel, detailList }

class A2UiProjection {
  const A2UiProjection({
    required this.catalogId,
    required this.template,
    required this.locale,
    required this.data,
    this.surfaceId = '',
  });

  final String catalogId;
  final String template;
  final String locale;
  final Map<String, dynamic> data;
  final String surfaceId;
}

class A2UiCatalogRegistry {
  static const String sampleTicketCardV1 = 'urn:io.dscope.sample.support:a2ui:ticket-card:v1';
  static const String sampleTicketCardV2 = 'urn:io.dscope.sample.support:a2ui:ticket-card:v2';

  static const List<String> supportedCatalogIds = <String>[
    sampleTicketCardV1,
    sampleTicketCardV2,
  ];

  static const Map<String, A2UiRendererKind> _rendererByCatalogId = <String, A2UiRendererKind>{
    sampleTicketCardV1: A2UiRendererKind.ticketCard,
    sampleTicketCardV2: A2UiRendererKind.ticketCard,
    'legacy.ticket-json': A2UiRendererKind.ticketCard,
    'legacy.agui.ticket-json': A2UiRendererKind.ticketCard,
    'legacy.realtime.ticket-json': A2UiRendererKind.ticketCard,
  };

  static A2UiRendererKind resolveRendererKind({
    required String catalogId,
    required String template,
  }) {
    final normalizedCatalogId = catalogId.trim().toLowerCase();
    final byCatalogId = _rendererByCatalogId[normalizedCatalogId];
    if (byCatalogId != null) {
      return byCatalogId;
    }

    final normalizedTemplate = template.trim().toLowerCase();
    if (normalizedTemplate == 'status-panel' || normalizedTemplate == 'status_panel') {
      return A2UiRendererKind.statusPanel;
    }
    if (normalizedTemplate == 'detail-list' ||
        normalizedTemplate == 'detail_list' ||
        normalizedTemplate == 'details') {
      return A2UiRendererKind.detailList;
    }
    return A2UiRendererKind.generic;
  }
}

class StructuredSurfaceModel {
  const StructuredSurfaceModel({
    required this.catalogId,
    required this.locale,
    required this.template,
    required this.title,
    required this.summary,
    required this.badges,
    required this.fields,
    this.rendererKind = A2UiRendererKind.generic,
    this.ticketCard,
  });

  final String catalogId;
  final String locale;
  final String template;
  final String title;
  final String summary;
  final List<String> badges;
  final List<StructuredField> fields;
  final A2UiRendererKind rendererKind;
  final TicketCardModel? ticketCard;

  bool get isTicketCard => rendererKind == A2UiRendererKind.ticketCard && ticketCard != null;

  bool get isStatusPanel => rendererKind == A2UiRendererKind.statusPanel;

  bool get isDetailList => rendererKind == A2UiRendererKind.detailList;

  factory StructuredSurfaceModel.fromA2Ui(A2UiProjection projection) {
    final rendererKind = A2UiCatalogRegistry.resolveRendererKind(
      catalogId: projection.catalogId,
      template: projection.template,
    );
    if (rendererKind == A2UiRendererKind.ticketCard) {
      final ticketCard = TicketCardModel.fromA2Ui(projection);
      return StructuredSurfaceModel(
        catalogId: projection.catalogId,
        locale: projection.locale,
        template: projection.template,
        title: ticketCard.title,
        summary: ticketCard.message,
        badges: <String>[projection.template, projection.catalogId, projection.locale],
        fields: <StructuredField>[
          StructuredField(label: ticketCard.idLabel, value: ticketCard.ticketId),
          StructuredField(label: ticketCard.statusLabel, value: ticketCard.status),
          StructuredField(label: ticketCard.queueLabel, value: ticketCard.assignedQueue),
          StructuredField(label: ticketCard.summaryLabel, value: ticketCard.summary),
        ],
        rendererKind: rendererKind,
        ticketCard: ticketCard,
      );
    }

    final data = projection.data;
    final i18n = data['i18n'];
    final labels = i18n is Map ? (i18n['labels'] as Map?) : null;
    final title = _firstText([
          data['title'],
          i18n is Map ? i18n['title'] : null,
          data['name'],
          projection.template,
          'Structured Response',
        ]) ??
        'Structured Response';
    final summary = _firstText([
          data['message'],
          data['summary'],
          data['description'],
          data['text'],
        ]) ??
        '';
    final excluded = <String>{'i18n', 'title', 'message'};
    final fields = <StructuredField>[];
    for (final entry in data.entries) {
      if (excluded.contains(entry.key) || entry.value == null) {
        continue;
      }
      final renderedValue = _stringifyFieldValue(entry.value);
      if (renderedValue.isEmpty) {
        continue;
      }
      fields.add(StructuredField(label: _labelForKey(entry.key, labels), value: renderedValue));
    }

    return StructuredSurfaceModel(
      catalogId: projection.catalogId,
      locale: projection.locale,
      template: projection.template,
      title: title,
      summary: summary,
      badges: <String>[
        projection.template,
        if (projection.surfaceId.isNotEmpty) projection.surfaceId,
        projection.catalogId,
        projection.locale,
      ],
      fields: fields,
      rendererKind: rendererKind,
    );
  }
}

class StructuredField {
  const StructuredField({required this.label, required this.value});

  final String label;
  final String value;
}

class TicketCardModel {
  const TicketCardModel({
    required this.catalogId,
    required this.locale,
    required this.title,
    required this.ticketId,
    required this.status,
    required this.summary,
    required this.assignedQueue,
    required this.message,
    required this.idLabel,
    required this.statusLabel,
    required this.queueLabel,
    required this.summaryLabel,
  });

  final String catalogId;
  final String locale;
  final String title;
  final String ticketId;
  final String status;
  final String summary;
  final String assignedQueue;
  final String message;
  final String idLabel;
  final String statusLabel;
  final String queueLabel;
  final String summaryLabel;

  static TicketCardModel fromA2Ui(A2UiProjection projection) {
    final data = projection.data;
    final i18n = data['i18n'];
    final labels = i18n is Map ? (i18n['labels'] as Map?) : null;
    final action = (data['action'] ?? '').toString().trim();
    final actionLabel = action.isEmpty
        ? ''
        : '${action[0].toUpperCase()}${action.substring(1).toLowerCase()}';
    final titleTemplate = labels?['ticketActionTitle'] ?? labels?['ticketTitle'] ?? i18n?['title'];
    final title = (titleTemplate?.toString() ?? 'Ticket Update').replaceAll(r'${action}', actionLabel);
    return TicketCardModel(
      catalogId: projection.catalogId,
      locale: projection.locale,
      title: title,
      ticketId: (data['ticketId'] ?? data['ticket_id'] ?? data['id'] ?? 'N/A').toString(),
      status: (data['status'] ?? 'OPEN').toString(),
      summary: (data['summary'] ?? data['issueDescription'] ?? '').toString(),
      assignedQueue: (data['assignedQueue'] ?? data['assigned_queue'] ?? data['queue'] ?? 'L1-SUPPORT').toString(),
      message: (data['message'] ?? '').toString(),
      idLabel: (labels?['idLabel'] ?? 'ID').toString(),
      statusLabel: (labels?['statusLabel'] ?? 'Status').toString(),
      queueLabel: (labels?['queueLabel'] ?? 'Queue').toString(),
      summaryLabel: (labels?['summaryLabel'] ?? 'Summary').toString(),
    );
  }
}

String? _firstText(List<dynamic> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) {
      return text;
    }
  }
  return null;
}

String _labelForKey(String key, Map? labels) {
  final direct = labels?[key]?.toString().trim() ?? '';
  if (direct.isNotEmpty) {
    return direct;
  }
  final normalized = key.replaceAll(RegExp(r'[_\-]+'), ' ');
  final withSpaces = normalized.replaceAllMapped(
    RegExp(r'([a-z0-9])([A-Z])'),
    (match) => '${match.group(1)} ${match.group(2)}',
  );
  final trimmed = withSpaces.trim();
  if (trimmed.isEmpty) {
    return key;
  }
  return trimmed[0].toUpperCase() + trimmed.substring(1);
}

String _stringifyFieldValue(dynamic value) {
  if (value == null) {
    return '';
  }
  if (value is String) {
    return value.trim();
  }
  if (value is num || value is bool) {
    return value.toString();
  }
  if (value is List) {
    final parts = value
        .map(_stringifyFieldValue)
        .where((item) => item.isNotEmpty)
        .toList(growable: false);
    return parts.join(', ');
  }
  if (value is Map) {
    return value.entries
        .map((entry) => '${_labelForKey(entry.key.toString(), null)}: ${_stringifyFieldValue(entry.value)}')
        .where((item) => !item.endsWith(': '))
        .join('\n');
  }
  return value.toString().trim();
}