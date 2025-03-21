package org.filemat.server.config.auth

import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.permission.model.Permission
import org.filemat.server.module.permission.model.SystemPermission
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Endpoint auth and configuration
 */
val endpointAuthMap: HashMap<String, EndpointAuth> = HashMap()


@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Authenticated(
    val systemPermissions: Array<SystemPermission> = [],
)

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Unauthenticated

/**
 * Allows an endpoint to be reached before the app is set up.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class BeforeSetup


data class EndpointAuth(
    val path: String,
    val httpMethod: RequestMethod,
    val authenticated: Boolean = true,
    val requiredPermissions: List<Permission> = emptyList(),
    val isBeforeSetup: Boolean,
)


/**
 * Component that collects annotations from endpoints
 *
 * For auth or configuration
 */
@Component
class AuthenticatedMappingConfig(
    private val requestMappingHandlerMapping: RequestMappingHandlerMapping
) {
    @EventListener(ApplicationReadyEvent::class)
    fun collectMappings() {
        endpointAuthMap.clear() // clear any previous mappings

        // Iterate over all Spring request mappings.
        val handlerMethods = requestMappingHandlerMapping.handlerMethods
        handlerMethods.forEach { (mappingInfo, handlerMethod) ->
            // Look for @Authenticated on the method; if not present, check the controller class.
            val methodAuthAnnotation = AnnotationUtils.findAnnotation(handlerMethod.method, Authenticated::class.java)
            val classAuthAnnotation = AnnotationUtils.findAnnotation(handlerMethod.beanType, Authenticated::class.java)
            val authAnnotation = methodAuthAnnotation ?: classAuthAnnotation

            // Look for @Unauthenticated on the method; if not present, check the controller class.
            val methodUnauthAnnotation = AnnotationUtils.findAnnotation(handlerMethod.method, Unauthenticated::class.java)
            val classUnauthAnnotation = AnnotationUtils.findAnnotation(handlerMethod.beanType, Unauthenticated::class.java)
            val unauthAnnotation = methodUnauthAnnotation ?: classUnauthAnnotation

            // Look for @BeforeSetup on the method; if not present, check the controller class.
            val methodBeforeSetupAnnotation = AnnotationUtils.findAnnotation(handlerMethod.method, BeforeSetup::class.java)
            val classBeforeSetupAnnotation = AnnotationUtils.findAnnotation(handlerMethod.beanType, BeforeSetup::class.java)
            val beforeSetupAnnotation = methodBeforeSetupAnnotation ?: classBeforeSetupAnnotation
            val isBeforeSetup = beforeSetupAnnotation != null

            // Authenticate endpoint by default, unless @Unauthenticated
            val (isAuthenticated, permissions) = when {
                // If there's an @Unauthenticated on either method or class
                unauthAnnotation != null -> false to emptyList<Permission>()

                // Otherwise, if there's @Authenticated (on method or class), use its permissions
                authAnnotation != null -> true to authAnnotation.systemPermissions.toList()

                // If neither annotation is present, default to authenticated with no permissions
                else -> true to emptyList<Permission>()
            }

            // Retrieve the mapping patterns (paths).
            val patterns = mappingInfo.patternsCondition?.patterns?.takeIf { it.isNotEmpty() }
                ?: mappingInfo.pathPatternsCondition?.patternValues.orEmpty()
            if (patterns.isEmpty()) return@forEach

            // Retrieve HTTP methods; default to GET if none specified.
            val httpMethods = if (mappingInfo.methodsCondition.methods.isEmpty())
                setOf(RequestMethod.GET)
            else
                mappingInfo.methodsCondition.methods

            // Create an EndpointAuth for every combination of path and HTTP method.
            for (pattern in patterns) {
                for (httpMethod in httpMethods) {
                    val endpointAuth = EndpointAuth(
                        path = pattern,
                        httpMethod = httpMethod,
                        authenticated = isAuthenticated,
                        requiredPermissions = permissions,
                        isBeforeSetup = isBeforeSetup
                    )
                    endpointAuthMap[pattern] = endpointAuth
                }
            }
        }

        println(endpointAuthMap)
    }
}
