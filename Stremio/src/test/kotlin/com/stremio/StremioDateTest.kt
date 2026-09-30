package com.stremio

private fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}

private const val DAY_MS = 86_400_000L

private fun dayOf(epochMillis: Long): String {
    val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    c.timeInMillis = epochMillis
    return "%04d-%02d-%02d".format(
        c.get(java.util.Calendar.YEAR),
        c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.DAY_OF_MONTH),
    )
}

fun parsesTheFormatCinemetaActuallySends() {
    val iso = parseReleaseDate("2008-01-21T05:00:00.000Z") ?: throw AssertionError("cinemeta format rejected")
    expect(dayOf(iso) == "2008-01-21") { "cinemeta date landed on ${dayOf(iso)}" }

    val series = parseReleaseDate("2010-10-12T05:00:00.000Z")
        ?: throw AssertionError("series format rejected")
    expect(dayOf(series) == "2010-10-12") { "series date landed on ${dayOf(series)}" }
}

fun utcIsHonouredNotStripped() {
    val withZ = parseReleaseDate("2008-01-21T23:30:00.000Z")!!
    val withOffset = parseReleaseDate("2008-01-21T23:30:00+00:00")!!
    expect(withZ == withOffset) { "Z and +00:00 must agree, got $withZ vs $withOffset" }

    val plusTwo = parseReleaseDate("2008-01-21T23:30:00+02:00")!!
    expect(dayOf(plusTwo) == "2008-01-21") { "offset date landed on ${dayOf(plusTwo)}" }
    expect(plusTwo < withZ) { "+02:00 should be two hours earlier in UTC" }
    expect(withZ - plusTwo == 2 * 3_600_000L) { "offset not applied, delta was ${withZ - plusTwo}" }
}

fun parsesTheOtherFormatsAddonsUse() {
    expect(dayOf(parseReleaseDate("2020-05-05")!!) == "2020-05-05") { "date-only failed" }
    expect(dayOf(parseReleaseDate("2020-05-05T00:00:00")!!) == "2020-05-05") { "no-zone instant failed" }
    expect(dayOf(parseReleaseDate("2020")!!) == "2020-01-01") { "year-only failed" }
    expect(dayOf(parseReleaseDate("  2020-05-05  ")!!) == "2020-05-05") { "padding not trimmed" }

    val millis = 1_588_713_600_000L
    expect(parseReleaseDate(millis.toString()) == millis) { "epoch millis altered" }
    expect(parseReleaseDate((millis / 1000).toString()) == millis) { "epoch seconds not scaled" }
}

fun missingOrNonsenseDatesAreRejected() {
    expect(parseReleaseDate(null) == null) { "null should be null" }
    expect(parseReleaseDate("") == null) { "empty should be null" }
    expect(parseReleaseDate("   ") == null) { "blank should be null" }
    expect(parseReleaseDate("soon") == null) { "prose should be null, not a bogus epoch" }
    expect(parseReleaseDate("not-a-date") == null) { "garbage should be null" }
}

fun findsADateInTheBlurb() {
    expect(dayOf(dateFromText("Air date: 5 May 2020.")!!) == "2020-05-05") { "d Month yyyy missed" }
    expect(dayOf(dateFromText("Originally aired May 5, 2020")!!) == "2020-05-05") { "Month d, yyyy missed" }
    expect(dayOf(dateFromText("Aired 12 November 2019.")!!) == "2019-11-12") { "day 12 missed" }
    expect(dateFromText("No date in this text at all") == null) { "should not invent a date" }
    expect(dateFromText("") == null) { "empty blurb should be null" }
    expect(dateFromText(null) == null) { "null blurb should be null" }
}

fun doesNotInventDatesFromPartialWords() {
    expect(dateFromText("Mayhem in October") == null) { "'Mayhem' should not parse as May" }
    expect(dateFromText("Marching on 5 soldiers") == null) { "'Marching' should not parse as March" }
}

fun runDateChecks() {
    parsesTheFormatCinemetaActuallySends()
    utcIsHonouredNotStripped()
    parsesTheOtherFormatsAddonsUse()
    missingOrNonsenseDatesAreRejected()
    findsADateInTheBlurb()
    doesNotInventDatesFromPartialWords()
}
