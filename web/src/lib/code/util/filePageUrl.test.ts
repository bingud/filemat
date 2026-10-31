import { describe, expect, it } from "vitest"
import { encodeUrlPathSegment, filePageUrl } from "./codeUtil.svelte"

describe(`encodeUrlPathSegment`, () => {
    it(`encodes characters that would break a URL`, () => {
        expect(encodeUrlPathSegment(`a#b`)).toBe(`a%23b`)
        expect(encodeUrlPathSegment(`a?b`)).toBe(`a%3Fb`)
        expect(encodeUrlPathSegment(`50%`)).toBe(`50%25`)
        expect(encodeUrlPathSegment(`a&b+c d`)).toBe(`a%26b%2Bc%20d`)
    })

    it(`keeps a colon readable`, () => {
        expect(encodeUrlPathSegment(`C:`)).toBe(`C:`)
        expect(encodeUrlPathSegment(`a:b`)).toBe(`a:b`)
    })

    it(`round-trips through decodeURIComponent`, () => {
        const names = [`a#b`, `a?b`, `50%`, `a&b`, `a+b`, `a b`, `ä€😀`, `C:`]
        for (const name of names) {
            expect(decodeURIComponent(encodeUrlPathSegment(name))).toBe(name)
        }
    })
})

describe(`filePageUrl`, () => {
    it(`encodes every segment and keeps the separators`, () => {
        expect(filePageUrl(`/a#b/c?d`)).toBe(`/files/a%23b/c%3Fd`)
    })

    it(`returns the page path for the root`, () => {
        expect(filePageUrl(`/`)).toBe(`/files`)
        expect(filePageUrl(``)).toBe(`/files`)
    })

    it(`drops empty segments`, () => {
        expect(filePageUrl(`//a///b/`)).toBe(`/files/a/b`)
    })

    it(`uses the given page path`, () => {
        expect(filePageUrl(`/a b`, `/share/token`)).toBe(`/share/token/a%20b`)
    })

    it(`keeps a Windows drive readable`, () => {
        expect(filePageUrl(`/C:/Users/me`)).toBe(`/files/C:/Users/me`)
    })
})
