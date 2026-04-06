/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.ui

import dev.streampack.startrader.model.Planet
import dev.streampack.startrader.model.UniverseState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D rotating star map rendered with perspective projection. Supports auto-rotation, mouse drag for
 * manual rotation, and scroll wheel for zoom.
 */
class UniverseMapPanel : JPanel() {
    private var state: UniverseState? = null

    // Rotation angles in radians (around Y and X axes)
    private var angleY = 0.3
    private var angleX = 0.2

    // Auto-rotation speed (radians per frame)
    private var autoRotateSpeed = 0.008
    private var autoRotating = true

    // Zoom factor: multiplier for filling the viewport (1.0 = universe fills ~80% of window)
    private var zoomFactor = 1.0

    // Mouse drag state
    private var lastMouseX = 0
    private var lastMouseY = 0
    private var dragging = false

    // Visual constants
    private val bgColor = Color(10, 10, 30)
    private val gridColor = Color(30, 30, 60)
    private val labelFont = Font("Monospaced", Font.BOLD, 13)
    private val smallFont = Font("Monospaced", Font.PLAIN, 10)

    // Precomputed: center of the universe (for rotation around center)
    private var centerX = 50.0
    private var centerY = 50.0
    private var centerZ = 50.0

    init {
        background = bgColor
        isDoubleBuffered = true

        // Auto-rotation timer at ~30fps
        val timer =
            Timer(33) {
                if (autoRotating) {
                    angleY += autoRotateSpeed
                    repaint()
                }
            }
        timer.start()

        val mouseHandler =
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    lastMouseX = e.x
                    lastMouseY = e.y
                    dragging = true
                    autoRotating = false
                }

                override fun mouseReleased(e: MouseEvent) {
                    dragging = false
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (dragging) {
                        val dx = e.x - lastMouseX
                        val dy = e.y - lastMouseY
                        angleY += dx * 0.005
                        angleX += dy * 0.005
                        // Clamp X rotation to avoid gimbal weirdness
                        angleX = angleX.coerceIn(-Math.PI / 2 + 0.1, Math.PI / 2 - 0.1)
                        lastMouseX = e.x
                        lastMouseY = e.y
                        repaint()
                    }
                }

                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    zoomFactor -= e.wheelRotation * 0.1
                    zoomFactor = zoomFactor.coerceIn(0.3, 4.0)
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        autoRotating = !autoRotating
                    }
                }
            }

        addMouseListener(mouseHandler)
        addMouseMotionListener(mouseHandler)
        addMouseWheelListener(mouseHandler)
    }

    fun updateState(newState: UniverseState) {
        state = newState

        // Recompute center of universe
        if (newState.planets.isNotEmpty()) {
            centerX = newState.planets.sumOf { it.x } / newState.planets.size
            centerY = newState.planets.sumOf { it.y } / newState.planets.size
            centerZ = newState.planets.sumOf { it.z } / newState.planets.size
        }

        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )

        val w = width
        val h = height
        val screenCenterX = w / 2.0
        val screenCenterY = h / 2.0

        // Draw background grid (subtle reference cube edges)
        drawReferenceGrid(g2, screenCenterX, screenCenterY)

        val planets = state?.planets ?: return

        // Project all planets to screen coordinates with depth
        data class ProjectedPlanet(
            val planet: Planet,
            val screenX: Double,
            val screenY: Double,
            val depth: Double,
        )

        val projected =
            planets
                .map { planet ->
                    val (sx, sy, depth) =
                        projectToScreen(planet.x, planet.y, planet.z, screenCenterX, screenCenterY)
                    ProjectedPlanet(planet, sx, sy, depth)
                }
                .sortedByDescending { it.depth }

        // Draw connection lines between nearby planets (faint, depth-faded)
        drawConnections(g2, projected)

        // Draw planets (back to front for correct overlap)
        for (pp in projected) {
            drawPlanet(g2, pp.planet, pp.screenX, pp.screenY, pp.depth)
        }

        // Draw HUD
        drawHud(g2, w, h)
    }

    /** Project a 3D point to 2D screen coordinates using rotation + perspective */
    private fun projectToScreen(
        worldX: Double,
        worldY: Double,
        worldZ: Double,
        screenCenterX: Double,
        screenCenterY: Double,
    ): Triple<Double, Double, Double> {
        // Translate to center of universe
        var x = worldX - centerX
        var y = worldY - centerY
        var z = worldZ - centerZ

        // Rotate around Y axis
        val cosY = cos(angleY)
        val sinY = sin(angleY)
        val rx = x * cosY + z * sinY
        val rz = -x * sinY + z * cosY
        x = rx
        z = rz

        // Rotate around X axis
        val cosX = cos(angleX)
        val sinX = sin(angleX)
        val ry = y * cosX - z * sinX
        val rz2 = y * sinX + z * cosX
        y = ry
        z = rz2

        // Perspective projection: slight depth effect, then scale to fill the viewport
        val perspectiveScale = 300.0 / (300.0 + z + 50.0)
        val viewportScale = min(screenCenterX, screenCenterY) / 70.0 * zoomFactor
        val screenX = screenCenterX + x * perspectiveScale * viewportScale
        val screenY = screenCenterY - y * perspectiveScale * viewportScale

        return Triple(screenX, screenY, z)
    }

    /** Draw faint edges of a reference cube around the universe */
    private fun drawReferenceGrid(g2: Graphics2D, cx: Double, cy: Double) {
        g2.color = gridColor
        g2.stroke = BasicStroke(0.5f)

        val corners =
            listOf(
                Triple(0.0, 0.0, 0.0),
                Triple(100.0, 0.0, 0.0),
                Triple(100.0, 100.0, 0.0),
                Triple(0.0, 100.0, 0.0),
                Triple(0.0, 0.0, 100.0),
                Triple(100.0, 0.0, 100.0),
                Triple(100.0, 100.0, 100.0),
                Triple(0.0, 100.0, 100.0),
            )

        val edges =
            listOf(
                0 to 1,
                1 to 2,
                2 to 3,
                3 to 0,
                4 to 5,
                5 to 6,
                6 to 7,
                7 to 4,
                0 to 4,
                1 to 5,
                2 to 6,
                3 to 7,
            )

        val projected = corners.map { (x, y, z) -> projectToScreen(x, y, z, cx, cy) }

        for ((a, b) in edges) {
            val (x1, y1) = projected[a]
            val (x2, y2) = projected[b]
            g2.drawLine(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
        }
    }

    /** Draw faint lines between planets that are within a certain distance */
    private fun drawConnections(g2: Graphics2D, projected: List<Any>) {
        // Access the projected planets through the state directly
        val planets = state?.planets ?: return
        val connectionDistance = 40.0

        g2.stroke = BasicStroke(0.5f)
        val cx = width / 2.0
        val cy = height / 2.0

        for (i in planets.indices) {
            for (j in i + 1 until planets.size) {
                val p1 = planets[i]
                val p2 = planets[j]
                val dist = distance3d(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z)
                if (dist > connectionDistance) continue

                val (x1, y1, d1) = projectToScreen(p1.x, p1.y, p1.z, cx, cy)
                val (x2, y2, d2) = projectToScreen(p2.x, p2.y, p2.z, cx, cy)

                // Fade connection lines by depth
                val avgDepth = (d1 + d2) / 2.0
                val alpha = ((1.0 - (avgDepth + 80.0) / 200.0) * 60).toInt().coerceIn(10, 60)
                g2.color = Color(100, 150, 200, alpha)
                g2.drawLine(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
            }
        }
    }

    /** Draw a single planet as a dot with label */
    private fun drawPlanet(
        g2: Graphics2D,
        planet: Planet,
        screenX: Double,
        screenY: Double,
        depth: Double,
    ) {
        // Size and brightness based on depth (closer = bigger, brighter)
        val depthFactor = ((1.0 - (depth + 80.0) / 200.0)).coerceIn(0.2, 1.0)
        val radius = (4.0 + depthFactor * 7.0).toInt()

        // Color: SOL is yellow, others vary by production profile
        val planetColor = planetColor(planet, depthFactor)
        val alpha = (depthFactor * 255).toInt().coerceIn(40, 255)

        // Glow effect
        val glowRadius = radius + 4
        g2.color = Color(planetColor.red, planetColor.green, planetColor.blue, alpha / 3)
        g2.fillOval(
            (screenX - glowRadius).toInt(),
            (screenY - glowRadius).toInt(),
            glowRadius * 2,
            glowRadius * 2,
        )

        // Planet dot
        g2.color = Color(planetColor.red, planetColor.green, planetColor.blue, alpha)
        g2.fillOval((screenX - radius).toInt(), (screenY - radius).toInt(), radius * 2, radius * 2)

        // Label
        g2.font = if (depthFactor > 0.5) labelFont else smallFont
        val labelAlpha = (depthFactor * 220).toInt().coerceIn(30, 220)
        g2.color = Color(220, 220, 240, labelAlpha)
        g2.drawString(planet.name, (screenX + radius + 3).toInt(), (screenY + 4).toInt())
    }

    /** Assign a color to a planet based on its primary production */
    private fun planetColor(planet: Planet, brightness: Double): Color {
        if (planet.name == "SOL") return Color(255, 255, 100)

        // Color based on the dominant production tier
        val topProduction = planet.production.maxByOrNull { it.value }
        val tier = topProduction?.key?.tier ?: 1
        val b = (brightness * 255).toInt().coerceIn(60, 255)

        return when (tier) {
            1 -> Color(b, b / 2, b / 4) // Raw materials: warm orange
            2 -> Color(b / 3, b, b / 3) // Refined: green
            3 -> Color(b / 3, b / 2, b) // Advanced: blue
            4 -> Color(b, b / 3, b) // Consumer: purple
            else -> Color(b, b, b)
        }
    }

    private fun drawHud(g2: Graphics2D, w: Int, h: Int) {
        g2.font = smallFont
        g2.color = Color(120, 120, 160)
        val instructions = "Drag to rotate | Scroll to zoom | Double-click to toggle auto-rotate"
        g2.drawString(instructions, 10, h - 10)

        val rotateStatus = if (autoRotating) "Auto-rotating" else "Manual"
        g2.drawString(rotateStatus, w - 100, h - 10)
    }

    private fun distance3d(
        x1: Double,
        y1: Double,
        z1: Double,
        x2: Double,
        y2: Double,
        z2: Double,
    ): Double {
        val dx = x1 - x2
        val dy = y1 - y2
        val dz = z1 - z2
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
