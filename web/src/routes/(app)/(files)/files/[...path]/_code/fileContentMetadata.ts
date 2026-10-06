import type { FileContentMetadata, FullFileMetadata } from "$lib/code/auth/types"
import { isMediaContentFile } from "$lib/code/data/files"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"
import { formData, safeFetch } from "$lib/code/util/codeUtil.svelte"

// Paths per request. Visible files past this are fetched in the following requests.
const REQUEST_SIZE = 100
// Paths whose content metadata is currently being fetched.
const inFlight = new Set<string>()

/** Set contentMeta on listing, search, and open-file objects for this path. `undefined` means it still needs a fetch. */
export function applyContentMeta(path: string, meta: FileContentMetadata | undefined) {
    const listing = filesState.data.entryMap.get(path)
    if (listing) listing.contentMeta = meta

    const searchEntry = filesState.search.entries?.find(entry => entry.path === path)
    if (searchEntry) searchEntry.contentMeta = meta

    if (filesState.data.fileMeta?.path === path) {
        filesState.data.fileMeta.contentMeta = meta
    }
}

/** contentMeta already fetched for this path on a listing or search entry. */
export function existingContentMeta(path: string): FileContentMetadata | undefined {
    return filesState.data.entryMap.get(path)?.contentMeta
        ?? filesState.search.entries?.find(entry => entry.path === path)?.contentMeta
}

/** Visible media files that don't have contentMeta yet. */
function missingMediaEntries(
    entries: FullFileMetadata[],
    visiblePaths: ReadonlySet<string>,
): FullFileMetadata[] {
    const missing: FullFileMetadata[] = []
    for (const entry of entries) {
        if (!visiblePaths.has(entry.path)) continue
        if (entry.contentMeta !== undefined || inFlight.has(entry.path)) continue
        if (!isMediaContentFile(entry.filename ?? entry.path)) continue
        missing.push(entry)
    }
    return missing
}

/** Fetch contentMeta for visible image/video/audio files. */
export async function loadVisibleContentMeta(
    entries: FullFileMetadata[] | null | undefined,
    visiblePaths: ReadonlySet<string>,
    signal: AbortSignal,
): Promise<void> {
    if (!entries) return

    const missing = missingMediaEntries(entries, visiblePaths)
    for (let i = 0; i < missing.length; i += REQUEST_SIZE) {
        if (signal.aborted) return
        await fetchContentMetaChunk(missing.slice(i, i + REQUEST_SIZE), signal)
    }
}

async function fetchContentMetaChunk(entries: FullFileMetadata[], signal: AbortSignal): Promise<void> {
    const paths = entries.map(entry => entry.path)
    for (const path of paths) inFlight.add(path)

    try {
        const response = await safeFetch(`/api/v1/file/content-metadata-batch`, {
            body: formData({
                paths: JSON.stringify(paths),
                shareToken: filesState.getShareToken(),
            }),
            signal,
        })

        if (response.failed) return
        if (response.code.failed) return

        const json = response.json() as Record<string, FileContentMetadata> | null
        if (!json) return

        for (const entry of entries) {
            // Missing key: server skipped this file, so leave it unset and try again later.
            applyContentMeta(entry.path, json[entry.path])
        }
    } finally {
        for (const path of paths) inFlight.delete(path)
    }
}

/** Fetch contentMeta for one file. */
export async function loadContentMetadata(
    path: string,
    signal: AbortSignal,
): Promise<FileContentMetadata | null> {
    inFlight.add(path)
    try {
        const params = new URLSearchParams()
        params.set(`path`, path)
        const shareToken = filesState.getShareToken()
        if (shareToken) params.set(`shareToken`, shareToken)

        const response = await safeFetch(`/api/v1/file/content-metadata?${params}`, {
            method: `GET`,
            signal,
        })
        if (response.failed) {
            if (response.exception?.name === `AbortError`) return null
            return null
        }
        if (response.code.failed) return null

        const json = response.json() as FileContentMetadata | null
        if (json?.width == null && json?.height == null && json?.durationMs == null) {
            return {}
        }
        return json
    } finally {
        inFlight.delete(path)
    }
}
