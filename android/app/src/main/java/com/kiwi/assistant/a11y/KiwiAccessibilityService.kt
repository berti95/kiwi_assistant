package com.kiwi.assistant.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kiwi.assistant.log.KLog
import java.text.Normalizer

/**
 * Servicio de accesibilidad de Kiwi. Primitivo genérico: pulsar por
 * voz un botón de la app en primer plano (YouTube) buscándolo por su
 * etiqueta. "dale like" → pulsa "Me gusta"; "salta el anuncio" →
 * "Saltar anuncio"; "suscríbete" → "Suscribirse", etc. — todo con la
 * misma [clickByLabel], sin código por botón.
 *
 * Cómo funciona: cuando llega el device_command `ui_click` con una
 * etiqueta, leemos el árbol de nodos de la ventana activa (que es
 * YouTube, porque el overlay de la conversación es NOT_FOCUSABLE y no
 * le roba el foco), buscamos el nodo cuya contentDescription/text
 * coincide con la etiqueta (normalizado, sin acentos: exacto → prefijo
 * → substring) y lo pulsamos (ACTION_CLICK; si no es clickable, gesto
 * de toque en sus coordenadas).
 *
 * Si no encuentra match, [clickByLabel] vuelca al log los candidatos
 * clickables para depurar (las etiquetas de YouTube varían por
 * versión/idioma).
 *
 * El usuario debe habilitar el servicio a mano una vez en Ajustes →
 * Accesibilidad → Kiwi. Sin eso, [instance] es null y los ui_click se
 * ignoran (con un warning en el log).
 */
class KiwiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        KLog.i(TAG, "AccessibilityService conectado")
    }

    // No reaccionamos a eventos: las acciones son on-demand vía
    // device_command. Necesitamos el servicio vivo para tener
    // rootInActiveWindow cuando nos pidan pulsar un botón.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Pulsa el botón cuya etiqueta coincide con [target] en la ventana
     * en primer plano. Devuelve true si encontró y pulsó algo; false si
     * no (y deja en el log el paquete activo + los candidatos
     * clickables para depurar). Matching sin acentos: exacto → prefijo
     * → substring; entre varios, gana el de etiqueta más corta (suele
     * ser el botón en sí, no un contenedor que lo describe largo).
     */
    fun clickByLabel(target: String): Boolean {
        val needle = normalize(target)
        if (needle.isBlank()) {
            KLog.w(TAG, "ui_click: etiqueta vacía")
            return false
        }
        val root = rootInActiveWindow
        if (root == null) {
            KLog.w(TAG, "ui_click: rootInActiveWindow == null (¿ventana sin contenido accesible?)")
            return false
        }
        KLog.i(TAG, "ui_click('$target'): ventana activa pkg=${root.packageName}")
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes)

        val match = bestMatch(nodes, needle)
        if (match == null) {
            KLog.w(TAG, "ui_click: no encontré '$target'. Candidatos clickables:")
            nodes.filter { it.isClickable }.take(40).forEach {
                KLog.w(TAG, "  · '${label(it)}' [${it.className}]")
            }
            return false
        }

        val clicked = clickNodeOrAncestor(match)
        KLog.i(TAG, "ui_click('$target'): match='${label(match)}' → clicked=$clicked")
        return clicked
    }

    /**
     * Elige el mejor nodo para [needle] (ya normalizado): exacto gana
     * sobre prefijo, prefijo sobre substring; a igualdad, etiqueta más
     * corta (más probable que sea el propio botón). Solo considera
     * nodos con etiqueta no vacía.
     *
     * Guarda de negación: descarta candidatos donde [needle] aparece
     * precedido de una negación ("no me gusta" cuando pides "me gusta",
     * "dislike" cuando pides "like"). Sin esto, el botón opuesto —que
     * contiene el needle como substring— podía ganar. Caso real: "dale
     * like" pulsaba "No me gusta".
     */
    private fun bestMatch(
        nodes: List<AccessibilityNodeInfo>,
        needle: String,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        for (n in nodes) {
            val l = normalize(label(n))
            if (l.isBlank()) continue
            val rank = when {
                l == needle -> 3
                l.startsWith(needle) -> 2
                needle in l -> if (isNegated(l, needle)) continue else 1
                else -> continue
            }
            // Puntúa: rank manda; a igual rank, etiqueta más corta gana.
            val score = rank * 10_000 - l.length
            if (score > bestScore) {
                bestScore = score
                best = n
            }
        }
        return best
    }

    /**
     * True si [needle] aparece dentro de [label] justo detrás de una
     * negación → es el botón contrario, no el pedido. Si el propio
     * needle empieza por "no" (el usuario pidió la negación) no aplica.
     */
    private fun isNegated(label: String, needle: String): Boolean {
        if (needle.startsWith("no ")) return false
        val idx = label.indexOf(needle)
        if (idx <= 0) return false
        val prevWord = label.substring(0, idx).trimEnd().substringAfterLast(' ')
        return prevWord in NEGATIONS
    }

    /**
     * Devuelve los textos visibles de la ventana en primer plano en
     * orden de lectura (botones + contenido: títulos de una lista,
     * etc.), deduplicados y capados. Lo usa el backend (ui_read_screen)
     * para que Gemini sepa qué hay en pantalla — p.ej. los títulos de
     * "Ver más tarde" para poner "el tercero", o las etiquetas reales
     * de los botones para no inventarlos.
     */
    fun readScreen(): List<String> {
        val root = rootInActiveWindow ?: run {
            KLog.w(TAG, "ui_read: rootInActiveWindow == null")
            return emptyList()
        }
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes)
        val seen = LinkedHashSet<String>()
        for (n in nodes) {
            val l = label(n).trim()
            if (l.isNotBlank() && l.length <= MAX_ITEM_LEN) seen.add(l)
            if (seen.size >= MAX_ITEMS) break
        }
        KLog.i(TAG, "ui_read: pkg=${root.packageName} → ${seen.size} items")
        return seen.toList()
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        out.add(node)
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out)
        }
    }

    private fun label(node: AccessibilityNodeInfo): String {
        val desc = node.contentDescription?.toString().orEmpty()
        if (desc.isNotBlank()) return desc
        return node.text?.toString().orEmpty()
    }

    private fun normalize(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace("\\p{Mn}+".toRegex(), "")
        return n.lowercase().trim()
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) {
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            n = n.parent
        }
        // Ningún ancestro clickable → toque por coordenadas en el centro.
        return tapNode(node)
    }

    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            KLog.w(TAG, "ui_click: nodo sin bounds, no puedo tocar")
            return false
        }
        val path = Path().apply {
            moveTo(bounds.exactCenterX(), bounds.exactCenterY())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAG = "KiwiA11y"

        // Tope de textos que devuelve readScreen y longitud máx. por
        // texto: evita mandarle a Gemini un volcado gigante (la home de
        // YouTube tiene cientos de nodos) y descarta párrafos largos
        // (descripciones) que no son ni botones ni títulos de lista.
        private const val MAX_ITEMS = 60
        private const val MAX_ITEM_LEN = 120

        // Palabras que, justo antes del needle, lo convierten en su
        // opuesto: "no me gusta" vs "me gusta", "dislike" vs "like".
        private val NEGATIONS = setOf("no", "sin", "dis", "not", "quitar", "deshacer")

        /** Instancia viva del servicio (null si no está habilitado). */
        @Volatile
        var instance: KiwiAccessibilityService? = null
            private set
    }
}
