@file:Suppress("TooManyFunctions")

package net.raquezha.nuecagram.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.h4
import kotlinx.html.hiddenInput
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.strong
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.unsafe
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.db.redactedUrl
import org.koin.ktor.ext.inject

private const val SESSION_COOKIE_NAME = "nuecagram_manage_session"
private const val CSRF_COOKIE_NAME = "nuecagram_manage_csrf"
private const val SESSION_TTL_HOURS = 8L
private const val SESSION_TTL_SECONDS = SESSION_TTL_HOURS * 60L * 60L
private const val ROTATION_GRACE_MINUTES = 0L
private const val SHORT_ID_LENGTH = 8

@Suppress("LongMethod", "CyclomaticComplexMethod")
fun Route.managementRouting(basePath: String) {
    val installationRepository by inject<InstallationRepository>()

    val rootPath = basePath.ifEmpty { "/" }
    get(rootPath) {
        call.respondManagementHtml(
            title = "Nuecagram setup",
            body = onboardingHtml(basePath),
        )
    }

    if (basePath.isNotEmpty()) {
        get("$basePath/") {
            call.respondManagementHtml(
                title = "Nuecagram setup",
                body = onboardingHtml(basePath),
            )
        }
    }

    get("$basePath/setup") {
        call.respondManagementHtml(
            title = "Nuecagram setup",
            body = onboardingHtml(basePath),
        )
    }

    get("$basePath/manage/{token}") {
        val token = call.parameters["token"]?.trim().orEmpty()
        if (token.isBlank()) {
            call.respondManagementHtml(
                status = HttpStatusCode.NotFound,
                title = "Link unavailable",
                body = recoveryHtml(basePath),
            )
            return@get
        }
        val session =
            installationRepository.exchangeManagementLinkForSession(
                raw = token,
                sessionExpiresAt = managementSessionExpiry(),
            )
        if (session == null) {
            call.respondManagementHtml(
                status = HttpStatusCode.Gone,
                title = "Link expired",
                body = recoveryHtml(basePath),
            )
            return@get
        }
        call.response.headers.append(
            HttpHeaders.SetCookie,
            buildSessionCookie(basePath, session.raw, call.isHttps()),
        )
        call.response.headers.append(
            HttpHeaders.SetCookie,
            buildCsrfCookie(basePath, session.csrf, call.isHttps()),
        )
        call.appendSecurityHeaders()
        call.respondRedirect("$basePath/manage")
    }

    get("$basePath/manage") {
        val session = call.managementSession(installationRepository)
        if (session == null) {
            call.clearManagementSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Session required",
                body = recoveryHtml(basePath),
            )
            return@get
        }
        val csrf = call.request.cookies[CSRF_COOKIE_NAME].orEmpty()
        if (!installationRepository.verifyManagementCsrf(session, csrf)) {
            call.clearManagementSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Session required",
                body = recoveryHtml(basePath),
            )
            return@get
        }
        val installation = installationRepository.installationAdminContext(session.installationId)
        if (installation == null) {
            call.clearManagementSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.NotFound,
                title = "Installation unavailable",
                body = recoveryHtml(basePath),
            )
            return@get
        }
        call.respondManagementHtml(
            title = "Manage installation",
            body = manageHtml(basePath, installation, csrf),
            rightHeaderHtml = manageLogoutHeaderButton(basePath, csrf),
        )
    }

    post("$basePath/manage/rotate") {
        val session = call.managementSession(installationRepository)
        if (session == null) {
            call.clearManagementSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Session required",
                body = recoveryHtml(basePath),
            )
            return@post
        }
        val csrf = call.receiveParameters()["csrf"].orEmpty()
        if (!installationRepository.verifyManagementCsrf(session, csrf)) {
            call.respondManagementHtml(
                status = HttpStatusCode.Forbidden,
                title = "Request rejected",
                body = rejectionHtml("Request rejected", "The CSRF token is invalid."),
            )
            return@post
        }
        val installation = installationRepository.installationAdminContext(session.installationId)
        if (installation == null) {
            call.clearManagementSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.NotFound,
                title = "Installation unavailable",
                body = recoveryHtml(basePath),
            )
            return@post
        }
        val credential =
            installationRepository.rotateWebhookSecret(
                installationId = installation.id,
                graceUntil = Instant.now().plus(ROTATION_GRACE_MINUTES, ChronoUnit.MINUTES),
            )
        installationRepository.writeAuditEvent(
            installationId = installation.id,
            actorType = "management_session",
            actorId = session.sessionId.toString(),
            action = "management_rotate",
        )
        call.respondManagementHtml(
            title = "Credential rotated",
            body = rotatedHtml(basePath, installation, credential.raw),
        )
    }

    post("$basePath/manage/mute") {
        val session = call.managementSession(installationRepository)
        if (session == null) {
            call.clearManagementSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Session required",
                body = recoveryHtml(basePath),
            )
            return@post
        }
        val form = call.receiveParameters()
        val csrf = form["csrf"].orEmpty()
        if (!installationRepository.verifyManagementCsrf(session, csrf)) {
            call.respondManagementHtml(
                status = HttpStatusCode.Forbidden,
                title = "Request rejected",
                body = rejectionHtml("Request rejected", "The CSRF token is invalid."),
            )
            return@post
        }
        val installation = installationRepository.installationAdminContext(session.installationId)
        if (installation == null) {
            call.clearManagementSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.NotFound,
                title = "Installation unavailable",
                body = recoveryHtml(basePath),
            )
            return@post
        }
        val muted = form["muted"]?.lowercase() == "true"
        installationRepository.setMuted(installation.id, muted)
        installationRepository.writeAuditEvent(
            installationId = installation.id,
            actorType = "management_session",
            actorId = session.sessionId.toString(),
            action = if (muted) "management_mute" else "management_unmute",
        )
        call.appendSecurityHeaders()
        call.respondRedirect("$basePath/manage")
    }

    post("$basePath/manage/logout") {
        val session = call.managementSession(installationRepository)
        val csrf = call.receiveParameters()["csrf"].orEmpty()
        if (session == null || !installationRepository.verifyManagementCsrf(session, csrf)) {
            call.respondManagementHtml(
                status = HttpStatusCode.Forbidden,
                title = "Request rejected",
                body = rejectionHtml("Request rejected", "The session or CSRF token is invalid."),
            )
            return@post
        }
        installationRepository.deleteManagementSession(session.sessionId)
        call.clearManagementSession(basePath)
        call.appendSecurityHeaders()
        call.respondRedirect(basePath.ifEmpty { "/" })
    }
}

private suspend fun ApplicationCall.managementSession(
    installationRepository: InstallationRepository,
) =
    request.cookies[SESSION_COOKIE_NAME]
        ?.takeIf(String::isNotBlank)
        ?.let { installationRepository.verifyManagementSession(it) }

private fun ApplicationCall.clearManagementSession(basePath: String) {
    response.headers.append(HttpHeaders.SetCookie, buildExpiredSessionCookie(basePath, isHttps()))
    response.headers.append(HttpHeaders.SetCookie, buildExpiredCsrfCookie(basePath, isHttps()))
}

internal suspend fun ApplicationCall.respondManagementHtml(
    title: String,
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    rightHeaderHtml: String? = null,
) {
    appendSecurityHeaders()
    respondText(
        managementDocument(title, body, rightHeaderHtml),
        ContentType.Text.Html,
        status,
    )
}

internal fun ApplicationCall.appendSecurityHeaders() {
    response.headers.append("Cache-Control", "no-store")
    response.headers.append("Pragma", "no-cache")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
    )
    if (isHttps()) {
        response.headers.append(
            "Strict-Transport-Security",
            "max-age=31536000; includeSubDomains",
        )
    }
}

internal fun ApplicationCall.isHttps(): Boolean =
    request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim().equals("https", ignoreCase = true)

private fun buildSessionCookie(
    basePath: String,
    value: String,
    secure: Boolean,
): String =
    buildString {
        append(SESSION_COOKIE_NAME)
        append('=')
        append(value)
        append("; Path=")
        append(basePath)
        append("/manage; Max-Age=")
        append(SESSION_TTL_SECONDS)
        append("; HttpOnly; SameSite=Strict")
        if (secure) append("; Secure")
    }

private fun buildCsrfCookie(
    basePath: String,
    value: String,
    secure: Boolean,
): String = buildCookie(CSRF_COOKIE_NAME, value, basePath, SESSION_TTL_SECONDS, secure)

private fun buildExpiredCsrfCookie(basePath: String, secure: Boolean): String =
    buildCookie(CSRF_COOKIE_NAME, "", basePath, 0, secure)

private fun buildCookie(
    name: String,
    value: String,
    basePath: String,
    maxAge: Long,
    secure: Boolean,
): String =
    buildString {
        append("$name=$value; Path=$basePath/manage; Max-Age=$maxAge; HttpOnly; SameSite=Lax")
        if (secure) append("; Secure")
    }

private fun buildExpiredSessionCookie(basePath: String, secure: Boolean): String =
    buildString {
        append(SESSION_COOKIE_NAME)
        append("=; Path=")
        append(basePath)
        append("/manage; Max-Age=0; HttpOnly; SameSite=Lax")
        if (secure) append("; Secure")
    }

private fun managementSessionExpiry(): Instant = Instant.now().plus(SESSION_TTL_HOURS, ChronoUnit.HOURS)

private const val EXTERNAL_LINK_SVG_ICON =
    """<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" """ +
        """stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-left: 0.35rem;">""" +
        """<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>""" +
        """<polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>"""

private fun manageLogoutHeaderButton(basePath: String, csrf: String): String =
    """
    <form method="post" action="${(basePath + "/manage/logout").html()}" class="header-form">
      <input type="hidden" name="csrf" value="${csrf.html()}">
      <button type="submit" class="header-btn btn-logout" aria-label="Log out">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
        <span>Log out</span>
      </button>
    </form>
    """.trimIndent()

private fun rejectionHtml(title: String, message: String): String =
    createHTML().div(classes = "auth-card") {
        h2 { +title }
        p(classes = "auth-desc") { +message }
    }

@Suppress("LongMethod")
private fun onboardingHtml(basePath: String): String =
    createHTML().div {
        h2 { +"How to Start in 3 Easy Steps" }

        div(classes = "step-card") {
            div(classes = "step-header") {
                span(classes = "step-num") { +"1" }
                h3 { +"Add Bot to Telegram Group" }
            }
            p {
                +"Add "
                a(href = "https://t.me/NuecagramBot", classes = "table-link") {
                    target = "_blank"
                    rel = "noopener"
                    strong { +"@NuecagramBot" }
                }
                +" to your destination Telegram group or forum topic, then promote it to "
                strong { +"Administrator" }
                +"."
            }
        }

        div(classes = "step-card") {
            div(classes = "step-header") {
                span(classes = "step-num") { +"2" }
                h3 { +"Start Private Onboarding" }
            }
            p {
                +"Send a private message to "
                strong { +"@NuecagramBot" }
                +" and click "
                strong { +"Start" }
                +" (or send "
                code(classes = "cmd") { +"/start" }
                +")."
            }
        }

        div(classes = "step-card") {
            div(classes = "step-header") {
                span(classes = "step-num") { +"3" }
                h3 { +"Connect your GitLab Repository" }
            }
            p { +"Inside your Telegram group or forum topic, send this command:" }
            p {
                span(classes = "cmd-label") { +"Telegram Command" }
                +" "
                code(classes = "cmd") { +"/setup" }
            }
            p {
                attributes["style"] = "margin-bottom: 0;"
                +("Nuecagram replies with an inline button for that group or topic; " +
                    "tap it to open the Web App wizard and reveal your webhook URL and token in-app.")
            }
        }

        h2 { +"Managing Your Notifications" }
        p {
            +"To rotate secrets, check status, or mute notifications, send "
            code(classes = "cmd") { +"/manage <installation-id>" }
            +" inside your Telegram group. "
            +"The bot will privately send you a single-use link to your web management dashboard."
        }

        h2 { +"Documentation & Resources" }
        div(classes = "docs-card") {
            div(classes = "docs-info") {
                h4 { +"Operations & Setup Guide" }
                p { +"Complete self-hosting guide, webhook configuration, and system architecture." }
            }
            a(href = "$basePath/docs", classes = "btn-docs") {
                span { +"View Documentation" }
                unsafe {
                    +("""<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" """ +
                        """stroke-width="2" stroke-linecap="round" stroke-linejoin="round" """ +
                        """style="margin-left: 0.4rem;"><line x1="5" y1="12" x2="19" y2="12"/>""" +
                        """<polyline points="12 5 19 12 12 19"/></svg>""")
                }
            }
        }
    }

private fun recoveryHtml(basePath: String): String =
    createHTML().section(classes = "auth-card recovery-card") {
        h1 { +"Recovery" }
        p(classes = "auth-desc") { +"This link is missing, expired, or already used." }
        p {
            +"Use Telegram private "
            code { +"/start" }
            +", then run "
            code { +"/manage <installation-id>" }
            +" or "
            code { +"/rotate <installation-id>" }
            +" in the installation group for a fresh management link."
        }
        p {
            a(href = basePath.ifEmpty { "/" }) { +"Back to setup" }
        }
    }

@Suppress("LongMethod")
private fun manageHtml(
    basePath: String,
    installation: InstallationAdminContext,
    csrf: String,
): String =
    createHTML().section(classes = "admin-shell") {
        div(classes = "admin-hero") {
            span(classes = "admin-kicker") { +"Installation Workstation" }
            h1 { +"Manage Installation" }
            p { +"Review active target metadata, rotate GitLab webhook credentials, or toggle delivery mute state." }
        }

        div(classes = "admin-panel") {
            h3 { +"Management Controls" }
            div(classes = "control-rows") {
                div(classes = "control-row") {
                    div(classes = "control-info") {
                        strong { +"Rotate Webhook Credential" }
                        p {
                            +"Revoke existing GitLab secret and issue a fresh token. "
                            +"Displayed once immediately after rotation."
                        }
                    }
                    div(classes = "control-action") {
                        details(classes = "confirm-dialog") {
                            summary(classes = "btn-primary") {
                                +"Rotate credential"
                            }
                            div(classes = "confirm-content") {
                                p {
                                    +"Are you sure you want to rotate the credential? "
                                    +"The existing secret token will be revoked immediately."
                                }
                                div(classes = "confirm-actions") {
                                    form(action = "$basePath/manage/rotate", method = kotlinx.html.FormMethod.post) {
                                        hiddenInput { name = "csrf"; value = csrf }
                                        button(classes = "btn-primary btn-danger") {
                                            type = kotlinx.html.ButtonType.submit
                                            +"Confirm Rotation"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                div(classes = "control-row") {
                    div(classes = "control-info") {
                        strong { +"Notification Delivery" }
                        p {
                            if (installation.muted) {
                                +"Notifications are currently muted. Unmute to resume alert dispatches."
                            } else {
                                +"Temporarily pause notification dispatches without revoking credentials."
                            }
                        }
                    }
                    div(classes = "control-action") {
                        details(classes = "confirm-dialog") {
                            summary(classes = if (installation.muted) "btn-primary" else "btn-secondary") {
                                +if (installation.muted) "Unmute notifications" else "Mute notifications"
                            }
                            div(classes = "confirm-content") {
                                p {
                                    if (installation.muted) {
                                        +"Are you sure you want to unmute notifications for this installation?"
                                    } else {
                                        +"Are you sure you want to mute notifications for this installation?"
                                    }
                                }
                                div(classes = "confirm-actions") {
                                    form(action = "$basePath/manage/mute", method = kotlinx.html.FormMethod.post) {
                                        hiddenInput { name = "csrf"; value = csrf }
                                        hiddenInput {
                                            name = "muted"
                                            value = if (installation.muted) "false" else "true"
                                        }
                                        button(classes = "btn-primary btn-danger") {
                                            type = kotlinx.html.ButtonType.submit
                                            +if (installation.muted) "Confirm Unmute" else "Confirm Mute"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            div(classes = "recovery-notice") {
                span {
                    +"Need access to this session later? Run "
                    code { +"/manage ${installation.id.toString().take(SHORT_ID_LENGTH)}" }
                    +" inside your Telegram group to issue a new single-use link."
                }
            }
        }

        div(classes = "admin-panel") {
            h3 { +"Installation Details" }
            div(classes = "table-wrapper") {
                table {
                    thead {
                        tr {
                            th { +"ID" }
                            th { +"GitLab Base URL" }
                            th { +"Project ID" }
                            th { +"Telegram Destination" }
                            th { +"State" }
                        }
                    }
                    tbody {
                        tr {
                            td { code { +installation.id.toString().take(SHORT_ID_LENGTH) } }
                            td {
                                val gitlabUrl = installation.gitlabBaseUrl.redactedUrl()
                                if (gitlabUrl.startsWith("http")) {
                                    a(href = gitlabUrl, target = "_blank", classes = "table-link") {
                                        rel = "noopener"
                                        span { +gitlabUrl }
                                        unsafe {
                                            +EXTERNAL_LINK_SVG_ICON
                                        }
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
                                if (installation.telegramTopicId != null) {
                                    code { +"Topic #${installation.telegramTopicId}" }
                                } else {
                                    span { +"Group Chat" }
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



private fun rotatedHtml(
    basePath: String,
    installation: InstallationAdminContext,
    credential: String,
): String =
    createHTML().section(classes = "admin-shell") {
        div(classes = "admin-panel") {
            attributes["style"] = "border-left: 4px solid #3b8b68;"
            h3 { +"Credential Rotated" }
            p {
                strong { +"Installation:" }
                +" "
                code { +installation.id.toString() }
            }
            p {
                strong { +"GitLab credential:" }
                +" "
                code { +credential }
            }
            p { +"Store it now. This page is the only place the raw credential is shown." }
            p {
                a(href = "$basePath/manage", classes = "btn-docs") { +"Return to management" }
            }
        }
    }


@Suppress("LongMethod")
internal fun managementDocument(
    title: String,
    body: String,
    rightHeaderHtml: String? = null,
): String {
    val version = net.raquezha.nuecagram.appVersion()
    return """
    <!doctype html>
    <html lang="en">
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="theme-color" content="#eee4d5">
        <link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='6' fill='%232c251e'/%3E%3Cpath d='M10 22V10l12 12V10' stroke='%23ffffff' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E">
        <title>${title.html()}</title>
        <link rel="preconnect" href="https://fonts.googleapis.com">
        <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
        <link href="https://fonts.googleapis.com/css2?family=Reddit+Mono:ital,wght@0,200..900;1,200..900&family=Space+Grotesk:wght@300..700&display=swap" rel="stylesheet">
        <style>
          html { background-color: #eee4d5; background-image: radial-gradient(ellipse at center, rgba(255,255,255,0.4) 0%, rgba(205,185,160,0.3) 100%), url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='1600' height='1600'%3E%3Cfilter id='organic-crunchy'%3E%3CfeTurbulence type='turbulence' baseFrequency='0.005 0.011' numOctaves='3' result='folds'/%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.025 0.045' numOctaves='5' result='grain'/%3E%3CfeComposite in='folds' in2='grain' operator='arithmetic' k1='0.5' k2='0.5' k3='0' k4='0' result='combined'/%3E%3CfeDiffuseLighting in='combined' lighting-color='%23ffffff' surfaceScale='12' result='diffuse'%3E%3CfeDistantLight azimuth='55' elevation='35'/%3E%3C/feDiffuseLighting%3E%3CfeSpecularLighting in='combined' surfaceScale='9' specularConstant='1.2' specularExponent='8' lighting-color='%23ffffff' result='specular'%3E%3CfeDistantLight azimuth='55' elevation='35'/%3E%3C/feSpecularLighting%3E%3CfeArithmetic in='diffuse' in2='specular' k1='0' k2='0.85' k3='0.85' k4='0' result='lightMap'/%3E%3CfeComponentTransfer in='lightMap'%3E%3CfeFuncR type='linear' slope='0.88' intercept='0.12'/%3E%3CfeFuncG type='linear' slope='0.84' intercept='0.12'/%3E%3CfeFuncB type='linear' slope='0.78' intercept='0.12'/%3E%3C/feComponentTransfer%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23organic-crunchy)' opacity='0.48'/%3E%3C/svg%3E"); background-repeat: repeat; min-height: 100vh; padding: 0.75rem; box-sizing: border-box; }
          body { font-family: 'Reddit Mono', monospace; margin: 0 auto; max-width: 48rem; line-height: 1.6; padding: 1.25rem; color: #2c251e; background-color: #f6f2ec; background-image: radial-gradient(ellipse at center, rgba(255,255,255,0.5) 0%, rgba(225,215,200,0.3) 100%), url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='600' height='600'%3E%3Cfilter id='crumpled'%3E%3CfeTurbulence type='turbulence' baseFrequency='0.015 0.035' numOctaves='6' result='noise'/%3E%3CfeDiffuseLighting in='noise' lighting-color='%23ffffff' surfaceScale='14' result='diffuse'%3E%3CfeDistantLight azimuth='45' elevation='35'/%3E%3C/feDiffuseLighting%3E%3CfeSpecularLighting in='noise' surfaceScale='12' specularConstant='1.8' specularExponent='6' lighting-color='%23ffffff' result='specular'%3E%3CfeDistantLight azimuth='45' elevation='35'/%3E%3C/feSpecularLighting%3E%3CfeArithmetic in='diffuse' in2='specular' k1='0' k2='0.8' k3='0.8' k4='0' result='lightMap'/%3E%3CfeComponentTransfer in='lightMap'%3E%3CfeFuncR type='linear' slope='0.85' intercept='0.15'/%3E%3CfeFuncG type='linear' slope='0.82' intercept='0.15'/%3E%3CfeFuncB type='linear' slope='0.75' intercept='0.15'/%3E%3C/feComponentTransfer%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23crumpled)' opacity='0.65'/%3E%3C/svg%3E"); border: 1px solid #c8b9a6; border-radius: 0.75rem; box-shadow: 0 20px 60px rgba(45, 30, 15, 0.25), inset 0 0 100px rgba(195, 175, 150, 0.2); }
          code { font-family: 'Reddit Mono', monospace; background: #eae1d5; color: #8b3a00; padding: 0.15rem 0.4rem; border-radius: 0.25rem; font-size: 0.9em; }
          code.cmd { font-family: 'Reddit Mono', monospace; background: #229ed9; color: #ffffff; padding: 0.2rem 0.5rem; border-radius: 0.25rem; font-weight: 600; font-size: 0.9em; }
          .cmd-label { display: inline-block; font-family: 'Space Grotesk', sans-serif; font-size: 0.7rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em; background: #1c8bc0; color: #ffffff; padding: 0.15rem 0.4rem; border-radius: 0.25rem; margin-right: 0.3rem; }
          .step-header { display: flex; align-items: center; gap: 0.6rem; margin-bottom: 0.5rem; }
          .step-num { display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; background: #2c251e; color: #ffffff; font-family: 'Space Grotesk', sans-serif; font-size: 0.85rem; font-weight: 700; border-radius: 50%; }
          button { font-family: 'Space Grotesk', sans-serif; font-weight: 600; padding: 0.5rem 1.2rem; background: #2c251e; color: #ffffff; border: none; border-radius: 0.375rem; cursor: pointer; transition: background 0.2s ease; }
          button:hover { background: #0088cc; }
          form { margin: 1rem 0; }
          .header-form { margin: 0; padding: 0; display: flex; align-items: center; width: 100%; height: 36px; }
          .site-header { margin-bottom: 1rem; }
          .site-header .header-top { display: flex; flex-direction: column; align-items: stretch; gap: 0.9rem; }
          .site-header .title { font-family: 'Space Grotesk', sans-serif; font-size: 2rem; font-weight: 700; margin: 0; text-transform: lowercase; letter-spacing: -0.03em; color: #1a1612; text-align: center; }
          .site-header .right-meta { display: flex; flex-direction: column; align-items: stretch; gap: 0.5rem; width: 100%; }
          .header-btn { display: inline-flex; align-items: center; justify-content: center; gap: 0.45rem; height: 36px; padding: 0 0.85rem; border-radius: 0.375rem; font-family: 'Space Grotesk', sans-serif; font-size: 0.85rem; font-weight: 600; text-decoration: none; box-sizing: border-box; transition: all 0.2s ease; line-height: 1; border: 1px solid transparent; margin: 0; vertical-align: middle; width: 100%; }
          .header-form .header-btn { width: 100%; }
          .btn-telegram { background-color: #229ed9; color: #ffffff; border-color: #1c8bc0; }
          .btn-telegram:hover { background-color: #1c8cc3; color: #ffffff; }
          .btn-github { background-color: #2c251e; color: #ffffff; border-color: #1a1612; }
          .btn-github:hover { background-color: #1a1612; color: #ffffff; }
          .btn-logout { background-color: #ffffff; color: #a62b1e; border-color: #dfd5c6; cursor: pointer; }
          .btn-logout:hover { background-color: #a62b1e; color: #ffffff; border-color: #a62b1e; }
          .btn-primary, .btn-secondary { display: inline-flex; align-items: center; justify-content: center; font-family: 'Space Grotesk', sans-serif; font-weight: 600; font-size: 0.875rem; padding: 0.55rem 1.1rem; border-radius: 0.375rem; border: none; cursor: pointer; transition: all 0.18s ease; box-sizing: border-box; text-decoration: none; white-space: nowrap; }
          .btn-primary { background: #2c251e; color: #ffffff; }
          .btn-primary:hover { background: #0088cc; color: #ffffff; }
          .btn-secondary { background: #ffffff; color: #2c251e; border: 1px solid #c8b9a6; box-shadow: 0 1px 2px rgba(45, 30, 15, 0.04); }
          .btn-secondary:hover { background: #2c251e; color: #ffffff; border-color: #2c251e; }
          .btn-danger { background-color: #a62b1e; color: #ffffff; border: none; }
          .btn-danger:hover { background-color: #7d2016; color: #ffffff; }
          details.confirm-dialog { display: inline-block; position: relative; }
          details.confirm-dialog summary { list-style: none; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; box-sizing: border-box; }
          details.confirm-dialog summary::-webkit-details-marker { display: none; }
          .confirm-content { margin-top: 0.5rem; padding: 0.85rem 1rem; background: #fff5f5; border: 1px solid #feb2b2; border-radius: 0.5rem; color: #742a2a; font-size: 0.85rem; box-shadow: 0 4px 12px rgba(166, 43, 30, 0.08); }
          .confirm-content p { margin: 0 0 0.6rem 0; line-height: 1.4; }
          .confirm-actions { display: flex; gap: 0.5rem; align-items: center; }
          .control-rows { display: flex; flex-direction: column; gap: 0.85rem; margin-top: 0.85rem; }
          .control-row { display: flex; flex-direction: column; justify-content: space-between; align-items: flex-start; gap: 0.85rem; padding: 1rem 1.25rem; background: rgba(255, 255, 255, 0.7); border: 1px solid #dfd5c6; border-radius: 0.5rem; transition: background 0.15s ease; }
          .control-row:hover { background: rgba(255, 255, 255, 0.95); }
          .control-info strong { display: block; font-family: 'Space Grotesk', sans-serif; font-size: 0.95rem; color: #1a1612; margin-bottom: 0.2rem; }
          .control-info p { margin: 0; font-size: 0.825rem; color: #6e6154; }
          .control-action { flex-shrink: 0; }
          .control-action summary { width: 11.5rem; height: 2.35rem; padding: 0; box-sizing: border-box; }
          .recovery-notice { display: flex; align-items: center; gap: 0.6rem; margin-top: 1.25rem; padding: 0.85rem 1.25rem; background: #eae2d6; border-radius: 0.375rem; font-size: 0.825rem; color: #5c5146; line-height: 1.45; }
          @media (min-width: 640px) {
            .control-row { flex-direction: row; align-items: center; }
          }
          *:focus-visible { outline: 2px solid #2b7fa1; outline-offset: 2px; }
          .status-badge { display: inline-flex; align-items: center; gap: 0.35rem; font-weight: 600; }
          .status-dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }
          .status-active { color: #2a684d; }
          .status-active .status-dot { background: #2a684d; box-shadow: 0 0 0 2px rgba(42,104,77,0.18); }
          .status-degraded { color: #b45309; }
          .status-degraded .status-dot { background: #d97706; box-shadow: 0 0 0 2px rgba(217,119,6,0.18); }
          .status-muted { color: #a62b1e; }
          .status-muted .status-dot { background: #a62b1e; }
          .empty-state { padding: 2rem; text-align: center; color: #6e6154; font-size: 0.9rem; }
          .site-header .subtitle { text-align: center; font-size: 0.95rem; color: #6e6154; margin-top: 0.4rem; line-height: 1.45; }
          hr { border: none; height: 1px; background-color: #dfd5c6; margin: 1.25rem 0 1.5rem 0; }
          .table-link { display: inline-flex; align-items: center; color: #2b7fa1; font-weight: 600; text-decoration: none; transition: color 0.15s ease; }
          .table-link:hover { color: #0088cc; text-decoration: underline; }
          .step-card { background: rgba(255, 255, 255, 0.9); border: 1px solid #dfd5c6; border-left: 4px solid #2b7fa1; border-radius: 0.5rem; padding: 1rem 1.25rem; margin-bottom: 1rem; box-shadow: 0 2px 8px rgba(45, 30, 15, 0.03); transition: transform 0.2s ease, box-shadow 0.2s ease; }
          .step-card:hover { transform: translateY(-1px); box-shadow: 0 4px 12px rgba(45, 30, 15, 0.06); }
          .step-card:nth-of-type(1) { border-left-color: #2b7fa1; }
          .step-card:nth-of-type(2) { border-left-color: #6b5b95; }
          .step-card:nth-of-type(3) { border-left-color: #3b8b68; }
          .step-card h3 { margin-top: 0; margin-bottom: 0.5rem; font-family: 'Space Grotesk', sans-serif; font-size: 1.1rem; color: #1a1612; }
          .docs-card { display: flex; flex-direction: column; align-items: stretch; background: rgba(255, 255, 255, 0.9); border: 1px solid #dfd5c6; border-left: 4px solid #2c251e; border-radius: 0.5rem; padding: 1.25rem; margin-top: 1rem; gap: 1rem; box-shadow: 0 2px 8px rgba(45, 30, 15, 0.03); }
          .docs-card h4 { margin: 0 0 0.3rem 0; font-family: 'Space Grotesk', sans-serif; font-size: 1.05rem; color: #1a1612; }
          .docs-card p { margin: 0; font-size: 0.85rem; color: #6e6154; }
          .btn-docs { display: inline-flex; align-items: center; white-space: nowrap; background: #2c251e; color: #ffffff; font-family: 'Space Grotesk', sans-serif; font-size: 0.85rem; font-weight: 600; padding: 0.6rem 1rem; border-radius: 0.375rem; text-decoration: none; transition: background 0.2s ease; }
          .btn-docs:hover { background: #0088cc; color: #ffffff; }
          .auth-card { background: rgba(255, 255, 255, 0.9); border: 1px solid #dfd5c6; border-left: 4px solid #2b7fa1; border-radius: 0.5rem; padding: 1.25rem; max-width: 26rem; margin: 1.5rem auto; box-shadow: 0 4px 16px rgba(45, 30, 15, 0.04); }
          .auth-card h2 { font-family: 'Space Grotesk', sans-serif; font-size: 1.5rem; margin-top: 0; margin-bottom: 0.4rem; color: #1a1612; }
          .recovery-card { max-width: 34rem; border-left-color: #a62b1e; }
          .recovery-card h1 { font-family: 'Space Grotesk', sans-serif; font-size: 2rem; line-height: 1.05; margin: 0 0 0.5rem 0; color: #1a1612; }
          .recovery-card .auth-desc { margin-bottom: 1rem; }
          .recovery-card p:last-child { margin-bottom: 0; }
          .auth-desc { font-size: 0.85rem; color: #6e6154; margin-bottom: 1.5rem; }
          .auth-error { background: #fdf2f2; color: #c53030; border: 1px solid #feb2b2; padding: 0.6rem 0.8rem; border-radius: 0.375rem; font-size: 0.85rem; margin-bottom: 1rem; }
          .auth-form { display: flex; flex-direction: column; gap: 1.25rem; }
          .form-group { display: flex; flex-direction: column; gap: 0.4rem; }
          .form-group label { font-family: 'Space Grotesk', sans-serif; font-size: 0.85rem; font-weight: 600; color: #1a1612; }
          .input-text { font-family: 'Reddit Mono', monospace; padding: 0.6rem 0.8rem; border: 1px solid #c8b9a6; border-radius: 0.375rem; font-size: 0.95rem; background: #ffffff; box-sizing: border-box; width: 100%; }
          .input-text:focus { outline: none; border-color: #0088cc; box-shadow: 0 0 0 3px rgba(0, 136, 204, 0.15); }
          .search-toolbar-panel { padding: 0.85rem 1rem; }
          .toolbar-form { display: flex; flex-direction: column; gap: 0.75rem; margin: 0; }
          .search-input-group { position: relative; flex: 1; display: flex; align-items: center; }
          .search-icon { position: absolute; left: 0.75rem; color: #8c7f70; display: flex; align-items: center; pointer-events: none; }
          .search-clear-btn { position: absolute; right: 0.6rem; color: #8c7f70; display: flex; align-items: center; justify-content: center; width: 1.5rem; height: 1.5rem; border-radius: 50%; text-decoration: none; transition: all 0.15s ease; }
          .search-clear-btn:hover { color: #1a1612; background: #eae1d5; text-decoration: none; }
          .input-search { font-family: 'Reddit Mono', monospace; padding: 0.55rem 2.2rem 0.55rem 2.2rem; border: 1px solid #c8b9a6; border-radius: 0.375rem; font-size: 0.875rem; background: #ffffff; width: 100%; box-sizing: border-box; transition: all 0.15s ease; }
          .input-search:focus { outline: none; border-color: #2b7fa1; box-shadow: 0 0 0 3px rgba(43, 127, 161, 0.15); }
          .segmented-control { display: inline-flex; background: #eae1d5; border-radius: 0.375rem; padding: 2px; gap: 2px; align-self: flex-start; }
          .segmented-btn { padding: 0.35rem 0.75rem; font-family: 'Space Grotesk', sans-serif; font-size: 0.8rem; font-weight: 600; color: #6e6154; text-decoration: none; border-radius: 0.25rem; transition: all 0.15s ease; border: none; background: transparent; cursor: pointer; }
          .segmented-btn:hover { color: #1a1612; text-decoration: none; }
          .segmented-btn-active { background: #ffffff; color: #1a1612; box-shadow: 0 1px 3px rgba(45, 30, 15, 0.1); }
          .segmented-btn-active:hover { color: #1a1612; }
          .table-panel { padding: 0; overflow: hidden; }
          .table-header-bar { display: flex; justify-content: space-between; align-items: center; padding: 0.85rem 1.25rem 0.5rem 1.25rem; }
          .table-header-bar h3 { margin: 0; }
          .results-count { font-size: 0.8rem; font-weight: 600; color: #8c7f70; text-transform: uppercase; letter-spacing: 0.04em; }
          .table-panel .table-wrapper { margin: 0; border: none; }
          .table-panel table { border: none; border-radius: 0; }
          .table-footer-bar { display: flex; flex-direction: column; gap: 0.75rem; align-items: center; padding: 0.85rem 1.25rem; border-top: 1px solid #dfd5c6; background: rgba(246, 242, 236, 0.6); }
          .pagination-info { font-size: 0.8rem; color: #6e6154; font-weight: 500; }
          .pagination-controls { display: flex; align-items: center; gap: 0.5rem; }
          .page-indicator { font-size: 0.8rem; font-weight: 600; color: #1a1612; padding: 0 0.4rem; }
          .btn-pag { display: inline-flex; align-items: center; justify-content: center; min-width: 2.2rem; height: 2rem; padding: 0 0.75rem; border-radius: 0.375rem; border: 1px solid #c8b9a6; background: #ffffff; color: #2c251e; font-family: 'Space Grotesk', sans-serif; font-size: 0.8rem; font-weight: 600; text-decoration: none; transition: all 0.15s ease; box-shadow: 0 1px 2px rgba(45, 30, 15, 0.04); }
          .btn-pag:hover { background: #2c251e; color: #ffffff; border-color: #2c251e; text-decoration: none; }
          .btn-pag-disabled { background: #eee4d5; color: #a89986; border-color: #dfd5c6; box-shadow: none; cursor: not-allowed; }
          .filter-chip-group { display: flex; flex-wrap: wrap; gap: 0.6rem; align-items: center; margin-top: -0.15rem; }
          .filter-chip { display: inline-flex; align-items: center; justify-content: center; min-height: 2rem; padding: 0 0.9rem; border-radius: 999px; border: 1px solid #d6ccbf; background: #f6f2ec; color: #5c5146; font-family: 'Space Grotesk', sans-serif; font-size: 0.85rem; font-weight: 600; text-decoration: none; box-shadow: 0 1px 2px rgba(45, 30, 15, 0.05); transition: all 0.18s ease; }
          .filter-chip:hover { color: #2b7fa1; border-color: #9bc8da; background: #f7fbfd; text-decoration: none; box-shadow: 0 2px 6px rgba(43, 127, 161, 0.12); }
          .filter-chip-active { border-color: #1c8bc0; background: #229ed9; color: #ffffff; box-shadow: 0 4px 12px rgba(34, 158, 217, 0.22); }
          .filter-chip-active:hover { color: #ffffff; border-color: #1c8bc0; background: #1c8cc3; }
          .table-wrapper { width: 100%; overflow-x: auto; margin-top: 0.85rem; -webkit-overflow-scrolling: touch; }
          .table-wrapper::-webkit-scrollbar { height: 6px; }
          .table-wrapper::-webkit-scrollbar-track { background: #eee4d5; border-radius: 3px; }
          .table-wrapper::-webkit-scrollbar-thumb { background: #c8b9a6; border-radius: 3px; }
          .table-wrapper::-webkit-scrollbar-thumb:hover { background: #a89986; }
          table { width: 100%; min-width: 34rem; border-collapse: separate; border-spacing: 0; background: rgba(255, 255, 255, 0.9); border: 1px solid #dfd5c6; border-radius: 0.5rem; overflow: hidden; font-size: 0.85rem; }
          th { font-family: 'Space Grotesk', sans-serif; font-weight: 700; font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.05em; background: #eae2d6; color: #1a1612; padding: 0.8rem 1.25rem; text-align: left; border-bottom: 2px solid #dcd1c0; }
          td { padding: 0.75rem 1.25rem; text-align: left; border-bottom: 1px solid #eee4d5; vertical-align: middle; color: #2c251e; }
          tr:last-child td { border-bottom: none; }
          tr:nth-child(even) td { background: rgba(246, 242, 236, 0.5); }
          .site-footer { margin-top: 2rem; padding-top: 1rem; border-top: 1px dashed #dfd5c6; text-align: center; font-size: 0.8rem; color: #8c7f70; }
          .table-subtle { font-size: 0.8rem; color: #8c7f70; }
          .audit-timestamp { white-space: nowrap; vertical-align: middle; line-height: 1.25; min-width: 5.2rem; }
          .ts-time { font-family: 'Reddit Mono', monospace; font-size: 0.85rem; font-weight: 700; color: #1a1612; letter-spacing: 0.06em; font-variant-numeric: tabular-nums; }
          .ts-date { font-family: 'Space Grotesk', sans-serif; font-size: 0.725rem; font-weight: 700; color: #8c7f70; text-transform: uppercase; letter-spacing: 0.08em; margin-top: 2px; }
          .audit-repo { font-weight: 700; color: #1a1612; }
          .audit-actor { font-family: 'Reddit Mono', monospace; font-size: 0.825rem; color: #2b7fa1; }
          .audit-details-cell { line-height: 1.4; }
          .chat-target { display: inline-flex; align-items: center; gap: 0.4rem; font-family: 'Space Grotesk', sans-serif; font-size: 0.825rem; font-weight: 600; color: #1a1612; background: #f6f2ec; border: 1px solid #dfd5c6; padding: 0.2rem 0.55rem; border-radius: 0.375rem; margin-bottom: 0.25rem; }
          .chat-icon { display: inline-flex; align-items: center; color: #2b7fa1; }
          .chat-text { white-space: nowrap; }
          .detail-chips { display: flex; flex-wrap: wrap; gap: 0.35rem; margin-top: 0.25rem; }
          .detail-chip { font-family: 'Reddit Mono', monospace; font-size: 0.75rem; background: #ffffff; color: #4a4035; padding: 0.2rem 0.55rem; border-radius: 0.35rem; border: 1px solid #dfd5c6; display: inline-flex; align-items: center; gap: 0.2rem; box-shadow: 0 1px 2px rgba(45, 30, 15, 0.04); }
          .chip-key { color: #8c7f70; font-weight: 700; }
          .chip-val { color: #1a1612; font-weight: 600; }
          .chip-val-old { color: #a62b1e; text-decoration: line-through; opacity: 0.85; }
          .chip-arrow { color: #2b7fa1; font-weight: 800; padding: 0 0.05rem; }
          .chip-val-new { color: #3b8b68; font-weight: 700; }
          .table-link { color: #2b7fa1; text-decoration: none; font-weight: 600; transition: color 0.15s ease; }
          .table-link:hover { color: #1c8bc0; text-decoration: underline; }
          .site-footer a { color: #2c251e; font-weight: 600; text-decoration: none; }
          .site-footer a:hover { text-decoration: underline; }
          .admin-page { max-width: 56rem; }
          .admin-spacer { height: 0.25rem; }
          .admin-shell { display: grid; gap: 1rem; }
          .admin-hero { background: rgba(255, 255, 255, 0.95); border: 1px solid #dfd5c6; border-top: 3px solid #2b7fa1; border-radius: 0.75rem; padding: 1.25rem; box-shadow: 0 4px 16px rgba(45, 30, 15, 0.04); }
          .admin-kicker { display: inline-block; margin-bottom: 0.4rem; font-family: 'Space Grotesk', sans-serif; font-size: 0.75rem; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: #2b7fa1; }
          .admin-hero h1, .admin-hero h2 { margin: 0; font-family: 'Space Grotesk', sans-serif; font-size: 1.6rem; color: #1a1612; }
          .admin-hero p { margin: 0.4rem 0 0 0; font-size: 0.9rem; color: #6e6154; }
          .admin-panel { background: rgba(255, 255, 255, 0.9); border: 1px solid #dfd5c6; border-radius: 0.75rem; padding: 1.25rem; box-shadow: 0 4px 16px rgba(45, 30, 15, 0.04); }
          .admin-panel h3 { margin: 0 0 0.75rem 0; font-family: 'Space Grotesk', sans-serif; font-size: 1.1rem; color: #1a1612; }
          .admin-meta { display: grid; gap: 0.75rem; margin-top: 1rem; }
          .admin-meta-card { background: rgba(255, 255, 255, 0.95); border: 1px solid #dfd5c6; border-radius: 0.5rem; padding: 0.85rem 1rem; color: #2c251e; box-shadow: 0 2px 6px rgba(45, 30, 15, 0.03); }
          .admin-meta-card.meta-installations { border-left: 4px solid #2b7fa1; }
          .admin-meta-card.meta-audit { border-left: 4px solid #6b5b95; }
          .admin-meta-card.meta-recovery { border-left: 4px solid #3b8b68; }
          .admin-meta-card strong { display: block; margin-bottom: 0.25rem; font-family: 'Space Grotesk', sans-serif; font-size: 0.725rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em; color: #6e6154; }
          .admin-meta-card.meta-installations strong { color: #1d617d; }
          .admin-meta-card.meta-audit strong { color: #534575; }
          .admin-meta-card.meta-recovery strong { color: #2a684d; }
          .admin-meta-card .stat-val { font-family: 'Space Grotesk', sans-serif; font-size: 1.4rem; font-weight: 700; color: #1a1612; line-height: 1.2; }
          @media (min-width: 720px) {
            html { padding: 2rem 1rem; }
            body { margin: 1rem auto; padding: 2.5rem; }
            .site-header .header-top { flex-direction: row; align-items: center; justify-content: space-between; gap: 1rem; }
            .site-header .title { font-size: 2.4rem; text-align: left; }
            .site-header .right-meta { flex-direction: row; align-items: center; width: auto; gap: 0.5rem; }
            .header-btn { width: auto; }
            .header-form { width: auto; display: inline-flex; }
            .header-form .header-btn { width: auto; }
            .site-header .subtitle { text-align: left; max-width: 28rem; }
            .docs-card { flex-direction: row; align-items: center; justify-content: space-between; }
            .auth-card { padding: 2rem; margin: 2rem auto; }
            .admin-shell { gap: 1.25rem; }
            .admin-hero, .admin-panel { padding: 1.5rem; }
            .admin-meta { grid-template-columns: repeat(3, minmax(0, 1fr)); }
            .toolbar-form { flex-direction: row; align-items: center; }
            .segmented-control { align-self: auto; }
            .table-footer-bar { flex-direction: row; justify-content: space-between; }
          }
        </style>
      </head>
      <body>
        <header class="site-header">
          <div class="header-top">
            <div class="title">nuecagram</div>
            <div class="right-meta">
              <a href="https://t.me/NuecagramBot" target="_blank" rel="noopener" class="header-btn btn-telegram" aria-label="Open Telegram Bot">
                <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M12 0C5.37 0 0 5.37 0 12s5.37 12 12 12 12-5.37 12-12S18.63 0 12 0zm5.56 8.16l-1.97 9.28c-.15.68-.55.84-1.12.52l-3.01-2.22-1.45 1.4c-.16.16-.3.3-.61.3l.21-3.05 5.56-5.02c.24-.22-.05-.34-.37-.13l-6.87 4.33-2.96-.92c-.64-.2-.65-.64.13-.95l11.57-4.46c.54-.2 1.01.13.89.92z"/></svg>
                <span>@NuecagramBot</span>
              </a>
              <a href="https://github.com/raquezha/nuecagram" target="_blank" rel="noopener" class="header-btn btn-github" aria-label="GitHub Repository">
                <svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true" fill="currentColor"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.28.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"></path></svg>
                <span>v${version.html()}</span>
              </a>
              ${rightHeaderHtml.orEmpty()}
            </div>
          </div>
          <div class="subtitle">Instant GitLab alerts in Telegram.</div>
        </header>
        <hr>
        $body
        <footer class="site-footer">
          Crafted by <a href="https://github.com/raquezha" target="_blank" rel="noopener">@raquezha</a> &bull; &copy; 2026 Nuecagram &bull; Open Source under MIT License
        </footer>
      </body>
    </html>
    """.trimIndent()
}

internal fun String.html(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
