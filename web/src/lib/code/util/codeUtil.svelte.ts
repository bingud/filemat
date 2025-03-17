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
export function handleErrorResponse(response: ErrorResponse, defaultMessage: string) {
    const message = response?.message
    const error = response?.error
    
    console.log(`Error Response:\n\n${defaultMessage}\n${error}\n${message}`)

    if (message) {
        toast.error(message)
    } else {
        toast.error(defaultMessage)
    }
}

export function makeIdempotent<T, Args extends any[]>(
    fn: (isRunning: boolean, ...args: Args) => T | Promise<T>,
    after?: () => any,
): (...args: Args) => Promise<T> {
    let runningCount = 0

    return (...args: Args): Promise<T> => {
        // Determine if another call is already in progress.
        const isAlreadyRunning = runningCount > 0
        runningCount++

        try {
            const result = fn(isAlreadyRunning, ...args)
            // Wrap the result in a promise to handle both sync and async cases.
            return Promise.resolve(result).finally(() => {
                runningCount--
                if (runningCount === 0) {
                    if (after) {
                        try {
                            after()
                        } catch (e) {}
                    }
                }
            });
        } catch (error) {
            runningCount--
            return Promise.reject(error)
        }
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


type SafeFetchResult = Response & { failed: boolean, exception: any | null }

/**
 * Fetches without throwing an exception.
 */
export async function safeFetch(url: string, args?: RequestInit): Promise<SafeFetchResult> {
    try {
        const response = await fetch(url, args) as any as SafeFetchResult
        response.failed = false
        response.exception = null
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
