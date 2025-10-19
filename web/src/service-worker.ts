/// <reference types="@sveltejs/kit" />
/// <reference no-default-lib="true"/>
/// <reference lib="esnext" />
/// <reference lib="webworker" />

import { build, files, version } from '$service-worker'

const sw = self as unknown as ServiceWorkerGlobalScope

// Cache names
const CACHE_NAME = `app-cache-${version}`
const THUMB_CACHE_NAME = `thumb-cache-${version}`

// Assets to cache immediately
const STATIC_ASSETS = [...build, ...files]

// max age for thumbnails in seconds
const THUMB_MAX_AGE_SECONDS = 60 /* seconds */ * 60 /* minutes */ * 24 /* hours */ * 2 /* days */

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

// Fetch event - network strategies
sw.addEventListener('fetch', (event) => {
    const { request } = event
    const url = new URL(request.url)
    
    if (request.method !== 'GET') return
    if (url.origin !== location.origin) return   

    // Cache file previews
    if (url.pathname.includes("/image-thumbnail") || url.pathname.includes("/video-preview")) {
        event.respondWith(cacheResponse(request, caches.open(THUMB_CACHE_NAME)))
        return
    }

    if (url.pathname.startsWith('/api/')) return
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