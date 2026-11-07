import { afterEach, describe, expect, it, vi } from "vitest"
import type { FullFileMetadata } from "$lib/code/auth/types"

const deleteFilesMock = vi.hoisted(() => vi.fn())
vi.mock(`$lib/code/module/files/files`, () => ({
    deleteFiles: deleteFilesMock,
    deleteWarning: () => ``,
}))
vi.mock(`$app/navigation`, () => ({ goto: vi.fn() }))

// A plain stand-in for the files page state. The real one is created by the page, and its `$derived` values don't update in unit tests.
const fakeFilesState = vi.hoisted(() => ({
    isSearchOpen: false,
    search: { entries: null as unknown[] | null },
    selectedEntries: { metaList: [] as unknown[], searchList: [] as string[] },
}))
vi.mock(`$lib/code/stateObjects/filesState.svelte`, () => ({ filesState: fakeFilesState }))

let confirmMessage = ``
vi.mock(`$lib/code/stateObjects/subState/utilStates.svelte`, () => ({
    confirmDialogState: {
        show: async (options: { message: string }) => {
            confirmMessage = options.message
            return true
        },
    },
    downloadChooserState: {},
    folderSelectorState: {},
}))

import { option_deleteSelectedFiles } from "./fileActions"

function meta(path: string): FullFileMetadata {
    return { path, modifiedDate: 0, createdDate: 0, fileType: `FILE`, size: 1 } as FullFileMetadata
}

describe(`option_deleteSelectedFiles`, () => {
    afterEach(() => {
        deleteFilesMock.mockClear()
        confirmMessage = ``
        fakeFilesState.isSearchOpen = false
        fakeFilesState.search.entries = null
        fakeFilesState.selectedEntries.metaList = []
        fakeFilesState.selectedEntries.searchList = []
    })

    it(`in search results, deletes the selected results, not the folder's selection`, async () => {
        const folderFile = meta(`/folder/report.pdf`)
        const result = meta(`/elsewhere/deep/result.txt`)
        fakeFilesState.selectedEntries.metaList = [folderFile]
        fakeFilesState.isSearchOpen = true
        fakeFilesState.search.entries = [meta(`/elsewhere/other.txt`), result]
        fakeFilesState.selectedEntries.searchList = [result.path]

        option_deleteSelectedFiles()
        await vi.waitFor(() => expect(deleteFilesMock).toHaveBeenCalled())

        expect(deleteFilesMock).toHaveBeenCalledWith([result])
        expect(confirmMessage).toContain(`delete 1 selected file?`)
    })

    it(`in the folder view, deletes the folder's selection`, async () => {
        const folderFile = meta(`/folder/report.pdf`)
        fakeFilesState.selectedEntries.metaList = [folderFile]

        option_deleteSelectedFiles()
        await vi.waitFor(() => expect(deleteFilesMock).toHaveBeenCalled())

        expect(deleteFilesMock).toHaveBeenCalledWith([folderFile])
    })

    it(`does nothing in search results when no result is selected`, async () => {
        fakeFilesState.selectedEntries.metaList = [meta(`/folder/report.pdf`)]
        fakeFilesState.isSearchOpen = true
        fakeFilesState.search.entries = [meta(`/elsewhere/result.txt`)]

        option_deleteSelectedFiles()
        await new Promise((resolve) => setTimeout(resolve, 10))

        expect(deleteFilesMock).not.toHaveBeenCalled()
        expect(confirmMessage).toBe(``)
    })
})
