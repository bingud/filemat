package org.filemat.server.common.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob


val globalCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())