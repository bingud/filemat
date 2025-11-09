import type { EditorView } from "codemirror"


class TextFileViewerState {
    isFileSavable = $state(false)
    textEditorContainer: HTMLElement | undefined = $state()
    textEditor: EditorView | undefined
    isFocused = $state(false)
    filePath: string | null = $state(null)

    destroyEditor() { 
        try { this?.textEditor?.destroy() } catch (e) { console.log(e) }
    }
    reset() {
        this.destroyEditor()
        this.isFileSavable = false
        this.filePath = null
    }
}

export const textFileViewerState = new TextFileViewerState()