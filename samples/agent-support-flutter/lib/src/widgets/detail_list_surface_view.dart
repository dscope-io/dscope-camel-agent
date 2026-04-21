import 'package:flutter/material.dart';

import '../agent_support_api.dart';

class DetailListSurfaceView extends StatelessWidget {
  const DetailListSurfaceView({super.key, required this.surface});

  final StructuredSurfaceModel surface;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        color: const Color(0xCC101A2D),
        border: Border.all(color: const Color(0xFF28456C)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(surface.title, style: theme.textTheme.titleMedium?.copyWith(color: Colors.white, fontWeight: FontWeight.w700)),
          if (surface.summary.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(surface.summary, style: theme.textTheme.bodyMedium?.copyWith(color: const Color(0xFFE7F0FF))),
          ],
          const SizedBox(height: 12),
          for (final field in surface.fields)
            Container(
              margin: const EdgeInsets.only(bottom: 8),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(14),
                color: const Color(0x14FFFFFF),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 120,
                    child: Text(field.label, style: theme.textTheme.bodySmall?.copyWith(color: const Color(0xFF9DB4DA), fontWeight: FontWeight.w600)),
                  ),
                  Expanded(
                    child: Text(field.value, style: theme.textTheme.bodyMedium?.copyWith(color: Colors.white, height: 1.35)),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}