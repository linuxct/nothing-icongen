package space.linuxct.nothing.icongen

import android.content.Context
import org.json.JSONObject

/** Durable leases, not a work-in-progress flag: taking a snapshot never consumes work. */
internal object DirtyIconQueue {
    private const val PREFS = "icon_preparation"
    private const val DIRTY = "dirty"
    private const val REVISION = "revision"
    private const val EPOCH = "epoch"

    data class Entry(val identity: String, val revision: Long)
    data class Snapshot(val epoch: Long, val entries: Map<String, Entry>)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun entries(json: JSONObject): Map<String, Entry> = json.keys().asSequence().associateWith { key ->
        val value = json.getJSONObject(key)
        Entry(value.getString("identity"), value.getLong("revision"))
    }
    private fun encode(entries: Map<String, Entry>) = JSONObject().apply {
        entries.forEach { (key, value) -> put(key, JSONObject()
            .put("identity", value.identity).put("revision", value.revision)) }
    }.toString()

    /** Persist before the observed inventory or either scheduler request can advance. */
    @Synchronized fun record(context: Context, changed: Map<String, String>) {
        val prefs = prefs(context)
        val dirty = entries(JSONObject(prefs.getString(DIRTY, "{}")!!)).toMutableMap()
        val revision = prefs.getLong(REVISION, 0) + 1
        changed.forEach { (key, identity) ->
            if (dirty[key]?.identity != identity) dirty[key] = Entry(identity, revision)
        }
        prefs.edit().putString(DIRTY, encode(dirty)).putLong(REVISION, revision).remove("prune").commit()
    }

    @Synchronized fun snapshot(context: Context): Snapshot {
        val prefs = prefs(context)
        return Snapshot(prefs.getLong(EPOCH, 0), entries(JSONObject(prefs.getString(DIRTY, "{}")!!)))
    }

    /** An update (including an ABA replacement) or Stop must not be consumed by an older render. */
    @Synchronized fun acknowledge(context: Context, snapshot: Snapshot, current: Map<String, String>) {
        val prefs = prefs(context)
        if (prefs.getLong(EPOCH, 0) != snapshot.epoch) return
        val dirty = entries(JSONObject(prefs.getString(DIRTY, "{}")!!)).toMutableMap()
        snapshot.entries.forEach { (key, value) ->
            if (dirty[key] == value && current[key] == value.identity) dirty.remove(key)
        }
        prefs.edit().putString(DIRTY, encode(dirty)).commit()
    }

    /** Final generation may fill misses after quiet expiry without completing the build/install. */
    fun acknowledgePrepared(
        context: Context,
        snapshot: Snapshot,
        prepared: Map<String, String>,
        current: Map<String, String>,
    ) {
        val completed = snapshot.entries.filter { (key, entry) -> prepared[key] == entry.identity }
        acknowledge(context, snapshot.copy(entries = completed), current)
    }


    @Synchronized fun remove(context: Context, keys: Collection<String>) {
        val prefs = prefs(context)
        val dirty = entries(JSONObject(prefs.getString(DIRTY, "{}")!!)).toMutableMap()
        keys.forEach(dirty::remove)
        prefs.edit().putString(DIRTY, encode(dirty)).commit()
    }

    /** Cancels queued leases without invalidating valid, already-published cache files. */
    @Synchronized fun discard(context: Context) {
        val prefs = prefs(context)
        prefs.edit().remove(DIRTY).remove("prune").putLong(EPOCH, prefs.getLong(EPOCH, 0) + 1).commit()
    }

    fun isCurrent(context: Context, snapshot: Snapshot): Boolean =
        prefs(context).getLong(EPOCH, 0) == snapshot.epoch
}
