import { toast } from "@jill64/svelte-toast"
import type { Response } from "node-fetch"
import type { ErrorResponse } from "../types"


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
 * Returns parsed JSON or null.
 */
export function parseJson(j: string): any | null {
    try {
        return JSON.parse(j) ?? null
    } catch (e) {
        return null
    }
}

/**
 * Returns a debounced input function
 */
export function debounceFunction(action: any, delay: any, continuousCallDuration: any) {
    let timeoutId: any;
    let continuousCallTimeoutId: any;
    let lastCallTime: number | null = null;

    return function() {
        //@ts-ignore
        const context: any = this;
        const args = arguments;

        const currentTime = new Date().getTime();

        // If it's the first call or more than continuousCallDuration has passed since the last call
        if (!lastCallTime || currentTime - lastCallTime > continuousCallDuration) {
            clearTimeout(continuousCallTimeoutId);
            lastCallTime = currentTime;

            continuousCallTimeoutId = setTimeout(() => {
                action.apply(context, args);
                lastCallTime = null;  // Reset after action has been called
            }, continuousCallDuration);
        }

        clearTimeout(timeoutId);

        timeoutId = setTimeout(() => {
            action.apply(context, args);
            clearTimeout(continuousCallTimeoutId);
            lastCallTime = null;
        }, delay);
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
export function keysOf<T>(obj: {[key: string]: T}): string[] {
    return Object.keys(obj)
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


/**
 * Iterates through an object
 */
export function forEachObject<T>(obj: Record<string, T>, block: (key: string, value: T) => any): void {
    Object.entries(obj).forEach(([key, value]) => { block(key, value) })
}

/**
 * Filters an object with a predicate
 */
export function filterObject<T>(obj: Record<string, T>, predicate: (key: string, value: T) => boolean): Record<string, T> {
    const newObj: Record<string, T> = {}

    forEachObject(obj, ((k, v) => {
        if (predicate(k, v)) {
            newObj[k] = v
        }
    }))

    return newObj
}


/**
 * Returns current unix timestamp
 */
export function unixNow(): number { 
    return Date.now() / 1000
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