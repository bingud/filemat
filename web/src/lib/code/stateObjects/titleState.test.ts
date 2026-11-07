import { afterEach, describe, expect, it } from "vitest"
import { appState } from "./appState.svelte"

describe(`appState.title`, () => {
    afterEach(() => {
        appState.title.segments = []
    })

    it(`register returns a cleanup that removes the segment`, () => {
        const cleanup = appState.title.register(`Photos`)
        expect(appState.title.segments).toEqual([`Photos`])

        cleanup()

        expect(appState.title.segments).toEqual([])
    })

    it(`opening folders one after another keeps one segment`, () => {
        let cleanup = appState.title.register(`a`)
        for (const name of [`b`, `c`, `d`]) {
            cleanup()
            cleanup = appState.title.register(name)
        }

        expect(appState.title.segments).toEqual([`d`])
    })

    it(`a cleanup removes only its own segment when two have the same text`, () => {
        appState.title.register(`Settings`)
        const child = appState.title.register(`Settings`)

        child()

        expect(appState.title.segments).toEqual([`Settings`])
    })
})
