package org.filemat.server.common.util

import com.sun.management.OperatingSystemMXBean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.lang.management.ManagementFactory


val globalCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())