package com.jmsbrain.rolodex.sync

import com.jmsbrain.rolodex.setUp
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlin.time.Duration.Companion.hours

private const val SYNC_JOB = "rolodex-sync"

/** Hourly sync in the background, when Android allows it. */
@LightJob("rolodex-sync")
val syncJob: LightJobHandler = { ctx, _ ->
    setUp(ctx.filesDir)
    CardSync.sync()
    LightJobResult.Success(outputFilePath = null, message = null)
}

object SyncSchedule {
    fun start(ctx: SealedLightContext) {
        LightWork.enqueuePeriodic(ctx, SYNC_JOB, 1.hours)
    }

    fun stop(ctx: SealedLightContext) = LightWork.cancel(ctx, SYNC_JOB)
}
