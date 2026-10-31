import { afterEach, describe, expect, it } from "vitest"
import { appState, posixFilenameRules } from "$lib/code/stateObjects/appState.svelte"
import { childPath, displayPath, filenameOf, foldCase, isChildOf, isDirectChildOf, isDriveList, isOtherDrive, isRootPath, isSamePath, joinSegments, normalizePath, parentPath, pathSegments, stripSeparators } from "./path"

function useStyle(pathStyle: `posix` | `windows`) {
    appState.platform = { pathStyle, separator: pathStyle === `windows` ? `\\` : `/`, filenameRules: posixFilenameRules }
}

// Mirrors `PathPolicyWindowsTest` and `PathPolicyPosixTest` on the server
describe(`path`, () => {
    afterEach(() => useStyle(`posix`))

    describe(`with POSIX paths`, () => {
        it(`splits and joins`, () => {
            expect(pathSegments(`/a//b/`)).toEqual([`a`, `b`])
            expect(joinSegments([`a`, `b`])).toBe(`/a/b`)
            expect(joinSegments([])).toBe(`/`)
        })

        it(`keeps a backslash as part of the name`, () => {
            expect(pathSegments(`/a\\b`)).toEqual([`a\\b`])
            expect(displayPath(`/a\\b`)).toBe(`/a\\b`)
        })

        it(`treats C: as an ordinary name`, () => {
            expect(normalizePath(`/C:/a`)).toBe(`/C:/a`)
        })

        it(`finds parents and names`, () => {
            expect(parentPath(`/a/b`)).toBe(`/a`)
            expect(parentPath(`/a`)).toBe(`/`)
            expect(parentPath(`/`)).toBeNull()
            expect(filenameOf(`/a/b.txt`)).toBe(`b.txt`)
            expect(filenameOf(`/`)).toBe(``)
        })

        it(`has no other drives`, () => {
            expect(isOtherDrive(`/a`, `/b`)).toBe(false)
            expect(isOtherDrive(`C:/a`, `S:/a`)).toBe(false)
        })

        it(`compares case-sensitively`, () => {
            expect(isSamePath(`/a/B`, `/a/b`)).toBe(false)
            expect(isChildOf(`/A/b`, `/a`)).toBe(false)
        })

        it(`strips only the slash from a typed name`, () => {
            expect(stripSeparators(`a/b\\c`)).toBe(`ab\\c`)
        })

        // Child first, like isChildOf
        it.each([
            [`/a/b`, `/a`, true],
            [`/a/b/c`, `/a`, false],
            [`/ab`, `/a`, false],
            [`/a`, `/a/b`, false],
        ])(`finds out whether %s is directly inside %s`, (child, parent, expected) => {
            expect(isDirectChildOf(child, parent)).toBe(expected)
        })
    })

    describe(`with Windows paths`, () => {
        it(`the drive is the first segment`, () => {
            useStyle(`windows`)
            expect(pathSegments(`C:/Users/x`)).toEqual([`C:`, `Users`, `x`])
            expect(pathSegments(`C:\\Users\\x`)).toEqual([`C:`, `Users`, `x`])
            expect(joinSegments([`C:`])).toBe(`C:/`)
            expect(joinSegments([`c:`, `a`])).toBe(`C:/a`)
        })

        it.each([
            [`/C:/Users`, `C:/Users`],
            [`/C:`, `C:/`],
            [`/`, `/`],
        ])(`normalizes the page URL path %s to %s`, (pagePath, drivePath) => {
            useStyle(`windows`)
            expect(normalizePath(pagePath)).toBe(drivePath)
        })

        it(`keeps share paths without a drive under the root`, () => {
            useStyle(`windows`)
            expect(normalizePath(`/sub/file`)).toBe(`/sub/file`)
            expect(parentPath(`/sub`)).toBe(`/`)
        })

        it(`the parent of a drive is the drive list`, () => {
            useStyle(`windows`)
            expect(parentPath(`C:/Users`)).toBe(`C:/`)
            expect(parentPath(`C:/`)).toBe(`/`)
            expect(parentPath(`/`)).toBeNull()
            expect(isRootPath(`C:/`)).toBe(false)
            expect(isRootPath(`/`)).toBe(true)
        })

        it(`only the root is the drive list`, () => {
            useStyle(`windows`)
            expect(isDriveList(`/`)).toBe(true)
            expect(isDriveList(`C:/`)).toBe(false)
            useStyle(`posix`)
            expect(isDriveList(`/`)).toBe(false)
        })

        it(`a drive root is named after its drive`, () => {
            useStyle(`windows`)
            expect(filenameOf(`C:/`)).toBe(`C:`)
            expect(filenameOf(`C:/a.txt`)).toBe(`a.txt`)
        })

        it(`appends to a drive root and the drive list`, () => {
            useStyle(`windows`)
            expect(childPath(`C:/`, `a`)).toBe(`C:/a`)
            expect(childPath(`C:/a`, `b/c`)).toBe(`C:/a/b/c`)
            expect(childPath(`/`, `C:`)).toBe(`C:/`)
        })

        it(`compares segments without case`, () => {
            useStyle(`windows`)
            expect(isSamePath(`C:/Data`, `c:/data/`)).toBe(true)
            expect(isChildOf(`C:/Data/x`, `c:/data`)).toBe(true)
            expect(isChildOf(`C:/Data2`, `C:/Data`)).toBe(false)
        })

        it(`folds case like the server's path keys`, () => {
            useStyle(`windows`)
            // Same as `FolderUploadServiceNameTest` on the server
            expect(isSamePath(`C:/i.txt`, `C:/\u0131.txt`)).toBe(true)
            expect(isSamePath(`C:/stra\u00DFe`, `C:/stra\u1E9Ee`)).toBe(false)
            expect(isSamePath(`C:/k.txt`, `C:/\u212A.txt`)).toBe(false)
            expect(foldCase(`stra\u00DFe`)).toBe(`STRA\u00DFE`)
        })

        it(`tells a path on another drive from one in another case`, () => {
            useStyle(`windows`)
            expect(isOtherDrive(`C:/FmTest/names`, `S:/names`)).toBe(true)
            expect(isOtherDrive(`C:/FmTest/names`, `s:/names`)).toBe(true)
            expect(isOtherDrive(`C:/Data`, `c:/data`)).toBe(false)
            expect(isOtherDrive(`C:/Data`, `C:/Other`)).toBe(false)
            expect(isOtherDrive(`C:/Data`, `/`)).toBe(false)
            expect(isOtherDrive(`/`, `/`)).toBe(false)
        })

        it(`everything is inside the drive list`, () => {
            useStyle(`windows`)
            expect(isChildOf(`C:/`, `/`)).toBe(true)
            expect(isDirectChildOf(`C:/`, `/`)).toBe(true)
            expect(isDirectChildOf(`C:/a`, `/`)).toBe(false)
        })

        it(`strips both separators from a typed name`, () => {
            useStyle(`windows`)
            expect(stripSeparators(`a/b\\c`)).toBe(`abc`)
        })

        it.each([
            [`C:/Users/x`, `C:\\Users\\x`],
            [`C:/`, `C:\\`],
            [`/`, `/`],
        ])(`shows %s with native backslashes`, (path, shown) => {
            useStyle(`windows`)
            expect(displayPath(path)).toBe(shown)
        })
    })
})
