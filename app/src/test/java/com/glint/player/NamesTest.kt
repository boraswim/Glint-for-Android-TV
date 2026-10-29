package com.glint.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NamesTest {
    private fun movie(path: String) = Names.parse(path) as Parsed.MovieName
    private fun episode(path: String) = Names.parse(path) as Parsed.EpisodeName

    @Test fun sceneMovie() = assertEquals(Parsed.MovieName("Toy Story 3", "2010"), movie("Toy.Story.3.2010.REPACK.UHD.BluRay.2160p.TrueHD.Atmos.7.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv"))
    @Test fun spacedMovie() = assertEquals(Parsed.MovieName("Kung Fu Panda 2", "2011"), movie("Kung Fu Panda 2 2011 1080p Blu-ray Remux AVC TrueHD 7.1 - KRaLiMaRKo.mkv"))
    @Test fun numberTitle() = assertEquals(Parsed.MovieName("9", "2009"), movie("Video/9.2009.1080p.BluRay.Hybrid.REMUX.AVC.DTS-HD.MA.5.1-TRiToN.mkv"))
    @Test fun trailingJunk() = assertEquals(Parsed.MovieName("Airplane II The Sequel", "1982"), movie("Filmler/Airplane II The Sequel 1982. BORA ARDA SUNNET 03HAZ2011.mkv"))
    @Test fun apostrophe() = assertEquals(Parsed.MovieName("Harry Potter and the Sorcerer's Stone", "2001"), movie("Harry.Potter.and.the.Sorcerer's.Stone.2001.Theatrical.Cut.UHD.BluRay.2160p.mkv"))
    @Test fun sceneEpisode() = assertEquals(Parsed.EpisodeName("Battlestar Galactica", "1978", 1, 2), episode("Television/Battlestar.Galactica.1978.S01.1080p.REMUX-FraMeSToR/Battlestar.Galactica.1978.S01E02.1080p.REMUX.mkv"))
    @Test fun editionBeforeYear() = assertEquals(Parsed.MovieName("Avatar", "2009"), movie("Avatar.Extended.Cut.2009.UHD.Re-Grade.HDR4000nit.EN.TR-ExKinoRay.mkv"))
    @Test fun acronym() = assertEquals(Parsed.MovieName("GORA", "2004"), movie("G.O.R.A.2004.1080p.BluRay.mkv"))
    @Test fun yearFromFolder() = assertEquals(Parsed.EpisodeName("Battlestar Galactica", "1978", 1, 4), episode("Television/Battlestar.Galactica.1978.S01.1080p.REMUX-FraMeSToR/Battlestar.Galactica.S01E04.The.Lost.Planet.1080p.mkv"))
    @Test fun seasonFolder() = assertEquals(Parsed.EpisodeName("The Wire", "", 2, 5), episode("TV/The Wire/Season 2/05 - Undertow.mkv"))

    /** Dumps how every real file name parses, for eyeballing: -Dpaths=<file> */
    @Test fun dump() {
        val list = System.getProperty("paths")?.let(::File)?.takeIf { it.exists() } ?: return
        val out = File(list.parentFile, "parsed.txt")
        out.writeText(list.readLines().filter { it.isNotBlank() }.joinToString("\n") { line ->
            val path = line.substringAfter('|').trimStart('﻿')
            "${Names.parse(path)}   <=  $path"
        })
    }
}
