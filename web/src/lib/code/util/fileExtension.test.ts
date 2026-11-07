import { describe, expect, it } from "vitest"
import { getFileExtension } from "./codeUtil.svelte"
import { getFileCategoryFromFilename } from "$lib/code/data/files"

describe(`getFileExtension`, () => {
    it(`returns the part after the last dot`, () => {
        expect(getFileExtension(`report.pdf`)).toBe(`pdf`)
        expect(getFileExtension(`archive.tar.gz`)).toBe(`gz`)
        expect(getFileExtension(`.bashrc`)).toBe(`bashrc`)
    })

    it(`returns nothing for a name without a dot`, () => {
        expect(getFileExtension(`pdf`)).toBe(``)
        expect(getFileExtension(`Makefile`)).toBe(``)
    })

    it(`a file named like an extension gets no viewer`, () => {
        expect(getFileCategoryFromFilename(`pdf`)).toBeNull()
        expect(getFileCategoryFromFilename(`mp4`)).toBeNull()
        expect(getFileCategoryFromFilename(`movie.mp4`)).toBe(`video`)
    })
})
