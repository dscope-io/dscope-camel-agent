import 'package:flutter_test/flutter_test.dart';

import 'package:agent_support_flutter/src/agent_support_app.dart';

void main() {
  testWidgets('renders agent support shell', (WidgetTester tester) async {
    await tester.pumpWidget(const AgentSupportApp());

    expect(find.text('Agent Support Mobile'), findsOneWidget);
    expect(find.text('Send via AG-UI WebSocket'), findsOneWidget);
    expect(find.text('Start WebRTC Voice'), findsOneWidget);
  });
}
