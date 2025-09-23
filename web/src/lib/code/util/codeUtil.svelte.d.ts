// This would typically be in a file like `utils.d.ts`

import type { ErrorResponse } from "../types/types"
import type { FileMetadata, FileType } from "../auth/types"

export type ObjectKey = string | number | symbol

export type httpStatus = ({
    ok: true
    serverDown: false
    failed: false
    notFound: false
} | {
    ok: false
    serverDown: true
    failed: true
    notFound: false
} | {
    ok: false
    serverDown: false
    failed: true
    notFound: false
} | {
    ok: false
    serverDown: false
    failed: true
    notFound: true
}) & {
    raw: number
}

export type SafeFetchResult = Omit<Response, "json"> & {
    failed: boolean
    exception: any | null
    code: httpStatus
    content: string
    json: () => any | null
}

export type ResultType = "value" | "error" | "notFound"

export declare class Result<T> {
    private constructor()
    static ok<T>(value: T): Result<T>
    static error<T>(error: string): Result<T>
    static notFound<T>(): Result<T>
    get isOk(): boolean
    get hasError(): boolean
    get notFound(): boolean
    get value(): T | undefined
    get error(): string | undefined
    get isUnsuccessful(): boolean
}

/**
 * Returns whether HTTP status indicates that server is down.
 */
export declare function isServerDown(httpStatus: number): boolean

export declare function isFolder(type: FileType): boolean

/**
 * Returns a template for a page title.
 */
export declare function pageTitle(text: string): string

/**
 * Logs an exception
 */
export declare function handleException(
    message: string,
    userMessage: string | null,
    exception: any
): void

/**
 * Logs any error
 */
export declare function handleError(
    message: string,
    userMessage: string | null,
    isServerDown?: boolean | null
): void

export declare function handleServerDownError(
    message: string,
    userMessage: string | null
): void

/**
 * Logs an error HTTP response
 */
export declare function handleErrorResponse(
    response: ErrorResponse | any,
    defaultMessage: string
): void

/**
 * Returns whether a string is blank
 */
export declare function isBlank(str: string | null | undefined): boolean

/**
 * Removes spaces from input string
 */
export declare function removeSpaces(str: string): string

/**
 * Fetches without throwing an exception.
 *
 * Default request is POST and credentials same-origin
 */
export declare function safeFetch(
    url: string,
    args?: RequestInit,
    ignoreBody?: boolean
): Promise<SafeFetchResult>

/**
 * Appends a filename to a directory path
 */
export declare function appendFilename(path: string, filename: string): string

/**
 * Returns parsed JSON or null.
 */
export declare function parseJson(j: string): any | null

/**
 * add suffix if missing
 */
export declare function addSuffix(str: string, suffix: string): string

/**
 * Returns a debounced input function
 */
export declare function debounceFunction<Args extends any[]>(
    action: (...args: Args) => void,
    delay: number,
    continuousCallDuration: number
): (...args: Args) => void

/**
 * Returns values of an object
 */
export declare function valuesOf<T>(obj: { [key: string]: T }): T[]

/**
 * Returns keys of an object
 */
export declare function keysOf<K extends string | number | symbol, V>(
    obj: Record<K, V>
): K[]

/**
 * Formats unix timestamp into text
 *
 * 2000-12-31 23:59
 */
export declare function formatUnixTimestamp(unixTimestamp: number): string

export declare function formatUnixMillis(millis: number): string

/**
 * Creates FormData object
 */
export declare function formData(obj: { [key: string]: any }): FormData

/**
 * Parses HTTP status code
 */
export declare function toStatus(s: number): httpStatus

/**
 * Checks if a list contains items of another list
 */
export declare function includesList<T>(list: T[], includedItems: T[]): boolean

/**
 * Locks an input function, so that it can only run once at a time
 */
export declare function lockFunction<T, Args extends any[]>(
    block: (...args: Args) => T | Promise<T>
): (...args: Args) => Promise<T | void>

export declare const delay: (ms: number) => Promise<void>

/**
 * Removes a string element from a string array
 */
export declare function removeString(arr: string[], str: string): void

/**
 * Removes an element from an array
 */
export declare function arrayRemove<T>(
    arr: T[],
    predicate: (value: T) => boolean
): void

export declare function prependIfMissing(str: string, prefix: string): string

/**
 * Iterates through an object
 */
export declare function forEachObject<K extends string | number | symbol, V>(
    obj: Record<K, V>,
    block: (key: K, value: V) => any
): void

/**
 * Filters an object with a predicate
 */
export declare function filterObject<K extends string | number | symbol, V>(
    obj: Record<K, V>,
    predicate: (key: K, value: V) => boolean
): Record<K, V>

export declare function letterS(count: number): "s" | ""

/**
 * Resolve a path from a parent and filename
 */
export declare function resolvePath(
    inputFolder: string,
    inputFilename: string
): string

/**
 * Returns current unix timestamp
 */
export declare function unixNow(): number

export declare function unixNowMillis(): number

/**
 * Alphabetically sorts array of objects
 */
export declare function sortArrayAlphabetically<T>(
    arr: T[],
    accessor: (obj: T) => string
): T[]

/**
 * Sorts an array using custom property indexes
 */
export declare function sortArray<T>(
    arr: T[],
    accessor: (obj: T) => number
): T[]

/**
 * Sorts array of objects
 *
 * Ascending order
 */
export declare function sortArrayByNumber<T>(
    arr: T[],
    accessor: (obj: T) => number
): T[]

/**
 * Sorts array of objects
 *
 * Descending order
 */
export declare function sortArrayByNumberDesc<T>(
    arr: T[],
    accessor: (obj: T) => number
): T[]

/**
 * Checks if array includes any of provided items
 */
export declare function includesAny<T>(list: T[], items: T[]): boolean

/**
 * Returns filename from a file path
 */
export declare function filenameFromPath(path: string): string

/**
 * Checks if a file path is a descendant of a given parent directory
 */
export declare function isChildOf(path: string, parent: string): boolean

/**
 * Returns the parent directory from a file or folder path
 */
export declare function parentFromPath(path: string): string

/**
 * Formats byte number to readable
 */
export declare function formatBytes(bytes: number): string

export declare function formatBytesRounded(bytes: number): string

export declare function getFileExtension(name: string): string

export declare function forEachReversed<T>(
    array: T[],
    callback: (value: T, index: number) => any
): void

export declare function run<T>(block: () => T): T

export declare function mapToObject<K extends ObjectKey, V, T>(
    arr: T[],
    mapper: (value: T) => {
        key: K
        value: V
    }
): Record<K, V>

export declare function getUniqueFilename(
    filename: string,
    existing: string[]
): string

export declare function decodeBase64(input: string): string

/**
 * Creates an $effect where dependencies are specified manually.
 */
export declare function explicitEffect(fn: () => any, depsFn: () => any[]): void

/**
 * Creates an interval with a dynamic delay
 *
 * @returns cancel and reset methods
 */
export declare function dynamicInterval(
    callback: (delay: number) => void,
    getDelay: () => number
): {
    cancel: () => void
    reset: () => void
}

/**
 * Runs code in a setimeout
 */
export declare function macrotask(fn: () => any): void

export declare function sha256(message: string): Promise<string>

export declare function entriesOf<K extends ObjectKey, V>(
    obj: Record<K, V>
): [string, V][]

export function formatDuration(seconds: number): string