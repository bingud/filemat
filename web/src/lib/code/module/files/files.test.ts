import { afterEach, describe, expect, it, vi } from "vitest"

const toastMock = vi.hoisted(() => ({
    error: vi.fn(),
    loading: vi.fn(),
    remove: vi.fn(),
    plain: vi.fn(),
    success: vi.fn(),
}))
vi.mock(`@jill64/svelte-toast`, () => ({ toast: toastMock }))
vi.mock(`$app/navigation`, () => ({ goto: vi.fn() }))

import { deleteFiles, moveMultipleFiles } from "./files"
import type { FileMetadata } from "$lib/code/auth/types"

function networkDown() {
    vi.stubGlobal(`fetch`, vi.fn(async () => { throw new TypeError(`Failed to fetch`) }))
}

function meta(path: string): FileMetadata {
    return { path, modifiedDate: 0, createdDate: 0, fileType: `FILE`, size: 1 } as FileMetadata
}

describe(`file operations on a network error`, () => {
    afterEach(() => {
        vi.unstubAllGlobals()
        toastMock.error.mockClear()
    })

    it(`deleteFiles names the files, not [object Object]`, async () => {
        networkDown()

        await deleteFiles([meta(`/a/one.txt`), meta(`/a/two.txt`)])

        const message = toastMock.error.mock.calls.map((call) => String(call[0])).join(`\n`)
        expect(message).toContain(`/a/one.txt`)
        expect(message).toContain(`/a/two.txt`)
        expect(message).not.toContain(`[object Object]`)
    })

    it(`moveMultipleFiles shows an error instead of throwing`, async () => {
        networkDown()

        await expect(moveMultipleFiles(`/b`, [`/a/one.txt`])).resolves.toBeUndefined()
        expect(toastMock.error).toHaveBeenCalledWith(`Failed to move files.`)
    })
})
