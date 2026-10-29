package com.glint.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DubsTest {
    private fun film(id: String, title: String, year: String) = Dubs.Film(id, listOf(title), year)
    private fun dub(path: String) = DubFile("s", "Seagate", "Film sesleri/$path")
    private fun matchOne(films: List<Dubs.Film>, path: String) = Dubs.match(films, listOf(dub(path))).keys.singleOrNull()

    private val films = listOf(
        film("bttf1", "Back to the Future 1", "1985"),
        film("bttf3", "Back to the Future Part III", "1990"),
        film("antman", "Ant-Man", "2015"),
        film("sixth", "Altıncı His", "1999"),
        film("sw4", "Star Wars Episode IV A New Hope", "1977"),
        film("moon", "Moonwalker", "1988"),
        film("frozen", "Karlar Ülkesi", "2013"),
        film("frozen2", "Karlar Ülkesi II", "2019"),
        film("nine", "9", "2009"),
        film("nine-other", "9", "1999"),
    )

    @Test fun partNumber() = assertEquals("bttf3", matchOne(films, "Back.to.the.Future.Part.3.1990.E-AC3.640Kbps.23Fps.6Ch.TR.Audio.ac3"))
    @Test fun firstFilm() = assertEquals("bttf1", matchOne(films, "Back.to.the.Future.1985.448Kbps.23Fps.AC3.6Ch.TR.BluRay.AC3"))
    @Test fun hyphen() = assertEquals("antman", matchOne(films, "Antman.2015.640Kbps.23Fps.AC3.6Ch.TR.SES.ac3"))
    @Test fun turkishTitle() = assertEquals("sixth", matchOne(films, "Altinci.His.1999+150ver.ac3"))
    @Test fun episodePrefix() = assertEquals("sw4", matchOne(films, "SES.star wars serisi/Star.Wars.Episode.IV.1977.AC3.640Kbps.23Fps.6Ch.TR.Audio.ac3"))
    @Test fun leadingBitrate() = assertEquals("moon", matchOne(films, "204kbps.Moonwalker 1988 SES.mka"))
    @Test fun oneCharacterTitle() = assertEquals("nine", matchOne(films, "9.2009.448Kbps.23Fps.48KHz.DD.6Ch.TR.BLURAY.SHARESES+1950ver.ac3"))
    @Test fun noYear() = assertEquals("frozen", matchOne(films, "Karlar Ülkesi TR Ses.aac"))

    /** Matches every real dub against every real film name: -Dpaths=<videos> (audio.txt beside it). */
    @Test fun dump() {
        val videos = System.getProperty("paths")?.let(::File)?.takeIf { it.exists() } ?: return
        val audio = File(videos.parentFile, "audio.txt").takeIf { it.exists() } ?: return
        val filmList = videos.readLines().map { it.trimStart('﻿') }.filter { '|' in it && !Dubs.FOLDER.containsMatchIn(it.substringAfter('|').substringBeforeLast('/')) }
            .mapNotNull { line ->
                val path = line.substringAfter('|')
                (Names.parse(path) as? Parsed.MovieName)?.let { Dubs.Film(path, listOf(it.title), it.year) }
            }
        val dubs = audio.readLines().map { it.trimStart('﻿') }.filter { '|' in it }.map { it.substringAfter('|') }
            .filter { Names.ext(it) in Dubs.AUDIO_EXT }
            .map { DubFile("s", "Seagate", it) }
        val result = Dubs.match(filmList, dubs)
        val matched = result.values.flatten().map { it.path }.toSet()
        val out = StringBuilder()
        out.append("films ${filmList.size}, dubs ${dubs.size}, matched dubs ${matched.size}, films with dub ${result.size}\n\n== UNMATCHED\n")
        dubs.filter { it.path !in matched }.forEach { out.append("${Dubs.parse(it.path)}  <=  ${it.path}\n") }
        out.append("\n== MATCHED\n")
        result.toSortedMap().forEach { (film, list) -> list.forEach { out.append("${film.substringAfterLast('/')}  <=  ${it.path.substringAfterLast('/')}\n") } }
        File(videos.parentFile, "dubs-report.txt").writeText(out.toString())
    }
}
