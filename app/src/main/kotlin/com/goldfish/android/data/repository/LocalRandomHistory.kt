package com.goldfish.android.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-weiter Singleton-State der Zufallswiedergabe-History fuer die
 * lokalen Bibliotheken. Wird von der Navigation gefuettert: jedes neue
 * Random-Item wird ge`push`t, ⏮ ruft `goBack()`, ⏭ versucht erst
 * `goForwardFromHistory()` (wenn der User vorher zurueck gesprungen ist)
 * und faellt sonst auf ein neues Repository-Random zurueck.
 *
 * State ist nicht persistent — bei App-Restart frisch. Das ist gewollt:
 * eine Zufalls-Session beginnt jeweils neu.
 */
@Singleton
class LocalRandomHistory @Inject constructor() {
    private val history = mutableListOf<Int>()
    private var index: Int = -1

    /** Loescht die History — wird beim Start einer neuen Zufalls-Session
     *  aufgerufen (Library/Folder ⇄ Casino-Button). */
    fun reset() {
        history.clear()
        index = -1
    }

    /** Fuegt ein Item-ID am aktuellen Cursor an. Wenn der User vorher
     *  zurueck gesprungen war, wird der "Forward"-Tail verworfen — das
     *  entspricht typischem Browser-/Mediaplayer-Verhalten. */
    fun push(itemId: Int) {
        if (index >= 0 && index < history.size - 1) {
            // alte Forward-Geschichte abschneiden
            val cutFrom = index + 1
            history.subList(cutFrom, history.size).clear()
        }
        history.add(itemId)
        index = history.size - 1
    }

    /** Geht eine Position zurueck. Returns die ID des vorherigen Items
     *  oder null, wenn wir bereits am Anfang sind. */
    fun goBack(): Int? {
        if (index <= 0) return null
        index--
        return history[index]
    }

    /** Wenn der User zuvor zurueck gesprungen ist, gibt das naechste Item
     *  aus der History zurueck — sonst null (Caller soll dann ein neues
     *  zufaelliges Item holen und via push speichern). */
    fun goForwardFromHistory(): Int? {
        if (index >= history.size - 1) return null
        index++
        return history[index]
    }

    fun hasPrevious(): Boolean = index > 0
}
