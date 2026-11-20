package com.aravind.hendrifs

import com.aravind.hendrifs.protocol.IdCoreProtocol.Control
import org.w3c.dom.Element
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Blackstar Architect patch files (.bstar, XML). The field-to-parameter mapping was confirmed by capturing
 * Architect loading a .bstar onto an ID:Core V4 (research/V4-PROTOCOL.md).
 */
object BstarFormat {
    data class Patch(val name: String, val creator: String, val genre: String, val values: Map<Control, Int>)

    /** Architect's genre numbers are not documented; these labels are a best guess and only used for sorting. */
    private val GENRES = listOf("Rock", "Metal", "Pop", "Blues", "Punk", "Surf", "Other")

    fun parse(input: InputStream): Patch {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val root = f.newDocumentBuilder().parse(input).documentElement
        require(root.tagName == "Sound") { "Not an Architect patch" }

        fun el(parent: Element?, tag: String): Element? = parent?.getElementsByTagName(tag)?.item(0) as? Element
        fun num(parent: Element?, tag: String): Int? = el(parent, tag)?.textContent?.trim()?.toIntOrNull()
        val v = mutableMapOf<Control, Int>()
        fun put(c: Control, x: Int?) { if (x != null) v[c] = x.coerceIn(c.min, c.max) }

        val amp = el(root, "Amplifier")
        put(Control.VOICE, num(amp, "Voice")); put(Control.GAIN, num(amp, "Gain"))
        put(Control.BASS, num(amp, "Bass")); put(Control.MIDDLE, num(amp, "Middle")); put(Control.TREBLE, num(amp, "Treble"))
        put(Control.ISF, num(amp, "ISF"))
        el(amp, "TVP")?.let { put(Control.TVP, it.textContent.trim().toIntOrNull()); put(Control.TVP_ON, it.getAttribute("Status").toIntOrNull()) }
        put(Control.RESONANCE, num(amp, "Resonance")); put(Control.PRESENCE, num(amp, "Presence"))

        val fx = el(root, "EffectsChain")
        fx?.getAttribute("Focused")?.toIntOrNull()?.let { put(Control.FX_FOCUS, it) }
        el(fx, "Modulation")?.let { m ->
            put(Control.MOD_ON, m.getAttribute("Status").toIntOrNull()); put(Control.MOD_TYPE, num(m, "Types"))
            put(Control.MOD_SEGVAL, num(m, "Adjust1")); put(Control.MOD_DEPTH, num(m, "Adjust2"))
            put(Control.MOD_LEVEL, num(m, "Level")); put(Control.MOD_SPEED, num(m, "Rate"))
        }
        el(fx, "Delay")?.let { d ->
            put(Control.DELAY_ON, d.getAttribute("Status").toIntOrNull()); put(Control.DELAY_TYPE, num(d, "Types"))
            put(Control.DELAY_FEEDBACK, num(d, "Adjust1")); put(Control.DELAY_ADJ2, num(d, "Adjust2"))
            put(Control.DELAY_LEVEL, num(d, "Level")); put(Control.DELAY_TIME, num(d, "Tempo"))
        }
        el(fx, "Reverb")?.let { r ->
            put(Control.REVERB_ON, r.getAttribute("Status").toIntOrNull()); put(Control.REVERB_TYPE, num(r, "Types"))
            put(Control.REVERB_SIZE, num(r, "Adjust1")); put(Control.REVERB_ADJ2, num(r, "Adjust2")); put(Control.REVERB_LEVEL, num(r, "Level"))
        }
        el(root, "NoiseGate")?.let { g -> put(Control.GATE_SENS, num(g, "Sensitivity")); put(Control.GATE_AMOUNT, num(g, "Amount")) }

        val info = el(root, "Info")
        val name = el(info, "Name")?.textContent?.trim().orEmpty().ifEmpty { "Imported patch" }
        val genre = num(info, "Genre")?.let { GENRES.getOrNull(it) } ?: "Other"
        return Patch(name, el(info, "Creator")?.textContent?.trim().orEmpty(), genre, v)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Writes a patch Architect can open (same layout as Architect's own files). */
    fun write(name: String, genre: String, values: Map<Control, Int>): String {
        fun g(c: Control) = values[c] ?: 0
        val gi = GENRES.indexOf(genre).let { if (it < 0) -1 else it }
        val now = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date())
        return """<?xml version="1.0" encoding="UTF-8"?>

<Sound FirmwareVersion="1" CE="1" CS="1" LastModified="$now">
  <Amplifier>
    <Voice>${g(Control.VOICE)}</Voice>
    <Gain>${g(Control.GAIN)}</Gain>
    <Volume>64</Volume>
    <Bass>${g(Control.BASS)}</Bass>
    <Middle>${g(Control.MIDDLE)}</Middle>
    <Treble>${g(Control.TREBLE)}</Treble>
    <ISF>${g(Control.ISF)}</ISF>
    <TVP Status="${g(Control.TVP_ON)}">${g(Control.TVP)}</TVP>
  </Amplifier>
  <EffectsChain Focused="${g(Control.FX_FOCUS)}">
    <Modulation Position="0" Status="${g(Control.MOD_ON)}">
      <Types Phaser="0" Flanger="4" Chorus="8" Tremolo="12">${g(Control.MOD_TYPE)}</Types>
      <Adjust1>${g(Control.MOD_SEGVAL)}</Adjust1>
      <Adjust2>${g(Control.MOD_DEPTH)}</Adjust2>
      <Level>${g(Control.MOD_LEVEL)}</Level>
      <Rate>${g(Control.MOD_SPEED)}</Rate>
    </Modulation>
    <Delay Position="0" Status="${g(Control.DELAY_ON)}">
      <Types Linear="16" Analogue="17" Tape="21" Multi="25">${g(Control.DELAY_TYPE)}</Types>
      <Adjust1>${g(Control.DELAY_FEEDBACK)}</Adjust1>
      <Adjust2>${g(Control.DELAY_ADJ2)}</Adjust2>
      <Level>${g(Control.DELAY_LEVEL)}</Level>
      <Tempo>${values[Control.DELAY_TIME] ?: 400}</Tempo>
    </Delay>
    <Reverb Position="0" Status="${g(Control.REVERB_ON)}">
      <Types Room="29" Hall="33" Spring="37" Plate="41">${g(Control.REVERB_TYPE)}</Types>
      <Adjust1>${g(Control.REVERB_SIZE)}</Adjust1>
      <Adjust2>${g(Control.REVERB_ADJ2)}</Adjust2>
      <Level>${g(Control.REVERB_LEVEL)}</Level>
    </Reverb>
  </EffectsChain>
  <Audio>
    <Metronome Type="0">120</Metronome>
    <Track Repeat="0">NO TRACK</Track>
  </Audio>
  <Tuner>0</Tuner>
  <Bench>0</Bench>
  <Info>
    <Name>${esc(name.take(20))}</Name>
    <Creator>Hendrifs</Creator>
    <Genre>$gi</Genre>
    <SubGenre>-1</SubGenre>
    <SearchTags/>
    <About>NO DESCRIPTION</About>
  </Info>
</Sound>
"""
    }
}
