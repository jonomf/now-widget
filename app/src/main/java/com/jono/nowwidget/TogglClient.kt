package com.jono.nowwidget

import android.content.Context
import com.jono.nowwidget.core.TogglHttpClient
import com.jono.nowwidget.core.TogglClientStore
import com.jono.nowwidget.core.TogglMaybeSent
import com.jono.nowwidget.core.TogglNotSent
import com.jono.nowwidget.core.TogglReply
import com.jono.nowwidget.core.TogglTransport
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Toggl over plain HttpURLConnection. The token is only ever put in the Authorization header; it
 * is never logged and never part of a message. Request bodies have a fixed length, so the stack
 * can't silently send a POST a second time. Background threads only.
 */
class UrlTransport(private val token: String) : TogglTransport {
    companion object {
        const val BASE = "https://api.track.toggl.com/api/v9"
        private const val CONNECT_MS = 15_000
        private const val READ_MS = 30_000
        private const val MAX_BODY = 4 * 1024 * 1024
    }

    private fun basic(): String = "Basic " + Base64.getEncoder().encodeToString("$token:api_token".toByteArray(Charsets.UTF_8))

    private fun read(s: InputStream?): String {
        if (s == null) return ""
        s.use {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                if (out.size() + n > MAX_BODY) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }

    override fun send(method: String, path: String, body: String?): TogglReply {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Authorization", basic())
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val bytes = body?.toByteArray(Charsets.UTF_8)
            if (bytes != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
            }
            try {
                conn.connect()
            } catch (e: IOException) {
                throw TogglNotSent("Couldn't reach Toggl (" + e.javaClass.simpleName + ")")
            }
            try {
                if (bytes != null) conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                val text = read(if (code >= 400) conn.errorStream else conn.inputStream)
                return TogglReply(
                    code, text,
                    conn.getHeaderField("X-Toggl-Quota-Remaining")?.trim()?.toIntOrNull(),
                    conn.getHeaderField("X-Toggl-Quota-Resets-In")?.trim()?.toLongOrNull(),
                )
            } catch (e: IOException) {
                throw TogglMaybeSent("Toggl didn't answer (" + e.javaClass.simpleName + ")")
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** What the client keeps between runs, in the preferences. */
class PrefsClientStore(private val ctx: Context) : TogglClientStore {
    companion object {
        /** One at a time across clients, so the count in the preferences is never overwritten. */
        private val LOCK = Any()
    }

    override val lock: Any get() = LOCK
    override fun budget(): String? = Prefs.togglBudget(ctx)
    override fun saveBudget(v: String) = Prefs.setTogglBudget(ctx, v)
    override fun projects(ws: Long) = Prefs.togglProjects(ctx, ws)
    override fun addProjects(ws: Long, add: Map<String, Long>) = Prefs.addTogglProjects(ctx, ws, add)
    override fun forgetProject(ws: Long, key: String) = Prefs.forgetTogglProject(ctx, ws, key)
    override fun listed(ws: Long) = Prefs.togglProjectsListed(ctx, ws)
    override fun setListed(ws: Long, on: Boolean) = Prefs.setTogglProjectsListed(ctx, ws, on)
    override fun noProjects(ws: Long) = Prefs.togglNoProjects(ctx, ws)
    override fun setNoProjects(ws: Long) = Prefs.setTogglNoProjects(ctx, ws)
    override fun refused(ws: Long, key: String) = Prefs.togglRefusedProject(ctx, ws, key)
    override fun addRefused(ws: Long, key: String) = Prefs.addTogglRefusedProject(ctx, ws, key)
}

object TogglClient {
    fun make(ctx: Context, token: String, warnings: MutableList<String> = ArrayList()): TogglHttpClient =
        TogglHttpClient(UrlTransport(token), PrefsClientStore(ctx), System::currentTimeMillis, { Thread.sleep(it) }, warnings)
}
