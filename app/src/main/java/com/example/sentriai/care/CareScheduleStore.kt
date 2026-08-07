package com.example.sentriai.care

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * Single JSON file in internal storage holding the medicine and meal schedule.
 *
 * Mirrors [com.example.sentriai.data.TriggerLogStore]: one object, one file, every public
 * function synchronised on [LOCK]. No database, because there is one small document here and
 * adding Room for it would be the largest dependency in the app.
 *
 * **Day rollover.** `takenAtMillis` is a timestamp rather than a boolean so "taken" can expire
 * on its own. [load] clears any tick stamped before today's local midnight, which means a dose
 * marked yesterday cannot make this morning's check-in call report adherence that did not
 * happen. A boolean would have needed a scheduled job to reset it, and a job that fails to run
 * would fail in exactly the wrong direction.
 */
object CareScheduleStore {

    private const val TAG = "CareScheduleStore"
    private const val FILE_NAME = "care_schedule.json"
    private val LOCK = Any()

    private const val KEY_MEDICINES = "medicines"
    private const val KEY_MEALS = "meals"

    fun load(context: Context): CareSchedule {
        // No file yet means nothing has been entered, which is a real and correct state — the
        // schedule is the caregiver's to supply. An earlier version seeded two example
        // medicines here so the screen had something on it; that was a mistake. Example
        // prescriptions in a medicine list are indistinguishable from real ones at a glance,
        // and the check-in agent would have asked whether a drug nobody takes had been taken.
        val root = synchronized(LOCK) { readRoot(context) } ?: return CareSchedule.EMPTY

        val meals = root.optJSONObject(KEY_MEALS)
            ?.let { MealSchedule.fromJson(it) }
            ?: MealSchedule.DEFAULT

        val array = root.optJSONArray(KEY_MEDICINES) ?: JSONArray()
        val startOfToday = startOfToday()
        val medicines = buildList {
            for (i in 0 until array.length()) {
                val medicine = runCatching { Medicine.fromJson(array.getJSONObject(i)) }
                    .onFailure { Log.w(TAG, "skipping malformed medicine at $i: ${it.message}") }
                    .getOrNull() ?: continue
                // Yesterday's tick is not today's adherence.
                add(
                    if (medicine.takenAtMillis != null && medicine.takenAtMillis < startOfToday) {
                        medicine.copy(takenAtMillis = null)
                    } else {
                        medicine
                    },
                )
            }
        }

        return CareSchedule(medicines, meals)
    }

    fun save(context: Context, schedule: CareSchedule) {
        val root = JSONObject().apply {
            put(KEY_MEALS, schedule.meals.toJson())
            put(KEY_MEDICINES, JSONArray().apply { schedule.medicines.forEach { put(it.toJson()) } })
        }
        synchronized(LOCK) { file(context).writeText(root.toString(2)) }
    }

    /**
     * Records that a dose was taken, or un-records it when [taken] is false.
     *
     * @return the schedule as it now stands, so the caller has no reason to re-read the file.
     */
    fun setTaken(context: Context, medicineId: String, taken: Boolean): CareSchedule {
        val current = load(context)
        val stamp = if (taken) System.currentTimeMillis() else null
        val updated = current.copy(
            medicines = current.medicines.map {
                if (it.id == medicineId) it.copy(takenAtMillis = stamp) else it
            },
        )
        save(context, updated)
        Log.i(TAG, "medicine $medicineId marked taken=$taken")
        return updated
    }

    fun setMeals(context: Context, meals: MealSchedule): CareSchedule {
        val updated = load(context).copy(meals = meals)
        save(context, updated)
        return updated
    }

    fun upsertMedicine(context: Context, medicine: Medicine): CareSchedule {
        val current = load(context)
        val medicines = if (current.medicines.any { it.id == medicine.id }) {
            current.medicines.map { if (it.id == medicine.id) medicine else it }
        } else {
            current.medicines + medicine
        }
        val updated = current.copy(medicines = medicines)
        save(context, updated)
        return updated
    }

    fun removeMedicine(context: Context, medicineId: String): CareSchedule {
        val updated = load(context).let { it.copy(medicines = it.medicines.filterNot { m -> m.id == medicineId }) }
        save(context, updated)
        return updated
    }

    /** A fresh id for a medicine the caregiver has just added. */
    fun newMedicineId(): String = UUID.randomUUID().toString()

    /**
     * Whether a caregiver has ever saved a schedule.
     *
     * [load] cannot answer this: with no file it returns [CareSchedule.EMPTY], whose meals are
     * [MealSchedule.DEFAULT] — real-looking times at 8:00, 13:00 and 20:00 that nobody entered.
     * That is fine for a screen, which shows them as a starting point to correct, and wrong for
     * anything that acts on them unprompted. A reminder at a meal time the person never set is
     * the same class of mistake as the example medicines this store used to seed.
     */
    fun hasStoredSchedule(context: Context): Boolean = synchronized(LOCK) { file(context).exists() }

    /** Wipe the schedule. Testing / developer reset only. */
    fun clear(context: Context) {
        synchronized(LOCK) { file(context).delete() }
    }

    // ---- internal helpers -------------------------------------------------------

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Null when nothing has been saved yet, or when what was saved cannot be read back. */
    private fun readRoot(context: Context): JSONObject? {
        val file = file(context)
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }
            .onFailure { Log.e(TAG, "care schedule unreadable, starting empty: ${it.message}") }
            .getOrNull()
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
