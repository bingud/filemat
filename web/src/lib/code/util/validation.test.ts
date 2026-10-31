import { afterEach, describe, expect, it } from "vitest"
import { appState, posixFilenameRules, type FilenameRules } from "$lib/code/stateObjects/appState.svelte"
import { Validator } from "./validation"

const windowsLikeRules: FilenameRules = {
    invalidChars: `<>:"/\\|?*`,
    rejectControlChars: true,
    reservedNames: [`CON`, `NUL`, `COM1`],
    reservedNamesIgnoreExtension: true,
    rejectTrailingDotOrSpace: true,
    maxLength: 10,
    lengthUnit: `utf16Units`,
}

function useRules(rules: FilenameRules) {
    appState.platform = { ...appState.platform, filenameRules: rules }
}

// Mirrors the server's `FilenameRulesTest` (Windows-like rules) and the name checks of `PathPolicyPosixTest` (POSIX rules), so both sides give the same answers
describe(`Validator.filename`, () => {
    afterEach(() => useRules(posixFilenameRules))

    describe(`with the POSIX rules`, () => {
        it(`accepts names Windows would reject`, () => {
            for (const name of [`CON`, `trailing. `, `back\\slash`, `a:b`]) {
                expect(Validator.filename(name), name).toBeNull()
            }
        })

        it(`rejects empty, dot names, slash and NUL`, () => {
            expect(Validator.filename(``)).toBe(`Name is empty.`)
            expect(Validator.filename(`.`)).toBe(`This name is reserved.`)
            expect(Validator.filename(`..`)).toBe(`This name is reserved.`)
            expect(Validator.filename(`a/b`)).toBe(`Name contains invalid characters.`)
            expect(Validator.filename(`a\u0000b`)).toBe(`Name contains invalid characters.`)
        })

        it(`counts the length in UTF-8 bytes`, () => {
            expect(Validator.filename(`a`.repeat(255))).toBeNull()
            expect(Validator.filename(`a`.repeat(256))).toBe(`Name is too long.`)
            expect(Validator.filename(`ä`.repeat(128))).toBe(`Name is too long.`)
        })
    })

    describe(`with Windows-like rules`, () => {
        it(`rejects every invalid character and control characters`, () => {
            useRules(windowsLikeRules)
            for (const char of windowsLikeRules.invalidChars) {
                expect(Validator.filename(`a${char}b`), char).toBe(`Name contains invalid characters.`)
            }
            expect(Validator.filename(`a\tb`)).toBe(`Name contains invalid characters.`)
        })

        it(`matches reserved names in any case and with an extension`, () => {
            useRules(windowsLikeRules)
            for (const name of [`con`, `Nul`, `con.txt`, `COM1.a.b`, `CON .txt`]) {
                expect(Validator.filename(name), name).toBe(`This name is reserved.`)
            }
            for (const name of [`CONSOLE`, `xCON.txt`, `COM10`]) {
                expect(Validator.filename(name), name).toBeNull()
            }
        })

        it(`matches only the exact name when extensions are not ignored`, () => {
            useRules({ ...windowsLikeRules, reservedNamesIgnoreExtension: false })
            expect(Validator.filename(`CON`)).toBe(`This name is reserved.`)
            expect(Validator.filename(`con`)).toBeNull()
            expect(Validator.filename(`CON.txt`)).toBeNull()
        })

        it(`rejects a trailing dot or space`, () => {
            useRules(windowsLikeRules)
            expect(Validator.filename(`name.`)).toBe(`Name cannot end with a dot or space.`)
            expect(Validator.filename(`name `)).toBe(`Name cannot end with a dot or space.`)
            expect(Validator.filename(`.hidden`)).toBeNull()
        })

        it(`counts the length in UTF-16 units`, () => {
            useRules(windowsLikeRules)
            expect(Validator.filename(`ä`.repeat(10))).toBeNull()
            expect(Validator.filename(`a`.repeat(11))).toBe(`Name is too long.`)
            expect(Validator.filename(`😀`.repeat(5))).toBeNull()
            expect(Validator.filename(`😀`.repeat(5) + `a`)).toBe(`Name is too long.`)
        })
    })
})
