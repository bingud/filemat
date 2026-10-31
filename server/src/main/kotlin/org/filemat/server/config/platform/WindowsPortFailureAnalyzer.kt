package org.filemat.server.config.platform

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.boot.web.server.PortInUseException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

/**
 * On Windows, a port that "was already in use" is often reserved by Hyper-V, WSL or Docker instead (§5.4).
 * Registered in META-INF/spring.factories, ahead of Spring Boot's own analyzer.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
class WindowsPortFailureAnalyzer : AbstractFailureAnalyzer<PortInUseException>() {

    override fun analyze(rootFailure: Throwable, cause: PortInUseException): FailureAnalysis? {
        if (!Platform.isWindows) return null
        return analysis(cause.port)
    }

    companion object {
        internal fun analysis(port: Int) = FailureAnalysis(
            "Filemat could not listen on port $port. Another program may be using it, or Windows reserved it: Hyper-V, WSL and Docker reserve ranges of ports.",
            "Run `netsh int ipv4 show excludedportrange protocol=tcp` to see the reserved ranges, and `netstat -ano | findstr :$port` to find a program using the port. " +
                "Stop that program, or start Filemat on another port with the SERVER_PORT environment variable or `filemat.cmd --server.port=<port>`.",
            null,
        )
    }
}
