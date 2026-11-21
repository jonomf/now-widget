package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInFlowTest {
    private val day = 86_400_000L
    private val now = 1_000L * day

    private fun place(id: Long, title: String, pos: Int = id.toInt(), emoji: String = "") =
        Link(id, title, LinkKind.PLACE, emoji, pos, false)

    private fun person(id: Long, title: String, pos: Int = id.toInt()) = Link(id, title, LinkKind.PERSON, "", pos, false)

    private val entry = FlowEntry(entryId = 50, activityId = 7, place = null, people = emptySet())

    private fun started(settings: FlowSettings = FlowSettings(), place: Long? = 1, people: List<Long> = listOf(10)) =
        CheckInFlow.start(entry, settings, place, people, now)!!

    // --- Scoring ---

    @Test
    fun recentUsesWeighMore() {
        val s = CheckInFlow.scores(listOf(CheckInFlow.Use(1, now - day), CheckInFlow.Use(2, now - 30 * day)), now)
        assertTrue(s[1]!! > s[2]!!)
        // A use now counts 1, one half-life ago counts half.
        val t = CheckInFlow.scores(listOf(CheckInFlow.Use(3, now), CheckInFlow.Use(4, now - CheckInFlow.HALF_LIFE_MS)), now)
        assertEquals(1.0, t[3]!!, 1e-9)
        assertEquals(0.5, t[4]!!, 1e-9)
    }

    @Test
    fun frequencyCanBeatRecency() {
        val uses = listOf(CheckInFlow.Use(1, now - day)) + List(4) { CheckInFlow.Use(2, now - 10 * day) }
        val s = CheckInFlow.scores(uses, now)
        assertTrue(s[2]!! > s[1]!!)
        // Future uses (clock changes) count as now, never more.
        assertEquals(1.0, CheckInFlow.scores(listOf(CheckInFlow.Use(5, now + day)), now)[5]!!, 1e-9)
    }

    @Test
    fun rankPutsFirstThenScoreThenOwnOrder() {
        val ls = listOf(place(1, "A"), place(2, "B"), place(3, "C"), place(4, "D"))
        val r = CheckInFlow.rank(ls, mapOf(3L to 2.0, 2L to 1.0), first = listOf(4, 99))
        assertEquals(listOf(4L, 3L, 2L, 1L), r.map { it.id })
        // No scores: your own order.
        assertEquals(listOf(1L, 2L, 3L, 4L), CheckInFlow.rank(ls.reversed(), emptyMap(), emptyList()).map { it.id })
    }

    // --- Defaults ---

    @Test
    fun defaultsComeFromTheEntryElseHistory() {
        assertEquals(5L, CheckInFlow.defaultPlace(5, 1, setOf(1, 5)))
        assertEquals(1L, CheckInFlow.defaultPlace(null, 1, setOf(1)))
        assertNull(CheckInFlow.defaultPlace(null, 1, setOf(2))) // last place was removed
        assertNull(CheckInFlow.defaultPlace(null, null, setOf(1)))
        assertEquals(listOf(10L, 11L), CheckInFlow.defaultPeople(emptySet(), listOf(10, 11, 12), setOf(10, 11)))
        assertEquals(listOf(3L), CheckInFlow.defaultPeople(setOf(3), listOf(10), setOf(10)))
        assertEquals(emptyList<Long>(), CheckInFlow.defaultPeople(emptySet(), emptyList(), setOf(10)))
    }

    @Test
    fun startUsesSettingsAndWritesDefaultsOnlyWhenKept() {
        val st = started()
        assertEquals(FlowStep.WHERE, st.step)
        assertEquals(now + CheckInFlow.TIMEOUT_MS, st.deadline)
        assertEquals(
            listOf(FlowEffect.SetPlace(50, 1), FlowEffect.SetPeople(50, setOf(10L))),
            CheckInFlow.startEffects(st, entry),
        )
        val off = started(FlowSettings(keepDefaults = false))
        assertTrue(CheckInFlow.startEffects(off, entry).isEmpty())
        // A step that's off doesn't ask, and its default isn't written.
        val noWhere = started(FlowSettings(where = false))
        assertEquals(FlowStep.WHO, noWhere.step)
        assertEquals(listOf(FlowEffect.SetPeople(50, setOf(10L))), CheckInFlow.startEffects(noWhere, entry))
        // Already there: nothing to write.
        assertTrue(CheckInFlow.startEffects(st, entry.copy(place = 1, people = setOf(10))).isEmpty())
        assertNull(CheckInFlow.start(entry, FlowSettings(false, false, false), 1, emptyList(), now))
        // Nothing to choose from: that step is left out.
        val st2 = CheckInFlow.start(entry, FlowSettings(), null, emptyList(), now, setOf(FlowStep.WHO, FlowStep.FEELING))!!
        assertEquals(listOf(FlowStep.WHO, FlowStep.FEELING), st2.steps)
        assertNull(CheckInFlow.start(entry, FlowSettings(who = false, feeling = false), null, emptyList(), now, setOf(FlowStep.WHO)))
    }

    // --- Step transitions ---

    @Test
    fun whereStep() {
        val st = started()
        val e = entry.copy(place = 1, people = setOf(10)) // defaults applied
        val pick = CheckInFlow.tap(st, e, FlowTap.Pick(2), now + 5)
        assertEquals(FlowStep.WHO, pick.state!!.step)
        assertEquals(listOf(FlowEffect.SetPlace(50, 2)), pick.effects)
        assertEquals(now + 5 + CheckInFlow.TIMEOUT_MS, pick.state!!.deadline)

        val next = CheckInFlow.tap(st, e, FlowTap.Next, now)
        assertEquals(FlowStep.WHO, next.state!!.step)
        assertTrue(next.effects.isEmpty()) // the default is already on the entry

        val nextUnapplied = CheckInFlow.tap(started(FlowSettings(keepDefaults = false)), entry, FlowTap.Next, now)
        assertEquals(listOf(FlowEffect.SetPlace(50, 1)), nextUnapplied.effects)

        val skip = CheckInFlow.tap(st, e, FlowTap.Skip, now)
        assertEquals(listOf(FlowEffect.SetPlace(50, null)), skip.effects)
        assertNull(skip.state!!.place)

        val done = CheckInFlow.tap(started(FlowSettings(keepDefaults = false)), entry, FlowTap.Done, now)
        assertNull(done.state)
        assertEquals(listOf(FlowEffect.SetPlace(50, 1), FlowEffect.SetPeople(50, setOf(10L))), done.effects)

        // Taps that aren't for this step change nothing.
        val none = CheckInFlow.tap(st, e, FlowTap.None, now)
        assertEquals(FlowStep.WHERE, none.state!!.step)
        assertTrue(none.effects.isEmpty())
    }

    @Test
    fun whoStep() {
        val st = started().copy(step = FlowStep.WHO)
        val e = entry.copy(place = 1, people = setOf(10))
        // Keeping defaults (on): every toggle is saved to the entry right away.
        val t1 = CheckInFlow.tap(st, e, FlowTap.Pick(11), now)
        assertEquals(setOf(10L, 11L), t1.state!!.people)
        assertEquals(FlowStep.WHO, t1.state!!.step)
        assertEquals(listOf(FlowEffect.SetPeople(50, setOf(10L, 11L))), t1.effects)
        val e1 = e.copy(people = setOf(10, 11))
        val t2 = CheckInFlow.tap(t1.state, e1, FlowTap.Pick(10), now)
        assertEquals(setOf(11L), t2.state!!.people)
        assertEquals(listOf(FlowEffect.SetPeople(50, setOf(11L))), t2.effects)
        // The defaults keep their place at the front, whatever is chosen.
        assertEquals(listOf(10L), t2.state!!.defaultPeople)

        // Next has nothing left to write once the toggles are saved.
        val e2 = e.copy(people = setOf(11))
        val next = CheckInFlow.tap(t2.state, e2, FlowTap.Next, now)
        assertEquals(FlowStep.FEELING, next.state!!.step)
        assertTrue(next.effects.isEmpty())
        // Walking away after toggling: the entry already has the edit; the timed-out flow writes nothing.
        assertNull(CheckInFlow.tap(t2.state, e2, FlowTap.Next, t2.state!!.deadline).state)

        val none = CheckInFlow.tap(st, e, FlowTap.None, now)
        assertEquals(FlowStep.FEELING, none.state!!.step)
        assertEquals(listOf(FlowEffect.SetPeople(50, emptySet())), none.effects)

        val done = CheckInFlow.tap(t1.state, e, FlowTap.Done, now)
        assertNull(done.state)
        assertEquals(listOf(FlowEffect.SetPeople(50, setOf(10L, 11L))), done.effects)
    }

    @Test
    fun whoStepWithoutKeepingDefaultsSavesOnlyOnNext() {
        val st = started(FlowSettings(keepDefaults = false)).copy(step = FlowStep.WHO)
        val t1 = CheckInFlow.tap(st, entry, FlowTap.Pick(11), now)
        assertEquals(setOf(10L, 11L), t1.state!!.people)
        assertTrue(t1.effects.isEmpty())
        val t2 = CheckInFlow.tap(t1.state, entry, FlowTap.Pick(10), now)
        assertTrue(t2.effects.isEmpty())
        val next = CheckInFlow.tap(t2.state, entry, FlowTap.Next, now)
        assertEquals(listOf(FlowEffect.SetPeople(50, setOf(11L))), next.effects)
        // Walking away: nothing was written.
        assertTrue(CheckInFlow.tap(t2.state, entry, FlowTap.Next, t2.state!!.deadline).effects.isEmpty())
    }

    @Test
    fun feelingStep() {
        val st = started().copy(step = FlowStep.FEELING)
        val mood = CheckInFlow.tap(st, entry, FlowTap.Mood(3), now)
        assertNull(mood.state)
        assertEquals(listOf(FlowEffect.AddFeeling(50, 3)), mood.effects)
        val skip = CheckInFlow.tap(st, entry, FlowTap.Skip, now)
        assertNull(skip.state)
        assertTrue(skip.effects.isEmpty())
        val note = CheckInFlow.tap(st, entry, FlowTap.Note, now)
        assertNull(note.state)
        assertTrue(note.openNote)
        assertEquals(FlowStep.FEELING, CheckInFlow.tap(st, entry, FlowTap.Pick(1), now).state!!.step)
    }

    @Test
    fun lastStepEndsTheFlow() {
        val st = started(FlowSettings(feeling = false)).copy(step = FlowStep.WHO)
        val r = CheckInFlow.tap(st, entry, FlowTap.Next, now)
        assertNull(r.state)
        assertEquals(listOf(FlowEffect.SetPeople(50, setOf(10L))), r.effects)
    }

    @Test
    fun timeoutAndEntryChangeEndIt() {
        val st = started()
        assertTrue(CheckInFlow.live(st, entry, now))
        assertFalse(CheckInFlow.live(st, entry, st.deadline))
        assertNull(CheckInFlow.reconcile(st, entry, st.deadline + 1))
        assertNull(CheckInFlow.reconcile(st, entry.copy(entryId = 51), now)) // another switch
        assertNull(CheckInFlow.reconcile(st, entry.copy(activityId = 8), now)) // edited
        assertNull(CheckInFlow.reconcile(st, null, now)) // turned off
        assertEquals(st, CheckInFlow.reconcile(st, entry.copy(place = 3), now)) // a place change is fine
        val late = CheckInFlow.tap(st, entry, FlowTap.Pick(2), st.deadline + 1)
        assertNull(late.state)
        assertTrue(late.effects.isEmpty())
        assertTrue(CheckInFlow.tap(st, entry.copy(entryId = 51), FlowTap.Next, now).effects.isEmpty())
        assertNull(CheckInFlow.tap(null, entry, FlowTap.Next, now).state)
    }

    @Test
    fun aTapOnAnOldViewChangesNothing() {
        val st = started().copy(step = FlowStep.WHO)
        val r = CheckInFlow.tap(st, entry, FlowTap.Pick(1), now, shownStep = FlowStep.WHERE)
        assertEquals(st, r.state)
        assertTrue(r.effects.isEmpty())
        assertEquals(setOf(10L, 2L), CheckInFlow.tap(st, entry, FlowTap.Pick(2), now, FlowStep.WHO).state!!.people)
    }

    // --- Persistence ---

    @Test
    fun stateRoundTrips() {
        val st = started().copy(people = setOf(10, 12), defaultPeople = listOf(12, 10), step = FlowStep.WHO)
        assertEquals(st, FlowState.decode(st.encode()))
        val bare = CheckInFlow.start(entry, FlowSettings(where = false, keepDefaults = false), null, emptyList(), now)!!
        assertEquals(bare, FlowState.decode(bare.encode()))
        assertNull(FlowState.decode(null))
        assertNull(FlowState.decode(""))
        assertNull(FlowState.decode("garbage"))
        assertNull(FlowState.decode(st.encode().replace("WHO|", "NOPE|")))
        assertNull(FlowState.decode(st.encode().dropLast(2))) // cut short
        // A step that isn't among the steps is no flow.
        assertNull(FlowState.decode(bare.encode().replace("|WHO|", "|WHERE|")))
    }

    // --- What each size shows ---

    private val places = listOf(place(1, "Loft", emoji = "🏠"), place(2, "Park"), place(3, "Cafe"))
    private val people = listOf(person(10, "Sam"), person(11, "Kim"), person(12, "Ari"))
    private val moods = listOf(
        Mood(1, "rad", 5, "😄", 0, false), Mood(2, "good", 4, "🙂", 1, false), Mood(3, "meh", 3, "😐", 2, false),
        Mood(4, "bad", 2, "🙁", 3, false), Mood(5, "awful", 1, "😣", 4, false), Mood(6, "old", 3, "x", 5, true),
    )

    private fun opts(st: FlowState, scores: Map<Long, Double> = emptyMap()) =
        CheckInFlow.options(st, places, people, moods, scores) { it.emoji }

    @Test
    fun optionsPerStep() {
        val st = started(place = 3)
        val where = opts(st, mapOf(2L to 5.0))
        assertEquals(listOf(3L, 2L, 1L), where.map { it.id })
        assertEquals(listOf(true, false, false), where.map { it.on })
        val who = opts(st.copy(step = FlowStep.WHO, people = setOf(10, 12)), mapOf(12L to 1.0))
        assertEquals(listOf(10L, 12L, 11L), who.map { it.id })
        assertEquals(listOf(true, true, false), who.map { it.on })
        val faces = opts(st.copy(step = FlowStep.FEELING))
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), faces.map { it.id }) // by level, archived left out
        assertTrue(faces.all { it.label.isEmpty() })
        // Two moods on one level: every face gets its name.
        val named = CheckInFlow.options(st.copy(step = FlowStep.FEELING), places, people,
            moods + Mood(7, "calm", 3, "😌", 6, false), emptyMap()) { it.emoji }
        assertEquals("rad", named.last().label)
    }

    @Test
    fun windowKeepsChosenOnes() {
        val o = (1L..6L).map { FlowOption(it, "", "p$it", it == 6L) }
        assertEquals(listOf(1L, 2L, 6L), CheckInFlow.window(o, 3).map { it.id })
        assertEquals(o, CheckInFlow.window(o, 10))
        assertTrue(CheckInFlow.window(o, 0).isEmpty())
    }

    @Test
    fun controlsAndTheLockScreen() {
        assertEquals(listOf(FlowControl.SKIP, FlowControl.NEXT, FlowControl.DONE), CheckInFlow.controls(FlowStep.WHERE))
        assertEquals(listOf(FlowControl.NONE, FlowControl.NEXT, FlowControl.DONE), CheckInFlow.controls(FlowStep.WHO))
        assertEquals(listOf(FlowControl.SKIP, FlowControl.NOTE), CheckInFlow.controls(FlowStep.FEELING))
        assertEquals(listOf(FlowControl.SKIP, FlowControl.DONE), CheckInFlow.controls(FlowStep.WHERE, 2))
        assertEquals(listOf(FlowControl.NEXT, FlowControl.DONE), CheckInFlow.controls(FlowStep.WHO, 2))
        val st = started()
        assertTrue(CheckInFlow.onLockScreen(st))
        assertTrue(CheckInFlow.onLockScreen(st.copy(step = FlowStep.WHO)))
        assertFalse(CheckInFlow.onLockScreen(st.copy(step = FlowStep.FEELING)))
        assertFalse(CheckInFlow.onLockScreen(null))
    }

    @Test
    fun tileShowsTheDefault() {
        val st = started(place = 1)
        val t = CheckInFlow.tile(st, opts(st))
        assertEquals("where?", t.question)
        assertEquals("🏠 Loft?", t.chip)
        assertEquals(FlowControl.NEXT, t.control)
        val p2 = started(place = 2)
        assertEquals("📍 Park?", CheckInFlow.tile(p2, opts(p2)).chip)
        val none = started(place = null)
        assertEquals("📍 Nowhere?", CheckInFlow.tile(none, opts(none)).chip)
        val who = st.copy(step = FlowStep.WHO, people = setOf(10, 11))
        assertEquals("👥 Sam, Kim?", CheckInFlow.tile(who, opts(who)).chip)
        assertEquals("👥 No one?", CheckInFlow.tile(who.copy(people = emptySet()), opts(who.copy(people = emptySet()))).chip)
        val f = CheckInFlow.tile(st.copy(step = FlowStep.FEELING), emptyList())
        assertEquals(FlowControl.SKIP, f.control)
    }

    @Test
    fun stripSizes() {
        val st = started(place = 1)
        val many = (1L..20L).map { FlowOption(it, "", "p$it", it == 1L) }
        // 3×1 (4 columns): 4 chips, then 1 chip and Skip / Next / Done.
        val v = CheckInFlow.strip(st, "Hobby", many, 4)
        assertEquals("Hobby · where?", v.head)
        assertEquals(2, v.rows.size)
        assertTrue(v.rows.all { it.size == 4 })
        assertEquals(listOf(1L, 2L, 3L, 4L), v.rows[0].map { it.option!!.id })
        assertEquals(5L, v.rows[1][0].option!!.id)
        assertEquals(listOf(FlowControl.SKIP, FlowControl.NEXT, FlowControl.DONE), v.rows[1].drop(1).map { it.control })
        // 2×1 (2 columns): two chips; the two controls that matter most.
        val s = CheckInFlow.strip(st, "Hobby", many, 2)
        assertEquals(listOf(1L, 2L), s.rows[0].map { it.option!!.id })
        assertEquals(listOf(FlowControl.SKIP, FlowControl.DONE), s.rows[1].map { it.control })
        // 4×1 with few chips: fillers keep the columns, controls stay at the end.
        val f = CheckInFlow.strip(st, "Hobby", many.take(2), 6)
        assertEquals(6, f.rows[0].size)
        assertEquals(2, f.rows[0].count { !it.filler })
        assertTrue(f.rows[1].take(3).all { it.filler })
        // Feeling: five faces and Skip / 📝 at 3×1.
        val faces = (1L..5L).map { FlowOption(it, "😐", "", false) }
        val fe = CheckInFlow.strip(st.copy(step = FlowStep.FEELING), "Hobby", faces, 4)
        assertEquals(listOf(FlowControl.SKIP, FlowControl.NOTE), fe.rows[1].mapNotNull { it.control })
        assertEquals(5, fe.rows.flatten().count { it.option != null })
        assertEquals(listOf(FlowStep.WHERE to false, FlowStep.WHO to false, FlowStep.FEELING to true), fe.steps)
    }

    @Test
    fun blockSizes() {
        val st = started(place = 1).copy(step = FlowStep.WHO)
        val many = (1L..20L).map { FlowOption(it, "", "p$it", false) }
        // 2×2 (3 columns, 2 rows): 6 chips, then the controls row.
        val v = CheckInFlow.block(st, "Hobby", many, 3, 2)
        assertEquals(3, v.rows.size)
        assertEquals(listOf(FlowControl.NONE, FlowControl.NEXT, FlowControl.DONE), v.rows.last().map { it.control })
        assertEquals(6, v.rows.dropLast(1).flatten().count { it.option != null })
        assertEquals(listOf(FlowStep.WHERE to false, FlowStep.WHO to true, FlowStep.FEELING to false), v.steps)
        // Only as many chip rows as needed.
        val few = CheckInFlow.block(st, "Hobby", many.take(4), 7, 4)
        assertEquals(2, few.rows.size)
        assertEquals(7, few.rows[0].size)
        // No chips at all: just the controls.
        assertEquals(1, CheckInFlow.block(st, "Hobby", emptyList(), 3, 2).rows.size)
    }
}
