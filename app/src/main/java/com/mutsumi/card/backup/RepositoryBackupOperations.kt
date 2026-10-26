package com.mutsumi.card.backup

import java.io.File

/** ZIP 只面向数据库快照与原子导入，不参与云端合并。 */
interface BackupDataAccess {
    suspend fun loadExportData(): BackupExportData
    suspend fun importBatch(batch: ImportBatch): ImportSummary
}

class RepositoryBackupOperations(
    dataAccess: BackupDataAccess,
    temporaryDirectory: File,
) : BackupOperations {
    private val operations = ValidatedBackupOperations(
        snapshotSource = BackupSnapshotSource { dataAccess.loadExportData() },
        service = BackupService(),
        validator = BackupValidator(),
        importer = BackupImporter(ImportGateway { dataAccess.importBatch(it) }),
        temporaryDirectory = temporaryDirectory,
    )

    override suspend fun export(output: java.io.OutputStream): ExportSummary = operations.export(output)
    override suspend fun import(input: java.io.InputStream): ImportSummary = operations.import(input)
}
