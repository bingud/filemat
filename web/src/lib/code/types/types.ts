import type { Snippet } from "svelte"

export type ulid = string
export type ErrorResponse = { message: string, error: string }

export type snippet = Snippet<[]>