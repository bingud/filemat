import type { EditorView } from "codemirror"


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
}

export const textFileViewerState = new TextFileViewerState()