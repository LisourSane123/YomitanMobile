package com.yomitanmobile.kindle

import org.junit.Assume
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Paints the window into build/window.png without showing it, so its layout
 * can be looked at on a machine whose screen nobody is watching. Needs a
 * display to lay out fonts; skipped without one.
 */
class WindowScreenshotTest {

    @Test
    fun paintsTheWindow() {
        Assume.assumeFalse("no display", GraphicsEnvironment.isHeadless())
        com.formdev.flatlaf.FlatLightLaf.setup()
        for (language in listOf("pl", "en")) {
            polishUi = language == "pl"
            var image: BufferedImage? = null
            SwingUtilities.invokeAndWait {
                val window = Window(Config.load().copy(language = language))
                window.build(visible = false)
                val c = window.content
                c.setSize(680, 480)
                c.doLayout()
                fun layout(x: java.awt.Component) { if (x is java.awt.Container) { x.doLayout(); x.components.forEach(::layout) } }
                layout(c)
                image = BufferedImage(c.width, c.height, BufferedImage.TYPE_INT_RGB).also { c.paint(it.graphics) }
            }
            ImageIO.write(image, "png", File("build/window-$language.png"))
        }
    }
}
