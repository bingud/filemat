import { toast } from "@jill64/svelte-toast"
import type { Response } from "node-fetch"
import type { ErrorResponse } from "../types/types"


/**
 * Returns whether HTTP status indicates that server is down.
 */
export function isServerDown(httpStatus: number): boolean {
    if (httpStatus > 500 && httpStatus < 530) return true
    return false
}

/**
 * Returns a template for a page title.
 */
export function pageTitle(text: string) { return `${text} — Filemat` }

/**
 * Logs an exception
 */
export function handleException(message: string, userMessage: string | null, exception: any) {
    console.log(`${message}\n(${userMessage ?? "No user message"})\n${exception}`)

    if (userMessage) {
        toast.error(userMessage)
    }
}

/**
 * Logs any error
 */
export function handleError(message: string, userMessage: string | null) {
    console.log(`${message}\n(${userMessage ?? "No user message"})`)

    if (userMessage) {
        toast.error(userMessage)
    }
}

/**
 * Logs an error HTTP response
 */
export function handleErrorResponse(response: ErrorResponse | any, defaultMessage: string) {
    const message = response?.message
    const error = response?.error
    
    console.log(`Error Response:\n\n${defaultMessage}\n${error}\n${message}`)

    if (message) {
        toast.error(message)
    } else {
        toast.error(defaultMessage)
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
        let arg = args ? args : {}
        if (!arg.credentials) arg.credentials = "same-origin"
        if (!arg.method) arg.method = "POST"

        const response = await fetch(url, args) as any as SafeFetchResult
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


type httpStatus = ({ ok: true, serverDown: false, failed: false } | { ok: false, serverDown: true, failed: true } | { ok: false, serverDown: false, failed: true }) & { raw: number } 

/**
 * Parses HTTP status code
 */
export function toStatus(s: number): httpStatus {
    let result: httpStatus
    if (s === 200){
        result = { ok: true, serverDown: false, failed: false, raw: s }
    } else if (isServerDown(s)) {
        result = { ok: false, serverDown: true, failed: true, raw: s }
    } else {
        result = { ok: false, serverDown: false, failed: true, raw: s }
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
export function sortArrayAlphabetically<T>(arr: T[], accessor: (obj: T) => string): T[] {
    return [...arr].sort((a, b) => accessor(a).localeCompare(accessor(b)))
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

export function mapToObject<K extends string | number | symbol, V, T>(arr: T[], mapper: (value: T) => { key: K, value: V }): Record<K, V> {
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


export function getFileId(file: File): string {
    return `${file.name}-${file.size}-${file.lastModified}`
}

export function count(text: string, subString: string): number {
    return text.split(subString).length - 1
}