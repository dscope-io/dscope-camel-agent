import 'package:flutter/material.dart';

import '../agent_support_api.dart';

class StatusPanelView extends StatelessWidget {
  const StatusPanelView({super.key, required this.surface});

  final StructuredSurfaceModel surface;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final primaryField = surface.fields.isNotEmpty ? surface.fields.first : null;
    final remainingFields = primaryField == null ? surface.fields : surface.fields.skip(1).toList(growable: false);
    final tone = _toneFor(primaryField?.value ?? surface.summary);

    return Container(
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        gradient: const LinearGradient(
          colors: [Color(0xFF11243A), Color(0xFF0A1626)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        border: Border.all(color: tone.withValues(alpha: 0.5)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  surface.title,
                  style: theme.textTheme.titleMedium?.copyWith(color: Colors.white, fontWeight: FontWeight.w700),
                ),
              ),
              if (primaryField != null)
                _StatusBadge(label: '${primaryField.label}: ${primaryField.value}', color: tone),
            ],
          ),
          if (surface.summary.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(surface.summary, style: theme.textTheme.bodyMedium?.copyWith(color: const Color(0xFFE7F0FF))),
          ],
          if (remainingFields.isNotEmpty) ...[
            const SizedBox(height: 14),
            Wrap(
              spacing: 10,
              runSpacing: 10,
              children: remainingFields
                  .map((field) => _MetricTile(label: field.label, value: field.value))
                  .toList(growable: false),
            ),
          ],
        ],
      ),
    );
  }

  Color _toneFor(String value) {
    final lower = value.toLowerCase();
    if (lower.contains('green') || lower.contains('ok') || lower.contains('healthy') || lower.contains('open')) {
      return const Color(0xFF31D0AA);
    }
    if (lower.contains('warn') || lower.contains('yellow')) {
      return const Color(0xFFF4C15D);
    }
    if (lower.contains('red') || lower.contains('error') || lower.contains('critical')) {
      return const Color(0xFFFF6B6B);
    }
    return const Color(0xFF5AB3FF);
  }
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        color: color.withValues(alpha: 0.12),
        border: Border.all(color: color.withValues(alpha: 0.35)),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelMedium?.copyWith(color: color, fontWeight: FontWeight.w700),
      ),
    );
  }
}

class _MetricTile extends StatelessWidget {
  const _MetricTile({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: const BoxConstraints(minWidth: 140, maxWidth: 240),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(14),
          color: const Color(0x16FFFFFF),
          border: Border.all(color: const Color(0x3328456C)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: Theme.of(context).textTheme.labelMedium?.copyWith(color: const Color(0xFF9DB4DA))),
            const SizedBox(height: 6),
            Text(value, style: Theme.of(context).textTheme.titleMedium?.copyWith(color: Colors.white, fontWeight: FontWeight.w700)),
          ],
        ),
      ),
    );
  }
}