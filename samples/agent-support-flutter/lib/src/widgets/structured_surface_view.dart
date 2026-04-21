import 'package:flutter/material.dart';

import '../agent_support_api.dart';
import 'detail_list_surface_view.dart';
import 'status_panel_view.dart';
import 'ticket_card_view.dart';

class StructuredSurfaceView extends StatelessWidget {
  const StructuredSurfaceView({super.key, required this.surface});

  final StructuredSurfaceModel surface;

  @override
  Widget build(BuildContext context) {
    if (surface.isTicketCard) {
      return TicketCardView(card: surface.ticketCard!);
    }

    if (surface.isStatusPanel) {
      return StatusPanelView(surface: surface);
    }

    if (surface.isDetailList) {
      return DetailListSurfaceView(surface: surface);
    }

    final theme = Theme.of(context);
    return Container(
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        gradient: const LinearGradient(
          colors: [Color(0xFF141E34), Color(0xFF0B1527)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        border: Border.all(color: const Color(0xFF315180)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            surface.title,
            style: theme.textTheme.titleMedium?.copyWith(
              color: Colors.white,
              fontWeight: FontWeight.w700,
            ),
          ),
          if (surface.summary.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(
              surface.summary,
              style: theme.textTheme.bodyMedium?.copyWith(color: const Color(0xFFE7F0FF)),
            ),
          ],
          if (surface.fields.isNotEmpty) ...[
            const SizedBox(height: 14),
            for (final field in surface.fields)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(
                      width: 110,
                      child: Text(
                        field.label,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: const Color(0xFF9DB4DA),
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    Expanded(
                      child: Text(
                        field.value,
                        style: theme.textTheme.bodyMedium?.copyWith(color: Colors.white, height: 1.35),
                      ),
                    ),
                  ],
                ),
              ),
          ],
          if (surface.badges.isNotEmpty) ...[
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: surface.badges
                  .where((badge) => badge.trim().isNotEmpty)
                  .map((badge) => _Badge(label: badge))
                  .toList(growable: false),
            ),
          ],
        ],
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        color: const Color(0x1418C6A0),
        border: Border.all(color: const Color(0x3328C5A0)),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: const Color(0xFF9CE4D6),
              fontWeight: FontWeight.w600,
            ),
      ),
    );
  }
}