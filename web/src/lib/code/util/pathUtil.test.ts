import { describe, expect, it } from "vitest"
import { getUniqueFilename, isChildOf, parentFromPath, resolvePath } from "./codeUtil.svelte"

// `deleteFiles` uses these to find the closest parent that still exists, so prefixes must match whole segments
describe(`isChildOf`, () => {
    it(`is true for paths below the parent`, () => {
        expect(isChildOf(`/a/b`, `/a`)).toBe(true)
        expect(isChildOf(`/a/b/c`, `/a`)).toBe(true)
    })

    it(`is false for the parent itself`, () => {
        expect(isChildOf(`/a`, `/a`)).toBe(false)
    })

    it(`is false for a sibling with the same prefix`, () => {
        expect(isChildOf(`/ab`, `/a`)).toBe(false)
        expect(isChildOf(`/a_b/c`, `/a`)).toBe(false)
    })

    it(`treats the root as the parent of everything`, () => {
        expect(isChildOf(`/a`, `/`)).toBe(true)
    })
})

describe(`parentFromPath`, () => {
    it(`removes the last segment`, () => {
        expect(parentFromPath(`/a/b`)).toBe(`/a`)
        expect(parentFromPath(`/a/b/`)).toBe(`/a`)
    })

    it(`gives an empty string for a top-level path`, () => {
        expect(parentFromPath(`/a`)).toBe(``)
    })
})

describe(`resolvePath`, () => {
    it(`joins with exactly one slash`, () => {
        expect(resolvePath(`/a/`, `/b`)).toBe(`/a/b`)
        expect(resolvePath(`/a`, `b`)).toBe(`/a/b`)
    })

    it(`does not double the slash at the root`, () => {
        expect(resolvePath(`/`, `name`)).toBe(`/name`)
    })
})

describe(`getUniqueFilename`, () => {
    it(`numbers a name that is taken`, () => {
        expect(getUniqueFilename(`a.txt`, [`b.txt`], false)).toBe(`a.txt`)
        expect(getUniqueFilename(`a.txt`, [`a.txt`, `a (1).txt`], false)).toBe(`a (2).txt`)
    })

    it(`ignores case only when asked, as in a Windows folder`, () => {
        expect(getUniqueFilename(`photo.jpg`, [`Photo.jpg`], false)).toBe(`photo.jpg`)
        expect(getUniqueFilename(`photo.jpg`, [`Photo.jpg`], true)).toBe(`photo (1).jpg`)
    })
})
