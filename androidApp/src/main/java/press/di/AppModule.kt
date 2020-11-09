package press.di

import com.squareup.inject.assisted.dagger2.AssistedModule
import com.squareup.inject.inflation.InflationModule
import dagger.Module
import dagger.Provides
import me.saket.press.shared.SharedAppComponent
import me.saket.press.shared.localization.Strings
import me.saket.press.shared.sync.SyncCoordinator
import press.editor.EditorModule
import press.home.HomeModule
import press.sync.SyncModule
import press.theme.ThemeModule

@InflationModule
@AssistedModule
@Module(
  includes = [
    InflationInject_AppModule::class,
    AssistedInject_AppModule::class,
    ThemeModule::class,
    HomeModule::class,
    EditorModule::class,
    SyncModule::class
  ]
)
object AppModule {
  @Provides
  fun strings(): Strings = SharedAppComponent.strings()

  @Provides
  fun syncCoordinator(): SyncCoordinator = SharedAppComponent.syncCoordinator()
}
