import { forEachObject, valuesOf } from "$lib/code/util/codeUtil.svelte"
import { Upload } from "tus-js-client"

type fileUploadStatus = "paused" | "uploading" | "success" | "failed" | "canceled" | "queued" | "skipped" | "incomplete"

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
    /** FS Access handle for resume-without-repick (Chromium). Not persisted in tus localStorage. */
    fileHandle?: FileSystemFileHandle,
}

export type PreviousTusUpload = {
    size: number | null,
    metadata: Record<string, string>,
    creationTime: string,
    urlStorageKey: string,
    uploadUrl: string | null,
    parallelUploadUrls?: string[] | null,
}

export type FileUpload = {
    path: string,
    actualPath: string | null,
    displayPath: string | null,
    batchId: string | null,
    relativePath: string | null,
    percentage: number,
    status: fileUploadStatus,
    action: "canceling" | "pausing" | null,
    bytesTotal: number,
    bytesUploaded: number,
    upload: Upload | null,
    /** Original options used to create this upload; needed for a fresh retry. */
    options: TusUploadOptions,
    uploadUrl: string | null,
    urlStorageKey: string | null,
    previousUpload: PreviousTusUpload | null,
    /** In-memory FS Access handle (also mirrored in IndexedDB). */
    fileHandle: FileSystemFileHandle | null,
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
        let incomplete = 0
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
            else if (v.status === "incomplete") { incomplete++ }
        })

        return {
            uploading: uploading,
            successful: successful,
            failed: failed,
            canceled: canceled,
            paused: paused,
            queued: queued,
            skipped: skipped,
            incomplete: incomplete,
            total: total,
        }
    })

    get hasBlockingUploads() {
        return this.counts.uploading > 0
            || this.counts.incomplete > 0
            || this.counts.paused > 0
            || this.counts.queued > 0
    }

    get(path: string): FileUpload | null {
        return this.all[path]
    }

    addUpload(
        path: string,
        upload: Upload | null,
        status: fileUploadStatus,
        options: TusUploadOptions = {},
        extras: {
            uploadUrl?: string | null,
            urlStorageKey?: string | null,
            previousUpload?: PreviousTusUpload | null,
            bytesUploaded?: number,
            bytesTotal?: number,
            fileHandle?: FileSystemFileHandle | null,
        } = {},
    ): boolean {
        this.panelExpanded = true
        this.panelOpen = true

        const existing = this.all[path]
        if (existing && (existing.status === "uploading" || existing.status === "queued")) return false

        const fileSize = upload
            ? (upload.file as File).size
            : (extras.bytesTotal ?? extras.previousUpload?.size ?? 0)
        const bytesUploaded = extras.bytesUploaded || 0
        const bytesTotal = fileSize || extras.bytesTotal || 0

        this.all[path] = {
            path: path,
            actualPath: null,
            displayPath: options.displayPath || null,
            batchId: options.batchId || null,
            relativePath: options.relativePath || null,
            percentage: bytesTotal
                ? Number(((bytesUploaded / bytesTotal) * 100).toFixed(2))
                : 0,
            bytesTotal,
            bytesUploaded,
            status: status,
            action: null,
            upload: upload,
            options: options,
            uploadUrl: extras.uploadUrl ?? null,
            urlStorageKey: extras.urlStorageKey ?? null,
            previousUpload: extras.previousUpload ?? null,
            fileHandle: extras.fileHandle ?? options.fileHandle ?? null,
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
