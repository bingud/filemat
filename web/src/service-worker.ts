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

// max age for thumbnails in seconds
const THUMB_MAX_AGE_SECONDS = 60 /* seconds */ * 60 /* minutes */ * 24 /* hours */ * 2 /* days */

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
        event.respondWith(cacheResponse(request, caches.open(THUMB_CACHE_NAME)))
        return
    }

    if (url.origin !== location.origin) return
    if (url.pathname.startsWith('/api/')) return

    if (url.pathname.startsWith("/__filemat-clear-sw-thumb-cache")) {
        caches.delete(THUMB_CACHE_NAME)
        event.respondWith(new Response(null, { status: 200 }))
    }
})

async function cacheResponse(request: Request, cachePromise: Cache | Promise<Cache>): Promise<Response> {
    const cache = await cachePromise

    const cached = await cache.match(request)
    if (cached) {
        const cachedTime = cached.headers.get('sw-cached-time')
        if (cachedTime) {
            const age = (Date.now() - parseInt(cachedTime)) / 1000
            if (age < THUMB_MAX_AGE_SECONDS) {
                return cached
            }
            cache.delete(request)
        }
    }

    try {
        const response = await fetch(request)
        if (response && response.ok) {
            const cloned = response.clone()
            const headers = new Headers(cloned.headers)
            headers.set('sw-cached-time', Date.now().toString())
            
            const cachedResponse = new Response(cloned.body, {
                status: cloned.status,
                statusText: cloned.statusText,
                headers
            })
            
            cache.put(request, cachedResponse)
        }
        return response
    } catch {
        return new Response(null, { status: 504 })
    }
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
        }
        lastUserId = userId
        await writeLastUserId(userId)
    } catch (e) {
        console.error('[SW] lastUserId write failed', e)
    }
}
