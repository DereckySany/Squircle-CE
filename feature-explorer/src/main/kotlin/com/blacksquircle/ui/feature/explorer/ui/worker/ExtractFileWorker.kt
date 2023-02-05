/*
 * Copyright 2023 Squircle CE contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.blacksquircle.ui.feature.explorer.ui.worker

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Observer
import androidx.work.*
import com.blacksquircle.ui.core.data.factory.FilesystemFactory
import com.blacksquircle.ui.core.domain.coroutine.DispatcherProvider
import com.blacksquircle.ui.core.ui.extensions.createChannel
import com.blacksquircle.ui.core.ui.extensions.createNotification
import com.blacksquircle.ui.core.ui.extensions.showToast
import com.blacksquircle.ui.feature.explorer.R
import com.blacksquircle.ui.feature.explorer.data.utils.toData
import com.blacksquircle.ui.feature.explorer.data.utils.toFileList
import com.blacksquircle.ui.feature.explorer.data.utils.toFileModel
import com.blacksquircle.ui.filesystem.base.exception.*
import com.blacksquircle.ui.filesystem.base.model.FileModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import com.blacksquircle.ui.uikit.R as UiR

@HiltWorker
class ExtractFileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dispatcherProvider: DispatcherProvider,
    private val filesystemFactory: FilesystemFactory,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return withContext(dispatcherProvider.io()) {
            setForeground(createForegroundInfo())
            try {
                val fileList = inputData.toFileList()
                val source = fileList.first()
                val dest = fileList.last()

                val filesystem = filesystemFactory.create(dest.filesystemUuid)
                filesystem.extractFiles(source, dest)
                    .onStart { setProgress(dest.toData()) }
                    .collect()

                withContext(dispatcherProvider.mainThread()) {
                    applicationContext.showToast(R.string.message_done)
                }
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, e.message, e)
                withContext(dispatcherProvider.mainThread()) {
                    when (e) {
                        is FileNotFoundException -> {
                            applicationContext.showToast(R.string.message_file_not_found)
                        }
                        is FileAlreadyExistsException -> {
                            applicationContext.showToast(R.string.message_file_already_exists)
                        }
                        is UnsupportedArchiveException -> {
                            applicationContext.showToast(R.string.message_unsupported_archive)
                        }
                        is EncryptedArchiveException -> {
                            applicationContext.showToast(R.string.message_encrypted_archive)
                        }
                        is SplitArchiveException -> {
                            applicationContext.showToast(R.string.message_split_archive)
                        }
                        is InvalidArchiveException -> {
                            applicationContext.showToast(R.string.message_invalid_archive)
                        }
                        is CancellationException -> {
                            applicationContext.showToast(R.string.message_operation_cancelled)
                        }
                        is UnsupportedOperationException -> {
                            applicationContext.showToast(R.string.message_operation_not_supported)
                        }
                        else -> {
                            applicationContext.showToast(R.string.message_error_occurred)
                        }
                    }
                }
                Result.failure()
            }
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        applicationContext.createChannel(
            channelId = CHANNEL_ID,
            channelName = R.string.explorer_channel_name,
            channelDescription = R.string.explorer_channel_description,
        )

        val notification = applicationContext.createNotification(
            channelId = CHANNEL_ID,
            notificationTitle = applicationContext.getString(R.string.dialog_title_extracting),
            smallIcon = UiR.drawable.ic_file_clock,
            indeterminate = true,
            ongoing = true,
            silent = true,
            actions = listOf(
                NotificationCompat.Action(
                    UiR.drawable.ic_close,
                    applicationContext.getString(android.R.string.cancel),
                    WorkManager.getInstance(applicationContext)
                        .createCancelPendingIntent(id),
                ),
            ),
        )
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {

        private const val TAG = "ExtractFileWorker"
        private const val JOB_NAME = "extract-file"

        private const val CHANNEL_ID = "file-explorer"
        private const val NOTIFICATION_ID = 146

        fun scheduleJob(context: Context, fileList: List<FileModel>) {
            val workRequest = OneTimeWorkRequestBuilder<ExtractFileWorker>()
                .setInputData(fileList.toData())
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(JOB_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)
        }

        fun observeJob(context: Context): Flow<FileModel> {
            return callbackFlow {
                val workManager = WorkManager.getInstance(context)
                val workInfoLiveData = workManager.getWorkInfosForUniqueWorkLiveData(JOB_NAME)
                val observer = Observer<List<WorkInfo>> { workInfos ->
                    val workInfo = workInfos.findLast { !it.state.isFinished }
                    if (workInfo != null) {
                        trySend(workInfo.progress.toFileModel())
                    } else {
                        close(ClosedSendChannelException("Channel was closed"))
                    }
                }
                workInfoLiveData.observeForever(observer)
                awaitClose { workInfoLiveData.removeObserver(observer) }
            }
        }

        fun cancelJob(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(JOB_NAME)
        }
    }
}