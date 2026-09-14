package com.piru.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the ahura.site Piru API. Provides accounts (so data survives an
 * app reinstall or phone change), payload sync, a friend circle and an
 * adherence leaderboard. The base URL is stored in settings so it can move.
 */
class SyncClient(private val repo: AppRepository) {

    private val baseUrl: String
        get() = repo.getSetting("server_url") ?: "https://ahura.site/piru-api"

    private var token: String?
        get() = repo.getSetting("auth_token")
        set(v) = repo.setSetting("auth_token", v ?: "")
    val signedIn: Boolean get() = !token.isNullOrBlank()

    private fun request(method: String, path: String, body: JSONObject? = null): Result<JSONObject> =
        try {
            val url = URL(baseUrl.trimEnd('/') + path)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            token?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().readText()
            val json = JSONObject(text)
            if (code in 200..299) Result.success(json)
            else Result.failure(Exception(json.optString("error", "HTTP $code")))
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun signUp(email: String, password: String, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val r = request("POST", "/api/signup",
                JSONObject().put("email", email).put("password", password).put("name", name))
            r.mapCatching { storeSession(it) }
        }

    suspend fun logIn(email: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            request("POST", "/api/login", JSONObject().put("email", email).put("password", password))
                .mapCatching { storeSession(it) }
        }

    private fun storeSession(json: JSONObject) {
        repo.setSetting("auth_token", json.getString("token"))
        repo.setSetting("auth_user", json.getString("user_id"))
    }

    fun signOut() { repo.setSetting("auth_token", "") }

    /** Build the sync payload from local tables. */
    suspend fun buildPayload(): String = withContext(Dispatchers.IO) {
        val o = JSONObject()
        val arr = JSONArray()
        for (e in repo.userDb.allEntries()) {
            arr.put(JSONObject()
                .put("timestamp", e.timestamp)
                .put("substance", e.substance).put("amount", e.amount).put("unit", e.unit)
                .put("route", e.route.rawValue).put("notes", e.notes ?: JSONObject.NULL))
        }
        o.put("events", arr)
        val meds = JSONArray()
        for (m in repo.userDb.allDailyItems()) {
            meds.put(JSONObject().put("id", m.id).put("substance", m.substance)
                .put("amount", m.amount).put("unit", m.unit).put("times", DailyDoseItem.encodeTimes(m.reminderMinutes)))
        }
        o.put("daily_items", meds)
        o.put("colors", JSONObject(repo.userDb.allColors() as Map<*, *>))
        // precomputed adherence the leaderboard prefers
        o.put("score", repo.adherenceThisWeek())
        o.put("updated", System.currentTimeMillis())
        o.toString()
    }

    suspend fun push(): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = buildPayload()
        request("POST", "/api/sync", JSONObject().put("payload", payload)).map { }
    }

    suspend fun pull(): Result<Unit> = withContext(Dispatchers.IO) {
        request("GET", "/api/sync").mapCatching { json ->
            val p = json.optString("payload")
            if (p.isNotBlank()) mergeFrom(p)
        }
    }

    /** Merge a server payload into local tables by id/timestamp (adds only, never deletes). */
    private fun mergeFrom(payload: String) {
        try {
            val o = JSONObject(payload)
            val local = repo.userDb.allEntries().map { it.timestamp }.toHashSet()
            val ev = o.optJSONArray("events") ?: return
            for (i in 0 until ev.length()) {
                val j = ev.getJSONObject(i)
                val ts = j.getLong("timestamp")
                if (ts in local) continue
                repo.userDb.insertEntry(DoseEntry(
                    substance = j.getString("substance"), amount = j.getDouble("amount"),
                    unit = j.getString("unit"),
                    route = com.piru.app.models.RouteOfAdministration.fromString(j.optString("route", "oral")),
                    timestamp = ts, notes = j.optString("notes").takeIf { it.isNotEmpty() && it != "null" },
                ))
                local.add(ts)
            }
        } catch (_: Exception) { /* partial merge is fine */ }
    }

    fun friendCode(): String? = repo.getSetting("auth_user")?.let { null } // code lives server-side

    suspend fun leaderboard(): Result<List<Triple<String, String, Double>>> = withContext(Dispatchers.IO) {
        request("GET", "/api/leaderboard").map { json ->
            val out = ArrayList<Triple<String, String, Double>>()
            json.getJSONArray("entries").let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    out.add(Triple(e.getString("id"), e.getString("name"), e.getDouble("score")))
                }
            }
            out
        }
    }

    suspend fun friends(): Result<List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        request("GET", "/api/friends").map { json ->
            val out = ArrayList<Pair<String, String>>()
            json.getJSONArray("friends").let { a ->
                for (i in 0 until a.length()) {
                    val f = a.getJSONObject(i)
                    out.add(f.getString("id") to f.getString("name"))
                }
            }
            out
        }
    }

    /** Add by a 6-hex-char user code, or list the current user's code. */
    suspend fun myCode(): Result<String> = withContext(Dispatchers.IO) {
        request("GET", "/api/me").map { it.getString("code") }
    }

    suspend fun addFriendByCode(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        request("POST", "/api/friends/add", JSONObject().put("code", code)).map { }
    }

    /** Darooyab search proxied through the server (blocked from abroad, not from ahura.site). */
    suspend fun darooyab(q: String): Result<List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        request("GET", "/api/darooyab?q=" + java.net.URLEncoder.encode(q, "UTF-8")).map { json ->
            val out = ArrayList<Pair<String, String>>()
            json.getJSONArray("results").let { a ->
                for (i in 0 until a.length()) {
                    val r = a.getJSONObject(i)
                    out.add(r.getString("Title") to r.getString("Href"))
                }
            }
            out
        }
    }
}
