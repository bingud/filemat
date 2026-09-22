/// <reference types="@sveltejs/kit" />
/// <reference no-default-lib="true"/>
/// <reference lib="esnext" />
/// <reference lib="webworker" />

import { build, files, version } from '$service-worker'

const sw = self as unknown as ServiceWorkerGlobalScope

// Cache names
const CACHE_NAME = `app-cache-${version}`
const THUMB_CACHE_NAME = `thumb-cache`

// Assets to cache immediately
// const STATIC_ASSETS = [...build, ...files]

const THUMB_MAX_COUNT = 15_000
const THUMB_EVICT_TO_COUNT = 14_800
// Entries older than this are dropped when opened, even under the count cap.
const THUMB_MAX_AGE_MS = 1000 * 60 * 60 * 24 * (365 / 2)
// Wait until thumbnail fetches have gone quiet before counting or deleting.
const THUMB_TRIM_DELAY_MS = 2 * 60 * 1000
// How often the in-memory count is checked against cache.keys().
const THUMB_RESYNC_MS = 30 * 60 * 1000
// Oldest-entry deletion reads every cached thumbnail, so it stays infrequent.
const THUMB_EVICT_COOLDOWN_MS = 10 * 60 * 1000

const LAST_USER_ID_DB = 'filemat-sw'
const LAST_USER_ID_STORE = 'kv'
const LAST_USER_ID_KEY = 'lastUserId'

let contentBaseOrigin: string | null = null
let lastUserId: string | undefined

sw.addEventListener('message', (event) => {
    const data = event.data
    if (!data) return

    if (data.type === 'contentBaseUrl') {
        contentBaseOrigin = typeof data.origin === 'string' && data.origin ? data.origin : null
        return
    }

    if (data.type === 'lastUserId') {
        const userId = data.lastUserId == null ? '' : String(data.lastUserId)
        const task = syncLastUserId(userId)
        if (typeof event.waitUntil === 'function') event.waitUntil(task)
        else void task
    }
})

// Install event - cache static assets
sw.addEventListener('install', (event) => {
    console.log('[SW] Installing')
    event.waitUntil(sw.skipWaiting())
})

// Activate event - clean old caches
sw.addEventListener('activate', (event) => {
    console.log('[SW] Activating')
    
    event.waitUntil(
        caches.keys().then((keys) => {
            return Promise.all(
                keys
                    .filter((key) => key !== CACHE_NAME && key !== THUMB_CACHE_NAME)
                    .map((key) => caches.delete(key))
            )
        }).then(() => sw.clients.claim())
    )
    scheduleThumbTrim()
})

function isThumbRequest(url: URL): boolean {
    return url.pathname.includes("/image-thumbnail") || url.pathname.includes("/video-preview")
}

function isAllowedThumbOrigin(url: URL): boolean {
    if (url.origin === location.origin) return true
    return contentBaseOrigin != null && url.origin === contentBaseOrigin
}

// Fetch event - network strategies
sw.addEventListener('fetch', (event) => {
    const { request } = event
    const url = new URL(request.url)
    
    if (request.method !== 'GET') return

    if (isThumbRequest(url)) {
        if (!isAllowedThumbOrigin(url)) return
        event.respondWith(cacheResponse(request, caches.open(THUMB_CACHE_NAME), event))
        return
    }

    if (url.origin !== location.origin) return
    if (url.pathname.startsWith('/api/')) return

    if (url.pathname.startsWith("/__filemat-clear-sw-thumb-cache")) {
        event.waitUntil(caches.delete(THUMB_CACHE_NAME).then(() => {
            thumbCount = 0
            lastThumbSyncAt = Date.now()
        }))
        event.respondWith(new Response(null, { status: 200 }))
    }
})

let thumbCount: number | null = null
let lastThumbSyncAt = 0
let lastThumbEvictAt = 0
let trimTimer: number | null = null
let trimming = false

async function cacheResponse(request: Request, cachePromise: Cache | Promise<Cache>, event: FetchEvent): Promise<Response> {
    const cache = await cachePromise

    const cached = await cache.match(request)
    if (cached) {
        const cachedTime = Number(cached.headers.get('sw-cached-time'))
        if (cachedTime && Date.now() - cachedTime < THUMB_MAX_AGE_MS) {
            return responseForRequest(cached, request)
        }
        await cache.delete(request)
        if (thumbCount !== null && thumbCount > 0) thumbCount -= 1
    }

    try {
        const response = await fetch(request)
        if (response && response.ok) {
            const stored = response.clone()
            event.waitUntil(storeThumbnail(cache, request, stored))
        }
        return response
    } catch {
        return new Response(null, { status: 504 })
    }
}

/** Stores the image bytes only. CORS headers are added when the entry is served. */
async function storeThumbnail(cache: Cache, request: Request, response: Response) {
    try {
        const headers = new Headers()
        const type = response.headers.get('content-type')
        if (type) headers.set('content-type', type)
        headers.set('sw-cached-time', Date.now().toString())
        await cache.put(request, new Response(response.body, {
            status: response.status,
            headers,
        }))
        if (thumbCount !== null) thumbCount += 1
        scheduleThumbTrim()
    } catch (e) {
        console.error('[SW] thumbnail store failed', e)
    }
}

/** One delayed pass. Under the cap this does not list the cache. */
function scheduleThumbTrim() {
    const unknown = thumbCount === null
    const over = thumbCount !== null && thumbCount > THUMB_MAX_COUNT
    const due = Date.now() - lastThumbSyncAt >= THUMB_RESYNC_MS
    if (!unknown && !over && !due) return
    if (over && !unknown && Date.now() - lastThumbEvictAt < THUMB_EVICT_COOLDOWN_MS && !due) return
    if (trimTimer !== null) clearTimeout(trimTimer)
    trimTimer = setTimeout(() => {
        trimTimer = null
        void runThumbTrim()
    }, THUMB_TRIM_DELAY_MS) as unknown as number
}

/** Recounts keys. Opens entries only when deleting the oldest past the cap. */
async function runThumbTrim() {
    if (trimming) return
    trimming = true
    try {
        const cache = await caches.open(THUMB_CACHE_NAME)
        const keys = await cache.keys()
        lastThumbSyncAt = Date.now()
        thumbCount = keys.length
        if (keys.length <= THUMB_MAX_COUNT) return

        const ranked: { request: Request, time: number }[] = []
        for (let i = 0; i < keys.length; i++) {
            const cached = await cache.match(keys[i])
            ranked.push({
                request: keys[i],
                time: Number(cached?.headers.get('sw-cached-time')) || 0,
            })
            if (i % 100 === 99) await new Promise((resolve) => setTimeout(resolve, 0)) // let thumbnail fetches run
        }
        ranked.sort((a, b) => a.time - b.time)
        const extra = ranked.length - THUMB_EVICT_TO_COUNT
        for (let i = 0; i < extra; i++) {
            await cache.delete(ranked[i].request)
            if (i % 50 === 49) await new Promise((resolve) => setTimeout(resolve, 0))
        }
        thumbCount = THUMB_EVICT_TO_COUNT
        lastThumbEvictAt = Date.now()
    } catch (e) {
        console.error('[SW] thumbnail trim failed', e)
    } finally {
        trimming = false
    }
}

/** Cached bytes have no CORS headers, so this request's headers are added here. */
function responseForRequest(cached: Response, request: Request): Response {
    const headers = new Headers(cached.headers)
    if (request.mode !== 'cors') {
        return new Response(cached.body, { status: cached.status, headers })
    }
    headers.delete('access-control-allow-origin')
    headers.delete('access-control-allow-credentials')
    if (request.credentials === 'include') {
        headers.set('Access-Control-Allow-Origin', location.origin)
        headers.set('Access-Control-Allow-Credentials', 'true')
    } else {
        headers.set('Access-Control-Allow-Origin', '*')
    }
    return new Response(cached.body, { status: cached.status, headers })
}

function idbRequest<T>(request: IDBRequest<T>): Promise<T> {
    return new Promise((resolve, reject) => {
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
    })
}

function openLastUserIdDb(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open(LAST_USER_ID_DB, 1)
        request.onupgradeneeded = () => {
            if (!request.result.objectStoreNames.contains(LAST_USER_ID_STORE)) {
                request.result.createObjectStore(LAST_USER_ID_STORE)
            }
        }
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
    })
}

async function readLastUserId(): Promise<string | undefined> {
    const db = await openLastUserIdDb()
    try {
        const value = await idbRequest(
            db.transaction(LAST_USER_ID_STORE, 'readonly').objectStore(LAST_USER_ID_STORE).get(LAST_USER_ID_KEY)
        )
        return typeof value === 'string' ? value : undefined
    } finally {
        db.close()
    }
}

async function writeLastUserId(userId: string): Promise<void> {
    const db = await openLastUserIdDb()
    try {
        await new Promise<void>((resolve, reject) => {
            const tx = db.transaction(LAST_USER_ID_STORE, 'readwrite')
            tx.oncomplete = () => resolve()
            tx.onerror = () => reject(tx.error)
            tx.onabort = () => reject(tx.error ?? new Error('IndexedDB write aborted'))
            tx.objectStore(LAST_USER_ID_STORE).put(userId, LAST_USER_ID_KEY)
        })
    } finally {
        db.close()
    }
}

async function syncLastUserId(userId: string): Promise<void> {
    try {
        const previous = lastUserId !== undefined ? lastUserId : await readLastUserId().catch(() => undefined)
        if (previous !== undefined && previous !== userId) {
            await caches.delete(THUMB_CACHE_NAME)
            thumbCount = 0
            lastThumbSyncAt = Date.now()
        }
        lastUserId = userId
        await writeLastUserId(userId)
    } catch (e) {
        console.error('[SW] lastUserId write failed', e)
    }
}
