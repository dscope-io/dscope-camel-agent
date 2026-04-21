import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:http/http.dart' as http;

import 'agent_support_api.dart';
import 'widgets/structured_surface_view.dart';

class AgentSupportApp extends StatelessWidget {
  const AgentSupportApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Agent Support Mobile',
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF09111F),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF5AB3FF),
          secondary: Color(0xFF31D0AA),
          surface: Color(0xFF101A2D),
        ),
        useMaterial3: true,
      ),
      home: const AgentSupportHomePage(),
    );
  }
}

class AgentSupportHomePage extends StatefulWidget {
  const AgentSupportHomePage({super.key});

  @override
  State<AgentSupportHomePage> createState() => _AgentSupportHomePageState();
}

class _AgentSupportHomePageState extends State<AgentSupportHomePage> {
  final AgentSupportApi _api = AgentSupportApi();
  final TextEditingController _promptController = TextEditingController(
    text: 'My login is failing, please open a support ticket',
  );
  final TextEditingController _baseUrlController = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  final List<ChatEntry> _messages = <ChatEntry>[];
  final List<String> _voiceLog = <String>[];

  String _sessionId = _newSessionId();
  String _locale = 'en';
  String _planName = 'support';
  String _planVersion = 'v2';
  bool _sending = false;
  bool _voiceConnecting = false;
  bool _voiceActive = false;
  String _status = 'Idle';

  RTCPeerConnection? _peerConnection;
  MediaStream? _localStream;
  RTCDataChannel? _dataChannel;
  final Set<String> _handledTranscripts = <String>{};

  @override
  void initState() {
    super.initState();
    _baseUrlController.text = _api.defaultBaseUrl();
    _messages.add(const ChatEntry(
      role: ChatRole.system,
      text: 'Connect the sample backend, then use AG-UI over WebSocket for chat or WebRTC for voice.',
      source: 'bootstrap',
    ));
  }

  @override
  void dispose() {
    _promptController.dispose();
    _baseUrlController.dispose();
    _scrollController.dispose();
    _stopVoice();
    _api.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            colors: [Color(0xFF09111F), Color(0xFF0D1A31), Color(0xFF14233D)],
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
          ),
        ),
        child: SafeArea(
          child: Column(
            children: [
              _buildHeader(context),
              Expanded(
                child: ListView.builder(
                  controller: _scrollController,
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                  itemCount: _messages.length,
                  itemBuilder: (context, index) => _MessageTile(entry: _messages[index]),
                ),
              ),
              _buildComposer(context),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildHeader(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: Column(
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Agent Support Mobile',
                      style: theme.textTheme.headlineSmall?.copyWith(
                        color: Colors.white,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'AG-UI over WebSocket, A2UI-native rendering, direct WebRTC voice.',
                      style: theme.textTheme.bodyMedium?.copyWith(color: const Color(0xFFA9BCD9)),
                    ),
                  ],
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(16),
                  color: const Color(0x1418C6A0),
                  border: Border.all(color: const Color(0x3328C5A0)),
                ),
                child: Text(
                  _status,
                  style: theme.textTheme.labelLarge?.copyWith(
                    color: const Color(0xFF9CE4D6),
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(24),
              color: const Color(0xAA101A2D),
              border: Border.all(color: const Color(0xFF223557)),
            ),
            child: Column(
              children: [
                TextField(
                  controller: _baseUrlController,
                  decoration: const InputDecoration(
                    labelText: 'Sample Base URL',
                    hintText: 'http://10.0.2.2:8080',
                  ),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(child: _DropdownField<String>(label: 'Plan', value: _planName, items: const ['support'], onChanged: (value) => setState(() => _planName = value))),
                    const SizedBox(width: 12),
                    Expanded(child: _DropdownField<String>(label: 'Version', value: _planVersion, items: const ['v1', 'v2'], onChanged: (value) => setState(() => _planVersion = value))),
                    const SizedBox(width: 12),
                    Expanded(child: _DropdownField<String>(label: 'Locale', value: _locale, items: const ['en', 'es', 'fr-CA'], onChanged: (value) => setState(() => _locale = value))),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: FilledButton.icon(
                        onPressed: _voiceConnecting ? null : _toggleVoice,
                        icon: Icon(_voiceActive ? Icons.stop_circle_outlined : Icons.mic_none_rounded),
                        label: Text(_voiceActive ? 'Stop WebRTC Voice' : 'Start WebRTC Voice'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: OutlinedButton.icon(
                        onPressed: _resetConversation,
                        icon: const Icon(Icons.restart_alt_rounded),
                        label: const Text('New Session'),
                      ),
                    ),
                  ],
                ),
                if (_voiceLog.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Container(
                    width: double.infinity,
                    constraints: const BoxConstraints(maxHeight: 110),
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(16),
                      color: const Color(0xAA0A1322),
                      border: Border.all(color: const Color(0xFF1D2E4A)),
                    ),
                    child: SingleChildScrollView(
                      child: Text(
                        _voiceLog.reversed.take(8).join('\n'),
                        style: theme.textTheme.bodySmall?.copyWith(color: const Color(0xFFA8BCD8), height: 1.4),
                      ),
                    ),
                  ),
                ]
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildComposer(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(24),
          color: const Color(0xCC101A2D),
          border: Border.all(color: const Color(0xFF223557)),
        ),
        child: Column(
          children: [
            TextField(
              controller: _promptController,
              minLines: 2,
              maxLines: 4,
              decoration: const InputDecoration(
                labelText: 'Ask the sample agent',
                hintText: 'My login is failing, please open a support ticket',
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _sending ? null : _sendPrompt,
                    icon: _sending
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.send_rounded),
                    label: const Text('Send via AG-UI WebSocket'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _sendPrompt() async {
    final prompt = _promptController.text.trim();
    if (prompt.isEmpty) {
      return;
    }
    setState(() {
      _sending = true;
      _status = 'Sending AG-UI request';
      _messages.add(ChatEntry(role: ChatRole.user, text: prompt, source: 'ag-ui.ws'));
    });
    _scrollToBottom();

    try {
      final events = await _api.sendPromptViaWebSocket(
        baseUrl: _baseUrlController.text.trim(),
        sessionId: _sessionId,
        prompt: prompt,
        locale: _locale,
        planName: _planName,
        planVersion: _planVersion,
      );
      final assistantText = _api.extractAssistantText(events);
      final projection = _api.extractProjectionFromAgUiEvents(events);
      final surface = _api.surfaceFromProjection(projection) ??
          _api.surfaceFromAssistantText(
            assistantText,
            locale: _locale,
            catalogId: 'legacy.agui.ticket-json',
          );
      setState(() {
        _messages.add(ChatEntry(
          role: ChatRole.assistant,
          text: _displayTextForAssistant(assistantText, surface),
          surface: surface,
          source: 'ag-ui.ws',
        ));
        _status = 'AG-UI run finished';
      });
      _scrollToBottom();
    } catch (error) {
      setState(() {
        _messages.add(ChatEntry(role: ChatRole.system, text: 'AG-UI request failed: $error', source: 'error'));
        _status = 'AG-UI failed';
      });
    } finally {
      if (mounted) {
        setState(() {
          _sending = false;
        });
      }
    }
  }

  Future<void> _toggleVoice() async {
    if (_voiceActive || _voiceConnecting) {
      await _stopVoice();
      return;
    }
    await _startVoice();
  }

  Future<void> _startVoice() async {
    setState(() {
      _voiceConnecting = true;
      _status = 'Preparing WebRTC';
    });
    _logVoice('Initializing realtime session for $_planName/$_planVersion ($_locale)');
    try {
      final sessionConfig = _api.buildRealtimeSessionConfig(locale: _locale);
      await _api.initializeRealtimeSession(
        baseUrl: _baseUrlController.text.trim(),
        sessionId: _sessionId,
        locale: _locale,
        planName: _planName,
        planVersion: _planVersion,
        session: sessionConfig,
      );
      final token = await _api.requestRealtimeToken(
        baseUrl: _baseUrlController.text.trim(),
        sessionId: _sessionId,
        locale: _locale,
      );

      final localStream = await navigator.mediaDevices.getUserMedia(<String, dynamic>{
        'audio': true,
        'video': false,
      });

      final peer = await createPeerConnection(<String, dynamic>{});
      _peerConnection = peer;
      _localStream = localStream;

      for (final track in localStream.getTracks()) {
        await peer.addTrack(track, localStream);
      }

      peer.onTrack = (RTCTrackEvent event) {
        if (event.track.kind == 'audio') {
          _logVoice('Remote audio track attached');
        }
      };

      final dataChannel = await peer.createDataChannel(
        'oai-events',
        RTCDataChannelInit()..ordered = true,
      );
      _dataChannel = dataChannel;

      dataChannel.onDataChannelState = (RTCDataChannelState state) {
        _logVoice('Data channel state: $state');
        if (state == RTCDataChannelState.RTCDataChannelOpen) {
          final update = <String, dynamic>{
            'type': 'session.update',
            'session': _api.buildRealtimeSessionConfig(locale: _locale),
          };
          dataChannel.send(RTCDataChannelMessage(jsonEncode(update)));
        }
      };
      dataChannel.onMessage = (RTCDataChannelMessage message) {
        unawaited(_handleRealtimeDataMessage(message.text));
      };

      final offer = await peer.createOffer(<String, dynamic>{'offerToReceiveAudio': true});
      await peer.setLocalDescription(offer);

      final sdpResponse = await http.post(
        Uri.parse('https://api.openai.com/v1/realtime/calls'),
        headers: <String, String>{
          'Authorization': 'Bearer $token',
          'Content-Type': 'application/sdp',
        },
        body: offer.sdp,
      );
      if (sdpResponse.statusCode < 200 || sdpResponse.statusCode >= 300) {
        throw StateError('Realtime SDP exchange failed: HTTP ${sdpResponse.statusCode}');
      }

      await peer.setRemoteDescription(RTCSessionDescription(sdpResponse.body, 'answer'));
      setState(() {
        _voiceActive = true;
        _status = 'WebRTC voice live';
      });
      _logVoice('WebRTC connected');
    } catch (error) {
      _logVoice('WebRTC start failed: $error');
      setState(() {
        _messages.add(ChatEntry(role: ChatRole.system, text: 'WebRTC start failed: $error', source: 'webrtc'));
        _status = 'WebRTC failed';
      });
      await _stopVoice();
    } finally {
      if (mounted) {
        setState(() {
          _voiceConnecting = false;
        });
      }
    }
  }

  Future<void> _stopVoice() async {
    await _dataChannel?.close();
    _dataChannel = null;
    await _peerConnection?.close();
    _peerConnection = null;
    for (final track in _localStream?.getTracks() ?? const <MediaStreamTrack>[]) {
      await track.stop();
    }
    await _localStream?.dispose();
    _localStream = null;
    if (mounted) {
      setState(() {
        _voiceActive = false;
        _voiceConnecting = false;
        _status = 'Voice stopped';
      });
    }
    _logVoice('WebRTC connection closed');
  }

  Future<void> _handleRealtimeDataMessage(String raw) async {
    if (raw.trim().isEmpty) {
      return;
    }
    Map<String, dynamic> event;
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic>) {
        return;
      }
      event = decoded;
    } catch (_) {
      return;
    }

    final type = (event['type'] ?? '').toString();
    if (type.isEmpty) {
      return;
    }

    if (type == 'error') {
      _logVoice('Realtime error: ${event['error'] ?? event['message'] ?? 'unknown'}');
      return;
    }

    final transcript = _api.extractTranscriptFromRealtimeEvent(event);
    if (transcript == null || transcript.trim().isEmpty) {
      return;
    }

    final key = '${event['item_id'] ?? event['id'] ?? type}:$transcript';
    if (_handledTranscripts.contains(key)) {
      return;
    }
    _handledTranscripts.add(key);

    _logVoice('Transcript final: $transcript');
    setState(() {
      _messages.add(ChatEntry(role: ChatRole.user, text: transcript, source: 'webrtc.transcript'));
      _status = 'Routing transcript';
    });
    _scrollToBottom();

    try {
      final routed = await _api.postTranscriptFinal(
        baseUrl: _baseUrlController.text.trim(),
        sessionId: _sessionId,
        transcript: transcript,
        locale: _locale,
        planName: _planName,
        planVersion: _planVersion,
      );
      final projection = _api.extractProjectionFromRealtimeResponse(routed);
      final assistantText = (routed['assistantMessage'] ?? '').toString().trim();
      final surface = _api.surfaceFromProjection(projection) ??
          _api.surfaceFromAssistantText(
            assistantText,
            locale: _locale,
            catalogId: 'legacy.realtime.ticket-json',
          );
      setState(() {
        _messages.add(ChatEntry(
          role: ChatRole.assistant,
          text: _displayTextForAssistant(assistantText, surface, emptyFallback: '(no assistant message)'),
          surface: surface,
          source: 'realtime.event',
        ));
        _status = 'Voice route completed';
      });
      _scrollToBottom();
    } catch (error) {
      setState(() {
        _messages.add(ChatEntry(role: ChatRole.system, text: 'Realtime route failed: $error', source: 'webrtc.error'));
        _status = 'Realtime route failed';
      });
    }
  }

  void _resetConversation() {
    setState(() {
      _sessionId = _newSessionId();
      _messages
        ..clear()
        ..add(const ChatEntry(
          role: ChatRole.system,
          text: 'Started a fresh mobile session.',
          source: 'session',
        ));
      _handledTranscripts.clear();
      _status = 'New session ready';
    });
  }

  void _logVoice(String message) {
    if (!mounted) {
      return;
    }
    setState(() {
      _voiceLog.add('[${DateTime.now().toIso8601String().substring(11, 19)}] $message');
      if (_voiceLog.length > 40) {
        _voiceLog.removeRange(0, _voiceLog.length - 40);
      }
    });
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) {
        return;
      }
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent + 200,
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOut,
      );
    });
  }

  String _displayTextForAssistant(
    String assistantText,
    StructuredSurfaceModel? surface, {
    String emptyFallback = '(no response)',
  }) {
    final trimmed = assistantText.trim();
    if (surface == null) {
      return trimmed.isEmpty ? emptyFallback : assistantText;
    }
    final looksLikeJson = trimmed.startsWith('{') && trimmed.endsWith('}');
    if (!looksLikeJson) {
      return trimmed.isEmpty ? emptyFallback : assistantText;
    }
    if (surface.summary.isNotEmpty) {
      return surface.summary;
    }
    if (surface.title.isNotEmpty) {
      return surface.title;
    }
    return emptyFallback;
  }

  static String _newSessionId() => 'mobile-${DateTime.now().millisecondsSinceEpoch}';
}

class _DropdownField<T> extends StatelessWidget {
  const _DropdownField({
    required this.label,
    required this.value,
    required this.items,
    required this.onChanged,
  });

  final String label;
  final T value;
  final List<T> items;
  final ValueChanged<T> onChanged;

  @override
  Widget build(BuildContext context) {
    return DropdownButtonFormField<T>(
      initialValue: value,
      decoration: InputDecoration(labelText: label),
      items: items
          .map((item) => DropdownMenuItem<T>(value: item, child: Text(item.toString())))
          .toList(growable: false),
      onChanged: (next) {
        if (next != null) {
          onChanged(next);
        }
      },
    );
  }
}

class _MessageTile extends StatelessWidget {
  const _MessageTile({required this.entry});

  final ChatEntry entry;

  @override
  Widget build(BuildContext context) {
    final isUser = entry.role == ChatRole.user;
    final isSystem = entry.role == ChatRole.system;
    final bubbleColor = isUser
        ? const Color(0xFF1C4F88)
        : isSystem
            ? const Color(0xFF3A2E11)
            : const Color(0xFF13203A);
    final align = isUser ? CrossAxisAlignment.end : CrossAxisAlignment.start;
    final textColor = isSystem ? const Color(0xFFF7D58A) : Colors.white;

    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: align,
        children: [
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(18),
                color: bubbleColor,
                border: Border.all(color: const Color(0xFF28456C)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (entry.source.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Text(
                        entry.source,
                        style: Theme.of(context).textTheme.labelSmall?.copyWith(
                              color: const Color(0xFF9DB4DA),
                              letterSpacing: 0.5,
                            ),
                      ),
                    ),
                  SelectableText(
                    entry.text,
                    style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: textColor, height: 1.45),
                  ),
                ],
              ),
            ),
          ),
          if (entry.surface != null)
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 520),
              child: StructuredSurfaceView(surface: entry.surface!),
            ),
        ],
      ),
    );
  }
}