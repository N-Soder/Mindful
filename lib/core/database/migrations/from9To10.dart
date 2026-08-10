// ignore_for_file: file_names

import 'package:drift/drift.dart';
import 'package:mindful/core/database/schemas/schema_versions.dart';
import 'package:mindful/core/utils/db_utils.dart';

/// FORK: Adds the cooldown gate's per-app configuration.
///
/// Both columns carry defaults, so existing rows need no backfill: every app comes
/// out of the migration with the gate off (`cooldownBreathSec` 0) and a 2 minute
/// window ready for whenever it's switched on.
Future<void> from9To10(Migrator m, Schema10 schema) async => await runSafe(
      "Migration(9 to 10)",
      () async {
        /// Add cooldown columns to [AppRestrictionTable]
        await m.addColumn(schema.appRestrictionTable,
            schema.appRestrictionTable.cooldownBreathSec);

        await m.addColumn(schema.appRestrictionTable,
            schema.appRestrictionTable.cooldownWindowSec);
      },
    );
