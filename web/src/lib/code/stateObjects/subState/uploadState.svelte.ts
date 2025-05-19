import { forEachObject, valuesOf } from "$lib/code/util/codeUtil.svelte"
import { Upload } from "tus-js-client"

type fileUploadStatus = "paused" | "uploading" | "success" | "failed" | "canceled" | "queued"

export type FileUpload = {
    path: string,
    actualPath: string | null,
    percentage: number,
    status: fileUploadStatus,
    action: "canceling" | null,
    bytesTotal: number,
    bytesUploaded: number,
    upload: Upload,
}

export class UploadState {
    /**
     * Holds state for uploaded files.
     */
    all: Record</* Filename */ string, FileUpload> = $state({})

    list = $derived(valuesOf(this.all))

    count = $derived(valuesOf(this.all).length)

    counts = $derived.by(() => {
        let successful = 0
        let uploading = 0
        let canceled = 0
        let failed = 0
        let paused = 0
        let queued = 0

        forEachObject(this.all, (k, v) => {
            if (v.status === "success") { successful++ }
            else if (v.status === "uploading") { uploading++ }
            else if (v.status === "canceled") { canceled++ }
            else if (v.status === "failed") { failed++ }
            else if (v.status === "paused") { paused++ }
            else if (v.status === "queued") { queued++ }
        })

        return {
            uploading: uploading,
            successful: successful,
            failed: failed,
            canceled: canceled,
            paused: paused,
            queued: queued
        }
    })

    get(path: string): FileUpload | null {
        return this.all[path]
    }

    addUpload(path: string, upload: Upload, status: fileUploadStatus): boolean {
        const existing = this.all[path]
        if (existing && existing.status === "uploading") return false

        this.all[path] = {
            path: path,
            actualPath: null,
            percentage: 0,
            bytesTotal: (upload.file as File).size,
            bytesUploaded: 0,
            status: status,
            action: null,
            upload: upload,
        }
        return true
    }

    removeUpload(path: string) {
        delete this.all[path]
    }

    panelOpen = $state(true)
}

export const uploadState = new UploadState()