package io.github.lumiagg.gestureevo.gesture.config

import kotlinx.coroutines.sync.Mutex

/** Serialize runtime legacy migration with settings commits across the two preference stores. */
internal val gestureConfigurationWriteMutex = Mutex()
