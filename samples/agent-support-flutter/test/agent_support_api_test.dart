import 'package:agent_support_flutter/src/agent_support_api.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final api = AgentSupportApi();

  group('StructuredSurfaceModel', () {
    test('builds ticket-card surface from ticket payload', () {
      const projection = A2UiProjection(
        catalogId: 'urn:io.dscope.sample.support:a2ui:ticket-card:v2',
        template: 'ticket-card',
        locale: 'fr-CA',
        data: <String, dynamic>{
          'ticketId': 'TCK-42',
          'status': 'OPEN',
          'summary': 'Login issue',
          'assignedQueue': 'L1-SUPPORT',
          'message': 'Ticket created',
          'action': 'create',
          'i18n': <String, dynamic>{
            'title': 'Mise à jour du ticket',
            'labels': <String, dynamic>{
              'ticketActionTitle': r'Ticket ${action}',
              'idLabel': 'ID',
              'statusLabel': 'Statut',
              'queueLabel': 'File',
              'summaryLabel': 'Resume',
            },
          },
        },
      );

      final surface = StructuredSurfaceModel.fromA2Ui(projection);

      expect(surface.ticketCard, isNotNull);
      expect(surface.rendererKind, A2UiRendererKind.ticketCard);
      expect(surface.title, 'Ticket Create');
      expect(surface.fields.length, 4);
      expect(surface.badges, contains('fr-CA'));
    });

    test('builds generic structured surface for non-ticket payload', () {
      const projection = A2UiProjection(
        catalogId: 'urn:example:a2ui:status-panel:v1',
        template: 'status-panel',
        locale: 'en',
        surfaceId: 'status-panel-1',
        data: <String, dynamic>{
          'title': 'Runtime Status',
          'message': 'The system is healthy.',
          'healthState': 'green',
          'activeUsers': 12,
          'regions': <String>['us-east', 'eu-west'],
        },
      );

      final surface = StructuredSurfaceModel.fromA2Ui(projection);

      expect(surface.ticketCard, isNull);
      expect(surface.rendererKind, A2UiRendererKind.statusPanel);
      expect(surface.title, 'Runtime Status');
      expect(surface.summary, 'The system is healthy.');
      expect(surface.fields.any((field) => field.label == 'Health State' && field.value == 'green'), isTrue);
      expect(surface.fields.any((field) => field.label == 'Active Users' && field.value == '12'), isTrue);
      expect(surface.fields.any((field) => field.label == 'Regions' && field.value == 'us-east, eu-west'), isTrue);
      expect(surface.badges, contains('status-panel-1'));
    });

    test('builds legacy ticket surface from assistant text json', () {
      final surface = api.surfaceFromAssistantText(
        '{"ticketId":"TCK-77","status":"OPEN","summary":"Login issue","assignedQueue":"L1-SUPPORT","message":"Ticket created"}',
        locale: 'en',
        catalogId: 'legacy.agui.ticket-json',
      );

      expect(surface, isNotNull);
      expect(surface!.ticketCard, isNotNull);
      expect(surface.ticketCard!.ticketId, 'TCK-77');
      expect(surface.catalogId, 'legacy.agui.ticket-json');
    });

    test('keeps unknown ticket-like catalogs generic', () {
      const projection = A2UiProjection(
        catalogId: 'urn:example:a2ui:case-summary:v1',
        template: 'card',
        locale: 'en',
        data: <String, dynamic>{
          'ticketId': 'TCK-99',
          'status': 'OPEN',
          'summary': 'Case overview',
          'assignedQueue': 'Tier-2',
          'message': 'Structured summary',
        },
      );

      final surface = StructuredSurfaceModel.fromA2Ui(projection);

      expect(surface.rendererKind, A2UiRendererKind.generic);
      expect(surface.ticketCard, isNull);
      expect(surface.fields.any((field) => field.label == 'Ticket Id' && field.value == 'TCK-99'), isTrue);
    });
  });
}