package com.jono.nowwidget.core

import kotlin.math.pow

/** The steps the widget asks after a switch, in order. */
enum class FlowStep(val title: String, val question: String) {
    WHERE("Where", "where?"),
    WHO("Who", "with whom?"),
    FEELING("Feeling", "how feeling?"),
}

/** The app's "Ask … after each switch" settings. */
data class FlowSettings(
    val where: Boolean = true,
    val who: Boolean = true,
    val feeling: Boolean = true,
    /** The defaults go on the new entry right away, so walking away keeps them. Off: only on Next. */
    val keepDefaults: Boolean = true,
) {
    val steps: List<FlowStep>
        get() = listOfNotNull(FlowStep.WHERE.takeIf { where }, FlowStep.WHO.takeIf { who }, FlowStep.FEELING.takeIf { feeling })
}

/**
 * Where the check-in flow is, saved between taps (the widget's process can die in between).
 * [place] / [people] are the current choice; [defaultPlace] / [defaultPeople] what it started with,
 * which keep their place at the front of the chips while you change your mind.
 */
data class FlowState(
    val entryId: Long,
    val activityId: Long,
    val steps: List<FlowStep>,
    val step: FlowStep,
    val place: Long?,
    val people: Set<Long>,
    val defaultPlace: Long?,
    val defaultPeople: List<Long>,
    val deadline: Long,
    /** The defaults were written to the entry when the flow started. */
    val applied: Boolean,
) {
    fun encode(): String = listOf(
        "f1",
        entryId.toString(),
        activityId.toString(),
        steps.joinToString(",") { it.name },
        step.name,
        place?.toString() ?: "",
        people.joinToString(","),
        defaultPlace?.toString() ?: "",
        defaultPeople.joinToString(","),
        deadline.toString(),
        if (applied) "1" else "0",
    ).joinToString("|")

    companion object {
        /** Null for anything that isn't a whole, valid saved flow (it then simply isn't running). */
        fun decode(s: String?): FlowState? {
            if (s.isNullOrBlank()) return null
            val p = s.split("|")
            if (p.size != 11 || p[0] != "f1") return null
            return try {
                val steps = p[3].split(",").filter { it.isNotEmpty() }.map { FlowStep.valueOf(it) }
                val step = FlowStep.valueOf(p[4])
                if (step !in steps) return null
                FlowState(
                    entryId = p[1].toLong(),
                    activityId = p[2].toLong(),
                    steps = steps,
                    step = step,
                    place = p[5].takeIf { it.isNotEmpty() }?.toLong(),
                    people = ids(p[6]).toSet(),
                    defaultPlace = p[7].takeIf { it.isNotEmpty() }?.toLong(),
                    defaultPeople = ids(p[8]),
                    deadline = p[9].toLong(),
                    applied = when (p[10]) { "1" -> true; "0" -> false; else -> return null },
                )
            } catch (e: IllegalArgumentException) {
                null // NumberFormatException is one too
            }
        }

        private fun ids(s: String): List<Long> = s.split(",").filter { it.isNotEmpty() }.map { it.toLong() }
    }
}

/** What the entry the flow is about looks like now (null when nothing is on). */
data class FlowEntry(val entryId: Long, val activityId: Long, val place: Long?, val people: Set<Long>)

/** A tap inside the flow. */
sealed class FlowTap {
    /** A place (where) or a person (who). */
    data class Pick(val id: Long) : FlowTap()
    data class Mood(val moodId: Long) : FlowTap()
    object Skip : FlowTap()
    object Next : FlowTap()
    object None : FlowTap()
    object Done : FlowTap()
    object Note : FlowTap()
}

/** The buttons after the chips. */
enum class FlowControl(val label: String, val key: String) {
    SKIP("Skip", "skip"),
    NONE("None", "none"),
    NEXT("Next", "next"),
    DONE("Done", "done"),
    NOTE("📝", "note");

    val tap: FlowTap
        get() = when (this) {
            SKIP -> FlowTap.Skip
            NONE -> FlowTap.None
            NEXT -> FlowTap.Next
            DONE -> FlowTap.Done
            NOTE -> FlowTap.Note
        }
}

/** A change the flow makes; the app makes each one through the Store, so exports see it. */
sealed class FlowEffect {
    data class SetPlace(val entryId: Long, val placeId: Long?) : FlowEffect()
    data class SetPeople(val entryId: Long, val people: Set<Long>) : FlowEffect()
    data class AddFeeling(val entryId: Long, val moodId: Long) : FlowEffect()
}

/** A chip in the flow: a place, a person or a mood face. */
data class FlowOption(val id: Long, val emoji: String, val label: String, val on: Boolean)

/** One cell of a flow grid: an option, a control, or an empty filler that keeps the columns even. */
data class FlowCell(val option: FlowOption? = null, val control: FlowControl? = null) {
    val filler: Boolean get() = option == null && control == null
}

/** What a widget size shows for the flow, as data. */
data class FlowView(
    val step: FlowStep,
    /** "Hobby · where?" (the emoji has its own view). */
    val head: String,
    val rows: List<List<FlowCell>>,
    /** Every step with whether it's the current one, for "Where · Who · Feeling". Null: no room. */
    val steps: List<Pair<FlowStep, Boolean>>?,
)

/** The 1×1: the question, and one chip that accepts the default. */
data class FlowTile(val question: String, val chip: String, val control: FlowControl)

/**
 * The "doing what, where, with whom, how feeling?" flow on the widget: after a switch it asks
 * WHERE → WHO → FEELING, then goes back to the buttons. No Android in here.
 */
object CheckInFlow {
    const val TIMEOUT_MS = 90_000L
    /** A use this long ago counts half as much as one now. */
    const val HALF_LIFE_MS = 14L * 86_400_000L
    /** How far back uses are counted. */
    const val HISTORY_MS = 180L * 86_400_000L

    /** One use of a link: by an entry that started at [at]. */
    data class Use(val linkId: Long, val at: Long)

    /**
     * Recency + frequency in one number: every use counts, and a use counts half as much for each
     * [halfLifeMs] it's older. Three uses last month can beat one yesterday; one today beats one a
     * month ago. Uses in the future (a clock change) count as now.
     */
    fun scores(uses: List<Use>, now: Long, halfLifeMs: Long = HALF_LIFE_MS): Map<Long, Double> {
        val out = HashMap<Long, Double>()
        uses.forEach { u ->
            val age = maxOf(0L, now - u.at).toDouble()
            out[u.linkId] = (out[u.linkId] ?: 0.0) + 0.5.pow(age / halfLifeMs.toDouble())
        }
        return out
    }

    /** [first] (in its order, if they're candidates), then by score, then in your own order. */
    fun rank(candidates: List<Link>, scores: Map<Long, Double>, first: List<Long>): List<Link> {
        val byId = candidates.associateBy { it.id }
        val head = first.distinct().mapNotNull { byId[it] }
        val headIds = head.map { it.id }.toSet()
        val rest = candidates.filter { it.id !in headIds }
            .sortedWith(compareByDescending<Link> { scores[it.id] ?: 0.0 }.thenBy { it.pos }.thenBy { it.id })
        return head + rest
    }

    /**
     * The default place: the new entry's own (a replaced mistake keeps its place), else the last
     * place you were, if it's still one you use. [usable] are the place ids that can be chips.
     */
    fun defaultPlace(entryPlace: Long?, lastPlace: Long?, usable: Set<Long>): Long? =
        entryPlace ?: lastPlace?.takeIf { it in usable }

    /** The default people: the new entry's own, else the people on the entry before it (not idle). */
    fun defaultPeople(entryPeople: Set<Long>, previousPeople: List<Long>, usable: Set<Long>): List<Long> =
        if (entryPeople.isNotEmpty()) entryPeople.sorted() else previousPeople.filter { it in usable }.distinct()

    /**
     * A switch to [entry] starts the flow (null when every step is off or has nothing to offer). With keepDefaults the
     * defaults go on the entry now ([startEffects]), so ignoring the flow keeps them.
     */
    fun start(
        entry: FlowEntry,
        settings: FlowSettings,
        defaultPlace: Long?,
        defaultPeople: List<Long>,
        now: Long,
        /** Steps with something to choose from: no place chips at all means no WHERE step. */
        available: Set<FlowStep> = FlowStep.values().toSet(),
    ): FlowState? {
        val steps = settings.steps.filter { it in available }
        if (steps.isEmpty()) return null
        return FlowState(
            entryId = entry.entryId,
            activityId = entry.activityId,
            steps = steps,
            step = steps.first(),
            place = defaultPlace,
            people = defaultPeople.toSet(),
            defaultPlace = defaultPlace,
            defaultPeople = defaultPeople,
            deadline = now + TIMEOUT_MS,
            applied = settings.keepDefaults,
        )
    }

    /** What starting writes: the defaults of the steps that are on, when keepDefaults is on. */
    fun startEffects(state: FlowState, entry: FlowEntry): List<FlowEffect> {
        if (!state.applied) return emptyList()
        val out = ArrayList<FlowEffect>()
        if (FlowStep.WHERE in state.steps) placeEffect(entry, state.place)?.let { out.add(it) }
        if (FlowStep.WHO in state.steps) peopleEffect(entry, state.people)?.let { out.add(it) }
        return out
    }

    /** Still running: same entry, same activity, not timed out. */
    fun live(state: FlowState?, entry: FlowEntry?, now: Long): Boolean =
        state != null && entry != null && entry.entryId == state.entryId &&
            entry.activityId == state.activityId && now < state.deadline

    /** The flow as it should be shown now: null once it timed out or its entry changed. */
    fun reconcile(state: FlowState?, entry: FlowEntry?, now: Long): FlowState? = state.takeIf { live(it, entry, now) }

    data class Result(val state: FlowState?, val effects: List<FlowEffect>, val openNote: Boolean = false)

    /**
     * What [tap] does. The flow ends when the result's state is null. [shownStep] is the step the
     * tapped view was drawn for: a tap on a view that's out of date (a notification not redrawn
     * yet) changes nothing, so a place id never lands among people or a mood.
     */
    fun tap(state: FlowState?, entry: FlowEntry?, tap: FlowTap, now: Long, shownStep: FlowStep? = null): Result {
        if (state == null || entry == null || !live(state, entry, now)) return Result(null, emptyList())
        if (shownStep != null && shownStep != state.step) return Result(state, emptyList())
        val s = state.copy(deadline = now + TIMEOUT_MS)
        return when (s.step) {
            FlowStep.WHERE -> when (tap) {
                is FlowTap.Pick -> advance(s.copy(place = tap.id), listOfNotNull(placeEffect(entry, tap.id)))
                FlowTap.Next -> advance(s, listOfNotNull(placeEffect(entry, s.place)))
                FlowTap.Skip -> advance(s.copy(place = null), listOfNotNull(placeEffect(entry, null)))
                FlowTap.Done -> Result(null, doneEffects(s, entry))
                else -> Result(s, emptyList())
            }
            FlowStep.WHO -> when (tap) {
                is FlowTap.Pick -> {
                    val people = if (tap.id in s.people) s.people - tap.id else s.people + tap.id
                    // Keeping defaults: each toggle is saved, so walking away keeps it. Off: only Next saves.
                    Result(s.copy(people = people), if (s.applied) listOfNotNull(peopleEffect(entry, people)) else emptyList())
                }
                FlowTap.Next -> advance(s, listOfNotNull(peopleEffect(entry, s.people)))
                FlowTap.None -> advance(s.copy(people = emptySet()), listOfNotNull(peopleEffect(entry, emptySet())))
                FlowTap.Done -> Result(null, listOfNotNull(peopleEffect(entry, s.people)))
                else -> Result(s, emptyList())
            }
            FlowStep.FEELING -> when (tap) {
                is FlowTap.Mood -> Result(null, listOf(FlowEffect.AddFeeling(s.entryId, tap.moodId)))
                FlowTap.Skip, FlowTap.Done, FlowTap.Next -> Result(null, emptyList())
                FlowTap.Note -> Result(null, emptyList(), openNote = true)
                else -> Result(s, emptyList())
            }
        }
    }

    /** Done: this step's choice and the defaults of the steps after it. */
    private fun doneEffects(s: FlowState, entry: FlowEntry): List<FlowEffect> {
        val out = ArrayList<FlowEffect>()
        placeEffect(entry, s.place)?.let { out.add(it) }
        if (FlowStep.WHO in s.steps) peopleEffect(entry, s.people)?.let { out.add(it) }
        return out
    }

    private fun advance(s: FlowState, effects: List<FlowEffect>): Result {
        val i = s.steps.indexOf(s.step)
        val next = s.steps.getOrNull(i + 1) ?: return Result(null, effects)
        return Result(s.copy(step = next), effects)
    }

    private fun placeEffect(entry: FlowEntry, place: Long?): FlowEffect? =
        if (entry.place == place) null else FlowEffect.SetPlace(entry.entryId, place)

    private fun peopleEffect(entry: FlowEntry, people: Set<Long>): FlowEffect? =
        if (entry.people == people) null else FlowEffect.SetPeople(entry.entryId, people)

    // --- What each size shows ---

    /** The buttons after the chips, in the order they show. */
    fun controls(step: FlowStep): List<FlowControl> = when (step) {
        FlowStep.WHERE -> listOf(FlowControl.SKIP, FlowControl.NEXT, FlowControl.DONE)
        FlowStep.WHO -> listOf(FlowControl.NONE, FlowControl.NEXT, FlowControl.DONE)
        FlowStep.FEELING -> listOf(FlowControl.SKIP, FlowControl.NOTE)
    }

    /**
     * When only [n] fit, the ones to keep (still shown in [controls] order). Where: tapping the
     * highlighted place already does Next. Who: Next carries the choice. The rest is in the panel.
     */
    fun controls(step: FlowStep, n: Int): List<FlowControl> {
        val all = controls(step)
        if (n >= all.size) return all
        val priority = when (step) {
            FlowStep.WHERE -> listOf(FlowControl.SKIP, FlowControl.DONE, FlowControl.NEXT)
            FlowStep.WHO -> listOf(FlowControl.NEXT, FlowControl.DONE, FlowControl.NONE)
            FlowStep.FEELING -> listOf(FlowControl.SKIP, FlowControl.NOTE)
        }
        val keep = priority.take(maxOf(0, n)).toSet()
        return all.filter { it in keep }
    }

    /** Where and who may show on the lock screen; how you feel never does. */
    fun onLockScreen(state: FlowState?): Boolean = state != null && state.step != FlowStep.FEELING

    /**
     * The chips for the current step. Where: the default place first, then by [scores]. Who: the
     * default people first, then by score. Feeling: [moods] (active, by level) with names when two
     * share a level, as in the 📝 box.
     */
    fun options(
        state: FlowState,
        places: List<Link>,
        people: List<Link>,
        moods: List<Mood>,
        scores: Map<Long, Double>,
        face: (Mood) -> String,
    ): List<FlowOption> = when (state.step) {
        FlowStep.WHERE -> rank(places, scores, listOfNotNull(state.defaultPlace))
            .map { FlowOption(it.id, it.emoji, it.title, it.id == state.place) }
        FlowStep.WHO -> rank(people, scores, state.defaultPeople)
            .map { FlowOption(it.id, it.emoji, it.title, it.id in state.people) }
        FlowStep.FEELING -> {
            val active = moods.filter { !it.archived }.sortedWith(compareBy({ it.level }, { it.pos }))
            val named = active.groupBy { it.level }.any { it.value.size > 1 }
            active.map { FlowOption(it.id, face(it), if (named) it.name else "", false) }
        }
    }

    /** The first [max] options, except that chosen ones past it take the places of the last unchosen. */
    fun window(options: List<FlowOption>, max: Int): List<FlowOption> {
        if (max <= 0) return emptyList()
        if (options.size <= max) return options
        val shown = options.take(max).toMutableList()
        val missing = options.drop(max).filter { it.on }
        for (m in missing) {
            val drop = shown.indexOfLast { !it.on }
            if (drop < 0) break
            shown.removeAt(drop)
            shown.add(m)
        }
        return shown
    }

    fun head(state: FlowState, actName: String): String = "$actName · ${state.step.question}"

    fun stepLine(state: FlowState): List<Pair<FlowStep, Boolean>> = state.steps.map { it to (it == state.step) }

    /** The 1×1: "📍 Loft?" accepts the default; the question opens the panel. */
    fun tile(state: FlowState, options: List<FlowOption>): FlowTile = when (state.step) {
        FlowStep.WHERE -> {
            val p = options.firstOrNull { it.on }
            val chip = if (p == null) "📍 Nowhere?" else (p.emoji.ifBlank { "📍" } + " " + p.label + "?")
            FlowTile(state.step.question, chip, FlowControl.NEXT)
        }
        FlowStep.WHO -> {
            val on = options.filter { it.on }.map { it.label }
            FlowTile(state.step.question, if (on.isEmpty()) "👥 No one?" else "👥 " + on.joinToString(", ") + "?", FlowControl.NEXT)
        }
        FlowStep.FEELING -> FlowTile(state.step.question, "Skip", FlowControl.SKIP)
    }

    /**
     * A one-row strip: two rows of [cols] cells. The first row is chips; the second is more chips
     * then the controls at the end (as many as fit: at least two cells are kept for them).
     */
    fun strip(state: FlowState, actName: String, options: List<FlowOption>, cols: Int): FlowView {
        val c = maxOf(1, cols)
        val ctl = controls(state.step, minOf(c, controls(state.step).size))
        val secondRow = maxOf(0, c - ctl.size)
        val shown = window(options, c + secondRow)
        val row1 = shown.take(c).map { FlowCell(option = it) }
        val row2 = shown.drop(c).map { FlowCell(option = it) }
        val rows = listOf(
            pad(row1, c),
            pad(row2, secondRow) + ctl.map { FlowCell(control = it) },
        )
        return FlowView(state.step, head(state, actName), rows, stepLine(state))
    }

    /**
     * Two rows or taller (and the panel): rows of [cols] chips, as many rows as they need up to
     * [rows], then a row of the controls, with the step line above.
     */
    fun block(state: FlowState, actName: String, options: List<FlowOption>, cols: Int, rows: Int): FlowView {
        val c = maxOf(1, cols)
        val shown = window(options, c * maxOf(1, rows))
        val optionRows = shown.chunked(c).map { pad(it.map { o -> FlowCell(option = o) }, c) }
        val ctl = controls(state.step).map { FlowCell(control = it) }
        return FlowView(state.step, head(state, actName), optionRows + listOf(ctl), stepLine(state))
    }

    private fun pad(cells: List<FlowCell>, n: Int): List<FlowCell> =
        if (cells.size >= n) cells else cells + List(n - cells.size) { FlowCell() }
}
