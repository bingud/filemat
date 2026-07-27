import { forEachObject, valuesOf } from "$lib/code/util/codeUtil.svelte"
import { Upload } from "tus-js-client"

type fileUploadStatus = "paused" | "uploading" | "success" | "failed" | "canceled" | "queued" | "skipped"

export type TusUploadOptions = {
    targetPath?: string,
    targetFilename?: string,
    metadata?: Record<string, string>,
    displayPath?: string,
    batchId?: string,
    relativePath?: string,
    refreshCurrentFolderOnSuccess?: boolean,
    onSuccess?: () => void,
    snapshotBeforeUpload?: boolean,
}

export type FileUpload = {
    path: string,
    actualPath: string | null,
    displayPath: string | null,
    batchId: string | null,
    relativePath: string | null,
    percentage: number,
    status: fileUploadStatus,
    action: "canceling" | null,
    bytesTotal: number,
    bytesUploaded: number,
    upload: Upload,
    /** Original options used to create this upload; needed for a fresh retry. */
    options: TusUploadOptions,
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
        let skipped = 0
        let total = 0

        forEachObject(this.all, (k, v) => {
            total++
            if (v.status === "success") { successful++ }
            else if (v.status === "uploading") { uploading++ }
            else if (v.status === "canceled") { canceled++ }
            else if (v.status === "failed") { failed++ }
            else if (v.status === "paused") { paused++ }
            else if (v.status === "queued") { queued++ }
            else if (v.status === "skipped") { skipped++ }
        })

        return {
            uploading: uploading,
            successful: successful,
            failed: failed,
            canceled: canceled,
            paused: paused,
            queued: queued,
            skipped: skipped,
            total: total,
        }
    })

    get(path: string): FileUpload | null {
        return this.all[path]
    }

    addUpload(
        path: string,
        upload: Upload,
        status: fileUploadStatus,
        options: TusUploadOptions = {}
    ): boolean {
        this.panelExpanded = true
        
        const existing = this.all[path]
        if (existing && existing.status === "uploading") return false

        this.all[path] = {
            path: path,
            actualPath: null,
            displayPath: options.displayPath || null,
            batchId: options.batchId || null,
            relativePath: options.relativePath || null,
            percentage: 0,
            bytesTotal: (upload.file as File).size,
            bytesUploaded: 0,
            status: status,
            action: null,
            upload: upload,
            options: options,
        }
        return true
    }

    removeUpload(path: string) {
        delete this.all[path]
    }

    panelOpen = $state(true)
    panelExpanded = $state(true)
}

export const uploadState = new UploadState()