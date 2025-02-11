package org.filemat.server.config

import com.github.f4b6a3.ulid.Ulid

object Props {

    val appName = "Filemat"
    val adminRoleId = Ulid.from("005QMX54X0AMAS9Z66WXDGY8EX")
    val userRoleId = Ulid.from("008BG034N8XRRQ1MKHXNF9Y7RR")
    val setupCodeFile = "/var/lib/filemat/setup-code.txt"

    object Settings {
        val appSetupCode = "application_setup_code"
        val isAppSetup = "is_application_setup"
    }


}