/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Constraints
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.testing.TestDataSourceImpl
import com.google.android.fhir.testing.TestDownloadManagerImpl
import com.google.android.fhir.testing.TestFhirEngineImpl
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncInstrumentedTest {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private lateinit var workManager: WorkManager

  class TestSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    FhirSyncWorker(appContext, workerParams) {

    override fun getFhirEngine(): FhirEngine = TestFhirEngineImpl
    override fun getDataSource(): DataSource = TestDataSourceImpl
    override fun getDownloadWorkManager(): DownloadWorkManager = TestDownloadManagerImpl()
    override fun getConflictResolver() = AcceptRemoteConflictResolver
  }

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
    workManager = WorkManager.getInstance(context)
  }

  @Test
  fun oneTime_worker_runs() {
    runBlocking {
      Sync(workManager)
        .oneTimeSync<TestSyncWorker>()
        .transformWhile {
          emit(it)
          it !is SyncJobStatus.Finished
        }
        .shareIn(this, SharingStarted.Eagerly, 5)
    }

    assertThat(workManager.getWorkInfosByTag(TestSyncWorker::class.java.name).get().first().state)
      .isEqualTo(WorkInfo.State.SUCCEEDED)
  }

  @Test
  fun periodic_worker_runs_with_oneTime() {
    runBlocking {
      Sync(workManager)
        .periodicSync<TestSyncWorker>(
          periodicSyncConfiguration =
            PeriodicSyncConfiguration(
              syncConstraints = Constraints.Builder().build(),
              repeat = RepeatInterval(interval = 15, timeUnit = TimeUnit.MINUTES)
            ),
        )
        .transformWhile {
          emit(it)
          it !is SyncJobStatus.Finished
        }
        .shareIn(this, SharingStarted.Eagerly, 5)
    }

    val periodicWorkerId =
      workManager.getWorkInfosByTag(TestSyncWorker::class.java.name).get().first().id
    assertThat(workManager.getWorkInfoById(periodicWorkerId).get().state)
      .isEqualTo(WorkInfo.State.ENQUEUED)

    Sync(workManager).oneTimeSync<TestSyncWorker>()
    assertThat(workManager.getWorkInfosByTag(TestSyncWorker::class.java.name).get().size)
      .isEqualTo(2)

    val testDriver = WorkManagerTestInitHelper.getTestDriver(context)
    testDriver?.setPeriodDelayMet(periodicWorkerId)

    assertThat(workManager.getWorkInfoById(periodicWorkerId).get().state)
      .isEqualTo(WorkInfo.State.RUNNING)
  }
}
