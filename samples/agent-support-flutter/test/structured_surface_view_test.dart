import 'package:agent_support_flutter/src/agent_support_api.dart';
import 'package:agent_support_flutter/src/widgets/structured_surface_view.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  Widget wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

  testWidgets('renders status panel view for status-panel template', (tester) async {
    const surface = StructuredSurfaceModel(
      catalogId: 'urn:example:a2ui:status-panel:v1',
      locale: 'en',
      template: 'status-panel',
      title: 'Runtime Status',
      summary: 'Everything is healthy.',
      badges: <String>['status-panel', 'en'],
      rendererKind: A2UiRendererKind.statusPanel,
      fields: <StructuredField>[
        StructuredField(label: 'Health State', value: 'green'),
        StructuredField(label: 'Active Users', value: '12'),
      ],
    );

    await tester.pumpWidget(wrap(const StructuredSurfaceView(surface: surface)));

    expect(find.text('Runtime Status'), findsOneWidget);
    expect(find.textContaining('Health State: green'), findsOneWidget);
    expect(find.text('12'), findsOneWidget);
  });

  testWidgets('renders detail list view for detail-list template', (tester) async {
    const surface = StructuredSurfaceModel(
      catalogId: 'urn:example:a2ui:detail-list:v1',
      locale: 'en',
      template: 'detail-list',
      title: 'Case Details',
      summary: 'Structured detail output.',
      badges: <String>['detail-list'],
      rendererKind: A2UiRendererKind.detailList,
      fields: <StructuredField>[
        StructuredField(label: 'Owner', value: 'Support Bot'),
        StructuredField(label: 'Priority', value: 'High'),
      ],
    );

    await tester.pumpWidget(wrap(const StructuredSurfaceView(surface: surface)));

    expect(find.text('Case Details'), findsOneWidget);
    expect(find.text('Owner'), findsOneWidget);
    expect(find.text('Support Bot'), findsOneWidget);
    expect(find.text('Priority'), findsOneWidget);
    expect(find.text('High'), findsOneWidget);
  });
}