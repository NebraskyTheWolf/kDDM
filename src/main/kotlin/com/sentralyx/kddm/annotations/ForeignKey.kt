package com.sentralyx.kddm.annotations

import com.sentralyx.kddm.processors.ForeignKeyType

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.SOURCE)
annotation class ForeignKey(
    val targetTable: String,
    val targetName: String,
    val onDelete: ForeignKeyType = ForeignKeyType.NO_ACTION,
    val onUpdate: ForeignKeyType = ForeignKeyType.NO_ACTION
)
