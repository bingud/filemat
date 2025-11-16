import { toast } from "@jill64/svelte-toast"
import { untrack } from "svelte"
import type { FileMetadata, FileType } from "../auth/types"
import { appState } from "../stateObjects/appState.svelte"

type ObjectKey = string | number | symbol

/**
 * Returns whether HTTP status indicates that server is down.
 */
export function isServerDown(httpStatus: number): boolean {
    if (httpStatus > 500 && httpStatus < 530) return true
    return false
}

export function isFolder(meta: FileMetadata | null | undefined): boolean {
    if (!meta) return false
    return meta.fileType === "FOLDER" || (meta.fileType === "FOLDER_LINK" && appState.followSymlinks === true)
}

/**
 * Returns a template for a page title.
 */
export function pageTitle(text: string) { return `${text} — Filemat` }

/**
 * Logs an exception
 */
export function handleException(message: string, userMessage: string | null, exception: any) {
    console.log(`${message || "(No description message)"}\n(${userMessage || "(No notification message)"})\n${exception}`)

    if (userMessage) {
        toast.error(userMessage)
    }
}

/**
 * Prints an error to console and user
 */
export function handleErr({
    description,
    notification,
    isServerDown,
}: {
    description?: string,
    notification?: string,
    isServerDown?: boolean,
}) {
    console.log(`${description || "(No description)"}  \n${notification || "(No notification message)"}  \n${isServerDown ? "(Server is unavailable)" : ""}`)

    if (notification) {
        const downMessage = "Sever is unavailable."
        const containsDownMessage = notification.includes(downMessage)
        toast.error(`${notification} ${isServerDown && !containsDownMessage ? downMessage : ""}`)
    }
}

/**
 * Returns whether a string is blank
 */
export function isBlank(str: string | null | undefined): boolean {
    return !str || str.trim().length === 0;
}

/**
 * Removes spaces from input string
 */
export function removeSpaces(str: string): string {
    return str.replaceAll(" ", "")
}


type SafeFetchResult = Omit<Response, 'json'> & { failed: boolean, exception: any | null, code: httpStatus, content: string, json: () => any | null }

/**
 * Fetches without throwing an exception.
 * 
 * Default request is POST and credentials same-origin
 */
export async function safeFetch(url: string, args?: RequestInit, ignoreBody: boolean = false): Promise<SafeFetchResult> {
    try {
        let arg = args || {}
        if (!arg.credentials) arg.credentials = "same-origin"
        if (!arg.method) arg.method = "POST"
        
        const response = await fetch(url, arg) as any as SafeFetchResult
        response.failed = false
        response.exception = null
        response.code = toStatus(response.status)
        if (!ignoreBody) {
            response.content = await response.text()
            response.json = () => { return parseJson(response.content) }
        }

        return response
    } catch (e) { 
        return { failed: true, exception: e } as any as SafeFetchResult
    }
}

/**
 * Appends a filename to a directory path
 */
export function appendFilename(path: string, filename: string): string {
    // ensure directory path ends with a single slash
    const normalizedPath = path.endsWith('/')
        ? path
        : path + '/'

    // strip leading slash from filename if present
    const normalizedFilename = filename.startsWith('/')
        ? filename.substring(1)
        : filename

    return normalizedPath + normalizedFilename
}

/**
 * Returns parsed JSON or null.
 */
export function parseJson(j: string): any | null {
    try {
        return JSON.parse(j) ?? null
    } catch (e) {
        return null
    }
}

// add suffix if missing
export function addSuffix(str: string, suffix: string): string {
    return str.endsWith(suffix) ? str : str + suffix
}

/**
 * Returns a debounced input function
 */
export function debounceFunction<Args extends any[]>(
    action: (...args: Args) => void,
    delay: number,
    continuousCallDuration: number
): (...args: Args) => void {
    let timeoutId: ReturnType<typeof setTimeout> | null = null
    let continuousCallTimeoutId: ReturnType<typeof setTimeout> | null = null
    let lastCallTime: number | null = null

    return function(this: any, ...args: Args): void {
        const context = this
        const currentTime = Date.now()

        // start or restart the “continuous” timer if too much time has passed
        if (!lastCallTime || currentTime - lastCallTime > continuousCallDuration) {
            if (continuousCallTimeoutId) clearTimeout(continuousCallTimeoutId)
            lastCallTime = currentTime

            continuousCallTimeoutId = setTimeout(() => {
                action.apply(context, args)
                lastCallTime = null
            }, continuousCallDuration)
        }

        // always restart the main debounce timer
        if (timeoutId) clearTimeout(timeoutId)
        timeoutId = setTimeout(() => {
            action.apply(context, args)
            if (continuousCallTimeoutId) clearTimeout(continuousCallTimeoutId)
            lastCallTime = null
        }, delay)
    }
}



/**
 * Returns values of an object
 */
export function valuesOf<T>(obj: {[key: string]: T}): T[] {
    return Object.values(obj)
}

/**
 * Returns keys of an object
 */
export function keysOf<K extends string | number | symbol, V>(obj: Record<K, V>): K[] {
    return Object.keys(obj) as K[]
}


/**
 * Formats unix timestamp into text
 * 
 * 2000-12-31 23:59
 */
export function formatUnixTimestamp(unixTimestamp: number) {
    const date = new Date(unixTimestamp * 1000);
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    
    return `${year}-${month}-${day} ${hours}:${minutes}`;
}

export function formatUnixMillis(millis: number) {
    return formatUnixTimestamp(millis / 1000)
}


/**
 * Creates FormData object
 */
export function formData(obj: { [key: string]: any }): FormData {
    const data = new FormData()
    Object.keys(obj).forEach((v: string) => {
        data.append(v, obj[v])
    })
    return data
}


type httpStatus = (
    { ok: true, serverDown: false, failed: false, notFound: false } 
    | { ok: false, serverDown: true, failed: true, notFound: false } 
    | { ok: false, serverDown: false, failed: true, notFound: false }
    | { ok: false, serverDown: false, failed: true, notFound: true }
) & { raw: number } 

/**
 * Parses HTTP status code
 */
export function toStatus(s: number): httpStatus {
    let result: httpStatus
    if (s === 200){
        result = { ok: true, serverDown: false, failed: false, notFound: false, raw: s }
    } else if (isServerDown(s)) {
        result = { ok: false, serverDown: true, failed: true, notFound: false, raw: s }
    } else if (s === 404) {
        
        result = { ok: false, serverDown: false, failed: true, notFound: true, raw: s }
    } else {
        result = { ok: false, serverDown: false, failed: true, notFound: false, raw: s }
    }

    result.toString = () => { return `${s}` }
    return result
}

/**
 * Checks if a list contains items of another list
 */
export function includesList<T>(list: T[], includedItems: T[]): boolean {
    return includedItems.every(i => list.includes(i));
}


/**
 * Locks an input function, so that it can only run once at a time
 */
export function lockFunction<T, Args extends any[]>(block: (...args: Args) => T | Promise<T>): (...args: Args) => Promise<T | void> {
    let running = false

    return async (...args: Args) => { 
        if (running) return
        running = true
        
        try {
            return await block(...args)
        } finally {
            running = false
        }
    }
}

export const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));


/**
 * Removes a string element from a string array
 */
export function removeString(arr: string[], str: string) {
    const index = arr.indexOf(str)
    if (index !== -1) arr.splice(index, 1)
}

/**
 * Removes an element from an array
 */
export function arrayRemove<T>(arr: T[], predicate: (value: T) => boolean) {
    const index = arr.findIndex(predicate)
    if (index !== -1) arr.splice(index, 1)
}

export function prependIfMissing(str: string, prefix: string) {
    return str.startsWith(prefix) ? str : prefix + str;
}  

/**
 * Iterates through an object
 */
export function forEachObject<K extends string | number | symbol, V>(obj: Record<K, V>, block: (key: K, value: V) => any): void {
    Object.entries(obj).forEach(([key, value]) => { block(key as K, value as V) })
}

/**
 * Filters an object with a predicate
 */
export function filterObject<K extends string | number | symbol, V>(obj: Record<K, V>, predicate: (key: K, value: V) => boolean): Record<K, V> {
    const newObj = {} as Record<K, V>

    forEachObject(obj, ((k, v) => {
        if (predicate(k, v)) {
            newObj[k] = v
        }
    }))

    return newObj
}

export function letterS(count: number): "s" | "" {
    if (count === 1) return ""
    return "s"
}

/**
 * Resolve a path from a parent and filename
 */
export function resolvePath(inputFolder: string, inputFilename: string): string {
    const folder = inputFolder.replace(/\/+$/, '')
    const file = inputFilename.replace(/^\/+/, '')
    return `${folder}/${file}`
}

/**
 * Returns current unix timestamp
 */
export function unixNow(): number { 
    return Date.now() / 1000
}

export function unixNowMillis(): number {
    return Date.now()
}

/**
 * Alphabetically sorts array of objects
 */
export function sortArrayAlphabetically<T>(
    arr: T[],
    accessor: (obj: T) => string,
    direction: "asc" | "desc" = "asc"
): T[] {
    return [...arr].sort((a, b) => {
        const res = accessor(a).localeCompare(accessor(b))
        return direction === "asc" ? res : -res
    })
}

/**
 * Sorts an array using custom property indexes
 */
export function sortArray<T>(arr: T[], accessor: (obj: T) => number): T[] {
    return arr.sort((a, b) => accessor(a) - accessor(b));
}

/**
 * Sorts array of objects 
 * 
 * Ascending order
 */
export function sortArrayByNumber<T>(arr: T[], accessor: (obj: T) => number): T[] {
    return [...arr].sort((a, b) => accessor(a) - accessor(b));
}

/**
 * Sorts array of objects 
 * 
 * Descending order
 */
export function sortArrayByNumberDesc<T>(arr: T[], accessor: (obj: T) => number): T[] {
    return [...arr].sort((a, b) => accessor(b) - accessor(a));
}

/**
 * Checks if array includes any of provided items
 */
export function includesAny<T>(list: T[], items: T[]): boolean {
    return list.some(item => items.includes(item));
}

/**
 * Returns filename from a file path
 */
export function filenameFromPath(path: string): string {
    return path.substring(path.lastIndexOf("/") + 1);
}

/**
 * Checks if a file path is a descendant of a given parent directory
 */
export function isChildOf(path: string, parent: string): boolean {
    // ensure parent ends with slash
    const normalizedParent = parent.endsWith('/')
        ? parent
        : parent + '/'
    // must start with parent path and not be exactly equal
    return path !== parent
        && path.startsWith(normalizedParent)
}


/**
 * Returns the parent directory from a file or folder path
 */
export function parentFromPath(path: string): string {
    // strip trailing slash if any
    const normalized = path.endsWith('/') 
        ? path.slice(0, -1) 
        : path
    const idx = normalized.lastIndexOf('/')
    return idx !== -1
        ? normalized.substring(0, idx)
        : ''
}

export function appendTrailingSlash(path: string): string {
    return path + (path === "/" ? "" : "/")
}

/**
 * Formats byte number to readable
 */
export function formatBytes(bytes: number) {
    if (bytes === 0) return '0 B';
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return parseFloat((bytes / Math.pow(1024, i)).toFixed(2)) + ' ' + sizes[i];
}

export function formatBytesRounded(bytes: number): string {
    const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
    let index = 0;

    while (bytes >= 1024 && index < units.length - 1) {
        bytes /= 1024;
        index++;
    }

    return `${Math.ceil(bytes)} ${units[index]}`;
}

export function getFileExtension(name: string): string {
    return name.substring(name.lastIndexOf(".") + 1)
}

export function forEachReversed<T>(array: T[], callback: (value: T, index: number) => any) {
    for (let i = array.length - 1; i >= 0; i--) {
        callback(array[i], i)
    }
}

export function run<T>(block: () => T): T {
    return block()
}

export function mapToObject<K extends ObjectKey, V, T>(arr: T[], mapper: (value: T) => { key: K, value: V }): Record<K, V> {
    let obj = {} as Record<K, V>
    arr.forEach((v) => {
        const entry = mapper(v)
        obj[entry.key] = entry.value
    })
    return obj
}

export function getUniqueFilename(
    filename: string,
    existing: string[]
): string {
    const dotIndex = filename.lastIndexOf('.')
    const base = dotIndex !== -1
        ? filename.slice(0, dotIndex)
        : filename
    const ext = dotIndex !== -1
        ? filename.slice(dotIndex)
        : ''
    // escape for regex
    const esc = (s: string) =>
        s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const re = new RegExp(
        `^${esc(base)}(?: \\((\\d+)\\))?${esc(ext)}$`
    )
    let max = -1
    for (const existingName of existing) {
        const m = existingName.match(re)
        if (m) {
            const n = m[1] ? parseInt(m[1], 10) : 0
            if (n > max) max = n
        }
    }
    // if no match at all, return original; otherwise bump
    return max < 0
        ? filename
        : `${base} (${max + 1})${ext}`
}

export function decodeBase64(input: string) {
    return decodeURIComponent(
        escape(window.atob(input))
    )
}

/**
 * Creates an $effect where dependencies are specified manually.
 */
export function explicitEffect(depsFn: () => any[], fn: () => any) {
    $effect(() => {
        depsFn()
        return untrack(fn)
    })
}


/**
 * Creates an interval with a dynamic delay
 * 
 * @returns cancel and reset methods
 */
export function dynamicInterval(
    callback: (delay: number) => void,
    getDelay: () => number
): { cancel: () => void, reset: () => void } {
    let timeoutId: ReturnType<typeof setTimeout> | undefined

    const run = (delay: number) => {
        callback(delay)
        set()
    }

    function set() {
        const delay = getDelay()
        clearTimeout(timeoutId)
        timeoutId = setTimeout(() => { run(delay) }, delay)
    }

    set()

    return {
        cancel: () => {
            if (timeoutId !== undefined) clearTimeout(timeoutId)
        },
        reset: set
    }
}

export type ResultType = 'value' | 'error' | 'notFound'
export class Result<T> {
    private constructor(
        private readonly type: ResultType,
        private readonly _value?: T,
        private readonly _error?: string
    ) {}

    static ok<T>(value: T) {
        return new Result<T>('value', value)
    }

    static error<T>(error: string) {
        return new Result<T>('error', undefined, error)
    }

    static notFound<T>() {
        return new Result<T>('notFound')
    }

    get isOk() {
        return this.type === 'value'
    }

    get hasError() {
        return this.type === 'error'
    }

    get notFound() {
        return this.type === 'notFound'
    }

    get value() {
        return this._value
    }

    get error() {
        return this._error
    }

    get isUnsuccessful() {
        return this.type !== "value"
    }
}

/**
 * Runs code in a setimeout
 */
export function macrotask(fn: () => any) {
    setTimeout(fn, 0)
}

export async function sha256(message: string) {
    const encoder = new TextEncoder()
    const data = encoder.encode(message)
    const hashBuffer = await crypto.subtle.digest('SHA-256', data)
    return Array.from(new Uint8Array(hashBuffer))
        .map(b => b.toString(16).padStart(2, '0'))
        .join('')
}

export function entriesOf<K extends string, V>(obj: Record<K, V> | {[key: ObjectKey]: V}): [K, V][] {
    return Object.entries(obj) as [K, V][]
}

export function formatDuration(seconds: number): string {
    if (seconds < 60) {
        return `${seconds} second${seconds === 1 ? "" : "s"}`
    }
    const minutes = Math.floor(seconds / 60)
    if (minutes < 60) {
        return `${minutes} minute${minutes === 1 ? "" : "s"}`
    }
    const hours = Math.floor(minutes / 60)
    if (hours < 24) {
        return `${hours} hour${hours === 1 ? "" : "s"}`
    }
    const days = Math.floor(hours / 24)
    return `${days} day${days === 1 ? "" : "s"}`
}



export async function doRequest(
    {
        body,
        method,
        path, 
        onException,
        onFailure,
        onNotFound,
        afterResponse,
        errors,
    }: {
        body?: FormData,
        method?: 'GET' | 'POST',
        path: string,
        onException?: (exception: any) => any,
        onFailure?: (response: SafeFetchResult) => any,
        onNotFound?: (response: SafeFetchResult) => any,
        afterResponse?: (response: SafeFetchResult) => any,
        errors: {
            exception: {
                description: string,
                notification: string,
            },
            failed: {
                description: string,
                notification: string,
            }
        }
    }
) {
    const response = await safeFetch(path, { body, method })

    if (afterResponse) afterResponse(response)

    if (response.failed) {
        handleException(
            errors.exception.description, 
            errors.exception.notification, 
            response.exception
        )

        if (onException) onException(response.exception)
        return
    }

    const status = response.code
    if (status.notFound) {
        if (onNotFound) onNotFound(response)
    } else if (status.failed) {
        const json = response.json()
        handleErr({
            description: errors.failed.description,
            notification: json.message || errors.failed.notification,
            isServerDown: status.serverDown
        })

        if (onFailure) onFailure(response)
        return
    }
}

export function generateRandomNumber() {
    return crypto.getRandomValues(new Uint32Array(1))[0]
}

export function printStack() {
    console.log(new Error().stack)
}

export function isPathDirectChild(parent: string, child: string): boolean {
    const p = parent.replace(/\/+$/, '')
    const c = child.replace(/\/+$/, '')
    const rel = c.slice(p.length)
    return c.startsWith(p + '/') && !rel.slice(1).includes('/')
}