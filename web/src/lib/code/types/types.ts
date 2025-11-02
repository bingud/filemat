import type { Snippet } from "svelte"

export type ulid = string
export type ErrorResponse = { message: string, error: string }

export type snippet = Snippet<[]>

export type record<K extends string | number | symbol, V> = Partial<Record<K, V>>

export type ValuesOf<T> = T[keyof T]