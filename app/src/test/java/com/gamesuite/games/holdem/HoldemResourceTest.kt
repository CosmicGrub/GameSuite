package com.gamesuite.games.holdem

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Reads the real res/values{,-es} files from disk (no Android needed). */
abstract class HoldemResourceTest {
    protected fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        val fromRoot = File("app/src/main/$relative")
        if (fromRoot.exists()) return fromRoot
        error("Cannot find $relative")
    }

    protected class Parsed(val strings: Map<String, String>, val arrays: Map<String, List<String>>)

    protected fun parseFile(f: File): Parsed {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val strings = linkedMapOf<String, String>()
        val arrays = linkedMapOf<String, List<String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = e.textContent
                "string-array" -> {
                    val items = e.getElementsByTagName("item")
                    arrays[e.getAttribute("name")] = (0 until items.length).map { items.item(it).textContent }
                }
            }
        }
        return Parsed(strings, arrays)
    }

    protected fun parseDirMerged(dir: String): Parsed {
        val strings = linkedMapOf<String, String>()
        val arrays = linkedMapOf<String, List<String>>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.sortedBy { it.name }.forEach {
            val p = parseFile(it)
            strings.putAll(p.strings)
            arrays.putAll(p.arrays)
        }
        return Parsed(strings, arrays)
    }

    protected val enAll: Map<String, String> by lazy { parseDirMerged("res/values").strings }
    protected val esAll: Map<String, String> by lazy { parseDirMerged("res/values-es").strings }
    protected val enArrays: Map<String, List<String>> by lazy { parseDirMerged("res/values").arrays }
    protected val esArrays: Map<String, List<String>> by lazy { parseDirMerged("res/values-es").arrays }

    protected fun placeholders(s: String): List<String> = Regex("%(\\d+\\$)?[sdf]").findAll(s).map { it.value }.sorted().toList()

    /** An Android string as a person reads it: \' is an apostrophe, \n a newline. */
    protected fun shown(raw: String) = raw.replace("\\'", "'").replace("\\n", "\n")
}
