import { afterEach, describe, expect, it, vi } from "vitest"
import { appState, posixFilenameRules, type AppPlatform } from "$lib/code/stateObjects/appState.svelte"
import { fetchAppState } from "./stateFetcher"

const windowsPlatform: AppPlatform = {
    pathStyle: `windows`,
    separator: `\\`,
    filenameRules: { ...posixFilenameRules, invalidChars: `<>:"/\\|?*` },
}

function respondWith(body: unknown, status: number = 200) {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(body), { status }))
    vi.stubGlobal(`fetch`, fetchMock)
    return fetchMock
}

describe(`fetchAppState`, () => {
    afterEach(() => {
        vi.unstubAllGlobals()
        appState.platform = { pathStyle: `posix`, separator: `/`, filenameRules: posixFilenameRules }
        appState.paths = null
    })

    it(`asks only for the app state`, async () => {
        const fetchMock = respondWith({ app: { value: { isSetup: false, followSymlinks: false }, status: 200 } })

        await fetchAppState()

        const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
        expect(url).toBe(`/api/v1/state/select`)
        expect((init.body as FormData).get(`app`)).toBe(`true`)
    })

    it(`applies the platform and paths`, async () => {
        const paths = {
            setupCodeFile: `C:\\Filemat\\setup-code.txt`,
            authCodeFile: `C:\\Filemat\\auth-code.txt`,
            defaultUploadFolder: `C:\\Filemat\\uploads`,
            defaultThumbnailCacheFolder: `C:\\Filemat\\cache\\thumbnails`,
        }
        respondWith({ app: { value: { isSetup: false, followSymlinks: true, platform: windowsPlatform, paths }, status: 200 } })

        expect(await fetchAppState()).toBe(true)

        expect(appState.isSetup).toBe(false)
        expect(appState.followSymlinks).toBe(true)
        expect(appState.platform).toEqual(windowsPlatform)
        expect(appState.paths).toEqual(paths)
    })

    it(`keeps the current platform when the server sends none, and clears the paths`, async () => {
        appState.platform = windowsPlatform
        respondWith({ app: { value: { isSetup: true, followSymlinks: false }, status: 200 } })

        expect(await fetchAppState()).toBe(true)

        expect(appState.platform).toEqual(windowsPlatform)
        expect(appState.paths).toBeNull()
    })

    it(`fails without changing anything on an error status`, async () => {
        respondWith({ app: { value: { isSetup: false, followSymlinks: false, platform: windowsPlatform }, status: 500 } })

        expect(await fetchAppState()).toBe(false)
        expect(appState.platform.pathStyle).toBe(`posix`)
    })

    it(`fails when the request fails`, async () => {
        vi.stubGlobal(`fetch`, vi.fn(async () => { throw new Error(`offline`) }))

        expect(await fetchAppState()).toBe(false)
    })
})
