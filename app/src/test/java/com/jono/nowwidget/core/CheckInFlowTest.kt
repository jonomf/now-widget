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

    private fun links(o: List<FlowOption>) = o.filter { it.type == OptionType.LINK }

    @Test
    fun optionsPerStep() {
        val st = started(place = 3)
        val where = opts(st, mapOf(2L to 5.0))
        assertEquals(listOf(3L, 2L, 1L), links(where).map { it.id })
        assertEquals(listOf(true, false, false), links(where).map { it.on })
        val who = opts(st.copy(step = FlowStep.WHO, people = setOf(10, 12)), mapOf(12L to 1.0))
        assertEquals(listOf(10L, 12L, 11L), links(who).map { it.id })
        assertEquals(listOf(true, true, false), links(who).map { it.on })
        val faces = opts(st.copy(step = FlowStep.FEELING))
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), faces.map { it.id }) // by level, archived left out
        assertTrue(faces.all { it.label.isEmpty() && it.type == OptionType.MOOD })
        // Two moods on one level: every face gets its name.
        val named = CheckInFlow.options(st.copy(step = FlowStep.FEELING), places, people,
            moods + Mood(7, "calm", 3, "😌", 6, false), emptyMap()) { it.emoji }
        assertEquals("rad", named.last().label)
    }

    @Test
    fun findIsAlwaysLastOnWhereAndWho() {
        val st = started(place = 3)
        for (o in listOf(opts(st), opts(st.copy(step = FlowStep.WHO)))) {
            assertEquals(OptionType.PLUS, o.last().type)
            assertEquals(1, o.count { it.type == OptionType.PLUS })
            assertEquals(0, o.count { it.type == OptionType.HINT })
        }
        assertTrue(opts(st.copy(step = FlowStep.FEELING)).none { it.type == OptionType.PLUS })
        // Windowed to any size, ＋ stays last; with no room it takes the last cell, never dropped.
        val o = opts(st)
        assertEquals(listOf(OptionType.LINK, OptionType.PLUS), CheckInFlow.window(o, 2).map { it.type })
        assertEquals(listOf(OptionType.PLUS), CheckInFlow.window(o, 1).map { it.type })
        assertTrue(CheckInFlow.window(o, 0).isEmpty())
        val strip = CheckInFlow.strip(st, "Hobby", o, 3, 2)
        assertEquals(OptionType.PLUS, strip.rows.flatten().last { !it.filler }.option!!.type)
    }

    @Test
    fun noChipsStillAsksWithFindAndAHint() {
        // His people come from the vault and aren't chips until used: they're still offered,
        // after the chips, by name.
        val vault = listOf(
            Link(20, "Zed", LinkKind.PERSON, "", 0, false, fromVault = true),
            Link(21, "amy", LinkKind.PERSON, "", 1, false, fromVault = true),
            Link(22, "Gone", LinkKind.PERSON, "", 2, true, fromVault = true),
        )
        assertTrue(vault.none { it.chip })
        val st = CheckInFlow.start(entry, FlowSettings(), null, emptyList(), now)!!
        assertEquals(listOf(FlowStep.WHERE, FlowStep.WHO, FlowStep.FEELING), st.steps)
        val who = CheckInFlow.options(st.copy(step = FlowStep.WHO), emptyList(), people + vault, moods, emptyMap()) { it.emoji }
        assertEquals(listOf(10L, 11L, 12L, 21L, 20L), links(who).map { it.id }) // chips, then vault a–z, removed left out
        // None at all: the hint, then Find.
        val none = CheckInFlow.options(st, emptyList(), emptyList(), moods, emptyMap()) { it.emoji }
        assertEquals(listOf(OptionType.HINT, OptionType.PLUS), none.map { it.type })
        assertEquals(CheckInFlow.HINT_PLACES, none[0].label)
        val nobody = CheckInFlow.options(st.copy(step = FlowStep.WHO), emptyList(), emptyList(), moods, emptyMap()) { it.emoji }
        assertEquals(CheckInFlow.HINT_PEOPLE, nobody[0].label)
        // The step's controls are still there.
        assertEquals(listOf(FlowControl.SKIP, FlowControl.NEXT, FlowControl.DONE), CheckInFlow.block(st, "Hobby", none, 3, 2).controls)
    }

    @Test
    fun aFoundPersonIsAddedAndBecomesAChip() {
        val found = Link(30, "Dulce", LinkKind.PERSON, "", 9, false, fromVault = true)
        assertFalse(found.chip)
        val st = started().copy(step = FlowStep.WHO)
        val e = entry.copy(place = 1, people = setOf(10))
        // A pick from Find is a chip tap: added to the selection, and (keeping defaults) saved.
        val r = CheckInFlow.tap(st, e, FlowTap.Pick(30), now, FlowStep.WHO)
        assertEquals(setOf(10L, 30L), r.state!!.people)
        assertEquals(listOf(FlowEffect.SetPeople(50, setOf(10L, 30L))), r.effects)
        // Chosen, it's shown even at the back of a long list.
        val many = (100L..140L).map { person(it, "P$it") }
        val o = CheckInFlow.options(r.state!!, places, people + many + found, moods, emptyMap()) { it.emoji }
        assertTrue(CheckInFlow.window(o, 6).any { it.id == 30L && it.on })
        // Saved on an entry, it's used, so it's a chip from then on.
        assertTrue(found.copy(uses = 1).chip)
    }

    @Test
    fun plusIsLastInEveryChipList() {
        // Activity buttons and link rows: as many items as fit before ＋; ＋ takes the last cell when they don't.
        assertEquals(3, CheckInFlow.itemsBeforePlus(10, 4)) // 2×1: three buttons and ＋
        assertEquals(2, CheckInFlow.itemsBeforePlus(2, 4))  // room to spare: ＋ right after them
        assertEquals(0, CheckInFlow.itemsBeforePlus(5, 1))  // one cell: just ＋
        assertEquals(0, CheckInFlow.itemsBeforePlus(0, 6))
        assertEquals(0, CheckInFlow.itemsBeforePlus(5, 0))
        assertEquals(27, CheckInFlow.itemsBeforePlus(Int.MAX_VALUE, 28)) // 4×4: 27 buttons and ＋
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
        assertEquals(FlowColumn(FlowControl.NEXT, listOf(FlowControl.SKIP, FlowControl.DONE)), CheckInFlow.column(FlowStep.WHERE))
        assertEquals(FlowColumn(FlowControl.NEXT, listOf(FlowControl.NONE, FlowControl.DONE)), CheckInFlow.column(FlowStep.WHO))
        assertEquals(FlowColumn(FlowControl.SKIP, listOf(FlowControl.NOTE)), CheckInFlow.column(FlowStep.FEELING))
        assertTrue(CheckInFlow.primary(FlowControl.NEXT))
        assertFalse(CheckInFlow.primary(FlowControl.SKIP))
        assertFalse(CheckInFlow.primary(FlowControl.DONE))
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
        val many = (1L..20L).map { FlowOption(it, "", "p$it", it == 1L) } + FlowOption(0, "＋", "More", false, OptionType.PLUS)
        // STRIP (2 × 2 chips): the chips only; the controls are the column.
        val v = CheckInFlow.strip(st, "Hobby", many, 2, 2)
        assertEquals("Hobby · where?", v.head)
        assertEquals(listOf(listOf(1L, 2L), listOf(3L, 0L)), v.rows.map { r -> r.map { it.option!!.id } })
        assertEquals(OptionType.PLUS, v.rows[1][1].option!!.type)
        // STRIP_S (one row of two): the default, then ＋.
        val s = CheckInFlow.strip(st, "Hobby", many, 2, 1)
        assertEquals(listOf(listOf(1L, 0L)), s.rows.map { r -> r.map { it.option!!.id } })
        assertEquals(OptionType.PLUS, s.rows[0][1].option!!.type)
        // A single cell: ＋ replaces the chip.
        assertEquals(OptionType.PLUS, CheckInFlow.strip(st, "Hobby", many, 1, 1).rows[0][0].option!!.type)
        // Few chips: fillers keep the columns.
        val f = CheckInFlow.strip(st, "Hobby", many.take(2), 3, 2)
        assertEquals(1, f.rows.size)
        assertEquals(1, f.rows[0].count { it.filler })
        // Feeling: five faces in STRIP_L's 3 × 2.
        val faces = (1L..5L).map { FlowOption(it, "😐", "", false, OptionType.MOOD) }
        val fe = CheckInFlow.strip(st.copy(step = FlowStep.FEELING), "Hobby", faces, 3, 2)
        assertEquals(5, fe.rows.flatten().count { !it.filler })
        assertEquals(listOf(FlowStep.WHERE to false, FlowStep.WHO to false, FlowStep.FEELING to true), fe.steps)
    }

    @Test
    fun blockSizes() {
        val st = started(place = 1).copy(step = FlowStep.WHO)
        val many = (1L..20L).map { FlowOption(it, "", "p$it", false) }
        // 2×2 (3 columns, 2 rows): 6 chips, and the controls as their own full-width row.
        val v = CheckInFlow.block(st, "Hobby", many, 3, 2)
        assertEquals(2, v.rows.size)
        assertEquals(listOf(FlowControl.NONE, FlowControl.NEXT, FlowControl.DONE), v.controls)
        assertEquals(6, v.rows.flatten().count { !it.filler })
        assertEquals(listOf(FlowStep.WHERE to false, FlowStep.WHO to true, FlowStep.FEELING to false), v.steps)
        // Only as many chip rows as needed.
        val few = CheckInFlow.block(st, "Hobby", many.take(4), 7, 4)
        assertEquals(1, few.rows.size)
        assertEquals(7, few.rows[0].size)
        // No chips at all: just the controls.
        assertTrue(CheckInFlow.block(st, "Hobby", emptyList(), 3, 2).rows.isEmpty())
    }
}
