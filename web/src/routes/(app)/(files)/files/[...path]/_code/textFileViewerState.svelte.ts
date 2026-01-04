import { filesState } from "$lib/code/stateObjects/filesState.svelte"
import { uiState } from "$lib/code/stateObjects/uiState.svelte"
import { basicSetup, EditorView } from "codemirror"
import { ayuLight, barf } from "thememirror"


class TextFileViewerState {
    isFileSavable = $state(false)
    textEditorContainer: HTMLElement | undefined = $state()
    textEditor: EditorView | undefined
    isFocused = $state(false)
    filePath: string | null = $state(null)

    
    wasEdited = $state(false)

    destroyEditor() { 
        try { this?.textEditor?.destroy() } catch (e) { console.log(e) }
    }
    reset() {
        this.destroyEditor()
        this.isFileSavable = false
        this.filePath = null
        this.wasEdited = false
    }

    createTextEditor() {
        this.destroyEditor()

        if (filesState.data.decodedContent == null || !textFileViewerState.textEditorContainer) return

        this.filePath = filesState.data.fileMeta!.path
        this.isFileSavable = false

        const theme = uiState.isDark ? barf : ayuLight
        this.textEditor = new EditorView({
            doc: filesState.data.decodedContent,
            parent: this.textEditorContainer,
            extensions: [
                basicSetup, theme,
                EditorView.updateListener.of((update) => {
                    if (update.docChanged && filesState.currentFile.isEditable) {
                        this.isFileSavable = true
                    }
                })
            ]
        })
    }
}

export const textFileViewerState = new TextFileViewerState()