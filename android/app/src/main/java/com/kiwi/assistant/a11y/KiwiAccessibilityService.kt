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
 * Servicio de accesibilidad de Kiwi. De momento solo hace UNA cosa:
 * darle "me gusta" al vídeo que se está reproduciendo en la app de
 * YouTube, a petición por voz ("oye kiwi, dale like").
 *
 * Cómo funciona: cuando llega el device_command `youtube_like`, leemos
 * el árbol de nodos de la ventana activa (que es YouTube, porque el
 * overlay de la conversación es NOT_FOCUSABLE y no le roba el foco),
 * buscamos el nodo del botón "Me gusta" por su contentDescription y lo
 * pulsamos (ACTION_CLICK; si no es clickable, gesto de toque en sus
 * coordenadas).
 *
 * Es un primer experimento: la etiqueta exacta del botón cambia entre
 * versiones de YouTube, así que [likeCurrentVideo] vuelca al log los
 * candidatos cuando no encuentra match, para afinar el matcher con
 * datos reales del tablet.
 *
 * El usuario debe habilitar el servicio a mano una vez en Ajustes →
 * Accesibilidad → Kiwi. Sin eso, [instance] es null y los like se
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
    // rootInActiveWindow cuando nos pidan el like.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Pulsa el botón "Me gusta" del vídeo en primer plano. Devuelve
     * true si encontró y pulsó algo; false si no (y deja en el log el
     * paquete activo + los candidatos clickables para depurar).
     */
    fun likeCurrentVideo(): Boolean {
        val root = rootInActiveWindow
        if (root == null) {
            KLog.w(TAG, "like: rootInActiveWindow == null (¿ventana sin contenido accesible?)")
            return false
        }
        KLog.i(TAG, "like: ventana activa pkg=${root.packageName}")
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes)

        val match = nodes.firstOrNull { isLikeNode(label(it)) }
        if (match == null) {
            KLog.w(TAG, "like: no encontré botón 'me gusta'. Candidatos clickables:")
            nodes.filter { it.isClickable }.take(40).forEach {
                KLog.w(TAG, "  · '${label(it)}' [${it.className}]")
            }
            return false
        }

        val clicked = clickNodeOrAncestor(match)
        KLog.i(TAG, "like: match='${label(match)}' → clicked=$clicked")
        return clicked
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

    /**
     * Heurística "esto es el botón de me gusta". YouTube en español
     * etiqueta el botón como "Me gusta" (a veces con el recuento). El
     * de no me gusta contiene "me gusta" como substring, así que lo
     * excluimos explícitamente. Inglés como respaldo, con guarda contra
     * "dislike".
     */
    private fun isLikeNode(rawLabel: String): Boolean {
        val l = normalize(rawLabel)
        if (l.isBlank()) return false
        if ("no me gusta" in l) return false           // botón de dislike
        if ("me gusta" in l) return true
        if ("dislike" in l) return false
        return l == "like" || l.startsWith("like ")
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
            KLog.w(TAG, "like: nodo sin bounds, no puedo tocar")
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

        /** Instancia viva del servicio (null si no está habilitado). */
        @Volatile
        var instance: KiwiAccessibilityService? = null
            private set
    }
}
