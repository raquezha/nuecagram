@file:Suppress("TooManyFunctions")

package net.raquezha.nuecagram.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.db.CredentialCodec
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.db.redactedUrl
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.db.PlatformAdminAuditRecord
import net.raquezha.nuecagram.db.PlatformAdminReadRepository
import net.raquezha.nuecagram.db.PlatformAdminSessionContext
import org.koin.ktor.ext.inject
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.FlowContent
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.passwordInput
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.strong
import kotlinx.html.table
import kotlinx.html.unsafe
import kotlinx.html.textInput
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

private const val ADMIN_SESSION_COOKIE = "nuecagram_admin_session"
private const val LOGIN_CSRF_COOKIE = "nuecagram_admin_login_csrf"
private const val ADMIN_CSRF_COOKIE = "nuecagram_admin_csrf"
private const val ADMIN_SESSION_HOURS = 8L
private const val SECONDS_PER_MINUTE = 60L
private const val ADMIN_SESSION_SECONDS = ADMIN_SESSION_HOURS * SECONDS_PER_MINUTE * SECONDS_PER_MINUTE
private const val LOGIN_CSRF_SECONDS = 10 * SECONDS_PER_MINUTE
private const val MAX_LOGIN_FAILURES = 5
private const val MAX_PASSWORD_LENGTH = 1024
private const val LOGIN_WINDOW_MINUTES = 15L
private const val AUDIT_WINDOW_HOURS = 24L
private const val MAX_PREVIEW_ITEMS = 5
private const val SHORT_ID_LENGTH = 8
private const val PLATFORM_ADMIN_INSTALLATIONS_PAGE_SIZE = 20
private const val PLATFORM_ADMIN_AUDIT_PAGE_SIZE = 20

@Suppress("LongMethod")
fun Route.platformAdminRouting(basePath: String) {
    val config by inject<ConfigWithSecrets>()
    val installationRepository by inject<InstallationRepository>()
    val platformAdminReadRepository by inject<PlatformAdminReadRepository>()
    val databaseFactory by inject<DatabaseFactory>()
    val loginThrottle = LoginThrottle()

    get("$basePath/admin/login") {
        if (call.platformAdminSession(installationRepository) != null) {
            call.appendSecurityHeaders()
            call.respondRedirect("$basePath/admin")
            return@get
        }
        val csrf = CredentialCodec.issueCredential().first
        call.response.headers.append(HttpHeaders.SetCookie, loginCsrfCookie(basePath, csrf, call.isHttps()))
        call.respondManagementHtml(
            title = "Platform administration",
            body = adminLoginHtml(basePath, csrf),
        )
    }

    post("$basePath/admin/login") {
        val clientId = call.request.origin.remoteHost
        if (loginThrottle.isBlocked(clientId)) {
            call.response.headers.append(
                HttpHeaders.RetryAfter,
                (LOGIN_WINDOW_MINUTES * SECONDS_PER_MINUTE).toString(),
            )
            call.respondManagementHtml(
                status = HttpStatusCode.TooManyRequests,
                title = "Try again later",
                body = authMessageHtml("Try again later", "Too many failed login attempts."),
            )
            return@post
        }
        val form = call.receiveParameters()
        val csrf = form["csrf"].orEmpty()
        val csrfCookie = call.request.cookies[LOGIN_CSRF_COOKIE].orEmpty()
        if (!constantTimeEquals(csrf, csrfCookie)) {
            call.respondManagementHtml(
                status = HttpStatusCode.Forbidden,
                title = "Request rejected",
                body = authMessageHtml("Request rejected", "Reload the login page and try again."),
            )
            return@post
        }
        val password = form["password"].orEmpty()
        val authenticated =
            password.length in 1..MAX_PASSWORD_LENGTH &&
                constantTimeEquals(password, config.platformAdminPassword)
        if (!authenticated) {
            loginThrottle.recordFailure(clientId)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Login failed",
                body = adminLoginHtml(basePath, csrf, failed = true),
            )
            return@post
        }

        loginThrottle.clear(clientId)
        val session =
            installationRepository.issuePlatformAdminSession(
                Instant.now().plus(ADMIN_SESSION_HOURS, ChronoUnit.HOURS),
            )
        call.response.headers.append(
            HttpHeaders.SetCookie,
            adminSessionCookie(basePath, session.raw, call.isHttps()),
        )
        call.response.headers.append(
            HttpHeaders.SetCookie,
            cookie(
                ADMIN_CSRF_COOKIE,
                session.csrf,
                "$basePath/admin",
                ADMIN_SESSION_SECONDS,
                call.isHttps(),
            ),
        )
        call.response.headers.append(
            HttpHeaders.SetCookie,
            expiredCookie(LOGIN_CSRF_COOKIE, "$basePath/admin", call.isHttps()),
        )
        call.appendSecurityHeaders()
        call.respondRedirect("$basePath/admin")
    }

    get("$basePath/admin") {
        val session = call.platformAdminSession(installationRepository)
        if (session == null) {
            call.clearAdminSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Platform login required",
                body = adminLoginRequiredHtml(basePath),
            )
            return@get
        }
        val csrf = call.request.cookies[ADMIN_CSRF_COOKIE].orEmpty()
        if (!installationRepository.verifyPlatformAdminCsrf(session, csrf)) {
            call.clearAdminSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Platform login required",
                body = adminLoginRequiredHtml(basePath),
            )
            return@get
        }
        call.respondManagementHtml(
            title = "Platform administration",
            body =
                platformAdminHtml(
                    basePath = basePath,
                    csrf = csrf,
                    installations = platformAdminReadRepository.installations(),
                    auditEvents = platformAdminReadRepository.auditEvents(),
                    dbReady = databaseFactory.isReady(),
                ),
            rightHeaderHtml = adminLogoutHeaderButton(basePath, csrf),
        )
    }

    get("$basePath/admin/installations") {
        val session = call.platformAdminSession(installationRepository)
        if (session == null) {
            call.clearAdminSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Platform login required",
                body = adminLoginRequiredHtml(basePath),
            )
            return@get
        }
        val csrf = call.request.cookies[ADMIN_CSRF_COOKIE].orEmpty()
        if (!installationRepository.verifyPlatformAdminCsrf(session, csrf)) {
            call.clearAdminSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Platform login required",
                body = adminLoginRequiredHtml(basePath),
            )
            return@get
        }

        val search = call.request.queryParameters["search"]?.trim().orEmpty()
        val status = call.request.queryParameters["status"].platformAdminStatusFilter()
        val page = call.request.queryParameters["page"].toPositivePage()
        val installationsPage =
            platformAdminReadRepository.installationsPage(
                search = search.ifBlank { null },
                status = status,
                limit = PLATFORM_ADMIN_INSTALLATIONS_PAGE_SIZE,
                offset = (page - 1L) * PLATFORM_ADMIN_INSTALLATIONS_PAGE_SIZE,
            )

        call.respondManagementHtml(
            title = "Platform installations",
            body =
                platformAdminInstallationsHtml(
                    basePath = basePath,
                    search = search,
                    status = status,
                    page = page,
                    installations = installationsPage.items,
                    totalCount = installationsPage.totalCount,
                ),
            rightHeaderHtml = adminLogoutHeaderButton(basePath, csrf),
        )
    }

    get("$basePath/admin/audit") {
        val session = call.platformAdminSession(installationRepository)
        if (session == null) {
            call.clearAdminSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Platform login required",
                body = adminLoginRequiredHtml(basePath),
            )
            return@get
        }
        val csrf = call.request.cookies[ADMIN_CSRF_COOKIE].orEmpty()
        if (!installationRepository.verifyPlatformAdminCsrf(session, csrf)) {
            call.clearAdminSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Platform login required",
                body = adminLoginRequiredHtml(basePath),
            )
            return@get
        }

        val action = call.request.queryParameters["action"].platformAdminActionFilter()
        val page = call.request.queryParameters["page"].toPositivePage()
        val auditPage =
            platformAdminReadRepository.auditEventsPage(
                action = action,
                limit = PLATFORM_ADMIN_AUDIT_PAGE_SIZE,
                offset = (page - 1L) * PLATFORM_ADMIN_AUDIT_PAGE_SIZE,
            )

        call.respondManagementHtml(
            title = "Platform audit log",
            body =
                platformAdminAuditHtml(
                    basePath = basePath,
                    action = action,
                    page = page,
                    auditEvents = auditPage.items,
                    totalCount = auditPage.totalCount,
                ),
            rightHeaderHtml = adminLogoutHeaderButton(basePath, csrf),
        )
    }

    post("$basePath/admin/logout") {
        val session = call.platformAdminSession(installationRepository)
        val csrf = call.receiveParameters()["csrf"].orEmpty()
        if (session == null || !installationRepository.verifyPlatformAdminCsrf(session, csrf)) {
            call.respondManagementHtml(
                status = HttpStatusCode.Forbidden,
                title = "Request rejected",
                body = authMessageHtml("Request rejected", "The session or CSRF token is invalid."),
            )
            return@post
        }
        installationRepository.deletePlatformAdminSession(session.id)
        call.clearAdminSession(basePath)
        call.appendSecurityHeaders()
        call.respondRedirect("$basePath/admin/login")
    }
}

private suspend fun ApplicationCall.platformAdminSession(
    installationRepository: InstallationRepository,
): PlatformAdminSessionContext? =
    request.cookies[ADMIN_SESSION_COOKIE]
        ?.takeIf(String::isNotBlank)
        ?.let { installationRepository.verifyPlatformAdminSession(it) }

private fun ApplicationCall.clearAdminSession(basePath: String) {
    response.headers.append(
        HttpHeaders.SetCookie,
        expiredCookie(ADMIN_SESSION_COOKIE, "$basePath/admin", isHttps()),
    )
    response.headers.append(
        HttpHeaders.SetCookie,
        expiredCookie(ADMIN_CSRF_COOKIE, "$basePath/admin", isHttps()),
    )
}

private fun adminSessionCookie(basePath: String, value: String, secure: Boolean): String =
    cookie(
        name = ADMIN_SESSION_COOKIE,
        value = value,
        path = "$basePath/admin",
        maxAge = ADMIN_SESSION_SECONDS,
        secure = secure,
    )

private fun loginCsrfCookie(basePath: String, value: String, secure: Boolean): String =
    cookie(LOGIN_CSRF_COOKIE, value, "$basePath/admin", LOGIN_CSRF_SECONDS, secure)

private fun expiredCookie(name: String, path: String, secure: Boolean): String =
    cookie(name, "", path, 0, secure)

private fun cookie(
    name: String,
    value: String,
    path: String,
    maxAge: Long,
    secure: Boolean,
): String =
    buildString {
        append("$name=$value; Path=$path; Max-Age=$maxAge; HttpOnly; SameSite=Strict")
        if (secure) append("; Secure")
    }

private fun constantTimeEquals(left: String, right: String): Boolean =
    left.isNotEmpty() &&
        java.security.MessageDigest.isEqual(
            left.toByteArray(Charsets.UTF_8),
            right.toByteArray(Charsets.UTF_8),
        )

private fun adminLogoutHeaderButton(basePath: String, csrf: String): String =
    """
    <form method="post" action="${(basePath + "/admin/logout").html()}" class="header-form">
      <input type="hidden" name="csrf" value="${csrf.html()}">
      <button type="submit" class="header-btn btn-logout" aria-label="Log out">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
        <span>Log out</span>
      </button>
    </form>
    """.trimIndent()

private fun authMessageHtml(title: String, description: String): String =
    createHTML().div(classes = "auth-card") {
        h2 { +title }
        p(classes = "auth-desc") { +description }
    }

private fun adminLoginHtml(basePath: String, csrf: String, failed: Boolean = false): String =
    createHTML().div(classes = "auth-card") {
        h2 { +"Platform Login" }
        p(classes = "auth-desc") { +"Enter admin password to access system installations & audit logs." }
        if (failed) {
            div(classes = "auth-error") { +"Invalid admin password. Please try again." }
        }
        form(action = "$basePath/admin/login", method = kotlinx.html.FormMethod.post, classes = "auth-form") {
            hiddenInput { name = "csrf"; value = csrf }
            div(classes = "form-group") {
                label {
                    htmlFor = "admin-password"
                    +"Platform Password"
                }
                passwordInput(classes = "input-text") {
                    id = "admin-password"
                    name = "password"
                    placeholder = "Enter admin password"
                    required = true
                }
            }
            button(classes = "btn-primary") {
                type = kotlinx.html.ButtonType.submit
                +"Log in to Platform"
            }
        }
    }

private fun adminLoginRequiredHtml(basePath: String): String =
    createHTML().div(classes = "auth-card") {
        h2 { +"Platform Session Expired" }
        p(classes = "auth-desc") { +"Please log in to continue accessing platform administration." }
        a(href = "$basePath/admin/login", classes = "btn-docs") {
            +"Log In"
        }
    }

private fun adminAuditHref(basePath: String, action: String, page: Long): String {
    val query = buildList {
        if (action.isNotBlank()) add("action=${action.urlEncoded()}")
        if (page > 1) add("page=$page")
    }.joinToString("&")
    return if (query.isEmpty()) "$basePath/admin/audit" else "$basePath/admin/audit?$query"
}

private fun String?.platformAdminActionFilter(): String =
    when (this?.trim()?.lowercase()) {
        "setup" -> "setup"
        "rotate" -> "rotate"
        "status_change", "status-change", "status change" -> "status_change"
        else -> ""
    }

private fun platformAdminAuditHtml(
    basePath: String,
    action: String,
    page: Long,
    auditEvents: List<PlatformAdminAuditRecord>,
    totalCount: Long,
): String {
    val totalPages = platformAdminAuditTotalPages(totalCount)
    return createHTML().section(classes = "admin-shell") {
        auditExplorerHero(basePath)
        auditExplorerResultsPanel(basePath, action, page, totalPages, auditEvents, totalCount)
    }
}

private fun platformAdminAuditTotalPages(totalCount: Long): Long =
    ((totalCount + PLATFORM_ADMIN_AUDIT_PAGE_SIZE - 1) / PLATFORM_ADMIN_AUDIT_PAGE_SIZE)
        .coerceAtLeast(1)

private fun FlowContent.auditExplorerHero(basePath: String) {
    div(classes = "admin-hero") {
        span(classes = "admin-kicker") { +"Platform administration" }
        h1 { +"Audit log explorer" }
        p { +"Inspect historical audit events, filter by activity type, and trace platform state changes." }
        div {
            a(href = "$basePath/admin", classes = "table-link") { +"← Back to Overview" }
        }
    }
}

private fun FlowContent.auditActionLink(
    basePath: String,
    currentAction: String,
    targetAction: String,
    label: String,
) {
    a(
        href = adminAuditHref(basePath, targetAction, 1),
        classes = if (currentAction == targetAction) "segmented-btn segmented-btn-active" else "segmented-btn",
    ) { +label }
}

private fun FlowContent.auditExplorerResultsPanel(
    basePath: String,
    action: String,
    page: Long,
    totalPages: Long,
    auditEvents: List<PlatformAdminAuditRecord>,
    totalCount: Long,
) {
    val pageSize = PLATFORM_ADMIN_AUDIT_PAGE_SIZE
    val startItem = if (totalCount == 0L) 0L else (page - 1) * pageSize + 1
    val endItem = (page * pageSize).coerceAtMost(totalCount)

    div(classes = "admin-panel table-panel") {
        div(classes = "segmented-control") {
            attributes["style"] = "margin-bottom: 1rem;"
            auditActionLink(basePath, action, "", "All")
            auditActionLink(basePath, action, "setup", "Setup")
            auditActionLink(basePath, action, "rotate", "Rotate")
            auditActionLink(basePath, action, "status_change", "Status Change")
        }
        div(classes = "table-header-bar") {
            h3 { +"Audit Log" }
            span(classes = "results-count") { +"$totalCount total" }
        }
        if (auditEvents.isEmpty()) {
            div(classes = "empty-state") { +"No audit events match the current filter." }
        } else {
            div(classes = "table-wrapper") {
                table {
                    thead {
                        tr {
                            th { +"Timestamp" }
                            th { +"Repository" }
                            th { +"Action" }
                            th { +"Actor" }
                            th { +"Chat & Details" }
                        }
                    }
                    tbody { auditEvents.forEach(::platformAdminAuditRow) }
                }
            }
        }
        div(classes = "table-footer-bar") {
            span(classes = "pagination-info") { +"Showing $startItem–$endItem of $totalCount" }
            div(classes = "pagination-controls") {
                auditPaginationBtn(basePath, action, page > 1, page - 1, "‹ Prev")
                span(classes = "page-indicator") { +"$page / $totalPages" }
                auditPaginationBtn(basePath, action, page < totalPages, page + 1, "Next ›")
            }
        }
    }
}

private fun FlowContent.auditPaginationBtn(
    basePath: String,
    action: String,
    enabled: Boolean,
    targetPage: Long,
    label: String,
) {
    if (enabled) {
        a(href = adminAuditHref(basePath, action, targetPage), classes = "btn-pag") { +label }
    } else {
        span(classes = "btn-pag btn-pag-disabled") { +label }
    }
}

private const val CHAT_SVG_ICON =
    """<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" """ +
        """stroke-width="2" stroke-linecap="round" stroke-linejoin="round">""" +
        """<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>"""

private fun kotlinx.html.TBODY.platformAdminAuditRow(event: PlatformAdminAuditRecord) {
    tr {
        td(classes = "audit-timestamp") {
            attributes["title"] = event.createdAt.formatAuditFullTooltip()
            div(classes = "ts-time") { +event.createdAt.formatAuditTime() }
            div(classes = "ts-date") { +event.createdAt.formatAuditDate() }
        }
        td(classes = "audit-repo") {
            +event.repository
        }
        td {
            span(classes = auditActionBadgeClass(event.action)) {
                attributes["data-action"] = event.action
                +event.action.formatAuditActionLabel()
            }
        }
        td(classes = "audit-actor") {
            +event.actor
        }
        td(classes = "audit-details-cell") {
            if (event.chatDetails != "Unknown Chat") {
                div(classes = "chat-target") {
                    span(classes = "chat-icon") {
                        unsafe {
                            +CHAT_SVG_ICON
                        }
                    }
                    span(classes = "chat-text") { +event.chatDetails }
                }
            } else {
                span(classes = "table-subtle") {
                    attributes["title"] = "Unknown Chat"
                    +"—"
                }
            }
            if (event.details.isNotEmpty()) {
                div(classes = "detail-chips") {
                    event.details.forEach { line ->
                        renderAuditDetailChip(line)
                    }
                }
            }
        }
    }
}

private fun FlowContent.renderAuditDetailChip(line: String) {
    span(classes = "detail-chip") {
        if (line.contains(": ")) {
            val key = line.substringBefore(": ")
            val valStr = line.substringAfter(": ").replace("->", "→")
            span(classes = "chip-key") { +"$key:" }
            if (valStr.contains(" → ")) {
                val from = valStr.substringBefore(" → ")
                val to = valStr.substringAfter(" → ")
                span(classes = "chip-val-old") { +from }
                span(classes = "chip-arrow") { +"→" }
                span(classes = "chip-val-new") { +to }
            } else {
                span(classes = "chip-val") { +valStr }
            }
        } else {
            +line.replace("->", "→")
        }
    }
}

private fun Instant.formatAuditTime(): String {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US)
        .withZone(java.time.ZoneOffset.UTC)
    return formatter.format(this)
}

private fun Instant.formatAuditDate(): String {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd", java.util.Locale.US)
        .withZone(java.time.ZoneOffset.UTC)
    return formatter.format(this)
}

private fun Instant.formatAuditFullTooltip(): String {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        .withZone(java.time.ZoneOffset.UTC)
    return formatter.format(this) + " UTC"
}

private fun String.formatAuditActionLabel(): String =
    when (this) {
        "telegram_setup", "webapp_setup" -> "Setup"
        "telegram_webapp_launch" -> "Web App Launch"
        "telegram_rotate", "management_rotate", "webapp_rotate" -> "Rotate Token"
        "telegram_mute", "management_mute", "webapp_mute" -> "Muted"
        "telegram_unmute", "management_unmute", "webapp_unmute" -> "Unmuted"
        "webapp_identity_update" -> "Identity Update"
        else -> replace('_', ' ').replace('-', ' ').lowercase()
            .split(' ').joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }



private fun auditActionBadgeClass(action: String): String =
    when (action) {
        "telegram_setup", "telegram_webapp_launch", "webapp_setup" -> "status-badge status-active"
        "telegram_rotate", "management_rotate", "webapp_rotate" -> "status-badge status-degraded"
        "telegram_mute", "telegram_unmute", "management_mute", "management_unmute", "webapp_mute", "webapp_unmute" ->
            "status-badge status-muted"
        else -> "status-badge"
    }

private fun platformAdminInstallationsHtml(
    basePath: String,
    search: String,
    status: String,
    page: Long,
    installations: List<InstallationAdminContext>,
    totalCount: Long,
): String {
    val totalPages = platformAdminTotalPages(totalCount)
    return createHTML().section(classes = "admin-shell") {
        installationsDirectoryHero(basePath)
        installationsDirectoryFilterPanel(basePath, search, status)
        installationsDirectoryResultsPanel(basePath, search, status, page, totalPages, installations, totalCount)
    }
}

private fun platformAdminTotalPages(totalCount: Long): Long =
    ((totalCount + PLATFORM_ADMIN_INSTALLATIONS_PAGE_SIZE - 1) / PLATFORM_ADMIN_INSTALLATIONS_PAGE_SIZE)
        .coerceAtLeast(1)

private fun FlowContent.installationsDirectoryHero(basePath: String) {
    div(classes = "admin-hero") {
        span(classes = "admin-kicker") { +"Platform administration" }
        h1 { +"Installations directory" }
        p { +"Search, filter, and paginate installations without exposing private delivery details." }
        div {
            a(href = "$basePath/admin", classes = "table-link") { +"← Back to Overview" }
        }
    }
}

private const val SEARCH_SVG_ICON =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" """ +
        """stroke-width="2" stroke-linecap="round" stroke-linejoin="round">""" +
        """<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>"""

private const val CLEAR_SVG_ICON =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" """ +
        """stroke-width="2" stroke-linecap="round" stroke-linejoin="round">""" +
        """<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>"""

private fun FlowContent.installationsDirectoryFilterPanel(basePath: String, search: String, status: String) {
    div(classes = "admin-panel search-toolbar-panel") {
        form(action = "$basePath/admin/installations", method = kotlinx.html.FormMethod.get, classes = "toolbar-form") {
            div(classes = "search-input-group") {
                span(classes = "search-icon") {
                    unsafe {
                        +SEARCH_SVG_ICON
                    }
                }
                textInput(classes = "input-search") {
                    id = "installation-search"
                    name = "search"
                    this.value = search
                    placeholder = "Search by ID, GitLab URL, or Project ID..."
                }
                if (search.isNotBlank()) {
                    a(href = adminInstallationsHref(basePath, "", status, 1), classes = "search-clear-btn") {
                        attributes["aria-label"] = "Clear search"
                        unsafe {
                            +CLEAR_SVG_ICON
                        }
                    }
                }
                if (status.isNotBlank()) {
                    hiddenInput {
                        name = "status"
                        value = status
                    }
                }
            }
            div(classes = "segmented-control") {
                installationsStatusLink(basePath, search, status, "", "All")
                installationsStatusLink(basePath, search, status, "active", "Active")
                installationsStatusLink(basePath, search, status, "muted", "Muted")
            }
        }
    }
}

private fun FlowContent.installationsDirectoryResultsPanel(
    basePath: String,
    search: String,
    status: String,
    page: Long,
    totalPages: Long,
    installations: List<InstallationAdminContext>,
    totalCount: Long,
) {
    val pageSize = PLATFORM_ADMIN_INSTALLATIONS_PAGE_SIZE
    val startItem = if (totalCount == 0L) 0L else (page - 1) * pageSize + 1
    val endItem = (page * pageSize).coerceAtMost(totalCount)

    div(classes = "admin-panel table-panel") {
        div(classes = "table-header-bar") {
            h3 { +"Installations" }
            span(classes = "results-count") { +"$totalCount total" }
        }
        if (installations.isEmpty()) {
            div(classes = "empty-state") { +"No installations match the current search and filters." }
        } else {
            div(classes = "table-wrapper") {
                table {
                    thead {
                        tr {
                            th { +"ID" }
                            th { +"Repository" }
                            th { +"Notification Label" }
                            th { +"GitLab" }
                            th { +"Project" }
                            th { +"Status" }
                        }
                    }
                    tbody { installations.forEach(::platformAdminInstallationRow) }
                }
            }
        }
        div(classes = "table-footer-bar") {
            span(classes = "pagination-info") { +"Showing $startItem–$endItem of $totalCount" }
            div(classes = "pagination-controls") {
                installationsPaginationBtn(basePath, search, status, page > 1, page - 1, "‹ Prev")
                span(classes = "page-indicator") { +"$page / $totalPages" }
                installationsPaginationBtn(basePath, search, status, page < totalPages, page + 1, "Next ›")
            }
        }
    }
}

private fun FlowContent.installationsStatusLink(
    basePath: String,
    search: String,
    currentStatus: String,
    targetStatus: String,
    label: String,
) {
    a(
        href = adminInstallationsHref(basePath, search, targetStatus, 1),
        classes = if (currentStatus == targetStatus) "segmented-btn segmented-btn-active" else "segmented-btn",
    ) { +label }
}

private fun FlowContent.installationsPaginationBtn(
    basePath: String,
    search: String,
    status: String,
    enabled: Boolean,
    targetPage: Long,
    label: String,
) {
    if (enabled) {
        a(href = adminInstallationsHref(basePath, search, status, targetPage), classes = "btn-pag") { +label }
    } else {
        span(classes = "btn-pag btn-pag-disabled") { +label }
    }
}

private fun kotlinx.html.TBODY.platformAdminInstallationRow(installation: InstallationAdminContext) {
    tr {
        td { code { +installation.id.toString().take(SHORT_ID_LENGTH) } }
        td { +installation.repoName.redactedUrl() }
        td {
            val label = installation.chatName?.takeIf(String::isNotBlank)
            if (label != null) {
                +label
            } else {
                span(classes = "table-subtle") { +"(none)" }
            }
        }
        td {
            val gitlabUrl = installation.gitlabBaseUrl.redactedUrl()
            if (gitlabUrl.startsWith("http")) {
                a(href = gitlabUrl, target = "_blank", classes = "table-link") {
                    rel = "noopener"
                    +gitlabUrl
                }
            } else {
                +gitlabUrl
            }
        }
        td {
            if (installation.gitlabProjectId != null) {
                code { +installation.gitlabProjectId.toString() }
            } else {
                span { +"Group-level" }
            }
        }
        td {
            if (installation.muted) {
                span(classes = "status-badge status-muted") {
                    span(classes = "status-dot")
                    +"Muted"
                }
            } else {
                span(classes = "status-badge status-active") {
                    span(classes = "status-dot")
                    +"Active"
                }
            }
        }
    }
}

@Suppress("LongMethod", "MaxLineLength")
private fun platformAdminHtml(
    basePath: String,
    csrf: String,
    installations: List<InstallationAdminContext>,
    auditEvents: List<PlatformAdminAuditRecord>,
    dbReady: Boolean = true,
): String {
    val now = Instant.now()
    val activeCount = installations.count { !it.muted }
    val mutedCount = installations.count { it.muted }
    val audit24hCount = auditEvents.count { it.createdAt.isAfter(now.minus(AUDIT_WINDOW_HOURS, ChronoUnit.HOURS)) }
    val previewInstallations = installations.take(MAX_PREVIEW_ITEMS)
    val previewAuditEvents = auditEvents.take(MAX_PREVIEW_ITEMS)

    return createHTML().section(classes = "admin-shell") {
        div(classes = "admin-hero") {
            span(classes = "admin-kicker") { +"Platform administration" }
            h1 { +"Operations dashboard" }
            p { +"Inspect active installations, review recent audit activity, and sign out when you are done." }
            div(classes = "admin-meta") {
                div(classes = "admin-meta-card meta-installations") {
                    strong { +"Active installations" }
                    div(classes = "stat-val") { +activeCount.toString() }
                }
                div(classes = "admin-meta-card meta-muted") {
                    strong { +"Muted installations" }
                    div(classes = "stat-val") { +mutedCount.toString() }
                }
                div(classes = "admin-meta-card meta-audit") {
                    strong { +"24h Audit events" }
                    div(classes = "stat-val") { +audit24hCount.toString() }
                }
                div(classes = "admin-meta-card meta-status") {
                    strong { +"System status" }
                    div(classes = "stat-val") {
                        if (!dbReady) {
                            span(classes = "status-badge status-muted") {
                                span(classes = "status-dot")
                                +"Database Outage"
                            }
                        } else if (mutedCount > 0) {
                            span(classes = "status-badge status-degraded") {
                                span(classes = "status-dot")
                                +"Degraded ($mutedCount muted)"
                            }
                        } else {
                            span(classes = "status-badge status-active") {
                                span(classes = "status-dot")
                                +"Operational"
                            }
                        }
                    }
                }
            }
        }

        div(classes = "admin-panel") {
            h3 { +"Recent installations (5 max)" }
            div(classes = "table-wrapper") {
                table {
                    thead {
                        tr {
                            th { +"ID" }
                            th { +"Repository" }
                            th { +"Notification Label" }
                            th { +"GitLab" }
                            th { +"Project" }
                            th { +"Status" }
                        }
                    }
                    tbody {
                        if (previewInstallations.isEmpty()) {
                            tr {
                                td {
                                    colSpan = "6"
                                    +"No installations recorded yet"
                                }
                            }
                        } else {
                            previewInstallations.forEach { installation ->
                                val gitlabUrl = installation.gitlabBaseUrl.redactedUrl()
                                tr {
                                    td { code { +installation.id.toString().take(SHORT_ID_LENGTH) } }
                                    td { +installation.repoName.redactedUrl() }
                                    td {
                                        val label = installation.chatName?.takeIf(String::isNotBlank)
                                        if (label != null) {
                                            +label
                                        } else {
                                            span(classes = "table-subtle") { +"(none)" }
                                        }
                                    }
                                    td {
                                        if (gitlabUrl.startsWith("http")) {
                                            a(href = gitlabUrl, target = "_blank", classes = "table-link") {
                                                rel = "noopener"
                                                span { +gitlabUrl }
                                            }
                                        } else {
                                            +gitlabUrl
                                        }
                                    }
                                    td {
                                        if (installation.gitlabProjectId != null) {
                                            code { +installation.gitlabProjectId.toString() }
                                        } else {
                                            span { +"Group-level" }
                                        }
                                    }
                                    td {
                                        if (installation.muted) {
                                            span(classes = "status-badge status-muted") {
                                                span(classes = "status-dot")
                                                +"Muted"
                                            }
                                        } else {
                                            span(classes = "status-badge status-active") {
                                                span(classes = "status-dot")
                                                +"Active"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            div {
                a(href = "$basePath/admin/installations", classes = "table-link") {
                    +"View all installations →"
                }
            }
        }

        div(classes = "admin-panel") {
            h3 { +"Recent audit events (5 max)" }
            div(classes = "table-wrapper") {
                table {
                    thead {
                        tr {
                            th { +"Time" }
                            th { +"Repository" }
                            th { +"Action" }
                            th { +"Actor" }
                        }
                    }
                    tbody {
                        if (previewAuditEvents.isEmpty()) {
                            tr {
                                td {
                                    colSpan = "4"
                                    +"No audit activity in past 24 hours"
                                }
                            }
                        } else {
                            previewAuditEvents.forEach { event ->
                                tr {
                                    td { +event.createdAt.toString() }
                                    td { +event.repository }
                                    td { +event.action }
                                    td { +event.actor }
                                }
                            }
                        }
                    }
                }
            }
            div {
                a(href = "$basePath/admin/audit", classes = "table-link") {
                    +"Explore audit logs →"
                }
            }
        }

        div(classes = "admin-panel") {
            h3 { +"Recovery" }
            p { +"Credential recovery is delivered only through the verified Telegram administrator flow." }
        }
    }
}

private fun adminInstallationsHref(basePath: String, search: String, status: String, page: Long): String {
    val query = buildList {
        if (search.isNotBlank()) add("search=${search.urlEncoded()}")
        if (status.isNotBlank()) add("status=${status.urlEncoded()}")
        if (page > 1) add("page=$page")
    }.joinToString("&")
    return if (query.isEmpty()) "$basePath/admin/installations" else "$basePath/admin/installations?$query"
}

private fun String?.platformAdminStatusFilter(): String =
    when (this?.trim()?.lowercase()) {
        "active" -> "active"
        "muted" -> "muted"
        else -> ""
    }

private fun String?.toPositivePage(): Long = this?.toLongOrNull()?.takeIf { it > 0 } ?: 1L

private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)

private class LoginThrottle {
    // ponytail: process-local throttle; move to shared storage if the service runs multiple replicas.
    private val failures = mutableMapOf<String, ArrayDeque<Instant>>()

    @Synchronized
    fun isBlocked(clientId: String, now: Instant = Instant.now()): Boolean =
        recentFailures(clientId, now).size >= MAX_LOGIN_FAILURES

    @Synchronized
    fun recordFailure(clientId: String, now: Instant = Instant.now()) {
        recentFailures(clientId, now).addLast(now)
    }

    @Synchronized
    fun clear(clientId: String) {
        failures.remove(clientId)
    }

    private fun recentFailures(clientId: String, now: Instant): ArrayDeque<Instant> {
        val attempts = failures.getOrPut(clientId) { ArrayDeque() }
        val cutoff = now.minus(LOGIN_WINDOW_MINUTES, ChronoUnit.MINUTES)
        while (attempts.firstOrNull()?.isBefore(cutoff) == true) attempts.removeFirst()
        return attempts
    }
}
