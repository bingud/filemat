import { afterEach, describe, expect, it } from "vitest"
import { appState, posixFilenameRules } from "$lib/code/stateObjects/appState.svelte"
import { withAccessDeniedHint } from "./accessDenied"

function useStyle(pathStyle: `posix` | `windows`) {
    appState.platform = { pathStyle, separator: pathStyle === `windows` ? `\\` : `/`, filenameRules: posixFilenameRules }
}

describe(`withAccessDeniedHint`, () => {
    afterEach(() => useStyle(`posix`))

    it.each([`Missing permission to delete file.`, `Missing permission to move file.`, `Missing permission to copy file.`, `Missing permission to replace file.`, `Missing permission to open this folder.`])(
        `adds the hint to "%s" on Windows`,
        (message) => {
            useStyle(`windows`)
            const result = withAccessDeniedHint(message)
            expect(result.startsWith(message)).toBe(true)
            expect(result).toContain(`icacls`)
            expect(result).toContain(`Controlled folder access`)
        },
    )

    it(`finds the message at the end of a longer one`, () => {
        useStyle(`windows`)
        expect(withAccessDeniedHint(`Failed to delete "a.txt": Missing permission to delete file.`)).toContain(`icacls`)
    })

    it(`leaves other messages alone`, () => {
        useStyle(`windows`)
        expect(withAccessDeniedHint(`File is in use.`)).toBe(`File is in use.`)
        expect(withAccessDeniedHint(`Missing permission to upload here.`)).toBe(`Missing permission to upload here.`)
        expect(withAccessDeniedHint(`Missing permission to change home folder.`)).toBe(`Missing permission to change home folder.`)
    })

    it(`leaves every message alone on Linux`, () => {
        useStyle(`posix`)
        expect(withAccessDeniedHint(`Missing permission to delete file.`)).toBe(`Missing permission to delete file.`)
    })
})
