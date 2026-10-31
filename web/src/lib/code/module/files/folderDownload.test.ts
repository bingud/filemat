import { afterEach, describe, expect, it, vi } from "vitest"
import { toLocalFilename, uniqueLocalFilename } from "./folderDownload"

function clientPlatform(platform: string, userAgentDataPlatform?: string) {
    vi.stubGlobal(`navigator`, {
        platform,
        userAgentData: userAgentDataPlatform === undefined ? undefined : { platform: userAgentDataPlatform },
    })
}

describe(`toLocalFilename`, () => {
    afterEach(() => vi.unstubAllGlobals())

    describe(`on a Windows client`, () => {
        it(`replaces characters Windows does not allow`, () => {
            clientPlatform(`Win32`)
            expect(toLocalFilename(`a:b`)).toBe(`a_b`)
            expect(toLocalFilename(`<>:"\\|?*`)).toBe(`________`)
            expect(toLocalFilename(`a\u0001b`)).toBe(`a_b`)
        })

        it(`replaces trailing dots and spaces`, () => {
            clientPlatform(`Win32`)
            expect(toLocalFilename(`name.`)).toBe(`name_`)
            expect(toLocalFilename(`name. .`)).toBe(`name___`)
            expect(toLocalFilename(`.hidden`)).toBe(`.hidden`)
        })

        it(`prefixes reserved device names`, () => {
            clientPlatform(`Win32`)
            expect(toLocalFilename(`CON`)).toBe(`_CON`)
            expect(toLocalFilename(`con.txt`)).toBe(`_con.txt`)
            expect(toLocalFilename(`COM1`)).toBe(`_COM1`)
            expect(toLocalFilename(`LPT¹`)).toBe(`_LPT¹`)
            expect(toLocalFilename(`CONIN$`)).toBe(`_CONIN$`)
            expect(toLocalFilename(`conout$.log`)).toBe(`_conout$.log`)
            expect(toLocalFilename(`NUL .txt`)).toBe(`_NUL .txt`)
            expect(toLocalFilename(`CONSOLE`)).toBe(`CONSOLE`)
            expect(toLocalFilename(`COM10`)).toBe(`COM10`)
        })

        it(`never returns an empty name`, () => {
            clientPlatform(`Win32`)
            expect(toLocalFilename(``)).toBe(`_`)
        })

        it(`prefers User-Agent Client Hints`, () => {
            clientPlatform(`Linux x86_64`, `Windows`)
            expect(toLocalFilename(`a:b`)).toBe(`a_b`)
        })
    })

    describe(`on other clients`, () => {
        it(`keeps the name as it is`, () => {
            clientPlatform(`Linux x86_64`)
            expect(toLocalFilename(`a:b.`)).toBe(`a:b.`)
            expect(toLocalFilename(`CON`)).toBe(`CON`)
        })

        it(`does not treat macOS as Windows`, () => {
            clientPlatform(`MacIntel`, `macOS`)
            expect(toLocalFilename(`a:b`)).toBe(`a:b`)
        })
    })
})

describe(`uniqueLocalFilename`, () => {
    afterEach(() => vi.unstubAllGlobals())

    it(`renames a second entry that cleans up to the same local name`, () => {
        clientPlatform(`Win32`)
        const taken = new Map<string, string[]>()
        expect(uniqueLocalFilename(`folder`, toLocalFilename(`a:b.txt`), taken)).toBe(`a_b.txt`)
        expect(uniqueLocalFilename(`folder`, toLocalFilename(`a?b.txt`), taken)).toBe(`a_b (1).txt`)
        expect(uniqueLocalFilename(`other`, toLocalFilename(`a?b.txt`), taken)).toBe(`a_b.txt`)
    })

    it(`ignores case on a Windows client`, () => {
        clientPlatform(`Win32`)
        const taken = new Map<string, string[]>()
        expect(uniqueLocalFilename(``, `A.txt`, taken)).toBe(`A.txt`)
        expect(uniqueLocalFilename(``, `a.txt`, taken)).toBe(`a (1).txt`)
    })

    it(`keeps names that differ in case on other clients`, () => {
        clientPlatform(`Linux x86_64`)
        const taken = new Map<string, string[]>()
        expect(uniqueLocalFilename(``, `A.txt`, taken)).toBe(`A.txt`)
        expect(uniqueLocalFilename(``, `a.txt`, taken)).toBe(`a.txt`)
    })
})
