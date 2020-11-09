package press.navigation

import android.content.Context
import android.view.View
import com.squareup.inject.inflation.ViewFactory
import me.saket.press.shared.editor.EditorScreenKey
import me.saket.press.shared.home.HomeScreenKey
import me.saket.press.shared.ui.ScreenKey
import press.editor.EditorView
import press.home.HomeView
import javax.inject.Inject

/**
 * Inflation Inject exposes view factories using dagger multi-bindings which means
 * it can be used for injecting views even when not _inflating_ them.
 */
class ViewFactories @Inject constructor(
  private val factories: Map<String, @JvmSuppressWildcards ViewFactory>
) {

  @Suppress("UNCHECKED_CAST")
  fun createView(context: Context, screen: ScreenKey): View {
    val actualScreen = if (screen is ExpandableScreenKey<*>) screen.screen else screen

    val name = when (actualScreen) {
      is HomeScreenKey -> HomeView::class
      is EditorScreenKey -> EditorView::class
      else -> error("Missing mapping for $actualScreen")
    }.qualifiedName

    val factory = factories[name] ?: error("No ViewFactory found for $name. Have factories for: ${factories.keys}")
    val view = factory.create(context, null)

    return if (screen is ExpandableScreenKey<*>) {
      screen.wrapInExpandablePage(view)
    } else {
      view
    }
  }
}
