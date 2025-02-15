package org.filemat.server.config.auth

import org.filemat.server.module.permission.model.Permission
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

val endpointAuthMap: HashMap<String, EndpointAuth> = HashMap()

// Define the custom annotations.
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Authenticated(val permissions: Array<Permission> = [])

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Unauthenticated

// Data class holding endpoint authentication details.
data class EndpointAuth(
    val path: String,
    val httpMethod: RequestMethod,
    val authenticated: Boolean = true,
    val requiredPermissions: List<Permission> = emptyList()
)

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

            // Process the endpoint only if one of the annotations is present.
            if (authAnnotation == null && unauthAnnotation == null) return@forEach

            // If both are present, we prioritize @Authenticated.
            val (isAuthenticated, permissions) = if (authAnnotation != null) {
                true to authAnnotation.permissions.toList()
            } else {
                false to emptyList()
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
                        requiredPermissions = permissions
                    )
                    endpointAuthMap[pattern] = endpointAuth
                }
            }
        }
    }
}
