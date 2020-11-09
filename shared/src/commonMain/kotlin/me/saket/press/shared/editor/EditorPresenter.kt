package me.saket.press.shared.editor

import com.badoo.reaktive.completable.Completable
import com.badoo.reaktive.completable.andThen
import com.badoo.reaktive.completable.completableOfEmpty
import com.badoo.reaktive.completable.subscribe
import com.badoo.reaktive.completable.subscribeOn
import com.badoo.reaktive.observable.Observable
import com.badoo.reaktive.observable.ObservableWrapper
import com.badoo.reaktive.observable.distinctUntilChanged
import com.badoo.reaktive.observable.filter
import com.badoo.reaktive.observable.flatMap
import com.badoo.reaktive.observable.flatMapCompletable
import com.badoo.reaktive.observable.map
import com.badoo.reaktive.observable.merge
import com.badoo.reaktive.observable.observableOfEmpty
import com.badoo.reaktive.observable.ofType
import com.badoo.reaktive.observable.refCount
import com.badoo.reaktive.observable.replay
import com.badoo.reaktive.observable.take
import com.badoo.reaktive.observable.takeUntil
import com.badoo.reaktive.observable.withLatestFrom
import com.badoo.reaktive.observable.wrap
import me.saket.press.data.shared.Note
import me.saket.press.shared.editor.EditorEvent.NoteTextChanged
import me.saket.press.shared.editor.EditorOpenMode.ExistingNote
import me.saket.press.shared.editor.EditorOpenMode.NewNote
import me.saket.press.shared.editor.EditorUiEffect.BlockedDueToSyncConflict
import me.saket.press.shared.editor.EditorUiEffect.UpdateNoteText
import me.saket.press.shared.home.HomePresenter
import me.saket.press.shared.localization.Strings
import me.saket.press.shared.note.NoteRepository
import me.saket.press.shared.rx.Schedulers
import me.saket.press.shared.rx.combineLatestWith
import me.saket.press.shared.rx.consumeOnNext
import me.saket.press.shared.rx.mapToOptional
import me.saket.press.shared.rx.mapToSome
import me.saket.press.shared.rx.observableInterval
import me.saket.press.shared.sync.SyncMergeConflicts
import me.saket.press.shared.ui.Navigator
import me.saket.press.shared.ui.Presenter
import me.saket.press.shared.util.Optional
import me.saket.press.shared.util.filterNone
import me.saket.wysiwyg.formatting.TextSelection

class EditorPresenter(
  val args: Args,
  private val noteRepository: NoteRepository,
  private val schedulers: Schedulers,
  private val strings: Strings,
  private val config: EditorConfig,
  private val syncConflicts: SyncMergeConflicts
) : Presenter<EditorEvent, EditorUiModel, EditorUiEffect>() {

  private val openMode = args.openMode
  private val noteStream = createOrFetchNote().replay(1).refCount()

  override fun defaultUiModel() =
    EditorUiModel(hintText = null)

  override fun uiModels(): ObservableWrapper<EditorUiModel> {
    val uiModels = viewEvents()
      .toggleHintText()
      .map { (hint) -> EditorUiModel(hintText = hint) }

    val autoSave = viewEvents().autoSaveContent()

    return merge(uiModels, autoSave, closeIfNoteGetsDeleted()).wrap()
  }

  override fun uiEffects(): ObservableWrapper<EditorUiEffect> {
    return merge(
      populateExistingNoteOnStart(),
      blockEditingOnSyncConflict()
    ).wrap()
  }

  private fun createOrFetchNote(): Observable<Note> {
    val newOrExistingId = when (openMode) {
      is NewNote -> openMode.placeholderId
      is ExistingNote -> openMode.noteId
    }

    val createIfNeeded = if (openMode is NewNote) {
      // This function can get called multiple times if it's re-subscribed.
      // Create a new note only if one doesn't exist already.
      noteRepository
        .note(newOrExistingId)
        .take(1)
        .filterNone()
        .flatMapCompletable {
          val content = openMode.preFilledNote.ifBlankOrNull { NEW_NOTE_PLACEHOLDER }
          noteRepository.create(newOrExistingId, content)
        }
    } else {
      // If the note gets deleted on another device (that is, deletedAt != null),
      // Press will continue updating the same note.
      completableOfEmpty()
    }

    return createIfNeeded
      .andThen(noteRepository.note(newOrExistingId))
      .mapToSome()
  }

  private fun populateExistingNoteOnStart(): Observable<EditorUiEffect> {
    return noteStream
      .take(1)
      .map {
        val isNewNote = it.content == NEW_NOTE_PLACEHOLDER
        UpdateNoteText(
          newText = it.content,
          newSelection = if (isNewNote) TextSelection.cursor(it.content.length) else null
        )
      }
  }

  private fun blockEditingOnSyncConflict(): Observable<EditorUiEffect> {
    return noteConflicts().map { BlockedDueToSyncConflict }
  }

  private fun noteConflicts(): Observable<Unit> {
    return noteStream
      .take(1)
      .flatMap { syncConflicts.isConflicted(it.id) }
      .filter { it }
      .map { Unit }
  }

  /**
   * Can happen if the note was deleted outside of the app (e.g., on another device).
   */
  private fun closeIfNoteGetsDeleted(): Observable<EditorUiModel> {
    return noteStream
      .filter { it.isPendingDeletion }
      .take(1)
      .consumeOnNext {
        args.navigator.goBack()
      }
  }

  private fun Observable<EditorEvent>.toggleHintText(): Observable<Optional<String>> {
    val randomHint = strings.editor.new_note_hints.shuffled().first()

    return ofType<NoteTextChanged>()
      .distinctUntilChanged()
      .mapToOptional { (text) ->
        when {
          text.trimEnd() == NEW_NOTE_PLACEHOLDER.trim() -> "# $randomHint"
          else -> null
        }
      }
  }

  private fun Observable<EditorEvent>.autoSaveContent(): Observable<EditorUiModel> {
    val textChanges = ofType<NoteTextChanged>().map { it.text }

    return noteStream
      .take(1)
      .flatMapCompletable { note ->
        observableInterval(config.autoSaveEvery, schedulers.computation)
          .withLatestFrom(textChanges) { _, text -> text }
          .distinctUntilChanged()
          .takeUntil(noteConflicts())
          .flatMapCompletable { text -> noteRepository.update(note.id, text) }
      }
      .andThen(observableOfEmpty())
  }

  fun saveEditorContentOnClose(content: String) {
    updateOrDeleteNote(content)
      .subscribeOn(schedulers.io)
      .subscribe()
  }

  private fun updateOrDeleteNote(content: String): Completable {
    val shouldDelete = openMode is NewNote
      && args.deleteBlankNewNoteOnExit
      && content.trim().let { it.isBlank() || it == NEW_NOTE_PLACEHOLDER.trim() }

    val noteId = when (openMode) {
      is NewNote -> openMode.placeholderId
      is ExistingNote -> openMode.noteId
    }

    // For reasons I don't understand, noteStream doesn't get re-subscribed
    // when this function is called after EditorView gets detached. Fetching
    // the note again here.
    return noteRepository.note(noteId)
      .mapToSome()
      .combineLatestWith(syncConflicts.isConflicted(noteId))
      .filter { (_, isConflicted) -> !isConflicted }
      .take(1)
      .flatMapCompletable { (note) ->
        val maybeDelete = when {
          shouldDelete -> noteRepository.markAsPendingDeletion(note.id)
          else -> completableOfEmpty()
        }
        noteRepository.update(note.id, content).andThen(maybeDelete)
      }
  }

  fun interface Factory {
    fun create(args: Args): EditorPresenter
  }

  data class Args(
    val openMode: EditorOpenMode,
    /** Should be kept in sync with [HomePresenter.Args.includeBlankNotes]. */
    val deleteBlankNewNoteOnExit: Boolean,
    val navigator: Navigator
  )

  companion object {
    const val NEW_NOTE_PLACEHOLDER = "# "
  }
}

private fun String?.ifBlankOrNull(default: () -> String): String {
  return this?.ifBlank(default) ?: default()
}
